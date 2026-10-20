package com.liuhaoran.hobao.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.liuhaoran.hobao.data.forex.ForexCache
import com.liuhaoran.hobao.data.forex.ForexConfig
import java.util.concurrent.TimeUnit

/**
 * 「自动联网更新」这个开关的全部后台行为。
 *
 * 三件事：读开关、开/关任务、立即跑一次。
 * 界面和 Worker 都只调这里，不直接碰 WorkManager。
 */
object ForexScheduler {

    fun isEnabled(context: Context): Boolean = ForexCache(context).autoSyncEnabled

    /** 开关：打开就排每日任务，关掉就取消 */
    fun setEnabled(context: Context, enabled: Boolean) {
        ForexCache(context).autoSyncEnabled = enabled
        if (enabled) enqueue(context) else cancel(context)
    }

    fun enqueue(context: Context) {
        val request = PeriodicWorkRequestBuilder<ForexSyncWorker>(
            ForexConfig.SYNC_PERIOD_HOURS,
            TimeUnit.HOURS,
        )
            .setConstraints(
                Constraints.Builder()
                    // 没网就不跑 —— 别让系统白唤醒一次
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            ForexConfig.WORK_NAME,
            // 同名任务只留一个：UPDATE 会用新的约束替换旧的，不会叠加
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(ForexConfig.WORK_NAME)
    }
}
