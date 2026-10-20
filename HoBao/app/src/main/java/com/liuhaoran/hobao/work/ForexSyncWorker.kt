package com.liuhaoran.hobao.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.liuhaoran.hobao.data.forex.ForexSyncOutcome
import com.liuhaoran.hobao.data.forex.ForexSyncer

/**
 * 每天跑一次的汇率同步任务。
 *
 * 【WorkManager 的语义，别当定时器看】
 * 它承诺的是"**最终会跑**"，不是"准点跑" ——
 * 系统会攒着几个任务一起跑（省电），所以实际执行时间可能有几分钟到几小时的偏移。
 * 对"一天一更的汇率"来说完全够用；要准点得用精确闹钟（还得申请权限，更耗电）。
 *
 * 【重试策略】
 * - 抓到新数据 / 用了缓存 → success（有数据就算完成）
 * - 两个源都挂 **且** 从来没成功过 → 交给系统退避重试，3 次后放弃
 */
class ForexSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return when (val outcome = ForexSyncer.forApp(applicationContext).sync(force = true)) {
            is ForexSyncOutcome.Fresh,
            is ForexSyncOutcome.Cached,
            is ForexSyncOutcome.Stale,
            -> Result.success()

            is ForexSyncOutcome.Failed ->
                if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
    }
}
