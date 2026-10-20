package com.liuhaoran.hobao.data

import androidx.room.withTransaction
import com.liuhaoran.hobao.data.backup.BackupCategory
import com.liuhaoran.hobao.data.backup.BackupCodec
import com.liuhaoran.hobao.data.backup.BackupCurrency
import com.liuhaoran.hobao.data.backup.BackupExpense
import com.liuhaoran.hobao.data.backup.BackupFile
import com.liuhaoran.hobao.data.backup.ImportMode
import com.liuhaoran.hobao.data.backup.ImportResult
import com.liuhaoran.hobao.data.db.HoBaoDatabase
import com.liuhaoran.hobao.data.db.entity.CategoryEntity
import com.liuhaoran.hobao.data.db.entity.CurrencyEntity
import com.liuhaoran.hobao.data.db.entity.ExpenseEntity
import com.liuhaoran.hobao.data.forex.ForexConfig
import com.liuhaoran.hobao.data.forex.ForexRateTable
import kotlinx.coroutines.flow.Flow

/**
 * 账单仓库 —— 数据的唯一来源。
 *
 * 界面代码和 ViewModel 只通过它拿数据 / 写数据，
 * 换掉整个存储引擎（或者像这次一样加备份导入）都只改这一个文件。
 */
class ExpenseRepository(private val db: HoBaoDatabase) {

    private val expenseDao = db.expenseDao()
    private val categoryDao = db.categoryDao()
    private val currencyDao = db.currencyDao()

    // ── 读：返回 Flow，数据一变界面自动刷新 ──
    fun observeExpenses(): Flow<List<ExpenseEntity>> = expenseDao.observeAll()
    fun observeCategories(): Flow<List<CategoryEntity>> = categoryDao.observeAll()
    fun observeCurrencies(): Flow<List<CurrencyEntity>> = currencyDao.observeAll()

    // ── 写 ──
    suspend fun addExpense(expense: ExpenseEntity) = expenseDao.insert(expense)
    suspend fun updateExpense(expense: ExpenseEntity) = expenseDao.update(expense)
    suspend fun deleteExpense(expense: ExpenseEntity) = expenseDao.delete(expense)

    suspend fun archiveCategory(id: Long) = categoryDao.archive(id)

    /**
     * 读取某个币种当前的汇率（1 人民币 = 多少该币种）。0.0 = 还没填。
     * 「汇率从哪来」收在这一个函数里，将来接自动联网只改这里。
     */
    suspend fun currentRatePerCny(code: String): Double =
        currencyDao.findByCode(code)?.ratePerCny ?: 0.0

    /**
     * 写入一个币种的汇率。
     * ⚠️ 只改 currencies 表里的"当前汇率"，已有账单里各自存着快照，一行都不会被动到。
     */
    suspend fun updateRate(code: String, ratePerCny: Double) =
        currencyDao.updateRate(code, ratePerCny, System.currentTimeMillis())

    /**
     * 把一张**联网抓到的**汇率表批量写进 currencies 表。
     *
     * 【和 updateRate 的区别】
     * updateRate 是用户手填一个币种；这里是整表刷新，一次改 5 行。
     * 共用同一条 DAO 语句 —— 所以「改汇率不动历史账单」的性质自动成立
     * （账单里存的是各自的快照，根本不读这张表）。
     *
     * ⚠️ 这里**故意不做两位小数截断**。
     *    手填的汇率限定两位是用户自己定的输入规则；API 给的是 8~9 位有效数字。
     *    把 USD 截成 0.15 会让每笔美元账的折算误差达到 0.7%（100 美元差 4 毛 4）。
     *    显示层仍然只显示两位，所以界面上看不出区别，但账算得对。
     */
    suspend fun applyForexTable(table: ForexRateTable) {
        val now = System.currentTimeMillis()
        table.ratesPerBase.forEach { (code, rate) ->
            if (ForexConfig.isSupported(code) && rate > 0.0) {
                currencyDao.updateRate(code, rate, now)
            }
        }
    }

    // ══════════════════════ 分析 ══════════════════════
    //
    // 这三个方法故意只做转发、不加逻辑：聚合的**口径**在 SQL 里，
    // **命名和展示**在 ViewModel 里。中间这一层不掺和，出问题时容易定位。

    suspend fun categorySums(from: Long, to: Long) = expenseDao.categorySums(from, to)

    suspend fun analysisTotal(from: Long, to: Long) = expenseDao.analysisTotal(from, to)

    suspend fun dailySums(from: Long, to: Long) = expenseDao.dailySums(from, to)

    // ══════════════════════ 备份：导出 ══════════════════════

