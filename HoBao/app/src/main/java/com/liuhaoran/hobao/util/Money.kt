package com.liuhaoran.hobao.util

import com.liuhaoran.hobao.data.db.entity.CurrencyEntity
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong

/** 10 的 n 次方（n 是小数位数）。JPY → 1；HKD → 100 */
private fun pow10(minorUnits: Int): Long = 10.0.pow(minorUnits.toDouble()).toLong()

/**
 * 把用户输入的 "12.5" 解析成最小货币单位的整数。返回 null = 不合法。
 *
 * 【为什么必须传 minorUnits，不能写死两位】
 *   港币 "12.5"  → 1250（2 位小数）
 *   日元 "980"   → 980 （0 位小数，日元没有"分"）
 * 写死除以 100，日元就会被当成 "9.80 円"。
 *
 * 【日元的小数怎么办】
 * 用户定的规则：日元保留整数，小数**一律向上进位**（980.4 → 981）。
 * 为什么向上而不是四舍五入？因为记账时"少记"比"多记"更危险 ——
 * 少记会让总额偏低，你以为还剩钱，其实花完了。
 */
fun parseAmountToMinor(input: String, minorUnits: Int): Long? {
    val s = input.trim().replace(",", "")
    if (s.isEmpty()) return null

    if (minorUnits == 0) {
        if (!s.matches(Regex("^\\d{0,9}(\\.\\d{1,2})?$"))) return null
        val d = s.toBigDecimalOrNull() ?: return null
        return d.setScale(0, RoundingMode.CEILING).toLong()
    }

    if (!s.matches(Regex("^\\d{0,9}(\\.\\d{1,$minorUnits})?$"))) return null
    val parts = s.split(".")
    val whole = parts[0].ifEmpty { "0" }.toLongOrNull() ?: return null
    val frac = if (parts.size > 1) parts[1].padEnd(minorUnits, '0').toLong() else 0L
    return whole * pow10(minorUnits) + frac
}

/**
 * 最小单位整数 → 显示金额。
 * 例：1250 + "HKD " + 2 → "HKD 12.50"
 *     980  + "JPY " + 0 → "JPY 980"     ← 日元没有小数位
 *
 * @param prefix 显示在数字前面的东西。**现在一律传货币的 ISO 代码**。
 */
fun formatAmount(amountMinor: Long, prefix: String, minorUnits: Int): String {
    val negative = amountMinor < 0
    val absValue = abs(amountMinor)
    val unit = pow10(minorUnits)
    val whole = absValue / unit
    val grouped = whole.toString().reversed().chunked(3).joinToString(",").reversed()
    val head = (if (negative) "-" else "") + prefix + grouped
    if (minorUnits == 0) return head
    return head + "." + (absValue % unit).toString().padStart(minorUnits, '0')
}

/**
 * 【金额显示的唯一入口】。**货币用 ISO 4217 三字母代码，不用符号。**
 *
 *   CNY 188.86 ／ HKD 100.00 ／ JPY 980
 *
 * 【为什么弃用符号】
 * 符号天生有歧义：人民币和日元都写 "¥"，$ 至少被 20 多个国家用。
 * 以前我用 "CN¥" / "JP¥" 这种自造前缀去区分 —— 那是**自己发明的写法**，
 * 既不是国际标准，也和界面上其它地方（芯片写 CNY、汇率页写"人民币"）对不上。
 * 同一件事三种写法，就是"UI 不统一"的根源。
 *
 * 代码没有歧义、全球通用、和银行/交易所的写法一致，而且和芯片上的文字天然统一。
 *
 * 💡 数据库里 currencies.symbol 这一列现在用不到了，但**不删** ——
 *    删列要改表结构、升版本、做迁移，为一个显示偏好不值得。留着，将来
 *    如果要做"紧凑模式"（¥188.86）还能用。
 */
fun formatMoney(amountMinor: Long, currency: CurrencyEntity): String =
    formatAmount(amountMinor, currency.code + " ", currency.minorUnits)

/**
 * 【账本层】外币金额 → 人民币的「分」。**写入时算一次，之后永远不再算。**
 *
 * 公式：amountMinor ÷ 10^minorUnits ÷ ratePerCny × 100
 *
 * ⚠️ 是**除法**，因为 ratePerCny 的语义是「1 人民币 = 多少该币种」。
 *    注意整个表达式一次算完、只在最后舍入一次 ——
 *    如果先算出 1 外币 = 多少人民币（取倒数）、再乘，就会多舍入一次，误差翻倍。
 *
 * 验算：
 *   港币 HK$12.50（1250 分），1 CNY = 1.09 HKD
 *       1250 × 100 ÷ (100 × 1.09) = 1146.8 → 1147 分 = ¥11.47  ✅
 *   日元 ¥980（980），1 CNY = 20.8 JPY
 *       980 × 100 ÷ (1 × 20.8) = 4711.5 → 4712 分 = ¥47.12  ✅
 */
fun toCnyMinor(amountMinor: Long, minorUnits: Int, ratePerCny: Double): Long {
    if (ratePerCny <= 0.0) return 0L
    return (amountMinor.toDouble() * 100.0 / (pow10(minorUnits) * ratePerCny)).roundToLong()
}

/**
 * 【显示层】人民币的「分」→ 某个币种的「最小单位」。**每次显示都算一次，随汇率变。**
 *
 * 这一步是乘法（ratePerCny 的方向天然就是乘），比反向安全。
 * 它算出来的值**不存数据库** —— 它是"你今天用哪个币种看"的角度，不是账本。
 */
fun cnyMinorToMinor(cnyMinor: Long, minorUnits: Int, ratePerCny: Double): Long {
    if (ratePerCny <= 0.0) return 0L
    return (cnyMinor.toDouble() * ratePerCny / 100.0 * pow10(minorUnits)).roundToLong()
}

private val dayFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("M月d日 HH:mm", Locale.getDefault())

/** 毫秒时间戳 → "10月14日 15:03" */
fun Long.toDayTimeText(): String =
    Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).format(dayFormatter)

/** 毫秒时间戳 → "2026 年 10 月" */
fun Long.toYearMonthText(): String {
    val d: LocalDate = Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDate()
    return "${d.year}年${d.monthValue}月"
}
