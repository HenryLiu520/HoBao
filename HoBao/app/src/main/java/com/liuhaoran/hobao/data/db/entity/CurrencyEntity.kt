package com.liuhaoran.hobao.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 表：currencies（货币与汇率）
 *
 * 【为什么要独立成表，而不是在代码里写死一个币种列表？】
 * 判断标准是那条：「它有没有自己的一组属性？」
 * 货币有：中文名、符号、小数位数、**汇率** —— 有自己一组属性 → 独立成实体。
 *
 * 【主键为什么用 code 而不是自增 id？】
 * 因为 ISO 4217 货币代码（HKD / JPY / USD）本身就是全世界统一的唯一标识，
 * 天然就是主键。再自造一个自增 id 是多余的。
 */
@Entity(tableName = "currencies")
data class CurrencyEntity(
    /** ISO 4217 代码："CNY" / "HKD" / "JPY" / "USD" / "EUR" / "GBP" */
    @PrimaryKey
    val code: String,

    /** 中文名：「港币」 */
    val nameZh: String,

    /** 货币符号：「HK$」/「CN¥」/「JP¥」 */
    val symbol: String,

    /**
     * 小数位数。
     *   HKD → 2 → 1250 显示成 "HK$12.50"
     *   JPY → 0 → 980  显示成 "JP¥980"   （日元没有"分"）
     */
    val minorUnits: Int,

    /**
     * 【汇率】1 人民币 = 多少该币种。人民币自己固定 1.0。
     *
     * 🔴 字段名必须把"方向"写进去，否则半年后你自己都会搞反。
     *    ratePerCny  = 1 CNY 等于多少外币   ← 我们现在用的
     *    rateToCny   = 1 外币 等于多少人民币 ← 方向相反，是它的倒数
     *    这两个是不同含义，混用会让所有账错好几倍，而且**不会报错**。
     *
     * ⚠️ 0.0 = 用户还没填（哨兵值）。判断是否已设置用 `ratePerCny > 0`，
     *    绝不能用 `!= 0` 之外的方式猜，也绝不能让 0 参与除法。
     *
     * ⚠️ 这是「当前汇率」，只在记账时当初始值用。
     *    每条账单里还会存一份「当时的快照」，算总额用的是快照。
     */
    val ratePerCny: Double,

    /** 是否出现在「记账」的币种选择里 */
    val isEnabled: Boolean = false,

    val sortOrder: Int,

    /** 汇率最后一次修改的时刻。0 = 从未设置过 */
    val rateUpdatedAt: Long,
) {
    /**
     * 人民币是账本锚点：汇率恒为 1，且不显示在汇率页里。
     *
     * 【为什么写在类体里、而不是构造参数里】
     * 构造参数 = 数据库的列，每一列都要有存储位置。
     * 类体里这种「没有 backing field、用 getter 算出来」的属性，Room 会**自动忽略**，
     * 因为它不需要存 —— 它随时能由 code 算出来。
     *
     * ⚠️ 反过来：如果你在构造参数里加了一个算出来的字段，Room 会要求库里有这一列，
     *    而且永远是 0/false，因为没人给它赋值。这是新手常踩的坑。
     */
    val isBase: Boolean get() = code == "CNY"
}
