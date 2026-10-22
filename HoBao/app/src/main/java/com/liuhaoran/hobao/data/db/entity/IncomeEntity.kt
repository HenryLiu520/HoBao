package com.liuhaoran.hobao.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 表：incomes（收入账本）
 *
 * 【为什么要独立成表，而不是和花销共用一张】
 * 它们回答的是两个不同的问题：
 *   花销账本：这个月净流出多少？
 *   收入账本：我这个月挣了多少、存了多少？
 *
 * 混在一张表里，每条统计都要写 `WHERE kind = 0` 或 `kind = 1`。
 * 只要漏了一条，就会出现"总额含收入、分类占比不含"这种**不会报错**的错数
 * （重构前真的漏过一次）。
 * 分成两张表之后，这类 bug 从结构上就不可能发生。
 *
 * 【和退款的关系】
 * 没有关系。退款是**负数花销**（在 expenses 里），不是收入 ——
 * 钱本来就是你的，只是退回来了。挣的钱才是收入。
 */
@Entity(
    tableName = "incomes",
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.NO_ACTION,
        )
    ],
    indices = [Index("categoryId"), Index("createdAt"), Index("currencyCode")],
)
data class IncomeEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,

    /** 收入金额，**正整数**（最小货币单位） */
    val amountMinor: Long,

    val currencyCode: String,

    /** 🔒 记账当时的汇率快照，和花销同一套规则 */
    val ratePerCny: Double,

    val reason: String,

    /** → categories.id（kind = 1 的收入分类） */
    val categoryId: Long,

    val createdAt: Long,
    val updatedAt: Long,
)
