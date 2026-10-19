package com.liuhaoran.hobao.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.liuhaoran.hobao.MainActivity
import com.liuhaoran.hobao.R
import com.liuhaoran.hobao.ui.quickadd.QuickAddActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 荷包唯一的桌面组件：2×2 四宫格。
 *
 *   上半（跨两格）：今日开销
 *   左下：打开 app
 *   右下：新建一笔（弹出快速记账小窗）
 *
 * 【它是什么】
 * 一个 BroadcastReceiver 子类。桌面会在这些时机给它发广播：
 *   onUpdate   → 组件需要重画（刚加到桌面、被缩放、app 主动请求刷新）
 *   onEnabled  → 第一个实例被创建
 *   onDisabled → 最后一个实例被移除
 *   onDeleted  → 某个实例被删掉
 *
 * 【它不在你的进程里"活着"】
 * onUpdate 只有几秒存活时间，超过就可能被系统杀掉。
 * 而读数据库是异步的 —— 所以要调 `goAsync()`：
 * 它会给你一个 PendingResult，系统会等你调 `finish()` 之后才回收这个广播。
 */
class HoBaoWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val snapshot = loadWidgetSnapshot(context)
                appWidgetIds.forEach { id ->
                    appWidgetManager.updateAppWidget(id, buildViews(context, snapshot))
                }
            } finally {
                pending.finish()
            }
        }
    }
}

private fun buildViews(context: Context, s: WidgetSnapshot): RemoteViews {
    val views = RemoteViews(context.packageName, R.layout.widget_hobao)
    views.setTextViewText(R.id.cell_today_total, s.todayTotalText)

    // 三个区域各自挂一个 PendingIntent。
    // PendingIntent = 「把一张未来要执行的意图交给系统保管」——
    // 组件在桌面进程里，你没法给它挂回调函数。
    //
    // ⚠️ requestCode 必须不同！getActivity 用 (requestCode + Intent) 判等，
    //    两个都用 0 的话系统会认为是同一个 PendingIntent，其中一个点击会被吞掉。
    views.setOnClickPendingIntent(
        R.id.cell_today,
        openApp(context, requestCode = 0),
    )
    views.setOnClickPendingIntent(
        R.id.cell_open,
        openApp(context, requestCode = 0),   // 和上面同一个目标，可以共用
    )
    views.setOnClickPendingIntent(
        R.id.cell_new,
        PendingIntent.getActivity(
            context,
            1,
            Intent(context, QuickAddActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        ),
    )
    return views
}

private fun openApp(context: Context, requestCode: Int): PendingIntent =
    PendingIntent.getActivity(
        context,
        requestCode,
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
