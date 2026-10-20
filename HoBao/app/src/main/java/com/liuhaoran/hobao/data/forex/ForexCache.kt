package com.liuhaoran.hobao.data.forex

import android.content.Context
import org.json.JSONObject

/**
 * 落盘缓存的抽象。
 *
 * 抽成接口只为一件事：**让同步逻辑能在 JVM 单元测试里跑**，
 * 不用为「双源都挂了要回退缓存」这种分支去开模拟器。
 */
interface ForexCacheStore {
    fun load(): ForexRateTable?
    fun save(table: ForexRateTable, fetchedAt: Long)
    fun fetchedAt(): Long
}

/**
 * 用 SharedPreferences 存"上一次成功的整张表"。
 *
 * 【为什么不用 Room】
 * 加一张表就要改 schema → 本项目现在是 `fallbackToDestructiveMigration(true)`，
 * 一动表结构用户数据全清。为一个 5 条记录的小缓存付这个代价不值得。
 *
 * 【为什么 key 是 date 不是本地时间】
 * 数据源一天一更，本地时钟可能和源不一致（时区、设备时间不对）。
 * 用响应里的 `date` 判断新旧，才不会出现"本地以为是新的一天，其实源还是昨天".
 */
class ForexCache(context: Context) : ForexCacheStore {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): ForexRateTable? {
        val raw = prefs.getString(KEY_TABLE, null) ?: return null
        return try {
            val o = JSONObject(raw)
            val date = o.optString("date", "")
            val base = o.optString("base", ForexConfig.BASE)
            if (!ForexConfig.DATE_REGEX.matches(date)) return null

            val block = o.optJSONObject("rates") ?: return null
            val rates = LinkedHashMap<String, Double>()
            block.keys().forEach { code ->
                val v = block.optDouble(code, Double.NaN)
                if (v.isFinite() && v > 0.0) rates[code] = v
            }
            if (rates.isEmpty()) return null

            ForexRateTable(
                date = date,
                base = base,
                ratesPerBase = rates,
                source = o.optString("source", "cache"),
            )
        } catch (t: Throwable) {
            // 缓存坏了不能影响记账，直接当没有
            null
        }
    }

    override fun save(table: ForexRateTable, fetchedAt: Long) {
        val rates = JSONObject().apply {
            table.ratesPerBase.forEach { (code, rate) -> put(code, rate) }
        }
        val o = JSONObject().apply {
            put("date", table.date)
            put("base", table.base)
            put("source", table.source)
            put("rates", rates)
        }
        prefs.edit().putString(KEY_TABLE, o.toString()).putLong(KEY_AT, fetchedAt).apply()
    }

    override fun fetchedAt(): Long = prefs.getLong(KEY_AT, 0L)

    /** 用户是否打开了「自动联网更新」 */
    var autoSyncEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO, value).apply()

    private companion object {
        const val PREFS_NAME = "hobao-forex"
        const val KEY_TABLE = "table"
        const val KEY_AT = "fetchedAt"
        const val KEY_AUTO = "autoSync"
    }
}
