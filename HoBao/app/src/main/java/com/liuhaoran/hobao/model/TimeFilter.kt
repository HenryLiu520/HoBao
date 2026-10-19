package com.liuhaoran.hobao.model

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

/** 毫秒时间戳 → 本地日期 */
fun Long.toLocalDate(): LocalDate =
    Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDate()

/** 毫秒时间戳 → 年月 */
fun Long.toYearMonth(): YearMonth = YearMonth.from(toLocalDate())

private val shortDate = DateTimeFormatter.ofPattern("yyyy-MM-dd")

/** LocalDate → "2026-10-14" */
fun LocalDate.toShortDateText(): String = format(shortDate)

/**
 * 某个本地日期的**起点**毫秒（当天 00:00:00.000）
 * endOfDay 传 true 则返回当天最后一毫秒。
 *
 * 【为什么需要这两个函数】
 * 官方 DatePickerDialog 交给你的是「UTC 午夜」的毫秒数。
 * 如果你直接拿它当本地时间用，在 UTC+8 会变成当天早上 8 点 ——
 * 仅仅比较日期时没事，但一旦要当边界去卡时间戳就会**差一天**。
 * 所以统一转成"本地某天的起点/终点"再存。
 */
fun LocalDate.toEpochMillisLocal(startOfDay: Boolean): Long {
    val zone = ZoneId.systemDefault()
    val at = if (startOfDay) atStartOfDay(zone) else atTime(23, 59, 59, 999_000_000).atZone(zone)
    return at.toInstant().toEpochMilli()
}

/** 本地日期 → 该日在 UTC 的午夜毫秒（官方 DatePicker 要的就是这个格式） */
fun LocalDate.toUtcMidnightMillis(): Long =
    atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()

/** DatePicker 交回来的 UTC 午夜毫秒 → 它代表的**那一天**（按 UTC 读，不按本地读） */
fun Long.utcMidnightToLocalDate(): LocalDate =
    Instant.ofEpochMilli(this).atZone(java.time.ZoneOffset.UTC).toLocalDate()

/** DatePicker 交回来的 UTC 午夜毫秒 → 本地那一天的起点/终点毫秒 */
fun utcMidnightToLocalDay(utcMillis: Long, startOfDay: Boolean): Long =
    Instant.ofEpochMilli(utcMillis).atZone(java.time.ZoneOffset.UTC)
        .toLocalDate().toEpochMillisLocal(startOfDay)

/**
 * 时间范围筛选器。
 *
 * 用 sealed interface 而不是 enum，因为 Custom 需要带参数。
 * sealed 的好处：when 分支时编译器会检查你穷举了所有情况，漏一个就编译不过。
 *
 * M2 之后这个类会给每个分支再加一个"转成 SQL 的 WHERE 条件"的方法，
 * 但参数仍然用占位符绑定（防注入），而不是拼字符串。
 */
sealed interface TimeFilter {
    /** 界面上显示的名字 */
    val label: String

    /** 这条时间戳是否落在范围内 */
    fun contains(epochMillis: Long): Boolean

    /** 不限 */
    data object All : TimeFilter {
        override val label = "不限"
        override fun contains(epochMillis: Long) = true
    }

    data object Today : TimeFilter {
        override val label = "今天"
        override fun contains(epochMillis: Long) =
            epochMillis.toLocalDate() == LocalDate.now()
    }

    data object ThisWeek : TimeFilter {
        override val label = "本周"
        override fun contains(epochMillis: Long): Boolean {
            val today = LocalDate.now()
            // 周一为一周的第一天（香港/内地习惯，不是周日）
            val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            val d = epochMillis.toLocalDate()
            return !d.isBefore(monday) && !d.isAfter(today)
        }
    }

    data object ThisMonth : TimeFilter {
        override val label = "本月"
        override fun contains(epochMillis: Long) =
            YearMonth.from(epochMillis.toLocalDate()) == YearMonth.now()
    }

    data object ThisQuarter : TimeFilter {
        override val label = "本季"
        override fun contains(epochMillis: Long): Boolean {
            val now = LocalDate.now()
            val d = epochMillis.toLocalDate()
            return d.year == now.year && quarter(d.monthValue) == quarter(now.monthValue)
        }

        /** 1–3 月 = 0，4–6 月 = 1，7–9 月 = 2，10–12 月 = 3 */
        private fun quarter(month: Int) = (month - 1) / 3
    }

    data object ThisYear : TimeFilter {
        override val label = "本年"
        override fun contains(epochMillis: Long) =
            epochMillis.toLocalDate().year == LocalDate.now().year
    }

    /**
     * 自定义区间。
     * startMillis / endMillis 都是**本地时间的当天起点/终点**（见 toEpochMillisLocal），
     * 所以直接做数值比较就是对的，不用再转日期。
     */
    data class Custom(
        val startMillis: Long,
        val endMillis: Long,
    ) : TimeFilter {
        override val label: String
            get() = startMillis.toLocalDate().toShortDateText() +
                " ~ " + endMillis.toLocalDate().toShortDateText()

        override fun contains(epochMillis: Long) =
            epochMillis in startMillis..endMillis
    }
}
