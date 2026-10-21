package com.totop.app.data

import com.totop.app.data.repo.UtcDay
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** UTC 日序号换算的 JVM 单测（daily_stats.date 与 mastery 快照使用）。 */
class UtcDayTest {
    @Test
    @DisplayName("UTC 毫秒到日序号：整点与日界")
    fun ofMillis() {
        assertEquals(0L, UtcDay.of(0L))
        assertEquals(0L, UtcDay.of(UtcDay.MILLIS_PER_DAY - 1))
        assertEquals(1L, UtcDay.of(UtcDay.MILLIS_PER_DAY))
        assertEquals(19_000L, UtcDay.of(19_000L * UtcDay.MILLIS_PER_DAY))
    }

    @Test
    @DisplayName("日序号回到当日零点毫秒")
    fun startOfDay() {
        assertEquals(0L, UtcDay.startOfDayMillis(0L))
        assertEquals(UtcDay.MILLIS_PER_DAY * 5, UtcDay.startOfDayMillis(5L))
    }

    @Test
    @DisplayName("日区间是半开区间，正好覆盖一天")
    fun dayRange() {
        val range = UtcDay.dayRange(3L)
        assertEquals(UtcDay.startOfDayMillis(3L), range.first)
        assertEquals(UtcDay.startOfDayMillis(4L) - 1, range.last)
        assertEquals(UtcDay.MILLIS_PER_DAY - 1, range.last - range.first)
    }

    @Test
    @DisplayName("最近 N 天升序且含今天")
    fun lastDays() {
        val now = 19_010L * UtcDay.MILLIS_PER_DAY + 12_345L
        val days = UtcDay.lastDays(now, 3)
        assertEquals(listOf(19_008L, 19_009L, 19_010L), days)
    }

    @Test
    @DisplayName("days 非正时至少返回今天")
    fun lastDaysBounded() {
        val now = 19_010L * UtcDay.MILLIS_PER_DAY
        assertEquals(listOf(19_010L), UtcDay.lastDays(now, 0))
        assertEquals(listOf(19_010L), UtcDay.lastDays(now, -5))
    }
}
