package com.totop.app.ui.study

import com.totop.app.domain.model.TermCalendar
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.TimeZone

/**
 * 学情页日期换算。
 *
 * 这里守的是一个真实踩过的坑：东八区凌晨 0 点到 8 点之间，UTC 还停在前一天，
 * 用 UTC 日序号做「今天」会把当天录入的成绩默认成昨天。
 */
class StudyFormatTest {
    @Test
    @DisplayName("东八区凌晨 3:30 的「今天」是本地 11-09，不是 UTC 的 11-08")
    fun todayFollowsLocalTimeZone() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
            // 2026-11-08T19:30:00Z = 2026-11-09 03:30（+08）
            val now = 1_794_166_200_000L
            val expected = TermCalendar.daysFromCivil(2026, 11, 9)
            assertEquals(expected, todayLocalEpochDay(now))
            assertEquals("2026-11-09", formatFullDay(todayLocalEpochDay(now)))
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test
    @DisplayName("UTC 时区下与日序号原值一致，说明不引入额外偏移")
    fun todayInUtcMatchesEpochDay() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val now = 1_794_166_200_000L
            assertEquals(TermCalendar.daysFromCivil(2026, 11, 8), todayLocalEpochDay(now))
        } finally {
            TimeZone.setDefault(original)
        }
    }
}
