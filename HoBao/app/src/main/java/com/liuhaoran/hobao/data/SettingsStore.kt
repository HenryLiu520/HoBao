package com.liuhaoran.hobao.data

import android.content.Context

/**
 * 轻量的键值设置。
 *
 * 【为什么用 SharedPreferences 而不是 DataStore】
 * 官方现在更推荐 DataStore（异步、类型安全、不会在主线程阻塞）。
 * 但我这里只存**一个字符串**，而且要在 ViewModel 构造时同步读到初始值 ——
 * SharedPreferences 更直接。等设置项多起来（10 个以上）再换 DataStore 才划算。
 *
 * ⚠️ 已知取舍：SharedPreferences 第一次读会碰磁盘。只读一个 key 影响可忽略。
 */
class SettingsStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("hobao_settings", Context.MODE_PRIVATE)

    /** 报告币种 —— 只影响"用哪个币种看"，不影响账本（账本锚点永远是人民币） */
    var reportCurrency: String
        get() = prefs.getString(KEY_REPORT_CURRENCY, BASE_CURRENCY) ?: BASE_CURRENCY
        set(value) {
            prefs.edit().putString(KEY_REPORT_CURRENCY, value).apply()
        }

    companion object {
        /** 账本锚点：恒定人民币 */
        const val BASE_CURRENCY = "CNY"
        private const val KEY_REPORT_CURRENCY = "report_currency"
    }
}
