package com.liuhaoran.hobao.data.forex

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * 汇率接入的单元测试。**全部离线跑**，不需要模拟器、不需要网络。
 *
 * 覆盖五件事（对应接入要求的五条）：
 *   1. 正常解析
 *   2. 主源失败 → 自动降级到备用源
 *   3. 双源都失败 → 用 stale 缓存（而不是返回空/0）
 *   4. 三角换算精度
 *   5. crypto 键被过滤
 */
class ForexClientTest {

    /** 仿真实响应：结构完全一致，只是币种精简了几个 */
    private val body = """
        {
          "date": "2026-10-19",
          "cny": {
            "1inch": 12.34,
            "aave": 0.0002,
            "btc": 0.0000021,
            "usd": 0.14904819,
            "hkd": 1.16676293,
            "jpy": 22.46534121,
            "eur": 0.12763482,
            "gbp": 0.11050321
          }
        }
    """.trimIndent()

    // ── 1. 正常解析 ──

    @Test
    fun `解析出日期和五个白名单币种`() {
        val table = ForexClient.parse(body, "cny")

        assertEquals("2026-10-19", table.date)
        assertEquals("cny", table.base)
        assertEquals(setOf("HKD", "JPY", "USD", "EUR", "GBP"), table.ratesPerBase.keys)
        assertEquals(0.14904819, table.ratesPerBase["USD"]!!, 1e-15)
        assertEquals(1.16676293, table.ratesPerBase["HKD"]!!, 1e-15)
    }

    @Test
    fun `base 自己不在表里且 perBase 恒为 1`() {
        val table = ForexClient.parse(body, "cny")

        assertFalse(table.ratesPerBase.containsKey("CNY"))
        assertEquals(1.0, table.perBase("CNY")!!, 0.0)
        // base 是 CNY 时，ratePerCny 就是响应里的原值，一次换算都没有
        assertEquals(0.14904819, table.ratePerCny("USD")!!, 1e-15)
    }

    // ── 5. crypto 被过滤 ──

    @Test
    fun `crypto 键不会进结果`() {
        val table = ForexClient.parse(body, "cny")

        listOf("1INCH", "AAVE", "BTC", "1inch", "aave", "btc").forEach {
            assertNull("$it 不该出现", table.perBase(it))
        }
        assertTrue(table.ratesPerBase.values.all { it > 0.0 })
    }

    @Test
    fun `整层只有 crypto 时解析失败而不是返回空表`() {
        val onlyCrypto = """{"date":"2026-10-19","cny":{"1inch":12.34,"aave":0.0002}}"""
        var thrown = false
        try {
            ForexClient.parse(onlyCrypto, "cny")
        } catch (e: IllegalStateException) {
            thrown = true
        }
        assertTrue("空表必须抛异常，不能冒充成功", thrown)
    }

    @Test
    fun `date 格式不对时解析失败`() {
        val badDate = """{"date":"19/10/2026","cny":{"usd":0.149}}"""
        var thrown = false
        try {
            ForexClient.parse(badDate, "cny")
        } catch (e: IllegalArgumentException) {
            thrown = true
        }
        assertTrue("date 是缓存 key，格式不对必须拒收", thrown)
    }

    // ── 2. 降级 ──

    @Test
    fun `主源失败时自动换备用源`() {
        val calls = mutableListOf<String>()
        val client = ForexClient(
            fetcher = { url, _ ->
                calls += url
                if (url.contains("pages.dev")) throw IOException("模拟主源 500")
                body
            },
        )

        val result = client.fetch("cny")

        assertTrue(result is ForexResult.Success)
        assertEquals("两个源都该被尝试", 2, calls.size)
        assertTrue(
            "来源要记成 jsdelivr",
            (result as ForexResult.Success).table.source.contains("jsdelivr"),
        )
    }

    @Test
    fun `关掉降级时只打一个源`() {
        val calls = mutableListOf<String>()
        val client = ForexClient(
            fetcher = { url, _ -> calls += url; throw IOException("down") },
            fallbackEnabled = false,
        )

        assertTrue(client.fetch("cny") is ForexResult.Failure)
        assertEquals(1, calls.size)
    }

