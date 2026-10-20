package com.liuhaoran.hobao.data.forex

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 一次同步拿到的汇率表。
 *
 * 【语义】`ratesPerBase[code]` = **1 个 base 能换多少 code**。
 * base 自己不在表里（恒等于 1）。
 *
 * 【为什么只存一个方向】
 * 每个基准各存一张表的话，5 个币种要发 5 次请求，而且两个方向会算出不一致的数。
 * 只存一个方向，其它全用三角换算推。
 */
data class ForexRateTable(
    /** 数据日期，`YYYY-MM-DD`，来自响应里的 date 字段（不是本地时间） */
    val date: String,
    /** 基准币种，小写，例如 "cny" */
    val base: String,
    /** 大写代码 → 1 base = X */
    val ratesPerBase: Map<String, Double>,
    /** 这份数据从哪个源来的，排查用 */
    val source: String,
) {

    /** 1 base = ? code（base 自己恒为 1） */
    fun perBase(code: String): Double? {
        if (code.equals(base, ignoreCase = true)) return 1.0
        return ratesPerBase[code.uppercase()]?.takeIf { it > 0.0 }
    }

    /**
     * 任意两个币种之间的汇率：`rate(A→B) = (B/base) ÷ (A/base)`
     *
     * 用 BigDecimal 做这一步除法 —— 它是唯一会「除」的地方，
     * 浮点除法最容易在末尾一位上抖，而金额换算最怕这个。
     * 12 位小数足够，最后才降到 Double 交给账本层（那边按整数分只舍入一次）。
     */
    fun rate(from: String, to: String): Double? {
        if (from.equals(to, ignoreCase = true)) return 1.0
        val f = perBase(from) ?: return null
        val t = perBase(to) ?: return null
        return BigDecimal.valueOf(t)
            .divide(BigDecimal.valueOf(f), CROSS_SCALE, RoundingMode.HALF_UP)
            .toDouble()
    }

    /**
     * 1 CNY = ? code —— 直接喂给 `CurrencyEntity.ratePerCny`。
     * base 是 CNY 时它就是 `ratesPerBase` 的原值（无需换算，零精度损失）。
     */
    fun ratePerCny(code: String): Double? = rate(ForexConfig.BASE, code)

    private companion object {
        const val CROSS_SCALE = 12
    }
}
