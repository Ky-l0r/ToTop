package com.totop.app.domain.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** 学期边界推算（docs/学情与成绩趋势.md 第二节）。 */
class TermCalendarTest {
    @Test
    @DisplayName("民用历与日序号可以来回换算")
    fun roundTrip() {
        val day = TermCalendar.daysFromCivil(2026, 11, 8)
        val (year, month, date) = TermCalendar.civilOf(day)
        assertEquals(2026, year)
        assertEquals(11, month)
        assertEquals(8, date)
    }

    @Test
    @DisplayName("正月属于上一年的秋季学期")
    fun januaryBelongsToPreviousAutumn() {
        val day = TermCalendar.daysFromCivil(2026, 1, 15)
        val term = TermCalendar.ofDay(day)
        assertEquals("2025A", term.key)
        assertEquals("2025 秋", term.label)
        assertEquals(TermCalendar.daysFromCivil(2025, 8, 1), term.fromDay)
        assertEquals(TermCalendar.daysFromCivil(2026, 2, 1), term.toDayExclusive)
    }

    @Test
    @DisplayName("八月起算秋季学期")
    fun augustStartsAutumn() {
        val term = TermCalendar.ofDay(TermCalendar.daysFromCivil(2026, 8, 1))
        assertEquals("2026A", term.key)
        assertTrue(term.contains(TermCalendar.daysFromCivil(2026, 12, 31)))
    }

    @Test
    @DisplayName("三月属于春季学期")
    fun marchStartsSpring() {
        val term = TermCalendar.ofDay(TermCalendar.daysFromCivil(2026, 3, 1))
        assertEquals("2026S", term.key)
        assertEquals(TermCalendar.daysFromCivil(2026, 2, 1), term.fromDay)
        assertEquals(TermCalendar.daysFromCivil(2026, 8, 1), term.toDayExclusive)
    }

    @Test
    @DisplayName("修改过的开学日只影响当前学期")
    fun overrideOnlyAffectsCurrentTerm() {
        val today = TermCalendar.daysFromCivil(2026, 11, 8)
        val override = TermCalendar.daysFromCivil(2026, 9, 1)
        val term = TermCalendar.ofDay(today, override)
        assertEquals(override, term.fromDay)
        assertEquals(TermCalendar.daysFromCivil(2027, 2, 1), term.toDayExclusive)

        val previous = TermCalendar.ofDay(TermCalendar.daysFromCivil(2026, 5, 1), override)
        assertEquals(TermCalendar.daysFromCivil(2026, 2, 1), previous.fromDay)
    }

    @Test
    @DisplayName("未来的开学日不生效")
    fun futureOverrideIgnored() {
        val today = TermCalendar.daysFromCivil(2026, 9, 1)
        val future = TermCalendar.daysFromCivil(2026, 10, 1)
        val term = TermCalendar.ofDay(today, future)
        assertEquals(TermCalendar.daysFromCivil(2026, 8, 1), term.fromDay)
    }

    @Test
    @DisplayName("recent 按时间倒序且首项为本学期")
    fun recentIsDescending() {
        val today = TermCalendar.daysFromCivil(2026, 11, 8)
        val terms = TermCalendar.recent(today, count = 4)
        assertEquals(4, terms.size)
        assertEquals("2026A", terms[0].key)
        assertEquals("2026S", terms[1].key)
        assertEquals("2025A", terms[2].key)
        assertTrue(terms[0].fromDay > terms[1].fromDay)
    }

    @Test
    @DisplayName("不认识的学期键返回 null")
    fun unknownKeyReturnsNull() {
        val today = TermCalendar.daysFromCivil(2026, 11, 8)
        assertNull(TermCalendar.byKey("1999A", today, count = 4))
    }
}
