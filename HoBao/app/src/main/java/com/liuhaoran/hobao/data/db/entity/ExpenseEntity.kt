package com.liuhaoran.hobao.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 表：expenses（账单）
 *
 * 【外键（Foreign Key）是什么】
 * "多的那一边存对方的 id" —— 多条账单属于一个分类，所以账单这边存 categoryId。
 *
 * @ForeignKey 是让数据库**帮你把关**：如果 categoryId 指向一个不存在的分类，
 * 插入会直接失败。这比"数据悄悄写脏了、某天才在界面上崩"要好得多。
 *
 * 【为什么 onDelete 是 NO_ACTION 而不是 CASCADE】
 * CASCADE 的意思是"分类被删就把相关账单一起删掉"—— 那是灾难。
 * 我们的策略是软删除（分类永远不会真的被删），所以这里不会有级联发生，
 * NO_ACTION 就是"什么都不做"，安全兜底。
 *
 * 【索引(Index)有什么用】
 * 数据库默认只给主键建索引。按 categoryId 查、按 createdAt 排序，
 * 数据量一大就会全表扫描。加索引 = 给这些列建"目录"。
 * 代价是写入稍慢一点、占一点空间 —— 对读多写少的记账 app 完全值得。
 */
@Entity(
    tableName = "expenses",
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.NO_ACTION,
        )
    ],
    indices = [
        Index("categoryId"),
        Index("createdAt"),
        Index("currencyCode"),
    ],
)
data class ExpenseEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,

    /**
     * 金额，**整数，最小货币单位**。
     * HK$12.50 → 1250；¥980（日元无小数）→ 980
     * 绝不能用 Double —— 浮点算钱会出错：0.1 + 0.2 = 0.30000000000000004
     */
    val amountMinor: Long,

    /** 记账时实际用的货币代码。存代码，不存符号（符号是显示时才拼的） */
    val currencyCode: String,

    /**
     * 🔒 汇率快照：记账当时「1 人民币 = 多少该币种」。
     *
     * 这是整个数据模型里最重要的一个字段。
     * 因为汇率是"事实的一部分"，不是"当前状态的查询" ——
     * 你 11 月改了汇率，10 月的旧账总额绝不能跟着变。
     *
     * 【为什么这里不另存一个折算后的人民币金额】
     * 因为汇率已经冻结了，折算值由 (amountMinor, minorUnits, ratePerCny)
     * 唯一确定 —— 今天算、明年算，结果一模一样。
     * 存第二份就等于同一件事记两遍，一旦哪天有人只改了一边，两份就打架。
     * 「冻结的是汇率，不是结果。」
     *
     * （万一以后要 export / 要让 SQL 直接 SUM，再加一个物化列也不迟 ——
     *   那是为了查询性能，不是为了正确性。）
     */
    val ratePerCny: Double,

    /** 原因 / 备注 */
    val reason: String,

    /** → categories.id */
    val categoryId: Long,

    /** 记账那一刻（毫秒时间戳）。不是 "2026-10-14" 这种字符串 */
    val createdAt: Long,

    /**
     * 最后修改时刻。由「允许修改记录」这条需求逼出来的字段 ——
     * 你既然能改，就得知道什么时候改的。
     */
    val updatedAt: Long,
) {
    /**
     * 是不是一笔退款 / 返现。
     *
     * 【为什么用"金额为负"表达，而不是再加一个布尔列】
     * 退款就是把钱加回来 —— 它和花销是同一个东西的两个方向。
     * 用符号表达之后：
     *   SUM(amountMinor) 天然就是净额，一行 SQL 就对
     *   不需要子表、不需要缓存列、不需要事务
     * 代价是要在显示层把"支出 / 退款 / 净额"三个数分开算（见 ExpenseItem）。
     */
    val isRefund: Boolean get() = amountMinor < 0
}