    /**
     * 把三张表全读出来，变成"对外"的备份结构。
     *
     * 注意所有引用都换成**跨设备稳定**的写法：
     *   分类 → 名字（不是自增 id）
     *   币种 → ISO 代码
     *   汇率 → 每条账自己的快照
     */
    suspend fun buildSnapshot(): BackupFile {
        val categories = categoryDao.getAll()
        val currencies = currencyDao.getAll()
        val expenses = expenseDao.getAll()
        val nameById = categories.associate { it.id to it.name }

        return BackupFile(
            formatVersion = BackupCodec.CURRENT_FORMAT_VERSION,
            exportedAt = System.currentTimeMillis(),
            appVersion = BackupCodec.APP_VERSION,
            categories = categories.map { BackupCategory(it.name, it.sortOrder, it.isArchived) },
            currencies = currencies.map {
                BackupCurrency(
                    code = it.code,
                    nameZh = it.nameZh,
                    symbol = it.symbol,
                    minorUnits = it.minorUnits,
                    ratePerCny = it.ratePerCny,
                    rateUpdatedAt = it.rateUpdatedAt,
                )
            },
            // 找不到分类的账单直接跳过（正常不会有，除非数据被外部改坏了）
            expenses = expenses.mapNotNull { e ->
                val name = nameById[e.categoryId] ?: return@mapNotNull null
                BackupExpense(
                    amountMinor = e.amountMinor,
                    currencyCode = e.currencyCode,
                    ratePerCny = e.ratePerCny,
                    reason = e.reason,
                    categoryName = name,
                    createdAt = e.createdAt,
                    updatedAt = e.updatedAt,
                )
            },
        )
    }

    // ══════════════════════ 备份：导入 ══════════════════════

    /**
     * 导入一份备份。
     *
     * ⚠️ 两种模式**都在一个事务里**。
     * 覆盖模式尤其重要：先 DELETE 再 INSERT，如果中途失败又没有事务，
     * 用户就会既没了旧数据、也没导入新数据 —— 两头空。
     */
    suspend fun importBackup(file: BackupFile, mode: ImportMode): ImportResult =
        db.withTransaction {
            when (mode) {
                ImportMode.MERGE -> importMerge(file)
                ImportMode.REPLACE -> importReplace(file)
            }
        }

    /**
     * 合并：追加，跳过重复。
     *
     * 【用什么判重】
     * 不用 uuid（那要给表加列、写数据库迁移），而用**复合键**：
     *   createdAt + 金额 + 币种 + 分类名 + 原因
     *
     * createdAt 精确到毫秒，是同一条账重导时天然相同的稳定指纹。
     * 代价：如果你导出后手改了某笔账的时间或金额，再导入会被当成新的一笔。
     *
     * 【币种怎么处理】
     * 只补设备上没有的，**不动本地已有的汇率** ——
     * 本地汇率是用户自己确认过的，比备份里的旧值更可信。
     */
    private suspend fun importMerge(file: BackupFile): ImportResult {
        val now = System.currentTimeMillis()

        // ① 币种：只补缺的
        val existingCodes = currencyDao.getAll().map { it.code }.toHashSet()
        val missingCurrencies = file.currencies.filter { it.code !in existingCodes }
        if (missingCurrencies.isNotEmpty()) {
            currencyDao.upsertAll(
                missingCurrencies.mapIndexed { i, c -> c.toEntity(i) }
            )
        }

        // ② 分类：同名直接复用，没有才新建
        val existingCats = categoryDao.getAll()
        val nameToId = existingCats.associate { it.name to it.id }.toMutableMap()
        var nextSort = (existingCats.maxOfOrNull { it.sortOrder } ?: -1) + 1
        var newCategories = 0

        // 文件里的分类 + 账单引用的分类，都要保证存在
        val neededNames = (file.categories.map { it.name } + file.expenses.map { it.categoryName })
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        neededNames.forEach { name ->
            if (name !in nameToId) {
                val id = categoryDao.insert(
                    CategoryEntity(
                        name = name,
                        colorArgb = colorForName(name),
                        sortOrder = nextSort++,
                        isArchived = false,
                        createdAt = now,
                    )
                )
                nameToId[name] = id
                newCategories++
            }
        }

        // ③ 账单：复合键去重
        val idToName = nameToId.entries.associate { (name, id) -> id to name }
        val seenKeys = expenseDao.getAll().mapNotNull { e ->
            val catName = idToName[e.categoryId] ?: return@mapNotNull null
            dedupeKey(e.createdAt, e.amountMinor, e.currencyCode, catName, e.reason)
        }.toHashSet()

        val toInsert = mutableListOf<ExpenseEntity>()
        var skipped = 0
        file.expenses.forEach { b ->
            val catName = b.categoryName.trim()
            val catId = nameToId[catName] ?: return@forEach
            val key = dedupeKey(b.createdAt, b.amountMinor, b.currencyCode, catName, b.reason)
            if (key in seenKeys) {
                skipped++
                return@forEach
            }
            seenKeys += key
            toInsert += b.toEntity(catId)
        }
        if (toInsert.isNotEmpty()) expenseDao.insertAll(toInsert)

        return ImportResult(
            added = toInsert.size,
            skipped = skipped,
            newCategories = newCategories,
            newCurrencies = missingCurrencies.size,
        )
    }

