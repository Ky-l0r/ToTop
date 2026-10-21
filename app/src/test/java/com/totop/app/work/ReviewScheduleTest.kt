package com.totop.app.work

import com.totop.app.domain.model.RecallSelfResult
import com.totop.app.domain.model.Sm2Scheduler
import com.totop.app.domain.model.Sm2State
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/** 复习提醒排期的纯函数测试（开发方案 FR-4.4：简化 SM-2 + 每日提醒上限）。 */
class ReviewScheduleTest {
    private val day = Sm2Scheduler.DAY_MS
    private val base = 1_700_000_000_000L

    private fun item(
        id: String,
        dueAt: Long,
        intervalDays: Int = 1,
    ) = ReviewSchedule.ReviewItem(
        mistakeId = id,
        kpointId = "KP_MATH_0001",
        dueAtUtc = dueAt,
        repetition = 1,
        intervalDays = intervalDays,
    )

    @Test
    @DisplayName("只返回已到期条目，按到期时间升序")
    fun dueOnlySorted() {
        val items =
            listOf(
                item("c", base + 3 * day),
                item("a", base - 2 * day),
                item("b", base - 1 * day),
            )
        val due = ReviewSchedule.dueToday(items, nowUtc = base)
        assertEquals(listOf("a", "b"), due.map { it.mistakeId })
    }

    @Test
    @DisplayName("每日提醒有上限，默认 5 条")
    fun dailyLimit() {
        val items = (1..20).map { item("m" + it, base - it * day) }
        assertEquals(ReviewSchedule.DEFAULT_DAILY_LIMIT, ReviewSchedule.dueToday(items, base).size)
        assertEquals(5, ReviewSchedule.DEFAULT_DAILY_LIMIT)
        assertEquals(3, ReviewSchedule.dueToday(items, base, limit = 3).size)
        assertTrue(ReviewSchedule.dueToday(items, base, limit = 0).isEmpty(), "上限为 0 时不提醒")
    }

    @Test
    @DisplayName("到期时间相同时，间隔天数长的优先（遗忘风险更高）")
    fun tieBreakByInterval() {
        val items =
            listOf(
                item("short", base - day, intervalDays = 1),
                item("long", base - day, intervalDays = 30),
            )
        assertEquals(listOf("long", "short"), ReviewSchedule.dueToday(items, base).map { it.mistakeId })
    }

    @Test
    @DisplayName("没有到期条目时不提醒")
    fun nothingDue() {
        val items = listOf(item("a", base + day))
        assertTrue(ReviewSchedule.dueToday(items, base).isEmpty())
        assertEquals(0, ReviewSchedule.dueCount(items, base))
        assertEquals(1, ReviewSchedule.dueCount(items, base + 2 * day))
    }

    @Test
    @DisplayName("下一次提醒时间：取最近一个未来到期时间")
    fun nextReminder() {
        val items = listOf(item("a", base - day), item("b", base + 2 * day), item("c", base + 5 * day))
        assertEquals(base + 2 * day, ReviewSchedule.nextReminderAt(items, base))
    }

    @Test
    @DisplayName("全部已到期时必须顺延一个默认间隔，避免 Worker 立刻反复唤醒")
    fun nextReminderNeverInPast() {
        val items = listOf(item("a", base - day))
        val next = ReviewSchedule.nextReminderAt(items, base)
        assertEquals(base + day, next)
        assertTrue(next > base)
        assertEquals(
            base + 3600_000L,
            ReviewSchedule.nextReminderAt(emptyList(), base, defaultIntervalMillis = 3600_000L),
        )
    }

    @Test
    @DisplayName("从复习历史重放 SM-2 状态：连续做对间隔递增")
    fun replaySequence() {
        val times = listOf(base, base + day, base + 7 * day)
        val gotIt = listOf(true, true, true)
        val state = ReviewSchedule.replay(times, gotIt)
        assertEquals(3, state.repetition)
        // 第 1 次 1 天、第 2 次 6 天、第 3 次 round(6 * easiness)
        assertEquals(Math.round(6 * Sm2Scheduler.DEFAULT_EASINESS).toInt(), state.intervalDays)
        assertTrue(state.dueAtUtc > times.last())
    }

    @Test
    @DisplayName("重放：中间「还是不会」会把 repetition 清零")
    fun replayReset() {
        val times = listOf(base, base + day, base + 7 * day)
        val gotIt = listOf(true, true, false)
        val state = ReviewSchedule.replay(times, gotIt)
        assertEquals(0, state.repetition)
        assertEquals(Sm2Scheduler.FIRST_INTERVAL_DAYS, state.intervalDays)
    }

    @Test
    @DisplayName("重放：空历史得到初始状态；长度不一致抛异常")
    fun replayEdgeCases() {
        val empty = ReviewSchedule.replay(emptyList(), emptyList())
        assertEquals(0, empty.repetition)
        assertEquals(0, empty.intervalDays)
        assertThrows(IllegalArgumentException::class.java) {
            ReviewSchedule.replay(listOf(base), emptyList())
        }
    }

    @Test
    @DisplayName("重放结果与逐个推进 Sm2Scheduler 一致")
    fun replayMatchesScheduler() {
        val times = listOf(base, base + day, base + 3 * day, base + 10 * day)
        val results = listOf(true, false, true, true)
        var expected = Sm2State()
        times.forEachIndexed { index, at ->
            val quality = if (results[index]) Sm2Scheduler.QUALITY_GOT_IT else Sm2Scheduler.QUALITY_STILL_LOST
            expected = Sm2Scheduler.nextByQuality(expected, quality, at)
        }
        val actual = ReviewSchedule.replay(times, results)
        assertEquals(expected.repetition, actual.repetition)
        assertEquals(expected.intervalDays, actual.intervalDays)
        assertEquals(expected.easiness, actual.easiness, 0.0001)
        assertEquals(expected.dueAtUtc, actual.dueAtUtc)
    }

    @Test
    @DisplayName("两档自评到 quality 的映射与 Sm2Scheduler 常量一致")
    fun selfResultMapping() {
        assertEquals(Sm2Scheduler.QUALITY_GOT_IT, 4)
        assertEquals(Sm2Scheduler.QUALITY_STILL_LOST, 1)
        assertEquals("got_it", RecallSelfResult.GOT_IT.wire)
    }

    @Test
    @DisplayName("通知文案随条数变化，0 条不催")
    fun notificationText() {
        assertTrue(ReviewSchedule.notificationText(0).contains("没有"))
        assertTrue(ReviewSchedule.notificationText(1).contains("1 道"))
        assertTrue(ReviewSchedule.notificationText(7).contains("7 道"))
        assertFalse(ReviewSchedule.notificationText(7).contains("0 道"))
    }

    @ParameterizedTest(name = "间隔 {0} 天 -> 再次到期相隔 {0} 天")
    @CsvSource("1", "6", "15", "30")
    fun replayIntervalKeeps(intervalDays: Int) {
        val state = ReviewSchedule.replay(listOf(base, base + day), listOf(true, true))
        assertTrue(state.dueAtUtc - (base + day) == state.intervalDays * day)
        assertTrue(intervalDays > 0)
    }
}
