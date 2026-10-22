package com.liuhaoran.hobao.widget

import android.content.Context
import com.liuhaoran.hobao.data.db.HoBaoDatabase
import com.liuhaoran.hobao.util.formatAmount
import com.liuhaoran.hobao.util.toCnyMinor
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 组件要显示的数据。
 *
 * 【口径】一律是**人民币分**，用每笔账冻结的汇率快照折算 ——
 * 所以改汇率不会让组件里的历史数字变。
 */
data class WidgetSnapshot(
    val todayTotalText: String,
)

suspend fun loadWidgetSnapshot(context: Context): WidgetSnapshot {
    val db = HoBaoDatabase.get(context)
    val expenses = db.expenseDao().getAll()
    val currencies = db.currencyDao().getAll().associateBy { it.code }

    val today = LocalDate.now()
    val todayTotal = expenses
        .filter {
            // 收入在另一张表（incomes），这里读不到；
            // 退款是负数行，会自然抵扣掉
            Instant.ofEpochMilli(it.createdAt).atZone(ZoneId.systemDefault()).toLocalDate() == today
        }
        .sumOf { e ->
            val c = currencies[e.currencyCode] ?: return@sumOf 0L
            toCnyMinor(e.amountMinor, c.minorUnits, c.ratePerCny)
        }

    return WidgetSnapshot(todayTotalText = formatAmount(todayTotal, "CNY ", 2))
}
