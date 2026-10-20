package com.liuhaoran.hobao.data.forex

import android.content.Context
import com.liuhaoran.hobao.data.ExpenseRepository
import com.liuhaoran.hobao.data.db.HoBaoDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 一次同步的结局。**四种都用得上**，别把 Cached / Stale 混成一个 */
sealed interface ForexSyncOutcome {
    /** 刚联网抓到的新数据 */
    data class Fresh(val table: ForexRateTable) : ForexSyncOutcome

    /** 冷却期内，直接用内存里那份 */
    data class Cached(val table: ForexRateTable) : ForexSyncOutcome

    /** 两个源都挂了，退回上一次成功的缓存 —— 界面要标「离线」 */
    data class Stale(val table: ForexRateTable) : ForexSyncOutcome

    /** 网络挂了**而且**从来没成功过 —— 只能如实报错 */
    data class Failed(val error: Throwable?) : ForexSyncOutcome
}

/**
 * 把「抓取 → 写库 → 落盘缓存」串起来。
 *
 * 【为什么要单独一层】
 * WorkManager（每日后台）和界面上的「立即更新」要走**完全同一条路**。
 * 写在两处的话，迟早一边更新了缓存、另一边没更新，界面就会显示错日期。
 */
class ForexSyncer(
    private val cache: ForexCacheStore,
    private val client: ForexClient = ForexClient(),
    /** 写库动作注入进来：生产环境写 Room，测试里写个 map 就行 */
    private val applyRates: suspend (ForexRateTable) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    suspend fun sync(force: Boolean = false): ForexSyncOutcome {
        val now = clock()

        // 进程内冷却：数据一天一更，没必要每次进页面都打一次
        if (!force) {
            val memo = memoTable
            if (memo != null && now - memoAt < ForexConfig.IN_PROCESS_COOLDOWN_MS) {
                return ForexSyncOutcome.Cached(memo)
            }
        }

        return when (val result = withContext(Dispatchers.IO) { client.fetch(ForexConfig.BASE) }) {

            is ForexResult.Success -> {
                try {
                    applyRates(result.table)          // ① 先写库：界面立刻能看到新汇率
                } catch (t: Throwable) {
                    return ForexSyncOutcome.Failed(t)
                }
                cache.save(result.table, now)         // ② 再落盘：下次冷启动有东西可用
                remember(result.table, now)
                ForexSyncOutcome.Fresh(result.table)
            }

            is ForexResult.Failure -> {
                val cached = cache.load()
                if (cached != null) {
                    // 有旧数据就先用旧的，但**必须标记出来**，不能假装是新的。
                    // 记一下时间，避免同一天反复去撞已经挂掉的源。
                    remember(cached, now)
                    ForexSyncOutcome.Stale(cached)
                } else {
                    ForexSyncOutcome.Failed(result.error)
                }
            }
        }
    }

    private fun remember(table: ForexRateTable, at: Long) {
        memoTable = table
        memoAt = at
    }

    companion object {
        /**
         * 进程级的记忆。
         *
         * 放 companion（等价于全局）而不是实例字段 —— 因为每次同步都可能 new 一个
         * ForexSyncer（Worker 一次、界面一次），实例字段记不住上次。
         */
        @Volatile private var memoTable: ForexRateTable? = null
        @Volatile private var memoAt: Long = 0L

        /** 生产环境的组装：Room 仓库 + SharedPreferences 缓存 */
        fun forApp(context: Context): ForexSyncer {
            val app = context.applicationContext
            val repo = ExpenseRepository(HoBaoDatabase.get(app))
            return ForexSyncer(
                cache = ForexCache(app),
                client = ForexClient(),
                applyRates = { table -> repo.applyForexTable(table) },
            )
        }
    }
}
