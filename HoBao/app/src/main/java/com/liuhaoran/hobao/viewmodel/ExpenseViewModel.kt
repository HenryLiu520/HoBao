package com.liuhaoran.hobao.viewmodel

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.liuhaoran.hobao.data.ExpenseRepository
import com.liuhaoran.hobao.data.SettingsStore
import com.liuhaoran.hobao.data.backup.BackupCodec
import com.liuhaoran.hobao.data.backup.ImportMode
import com.liuhaoran.hobao.data.backup.ImportResult
import com.liuhaoran.hobao.data.db.HoBaoDatabase
import com.liuhaoran.hobao.data.db.entity.CategoryEntity
import com.liuhaoran.hobao.data.db.entity.CurrencyEntity
import com.liuhaoran.hobao.data.db.entity.ExpenseEntity
import com.liuhaoran.hobao.model.SortOrder
import com.liuhaoran.hobao.model.TimeFilter
import com.liuhaoran.hobao.model.toEpochMillisLocal
import com.liuhaoran.hobao.model.toLocalDate
import com.liuhaoran.hobao.model.toYearMonth
import com.liuhaoran.hobao.util.cnyMinorToMinor
import com.liuhaoran.hobao.util.formatMoney
import com.liuhaoran.hobao.util.toCnyMinor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * 给界面用的一条账单：账单本身 + 它所属的分类 + 币种。
 */
data class ExpenseItem(
    val expense: ExpenseEntity,
    val category: CategoryEntity,
    val currency: CurrencyEntity,
) {
    /**
     * 🔒 这条账"值多少人民币"（整数分）。
     *
     * 用的是**账单里冻结的汇率快照**，不是当前汇率 —— 所以历史总额永远不变。
     * 每次算结果都一样，所以不需要再存一列。
     */
    val cnyCents: Long
        get() = toCnyMinor(expense.amountMinor, currency.minorUnits, expense.ratePerCny)
}

/** 按月分组：月份标签 + 该月小计（人民币分） + 该月的条目 */
data class MonthGroup(
    val label: String,
    val subtotalCnyCents: Long,
    val items: List<ExpenseItem>,
)

/**
 * 全 app 共用的"数据与状态中心"。
 *
 * 【最重要的一个概念：账本层 vs 显示层】
 *
 *   账本层（数据库）：每条账折算成人民币的金额 —— 由冻结的汇率决定，**永不变**
 *   显示层（界面）  ：把人民币按**当前汇率**折成你选的报告币种 —— **随汇率变**
 *
 * 所以"改汇率不影响已记的账"和"总额会随汇率变"是**同时成立**的两句话，
 * 它们说的是不同层。想清楚这个，多币种就通了。
 */
