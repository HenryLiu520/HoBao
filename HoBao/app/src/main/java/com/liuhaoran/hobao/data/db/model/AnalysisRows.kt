package com.liuhaoran.hobao.data.db.model

/**
 * 聚合查询的返回行。
 *
 * 【为什么单独一个包】
 * 这些不是"表"，是**查询结果**（Room 叫它 POJO）。放在 entity 包里会让人误以为
 * 数据库里有这几张表。
 */

/** 一个分类在区间内的合计（人民币分）和笔数 */
data class CategorySumRow(
    val categoryId: Long,
    val cnyCents: Long,
    val cnt: Int,
)

/** 整个区间一行：总额 + 笔数 */
data class TotalRow(
    val cnyCents: Long,
    val cnt: Int,
)

/** 按天汇总的一行。day 形如 "2026-10-19"（本地时区） */
data class DailySumRow(
    val day: String,
    val cnyCents: Long,
)
