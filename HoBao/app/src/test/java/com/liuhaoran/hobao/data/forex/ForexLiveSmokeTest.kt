package com.liuhaoran.hobao.data.forex

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 联网冒烟测试：真的去打一次 API，打印换算结果。
 *
 * 默认**跳过**（单元测试不该依赖网络）。
 * 打开方式：
 *
 *     ./gradlew :app:testDebugUnitTest --tests "*ForexLiveSmokeTest*" -Dhobao.live=1 -i
 */
class ForexLiveSmokeTest {

    @Test
    fun `真实拉取一次并打印 USD 与 HKD 的换算`() {
        assumeTrue("默认跳过联网测试，加 -Dhobao.live=1 打开", System.getProperty("hobao.live") == "1")

        when (val result = ForexClient().fetch()) {
            is ForexResult.Success -> {
                val t = result.table
                println("──────── 汇率联网实测 ────────")
                println("数据日期 (date) : ${t.date}")
                println("来源             : ${t.source}")
                println("1 CNY = ? USD    : ${t.ratePerCny("USD")}")
                println("1 CNY = ? HKD    : ${t.ratePerCny("HKD")}")
                println("1 USD = ? CNY    : ${t.rate("USD", "CNY")}")
                println("1 USD = ? HKD    : ${t.rate("USD", "HKD")}")
                println("表里币种         : ${t.ratesPerBase.keys.sorted()}")
                println("─────────────────────────────")

                // ── 再跑一次，证明"同一天只请求一次"真的生效 ──
                val syncer = ForexSyncer(
                    cache = MemoryCache(),
                    client = ForexClient(),
                    applyRates = { },
                )
                val (first, second) = kotlinx.coroutines.runBlocking {
                    syncer.sync(force = true) to syncer.sync(force = false)   // 冷却窗内：应该直接吃内存
                }
                println()
                println("第一次 sync : ${label(first)}")
                println("第二次 sync : ${label(second)}  ← 命中缓存，没有再发请求")
                assertTrue("第二次必须是 Cached", second is ForexSyncOutcome.Cached)

                assertTrue("date 必须是 YYYY-MM-DD", t.date.matches(Regex("""\d{4}-\d{2}-\d{2}""")))
                assertTrue("USD 汇率必须为正", (t.ratePerCny("USD") ?: 0.0) > 0.0)
                assertTrue("不该混进 crypto", t.ratesPerBase.keys.none { it.length != 3 })
            }
            is ForexResult.Failure -> fail("两个源都失败了：${result.error}")
        }
    }

    private fun label(outcome: ForexSyncOutcome): String = when (outcome) {
        is ForexSyncOutcome.Fresh -> "Fresh  (联网新抓，${outcome.table.date})"
        is ForexSyncOutcome.Cached -> "Cached (命中缓存，${outcome.table.date})"
        is ForexSyncOutcome.Stale -> "Stale  (用旧缓存，${outcome.table.date})"
        is ForexSyncOutcome.Failed -> "Failed (${outcome.error?.message})"
    }

    /** 只在内存里记一份，够证明缓存分支就行 */
    private class MemoryCache : ForexCacheStore {
        private var table: ForexRateTable? = null
        override fun load(): ForexRateTable? = table
        override fun save(table: ForexRateTable, fetchedAt: Long) { this.table = table }
        override fun fetchedAt(): Long = 0L
    }
}