class ExpenseViewModel(
    private val repo: ExpenseRepository,
    private val settings: SettingsStore,
) : ViewModel() {

    // ── 界面状态：筛选条件 ──
    var timeFilter by mutableStateOf<TimeFilter>(TimeFilter.All)
        private set
    var sortOrder by mutableStateOf(SortOrder.TIME_DESC)
        private set
    var query by mutableStateOf("")
        private set

    /** 类别筛选：null = 不限；否则是某一个分类的 id（单选） */
    var categoryFilter by mutableStateOf<Long?>(null)
        private set

    /** 自定义区间的边界（本地时间的当天起点/终点），null = 还没选过 */
    private var customStartMillis by mutableStateOf<Long?>(null)
    private var customEndMillis by mutableStateOf<Long?>(null)

    // ── 界面状态：币种 ──

    /** 报告币种：你"用哪个币种看账"。持久化在 SharedPreferences 里 */
    var reportCurrencyCode by mutableStateOf(settings.reportCurrency)
        private set

    /** 「记一笔」页当前选中的币种。默认跟报告币种一致 */
    var entryCurrencyCode by mutableStateOf(settings.reportCurrency)
        private set

    // ── 从数据库流过来的数据 ──
    private var allExpenses by mutableStateOf<List<ExpenseEntity>>(emptyList())
    private var allCategories by mutableStateOf<List<CategoryEntity>>(emptyList())
    private var allCurrencies by mutableStateOf<List<CurrencyEntity>>(emptyList())

    /** 分类是否已经从数据库读到过（哪怕读到的是空列表） —— 区分"加载中"和"确实是空" */
    var categoriesLoaded by mutableStateOf(false)
        private set

    init {
        viewModelScope.launch { repo.observeExpenses().collect { allExpenses = it } }
        viewModelScope.launch {
            repo.observeCategories().collect {
                allCategories = it
                categoriesLoaded = true
            }
        }
        viewModelScope.launch { repo.observeCurrencies().collect { allCurrencies = it } }
    }

    // ── 筛选状态修改 ──
    fun onTimeFilterChange(value: TimeFilter) { timeFilter = value }
    fun onSortOrderChange(value: SortOrder) { sortOrder = value }
    fun onQueryChange(value: String) { query = value }
    fun onCategoryFilterChange(categoryId: Long?) { categoryFilter = categoryId }

    fun onCustomSelected() {
        val start = customStartMillis ?: defaultCustomStart()
        val end = customEndMillis ?: defaultCustomEnd()
        customStartMillis = start
        customEndMillis = end
        timeFilter = TimeFilter.Custom(start, end)
    }

    /**
     * 改起始日期。⚠️ 起始不能晚于结束 —— 越界就把结束一起推过去（钳制），
     * 而不是让区间变成负数（那样一条都筛不出来，用户会以为数据丢了）。
     */
    fun onCustomStartChanged(localStartOfDay: Long) {
        customStartMillis = localStartOfDay
        val end = customEndMillis ?: defaultCustomEnd()
        customEndMillis =
            if (end < localStartOfDay) localStartOfDay.toLocalDate().toEpochMillisLocal(false) else end
        timeFilter = TimeFilter.Custom(customStartMillis!!, customEndMillis!!)
    }

    fun onCustomEndChanged(localEndOfDay: Long) {
        customEndMillis = localEndOfDay
        val start = customStartMillis ?: defaultCustomStart()
        customStartMillis =
            if (start > localEndOfDay) localEndOfDay.toLocalDate().toEpochMillisLocal(true) else start
        timeFilter = TimeFilter.Custom(customStartMillis!!, customEndMillis!!)
    }

    fun resetFilters() {
        timeFilter = TimeFilter.All
        categoryFilter = null
        sortOrder = SortOrder.TIME_DESC
        query = ""
    }

    // ── 币种 ──

    /**
     * 切换报告币种。
     *
     * ⚠️ 只改"看的角度"。数据库里一条账都不会被改写。
     *    界面上的总额会变 —— 那是按今天的汇率重新折算的结果，不是"改账"。
     */
    fun onReportCurrencyChange(code: String) {
        reportCurrencyCode = code
        settings.reportCurrency = code
        entryCurrencyCode = code
    }

    fun onEntryCurrencyChange(code: String) { entryCurrencyCode = code }

    /** 写入汇率。只影响"之后"记的账，已有账单里的快照动不了 */
    fun updateRate(code: String, ratePerCny: Double) {
        if (ratePerCny <= 0.0) return
        viewModelScope.launch { repo.updateRate(code, ratePerCny) }
    }

    /** 报告币种实体。数据库还没读出来时退回人民币 */
    val reportCurrency: CurrencyEntity
        get() = allCurrencies.firstOrNull { it.code == reportCurrencyCode } ?: FALLBACK_CNY

    /** 报告币种的汇率。防御性兜底：万一被选中的币种汇率是 0，按 1 显示而不是全变 0 */
    val reportRate: Double
        get() = reportCurrency.ratePerCny.let { if (it > 0.0) it else 1.0 }

    /** 「记一笔」页选中的币种实体 */
    val entryCurrency: CurrencyEntity?
        get() = allCurrencies.firstOrNull { it.code == entryCurrencyCode }

    /** 这个币种能不能记账（汇率填了才行） */
    val entryCurrencyUsable: Boolean
        get() = (entryCurrency?.ratePerCny ?: 0.0) > 0.0

    /** 【显示层】冻结的人民币分 → 报告币种的分 */
    fun reportMinor(cnyCents: Long): Long =
        cnyMinorToMinor(cnyCents, reportCurrency.minorUnits, reportRate)

    /** 冻结的人民币分 → 报告币种的显示文字 */
    fun reportText(cnyCents: Long): String =
        formatMoney(reportMinor(cnyCents), reportCurrency)

    // ── 写入数据库 ──
    /**
     * 记一笔。
     *
     * @param createdAt 这笔账"算在哪一天"。默认现在；用户补记时可以往回选。
     *                  ⚠️ 这个值同时被四个功能依赖：排序、按月分组、时间筛选、本月小计。
     *                  所以它不是装饰，是"这笔账属于哪个月"的唯一依据。
     * @param reason    留空 → 自动用分类名。
     *
     * 【"原因为空就用分类名"为什么写在 ViewModel 里，而不是界面上】
     * 因为这是**数据的规则**，不是某个界面的规则。
     * 将来做 CSV 导入，导入的账也走这条路，行为才一致。
     * 写在界面上 = 以后每加一个入口就要抄一遍，迟早漏。
     */
    fun addExpense(
        amountMinor: Long,
        reason: String,
        categoryId: Long,
        createdAt: Long = System.currentTimeMillis(),
    ) {
        val cur = entryCurrency ?: return
        if (cur.ratePerCny <= 0.0) return
        val categoryName = allCategories.firstOrNull { it.id == categoryId }?.name
        val finalReason = reason.trim().ifEmpty { categoryName ?: "未分类" }
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            repo.addExpense(
                ExpenseEntity(
                    amountMinor = amountMinor,
                    currencyCode = cur.code,
                    // 🔒 把"当前汇率"抄一份进账单，从此这条账跟汇率表再无关系
                    ratePerCny = cur.ratePerCny,
                    reason = finalReason,
                    categoryId = categoryId,
                    createdAt = createdAt,
                    updatedAt = now,
                )
            )
        }
    }

    fun deleteExpense(expense: ExpenseEntity) {
        viewModelScope.launch { repo.deleteExpense(expense) }
    }

    // ── 备份 ──
    //
    // 【职责边界：为什么文件读写不在这里】
    // ViewModel 只负责「数据 ↔ 文本」；「文本 ↔ 文件」交给界面层。
    // 因为只有界面知道用户选了哪个文件（那是 SAF 的 uri，属于平台细节）。
    // 这样 ViewModel 里没有任何 Android 文件 API，将来要写单元测试也容易。

    /** 把当前全部数据编码成备份文本 */
    suspend fun buildBackupText(): String = withContext(Dispatchers.IO) {
        BackupCodec.encode(repo.buildSnapshot())
    }

    /** 解析备份文本并写入数据库 */
    suspend fun importBackupText(text: String, mode: ImportMode): ImportResult =
        withContext(Dispatchers.IO) {
            val file = BackupCodec.decode(text)
            repo.importBackup(file, mode)
        }

    // ── 派生数据 ──

    val activeCategories: List<CategoryEntity>
        get() = allCategories.filter { !it.isArchived }

    /** 全部币种（设置页 / 汇率页要看全部） */
    val currencies: List<CurrencyEntity> get() = allCurrencies

    /** 汇率页要列的外币：除了锚点人民币以外，按 sortOrder */
    val foreignCurrencies: List<CurrencyEntity>
        get() = allCurrencies.filter { !it.isBase }.sortedBy { it.sortOrder }

    /** 记账页可选的币种（只列汇率已填的 + 人民币） */
    val entryCurrencies: List<CurrencyEntity>
        get() = allCurrencies.sortedBy { it.sortOrder }

    private val categoryById: Map<Long, CategoryEntity>
        get() = allCategories.associateBy { it.id }
    private val currencyByCode: Map<String, CurrencyEntity>
        get() = allCurrencies.associateBy { it.code }

    /** 把三张表拼成界面要的样子 */
    private val allItems: List<ExpenseItem>
        get() {
            val cats = categoryById
            val curs = currencyByCode
            return allExpenses.mapNotNull { e ->
                val c = cats[e.categoryId] ?: return@mapNotNull null
                val u = curs[e.currencyCode] ?: return@mapNotNull null
                ExpenseItem(e, c, u)
            }
        }

    /** 自定义区间的下界：第一笔账那天（没有账就退回今天） */
    val customRangeLowerBound: Long
        get() = (allExpenses.minOfOrNull { it.createdAt } ?: System.currentTimeMillis())
            .toLocalDate().toEpochMillisLocal(true)

    /** 自定义区间的上界：今天最后一毫秒 */
    val customRangeUpperBound: Long
        get() = LocalDate.now().toEpochMillisLocal(false)

    private fun defaultCustomStart(): Long = customRangeLowerBound
    private fun defaultCustomEnd(): Long = customRangeUpperBound

    /** 当前筛选条件的一句话摘要；null = 什么都没筛 */
    val filterSummary: String?
        get() {
            val parts = mutableListOf<String>()
            if (timeFilter != TimeFilter.All) parts += timeFilter.label
            categoryFilter?.let { id ->
                allCategories.firstOrNull { it.id == id }?.let { parts += it.name }
            }
            return if (parts.isEmpty()) null else parts.joinToString(" · ")
        }

    /**
     * 筛选管道：时间过滤 → 类别过滤 → 关键词过滤 → 排序。
     *
     * ⚠️ 跨币种按金额排序，必须先折算成**人民币**再比大小 ——
     *    否则"100 日元"会排在"12 港币"前面（100 > 12，但 100 円 才值 ~4.8 元）。
     */
    val visibleItems: List<ExpenseItem>
        get() {
            val q = query.trim()
            var list = allItems.filter { timeFilter.contains(it.expense.createdAt) }
            categoryFilter?.let { cid -> list = list.filter { it.expense.categoryId == cid } }
            if (q.isNotEmpty()) {
                list = list.filter { it.expense.reason.contains(q, ignoreCase = true) }
            }
            return when (sortOrder) {
                SortOrder.TIME_DESC -> list.sortedByDescending { it.expense.createdAt }
                SortOrder.TIME_ASC -> list.sortedBy { it.expense.createdAt }
                SortOrder.AMOUNT_DESC -> list.sortedByDescending { it.cnyCents }
                SortOrder.AMOUNT_ASC -> list.sortedBy { it.cnyCents }
                SortOrder.CATEGORY -> list.sortedWith(
                    compareBy({ it.category.sortOrder }, { -it.expense.createdAt })
                )
            }
        }

    /** 「记一笔」页顶部：本月小计（人民币分） */
    val thisMonthCnyCents: Long
        get() = allItems.filter { TimeFilter.ThisMonth.contains(it.expense.createdAt) }
            .sumOf { it.cnyCents }

    val thisMonthCount: Int
        get() = allItems.count { TimeFilter.ThisMonth.contains(it.expense.createdAt) }

    /** 「记一笔」页底部：最近 3 笔 */
    val recentItems: List<ExpenseItem>
        get() = allItems.sortedByDescending { it.expense.createdAt }.take(3)

    /** 按月分组（只在按时间排序时用） */
    fun groupByMonth(list: List<ExpenseItem>): List<MonthGroup> =
        list.groupBy { it.expense.createdAt.toYearMonth() }
            .toSortedMap(compareByDescending { it })
            .map { (ym, items) ->
                MonthGroup(
                    label = "${ym.year}年${ym.monthValue}月",
                    subtotalCnyCents = items.sumOf { it.cnyCents },
                    items = items,
                )
            }

    companion object {
        /** 账本锚点：恒定人民币 */
        const val BASE_CURRENCY = "CNY"

        /** 数据库还没读出来时的兜底 */
        private val FALLBACK_CNY = CurrencyEntity(
            code = "CNY", nameZh = "人民币", symbol = "CN¥",
            minorUnits = 2, ratePerCny = 1.0, isEnabled = true, sortOrder = 0, rateUpdatedAt = 0L,
        )

        /**
         * 手动依赖注入的入口。
         * ViewModel 需要 Repository 和 SettingsStore，二者都需要 Context ——
         * 而 Context 只有系统有。这个工厂就是"把系统给的 Context 递进去造出 ViewModel"的地方。
         */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application
                ExpenseViewModel(
                    ExpenseRepository(HoBaoDatabase.get(app)),
                    SettingsStore(app),
                )
            }
        }
    }
}
