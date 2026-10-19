package com.liuhaoran.hobao.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * 主动让桌面组件重画。
 *
 * 【为什么必须有这个】
 * 组件不是"活的" —— 它是**快照**。你在 app 里存了一笔账，
 * 组件不会自己知道。系统给的定时更新最快 30 分钟，还不保证准时。
 * 所以正确做法是：**app 每次写数据后，主动推一次**。
 *
 * 【推的方式】
 * 给组件发一条标准的 APPWIDGET_UPDATE 广播（带上所有实例 id），
 * 系统就会回调 onUpdate，重新读库、重画。
 * 没有实例时 getAppWidgetIds 返回空数组，直接什么都不做。
 */
object WidgetUpdater {

    fun refresh(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val component = ComponentName(context, HoBaoWidgetProvider::class.java)
        val ids = manager.getAppWidgetIds(component)
        if (ids.isEmpty()) return   // 桌面上没有这个组件，不用干活

        val intent = Intent(context, HoBaoWidgetProvider::class.java).apply {
            action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
        }
        context.sendBroadcast(intent)
    }
}
