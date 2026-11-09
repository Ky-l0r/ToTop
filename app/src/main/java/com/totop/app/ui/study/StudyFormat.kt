package com.totop.app.ui.study

import com.totop.app.data.repo.UtcDay
import com.totop.app.domain.model.TermCalendar

/*
 * 学情页的日期文案。
 *
 * 日期列存的是日序号（某一天在民用历里的编号），这里是纯整数换算，不经过 java.time：
 * minSdk 24 上 java.time 不可用，而成绩日期只需要「年月日」三个数。
 */

/**
 * 设备本地时区的「今天」。
 *
 * 为什么不用 UTC 今天：成绩日期是学生填的日历日，凌晨 0 点到 8 点之间（东八区）
 * UTC 还停在前一天，直接用 UTC 会把「今天考的」默认成昨天。
 * 日序号本身与时区无关，这里只是把「本地墙上的日期」换算成对应的日序号。
 */
fun todayLocalEpochDay(nowMillis: Long = System.currentTimeMillis()): Long =
    UtcDay.of(nowMillis + java.util.TimeZone.getDefault().getOffset(nowMillis))

fun formatFullDay(epochDay: Long): String {
    val (year, month, day) = TermCalendar.civilOf(epochDay)
    return year.toString() + "-" + pad2(month) + "-" + pad2(day)
}

/** 日序号 -> `10-15`（列表用）。 */
fun formatMonthDay(epochDay: Long): String {
    val (_, month, day) = TermCalendar.civilOf(epochDay)
    return pad2(month) + "-" + pad2(day)
}

/** `2026-10-15` -> 日序号；格式不对或日期不存在时返回 null。 */
fun parseFullDay(text: String): Long? {
    val parts = text.trim().split("-", "/", ".")
    if (parts.size != 3) return null
    val year = parts[0].toIntOrNull() ?: return null
    val month = parts[1].toIntOrNull() ?: return null
    val day = parts[2].toIntOrNull() ?: return null
    if (year !in MIN_YEAR..MAX_YEAR || month !in 1..MAX_MONTH || day !in 1..MAX_DAY_OF_MONTH) return null
    // 用民用历回算一次，拦住 2 月 30 日这类不存在的日期
    val epochDay = TermCalendar.daysFromCivil(year, month, day)
    val back = TermCalendar.civilOf(epochDay)
    if (back.first != year || back.second != month || back.third != day) return null
    return epochDay
}

private fun pad2(value: Int): String = if (value < 10) "0" + value else value.toString()

/** 可接受的年份下界：早于 2000 年的考试记录只会是误输入。 */
private const val MIN_YEAR: Int = 2000

/** 年份上界。 */
private const val MAX_YEAR: Int = 2100

/** 月份上限。 */
private const val MAX_MONTH: Int = 12

/** 日期上限（具体月份的天数由回算校验兜住）。 */
private const val MAX_DAY_OF_MONTH: Int = 31
