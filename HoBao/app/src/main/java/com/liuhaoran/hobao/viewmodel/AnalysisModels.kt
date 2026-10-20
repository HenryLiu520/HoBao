package com.liuhaoran.hobao.viewmodel

/** 分类占比里的一片 */
data class CategorySlice(
    val categoryId: Long,
    val name: String,
    val colorArgb: Long,
    /** 折算后的人民币分 */
    val cnyCents: Long,
    val count: Int,
    /** 占总额的比例，0..1。总额为 0 时是 0 */
    val fraction: Double,
    /** 归档的分类照常计入，但要标出来 */
    val archived: Boolean,
)

/** 趋势上的一个点 */
data class TrendPoint(val label: String, val cnyCents: Long)

/**
 * 横轴的时间粒度。
 *
 * 【为什么粒度是"查出来再折叠"，不是再写五条 SQL】
 * 聚合只下沉到"天"这一层（`dailySums`）。周/月/季/年都是**天的容器**，
 * 在内存里折叠 366 行以内的数据没有任何性能问题，却省掉了四条几乎一样的 SQL
 * （还要各自处理时区、边界、补零）。
 *
 * 【粒度只影响"怎么分组"，绝不影响总额】
 * 换粒度之后，所有桶加起来必须还是同一个数。这不是可选项 ——
 * 如果两条路径算法不同，用户会看到"按天加起来 334.89、按周加起来 334.90"。
 */
enum class TrendGranularity(val label: String, val unitZh: String) {
    Day("日", "天"),
    Week("周", "周"),
    Month("月", "个月"),
    Quarter("季度", "季"),
    Year("年", "年"),
}

/**
 * 分析页需要的**全部**数据，一次算好。
 *
 * 【为什么不把聚合计算放在 Composable 里】
 * 一是那样每次重组都会重算；二是"数字对不对"和"画得对不对"会搅在一起，
 * 出问题时无法判断是哪边错了。
 */
data class AnalysisData(
    val rangeLabel: String,
    val totalCnyCents: Long,
    val count: Int,
    /** 区间总天数（含没记账的日子）—— 这是"日均"的分母 */
    val days: Int,
    val avgPerDayCents: Long,
    val slices: List<CategorySlice>,
    val trend: List<TrendPoint>,
    /** 当前的横轴粒度 */
    val granularity: TrendGranularity,
)
