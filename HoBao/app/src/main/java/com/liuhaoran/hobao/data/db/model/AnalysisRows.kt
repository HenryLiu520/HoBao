package com.liuhaoran.hobao.data.db.model

/**
 * 聚合查询的返回行。
 *
 * 【为什么单独一个包】
 * 这些不是"表"，是**查询结果**（Room 叫它 POJO）。放在 entity 包里会让人误以为
 * 数据库里有这几张表。
 */

/** 一个分类在区间内的合计（人民币分）和笔数。**只统计正数行（支出）** */
data class CategorySumRow(
    val categoryId: Long,
    val cnyCents: Long,
    val cnt: Int,
)

/**
 * 区间的三个数。
 *
 * 【为什么不是一个数】
 * 退款是负数行。如果只给一个"净额"，它会变成负的 ——
 * 而"本月花了 −20 元"是一句荒谬的话。
 * 拆开之后：支出恒 ≥ 0，退款恒 ≤ 0，净额可正可负，每个数都有自己的意思。
 */
data class TotalRow(
    /** 支出合计（正数行），恒 ≥ 0 */
    val expenseCnyCents: Long,
    /** 退款合计（负数行），恒 ≤ 0 */
    val refundCnyCents: Long,
    /** 支出笔数（不含退款行） */
    val cnt: Int,
)

/** 按天汇总的一行。day 形如 "2026-10-19"（本地时区）。值是**净额**，可能是负数 */
data class DailySumRow(
    val day: String,
    val cnyCents: Long,
)