    // ── 3. 双源都失败 ──

    @Test
    fun `双源都失败时返回 Failure`() {
        val client = ForexClient(fetcher = { _, _ -> throw IOException("全部不可达") })

        val result = client.fetch("cny")

        assertTrue(result is ForexResult.Failure)
        assertNotNull((result as ForexResult.Failure).error)
    }

    @Test
    fun `双源都失败但有缓存时返回 stale 且不写库`() = runBlocking {
        val cached = ForexRateTable(
            date = "2026-10-18",
            base = "cny",
            ratesPerBase = mapOf("USD" to 0.15),
            source = "cache",
        )
        val cache = FakeCache(cached)
        var applied = false

        val syncer = ForexSyncer(
            cache = cache,
            client = ForexClient(fetcher = { _, _ -> throw IOException("没网") }),
            applyRates = { applied = true },
            clock = { 1_000_000L },
        )

        val outcome = syncer.sync(force = true)

        assertTrue(outcome is ForexSyncOutcome.Stale)
        assertEquals("2026-10-18", (outcome as ForexSyncOutcome.Stale).table.date)
        assertFalse("没拿到新数据就不该动数据库", applied)
        assertNull("也不该覆盖缓存", cache.saved)
    }

    @Test
    fun `双源都失败且没有缓存时如实失败`() = runBlocking {
        val syncer = ForexSyncer(
            cache = FakeCache(null),
            client = ForexClient(fetcher = { _, _ -> throw IOException("没网") }),
            applyRates = { _ -> },
            clock = { 1_000_000L },
        )

        assertTrue(syncer.sync(force = true) is ForexSyncOutcome.Failed)
    }

    @Test
    fun `成功时先写库再存缓存`() = runBlocking {
        val cache = FakeCache(null)
        var appliedTable: ForexRateTable? = null

        val syncer = ForexSyncer(
            cache = cache,
            client = ForexClient(fetcher = { _, _ -> body }),
            applyRates = { appliedTable = it },
            clock = { 1_000_000L },
        )

        val outcome = syncer.sync(force = true)

        assertTrue(outcome is ForexSyncOutcome.Fresh)
        assertNotNull(appliedTable)
        assertEquals("2026-10-19", cache.saved?.date)
    }

    // ── 4. 三角换算精度 ──

    @Test
    fun `三角换算 USD 到 JPY`() {
        val table = ForexRateTable(
            date = "2026-10-19",
            base = "cny",
            ratesPerBase = mapOf("USD" to 0.14904819, "JPY" to 22.46534121),
            source = "test",
        )

        // 独立算出来的期望值：22.46534121 ÷ 0.14904819 = 150.725354061663
        assertEquals(150.725354061663, table.rate("USD", "JPY")!!, 1e-9)

        // 正反相乘必须回到 1（比"各自等于多少"更能暴露除法精度问题）
        val forward = table.rate("USD", "JPY")!!
        val backward = table.rate("JPY", "USD")!!
        assertEquals(1.0, forward * backward, 1e-9)
    }

    @Test
    fun `同币种换算是 1`() {
        val table = ForexRateTable("2026-10-19", "cny", mapOf("USD" to 0.149), "test")
        assertEquals(1.0, table.rate("USD", "USD")!!, 0.0)
        assertEquals(1.0, table.rate("CNY", "CNY")!!, 0.0)
    }

    @Test
    fun `表里没有的币种返回 null 而不是 0`() {
        val table = ForexRateTable("2026-10-19", "cny", mapOf("USD" to 0.149), "test")
        assertNull(table.rate("CNY", "SGD"))
        assertNull(table.rate("SGD", "USD"))
    }

    // ── 测试替身 ──

    private class FakeCache(private var table: ForexRateTable?) : ForexCacheStore {
        var saved: ForexRateTable? = null
            private set

        override fun load(): ForexRateTable? = table
        override fun save(table: ForexRateTable, fetchedAt: Long) {
            this.table = table
            this.saved = table
        }
        override fun fetchedAt(): Long = 0L
    }
}
