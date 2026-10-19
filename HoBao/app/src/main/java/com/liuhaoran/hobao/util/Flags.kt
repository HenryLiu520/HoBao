package com.liuhaoran.hobao.util

/**
 * 币种 → 国旗 emoji。
 *
 * 【为什么用 emoji 而不是图片素材】
 * 零素材、零体积、任何分辨率都清晰、不用管深色模式。
 * 代价：个别定制 ROM 的字体不含区域指示符，会退化成 "HK" 两个字母。
 *
 * 【为什么不在数据库里存一列 flag】
 * 国旗不是货币的**业务属性**，它只是显示用的装饰。
 * 判断标准：这东西变了要不要动数据？不要 → 放代码里。
 */
fun flagEmoji(code: String): String = when (code) {
    "CNY" -> "\uD83C\uDDE8\uD83C\uDDF3"
    "HKD" -> "\uD83C\uDDED\uD83C\uDDF0"
    "JPY" -> "\uD83C\uDDEF\uD83C\uDDF5"
    "USD" -> "\uD83C\uDDFA\uD83C\uDDF8"
    "EUR" -> "\uD83C\uDDEA\uD83C\uDDFA"
    "GBP" -> "\uD83C\uDDEC\uD83C\uDDE7"
    else -> "\uD83C\uDFF3\uFE0F"
}
