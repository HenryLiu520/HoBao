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
import com.liuhaoran.hobao.data.db.model.DailySumRow
import com.liuhaoran.hobao.data.forex.ForexCache
import com.liuhaoran.hobao.data.forex.ForexSyncOutcome
import com.liuhaoran.hobao.data.forex.ForexSyncer
import com.liuhaoran.hobao.viewmodel.ForexUiState
import com.liuhaoran.hobao.model.MillisRange
import com.liuhaoran.hobao.model.toLocalDate
import com.liuhaoran.hobao.model.toMillisRange
import com.liuhaoran.hobao.model.toShortDateText
import com.liuhaoran.hobao.widget.WidgetUpdater
import com.liuhaoran.hobao.work.ForexScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

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
    /** Application Context —— 只用来通知桌面组件刷新，不持有任何界面 */
    private val app: Application,
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
        // 分析页的数据跟着两张表走：账单变了要重算，分类改名/归档也要重算
        viewModelScope.launch {
            repo.observeExpenses().collect {
                allExpenses = it
                refreshAnalysis()
            }
        }
        viewModelScope.launch {
            repo.observeCategories().collect {
                allCategories = it
                categoriesLoaded = true
                refreshAnalysis()
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

    // ══════════════════ 分析 ══════════════════

    /**
     * 分析页有**自己**的时间筛选器，和回顾页那个互不影响。
     *
     * 默认「本月」—— 打开分析页时最想看的通常是"这个月怎么样"。
     * 只提供 本周/本月/本季/本年 四种：「不限」没有意义（跨全部历史算日均是荒唐的）。
     */
    var analysisFilter by mutableStateOf<TimeFilter>(TimeFilter.ThisMonth)
        private set

    var analysis by mutableStateOf<AnalysisData?>(null)
        private set

    /**
     * 横轴粒度。默认按区间长度自动选（本周/本月按天，本季/本年按月）。
     *
     * 【为什么用户手动选过之后就不再自动改】
     * 自动改会在"切区间"时偷偷把用户刚选的粒度换掉。
     * 选了「日」再切到本年，就该继续看日 —— 他想看的就是一年里的每一天。
     */
    var analysisGranularity by mutableStateOf(TrendGranularity.Day)
        private set

    /** 用户手动选过粒度没有。选过就不再让区间自动覆盖它。 */
    private var granularityPicked = false

    fun onGranularityChange(granularity: TrendGranularity) {
        if (analysisGranularity == granularity) return
        granularityPicked = true
        analysisGranularity = granularity
        refreshAnalysis()
    }

    fun onAnalysisFilterChange(filter: TimeFilter) {
        if (analysisFilter == filter) return
        analysisFilter = filter
        if (!granularityPicked) {
            analysisGranularity = defaultGranularityFor(filter)
        }
        refreshAnalysis()
    }

    /** 区间越长，默认粒度越粗 —— 一年按天是 365 个点，默认给没人看得下去 */
    private fun defaultGranularityFor(filter: TimeFilter): TrendGranularity = when (filter) {
        TimeFilter.ThisQuarter, TimeFilter.ThisYear -> TrendGranularity.Month
        else -> TrendGranularity.Day
    }

    private fun refreshAnalysis() {
        viewModelScope.launch {
            val range = analysisFilter.toMillisRange()
            val total = repo.analysisTotal(range.from, range.toExclusive)
            val sums = repo.categorySums(range.from, range.toExclusive)
            val daily = repo.dailySums(range.from, range.toExclusive)

            val days = daysIn(range)
            val totalCents = total.cnyCents
            val categoriesById = allCategories.associateBy { it.id }

            val slices = sums.map { row ->
                val c = categoriesById[row.categoryId]
                CategorySlice(
                    categoryId = row.categoryId,
                    name = c?.name ?: "未分类",
                    colorArgb = c?.colorArgb ?: 0xFF9E9E9E,
                    cnyCents = row.cnyCents,
                    count = row.cnt,
                    // 总额为 0 时不做除法 —— 0/0 会得到 NaN，图会直接画不出来
                    fraction = if (totalCents > 0) row.cnyCents.toDouble() / totalCents else 0.0,
                    archived = c?.isArchived ?: false,
                )
            }

            analysis = AnalysisData(
                rangeLabel = analysisFilter.label,
                totalCnyCents = totalCents,
                count = total.cnt,
                days = days,
                avgPerDayCents =
                    if (days > 0) Math.round(totalCents.toDouble() / days) else 0L,
                slices = slices,
                trend = buildTrend(daily, range, analysisGranularity),
                granularity = analysisGranularity,
            )
        }
    }

    /**
     * 日均的分母。
     *
     * 【两个决定】
     * 1. 用**自然天数**，不是"有记账的天数" —— 没花钱的日子也是日子，
     *    否则"我每天花多少"会变成"我记账那天花多少"，数字虚高。
     * 2. 只算**已经过去**的天。10 月 20 号看「本月」，分母是 20 不是 31 ——
     *    把未来 11 天算进去会把日均白白压低三成。
     *
     * 昨天跑验证时才发现第 2 条：按 31 天算 10 月的日均是 10.80，
     * 按 20 天算是 16.74。差 55%，而且会随着日子推进天天变。
     */
    private fun daysIn(range: MillisRange): Int {
        val from = range.from.toLocalDate()
        val periodEndExclusive = range.toExclusive.toLocalDate()
        val todayEndExclusive = LocalDate.now().plusDays(1)
        val to = if (periodEndExclusive.isBefore(todayEndExclusive)) {
            periodEndExclusive
        } else {
            todayEndExclusive
        }
        return ChronoUnit.DAYS.between(from, to).toInt().coerceAtLeast(1)
    }

    /**
     * 把「按天」的数据折成图上要用的序列。
     *
     * 【三步】
     * 1. 按粒度造出所有桶，先全部设成 0
     * 2. 把按天的数字累加进对应的桶
     * 3. 换成标签
     *
     * 【为什么必须先造桶再累加】
     * SQL 只返回「有账的那些天」。不补零的话，10/01 和 10/20 会在图上变成相邻的两个点，
     * 看起来像"天天都在花"。中间没账的日子必须是 0，不能是"不存在"。
     *
     * 【为什么只算到今天】
     * 未来的日子不可能有账。补到区间末尾会在右边挂一串假的 0，
     * 还会把真实数据挤出可视区（第一版就踩过）。
     *
     * 【为什么粒度只影响分组、不影响总额】
     * 所有桶加起来必须仍是同一个数。否则用户会看到
     * "按天加起来 334.89、按周加起来 334.90" —— 这种差异没人能解释。
     */
    private fun buildTrend(
        daily: List<DailySumRow>,
        range: MillisRange,
        granularity: TrendGranularity,
    ): List<TrendPoint> {
        val endExclusive = minOf(range.toExclusive.toLocalDate(), LocalDate.now().plusDays(1))
        val start = range.from.toLocalDate()
        if (!start.isBefore(endExclusive)) return emptyList()

        // 1) 造桶。key 用「桶的起始日」的 ISO 字符串，天然唯一
        val buckets = LinkedHashMap<String, Long>()
        var cursor = bucketStart(start, granularity)
        var guard = 0
        while (cursor.isBefore(endExclusive) && guard++ < MAX_TREND_BUCKETS) {
            buckets[cursor.toString()] = 0L
            cursor = nextBucket(cursor, granularity)
        }

        // 2) 累加
        daily.forEach { row ->
            val key = bucketStart(LocalDate.parse(row.day), granularity).toString()
            buckets[key]?.let { buckets[key] = it + row.cnyCents }
        }

        // 3) 标签
        return buckets.map { (key, cents) ->
            TrendPoint(bucketLabel(LocalDate.parse(key), granularity), cents)
        }
    }

    /** 这一天属于哪个桶 —— 返回桶的**起始日**，桶的边界全部对齐自然单位 */
    private fun bucketStart(date: LocalDate, g: TrendGranularity): LocalDate = when (g) {
        TrendGranularity.Day -> date
        // 周一为一周之首 —— 和 TimeFilter.ThisWeek 的语义保持一致
        TrendGranularity.Week -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        TrendGranularity.Month -> date.withDayOfMonth(1)
        TrendGranularity.Quarter -> LocalDate.of(date.year, (date.monthValue - 1) / 3 * 3 + 1, 1)
        TrendGranularity.Year -> LocalDate.of(date.year, 1, 1)
    }

    private fun nextBucket(date: LocalDate, g: TrendGranularity): LocalDate = when (g) {
        TrendGranularity.Day -> date.plusDays(1)
        TrendGranularity.Week -> date.plusWeeks(1)
        TrendGranularity.Month -> date.plusMonths(1)
        TrendGranularity.Quarter -> date.plusMonths(3)
        TrendGranularity.Year -> date.plusYears(1)
    }

    private fun bucketLabel(date: LocalDate, g: TrendGranularity): String = when (g) {
        // 周用"那周周一"的日期当标签，比 "W42" 好读
        TrendGranularity.Day, TrendGranularity.Week ->
            "${date.monthValue}/${date.dayOfMonth.toString().padStart(2, '0')}"
        TrendGranularity.Month -> "${date.monthValue}月"
        TrendGranularity.Quarter -> "Q${(date.monthValue - 1) / 3 + 1}"
        TrendGranularity.Year -> "${date.year}"
    }

    // ══════════════════ 汇率：自动联网更新 ══════════════════

    private val forexCache = ForexCache(app)

    /** 开关的当前值（持久化在 SharedPreferences 里） */
    var forexAutoEnabled by mutableStateOf(ForexScheduler.isEnabled(app))
        private set

    var forexState by mutableStateOf<ForexUiState>(ForexUiState.Idle)
        private set

    /** 上一次成功同步的**数据日期**（来自响应里的 date，不是本地时间） */
    var forexDate by mutableStateOf(forexCache.load()?.date)
        private set

    /** 打开开关时顺手拉一次，用户不用等到明天 */
    fun setForexAuto(enabled: Boolean) {
        ForexScheduler.setEnabled(app, enabled)
        forexAutoEnabled = enabled
        if (enabled) syncForex()
    }

    fun syncForex(force: Boolean = true) {
        if (forexState is ForexUiState.Syncing) return
        forexState = ForexUiState.Syncing
        viewModelScope.launch {
            val outcome = ForexSyncer.forApp(app).sync(force = force)
            forexState = when (outcome) {
                is ForexSyncOutcome.Fresh -> {
                    forexDate = outcome.table.date
                    ForexUiState.Done(stale = false)
                }
                is ForexSyncOutcome.Cached -> {
                    forexDate = outcome.table.date
                    ForexUiState.Done(stale = false)
                }
                is ForexSyncOutcome.Stale -> {
                    // 有旧数据可用，但**必须让用户知道这是旧的**
                    forexDate = outcome.table.date
                    ForexUiState.Done(stale = true)
                }
                is ForexSyncOutcome.Failed ->
                    ForexUiState.Error(outcome.error?.message ?: "网络不可用")
            }
        }
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
        /**
         * 存完之后要做什么。快速记账的悬浮窗靠它来「存完再关」——
         * 如果先 finish() 再存，Activity 一销毁 ViewModel 就被清掉，
         * viewModelScope 里的协程会被取消，这笔账就丢了。
         */
        onSaved: (() -> Unit)? = null,
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
            WidgetUpdater.refresh(app)   // 组件是快照，存完必须主动推
            onSaved?.invoke()
        }
    }

    fun deleteExpense(expense: ExpenseEntity) {
        viewModelScope.launch {
            repo.deleteExpense(expense)
            WidgetUpdater.refresh(app)
        }
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
        }.also { WidgetUpdater.refresh(app) }

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

        /** 趋势桶数上限（防呆：粒度选「日」又选了很长的区间时不至于造出十万个桶） */
        private const val MAX_TREND_BUCKETS = 400

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
                    app,
                )
            }
        }
    }
}