    /**
     * 覆盖：清空后按文件重建。
     *
     * ⚠️ 删除顺序必须**先子表后父表**（expenses 有外键指向 categories），
     *    否则外键约束会让 DELETE 直接失败。
     */
    private suspend fun importReplace(file: BackupFile): ImportResult {
        val now = System.currentTimeMillis()

        expenseDao.deleteAll()
        categoryDao.deleteAll()
        currencyDao.deleteAll()

        // 币种：文件里没有人民币的话补一个，否则账本就没有锚点了
        val currencies = file.currencies.toMutableList()
        if (currencies.none { it.code == BackupCodec.BASE_CURRENCY }) {
            currencies += BackupCurrency(
                code = BackupCodec.BASE_CURRENCY,
                nameZh = "人民币",
                symbol = "CN¥",
                minorUnits = 2,
                ratePerCny = 1.0,
                rateUpdatedAt = now,
            )
        }
        currencyDao.upsertAll(currencies.mapIndexed { i, c -> c.toEntity(i) })

        // 分类
        val cats = file.categories.map { c ->
            CategoryEntity(
                name = c.name,
                colorArgb = colorForName(c.name),
                sortOrder = c.sortOrder,
                isArchived = c.isArchived,
                createdAt = now,
            )
        }.toMutableList()

        // 文件里 categories 没列、但账单引用了的名字 → 补建，别把账丢了
        val listed = cats.map { it.name }.toHashSet()
        file.expenses.map { it.categoryName.trim() }.distinct()
            .filter { it.isNotEmpty() && it !in listed }
            .forEach { name ->
                cats += CategoryEntity(
                    name = name,
                    colorArgb = colorForName(name),
                    sortOrder = cats.size,
                    isArchived = false,
                    createdAt = now,
                )
            }

        val ids = if (cats.isEmpty()) emptyList() else categoryDao.insertAll(cats)
        val nameToId = cats.mapIndexed { i, c -> c.name to ids[i] }.toMap()

        val exps = file.expenses.mapNotNull { b ->
            val id = nameToId[b.categoryName.trim()] ?: return@mapNotNull null
            b.toEntity(id)
        }
        if (exps.isNotEmpty()) expenseDao.insertAll(exps)

        return ImportResult(
            added = exps.size,
            skipped = 0,
            newCategories = cats.size,
            newCurrencies = currencies.size,
        )
    }

    // ── 小工具 ──

    /**
     * 合并判重用的复合键。
     * 用 \u001F（单元分隔符）而不是 "|"，避免原因文字里刚好含分隔符造成误判。
     */
    private fun dedupeKey(
        createdAt: Long,
        amountMinor: Long,
        currencyCode: String,
        categoryName: String,
        reason: String,
    ): String = "$createdAt\u001F$amountMinor\u001F$currencyCode\u001F$categoryName\u001F$reason"

    /**
     * 【新建分类的默认颜色】由分类名推出来。
     *
     * 为什么不是"按顺序从 7 色调色板里取"：
     *   1. 第 8 个分类开始必然重复
     *   2. 合并导入时新分类从第 0 色开始取，而设备上已有的分类往往正好占满调色板 → 必然撞色
     *
     * 为什么用名字推：
     *   · **同一个名字在任何设备、导入多少次，都得到同一个颜色** —— 可预期
     *   · 360 个色相，基本不会撞；撞了也不影响数据，只是两个色点相近
     *   · 文件里依然**不用存颜色**（用户的要求），颜色是算出来的，不是存出来的
     *
     * ⚠️ 用的是 String.hashCode()。它在 Java 规范里被**明确规定**了算法，
     *    所以跨设备、跨版本、跨 JVM 结果都一样 —— 这是它能当"稳定种子"的前提。
     *    （Kotlin 的 Random、对象地址、hashCode 的默认实现都不行，那些每次运行都不一样）
     */
    private fun colorForName(name: String): Long {
        val hue = (name.hashCode().toLong() and 0x7FFFFFFFL) % 360L
        // S/V 压得比较低：这是个 10dp 的小圆点，太艳会刺眼
        val argb = android.graphics.Color.HSVToColor(
            floatArrayOf(hue.toFloat(), 0.55f, 0.62f)
        )
        return argb.toLong() and 0xFFFFFFFFL
    }
}

// ── DTO → Entity ──

private fun BackupCurrency.toEntity(sortOrder: Int) = CurrencyEntity(
    code = code,
    nameZh = nameZh,
    symbol = symbol.ifEmpty { code },
    minorUnits = minorUnits,
    ratePerCny = ratePerCny,
    isEnabled = true,
    sortOrder = sortOrder,
    rateUpdatedAt = rateUpdatedAt,
)

private fun BackupExpense.toEntity(categoryId: Long) = ExpenseEntity(
    amountMinor = amountMinor,
    currencyCode = currencyCode,
    ratePerCny = ratePerCny,
    reason = reason,
    categoryId = categoryId,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
