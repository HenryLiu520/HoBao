package com.liuhaoran.hobao.data.forex

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** 一次抓取的结果。失败时带原因，不返回空表冒充成功 */
sealed interface ForexResult {
    data class Success(val table: ForexRateTable) : ForexResult
    data class Failure(val error: Throwable?) : ForexResult
}

/**
 * 抓一整个币种表并按白名单挑出需要的几个。
 *
 * 【为什么不按币对抓】
 * `/v1/currencies/usd/cny.json` 这种单币对路径实测 **404**，这个源只提供整表。
 * 整表约 7.5KB / 339 个键，挑 5 个出来即可。
 *
 * 【可测性】
 * 网络访问被抽成构造参数 [fetcher]，测试里换成假函数就能覆盖
 * 「主源失败→降级」「双源失败」这类分支，不用真联网。
 */
class ForexClient(
    private val fetcher: (url: String, timeoutMs: Int) -> String = ::httpGet,
    private val timeoutMs: Int = ForexConfig.TIMEOUT_MS,
    private val sources: List<String> = ForexConfig.SOURCES,
    private val fallbackEnabled: Boolean = ForexConfig.FALLBACK_ENABLED,
) {

    fun fetch(base: String = ForexConfig.BASE): ForexResult {
        val baseLower = base.lowercase()
        val candidates = if (fallbackEnabled) sources else sources.take(1)
        var lastError: Throwable? = null

        for (template in candidates) {
            val url = template.format(baseLower)
            try {
                val body = fetcher(url, timeoutMs)
                return ForexResult.Success(parse(body, baseLower, hostOf(url)))
            } catch (t: Throwable) {
                // 超时、404、JSON 结构不对…都归到"这个源不可用"，换下一个
                lastError = t
            }
        }
        return ForexResult.Failure(lastError)
    }

    companion object {

        /**
         * 解析响应体。
         *
         * 【两次校验，缺一不可】
         * 1. `date` 必须符合 `YYYY-MM-DD` —— 它是缓存 key，格式不对后面全乱
         * 2. 白名单独挑了之后不能是空的 —— 空表会让界面显示"未设置"，看起来像数据丢了
         */
        fun parse(body: String, base: String, source: String = "unknown"): ForexRateTable {
            val root = JSONObject(body)

            val date = root.optString("date", "")
            require(ForexConfig.DATE_REGEX.matches(date)) {
                "响应里没有合法的 date 字段：'$date'"
            }

            val block = root.optJSONObject(base)
                ?: throw IllegalStateException("响应里没有 '$base' 这一层")

            val rates = LinkedHashMap<String, Double>()
            ForexConfig.SUPPORTED
                .map { it.uppercase() }
                .filterNot { it.equals(base, ignoreCase = true) }
                .forEach { code ->
                    val key = code.lowercase()
                    if (!block.has(key)) return@forEach
                    val value = block.optDouble(key, Double.NaN)
                    // 过滤 crypto / 脏数据：只要有限且为正的数
                    if (value.isFinite() && value > 0.0) rates[code] = value
                }

            check(rates.isNotEmpty()) { "'$base' 层里没有任何白名单币种" }

            return ForexRateTable(
                date = date,
                base = base.lowercase(),
                ratesPerBase = rates,
                source = source,
            )
        }

        private fun hostOf(url: String): String =
            url.substringAfter("://").substringBefore('/')
    }
}

/** 真正的网络请求。只在 IO 线程调用 */
internal fun httpGet(url: String, timeoutMs: Int): String {
    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = timeoutMs
        readTimeout = timeoutMs
        instanceFollowRedirects = true
        setRequestProperty("Accept", "application/json")
    }
    try {
        val code = conn.responseCode
        if (code !in 200..299) throw IOException("HTTP $code from $url")
        return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    } finally {
        conn.disconnect()
    }
}
