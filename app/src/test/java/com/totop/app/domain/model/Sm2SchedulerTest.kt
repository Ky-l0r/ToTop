package com.totop.app.domain.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Sm2Scheduler 单测：首次间隔、二轮间隔、连续想通间隔递增、答错重置、
 * easiness 上下限与 UTC 到期时间。
 */
class Sm2SchedulerTest {
    private val now = UtcTime.EPOCH_2024_01_01

    @Nested
    @DisplayName("初始状态与首次复习")
    inner class InitialAndFirst {
        @Test
        fun `初始状态尚未到期且间隔为零`() {
            val state = Sm2Scheduler.initialState(now)
            assertEquals(0, state.repetition)
            assertEquals(0, state.intervalDays)
            assertEquals(2.5, state.easiness, 0.0)
            assertEquals(now, state.dueAtUtc)
            assertTrue(Sm2Scheduler.isDue(state, now))
        }

        @Test
        fun `首次想通间隔为一天`() {
            val next = Sm2Scheduler.next(Sm2Scheduler.initialState(now), RecallSelfResult.GOT_IT, now)
            assertEquals(1, next.repetition)
            assertEquals(1, next.intervalDays)
            assertEquals(now + UtcTime.DAY_MS, next.dueAtUtc)
            assertFalse(Sm2Scheduler.isDue(next, now))
            assertTrue(Sm2Scheduler.isDue(next, now + UtcTime.DAY_MS))
        }

        @Test
        fun `第二轮想通间隔为六天`() {
            val first = Sm2Scheduler.next(Sm2Scheduler.initialState(now), RecallSelfResult.GOT_IT, now)
            val second = Sm2Scheduler.next(first, RecallSelfResult.GOT_IT, first.dueAtUtc)
            assertEquals(2, second.repetition)
            assertEquals(6, second.intervalDays)
            assertEquals(first.dueAtUtc + 6 * UtcTime.DAY_MS, second.dueAtUtc)
        }

        @Test
        fun `第三次起按 easiness 递增间隔`() {
            var state = Sm2Scheduler.initialState(now)
            state = Sm2Scheduler.next(state, RecallSelfResult.GOT_IT, now)
            state = Sm2Scheduler.next(state, RecallSelfResult.GOT_IT, now)
            val before = state.intervalDays
            val easiness = state.easiness
            state = Sm2Scheduler.next(state, RecallSelfResult.GOT_IT, now)
            assertEquals(Math.round(before * easiness).toInt(), state.intervalDays)
            assertTrue(state.intervalDays > before)
        }
    }

    @Nested
    @DisplayName("答错重置与边界")
    inner class ResetAndBounds {
        @Test
        fun `还是不会时重置重复次数并从一天重新开始`() {
            var state = Sm2Scheduler.initialState(now)
            repeat(4) { state = Sm2Scheduler.next(state, RecallSelfResult.GOT_IT, now) }
            assertTrue(state.intervalDays >= 6)
            val reset = Sm2Scheduler.next(state, RecallSelfResult.STILL_LOST, now)
            assertEquals(0, reset.repetition)
            assertEquals(1, reset.intervalDays)
            assertEquals(now + UtcTime.DAY_MS, reset.dueAtUtc)
            assertTrue(reset.easiness < state.easiness)
        }

        @Test
        fun `easiness 下限为一点三_连续答错不越界`() {
            var easiness = 2.5
            repeat(20) { easiness = Sm2Scheduler.updateEasiness(easiness, 0) }
            assertEquals(1.3, easiness, 1e-9)
        }

        @Test
        fun `easiness 上限为三点零_连续满分不越界`() {
            var easiness = 2.5
            repeat(20) { easiness = Sm2Scheduler.updateEasiness(easiness, 5) }
            assertEquals(3.0, easiness, 1e-9)
        }

        @Test
        fun `质量分越界时抛异常`() {
            assertThrows<IllegalArgumentException> {
                Sm2Scheduler.nextByQuality(Sm2Scheduler.initialState(now), 6, now)
            }
            assertThrows<IllegalArgumentException> {
                Sm2Scheduler.nextByQuality(Sm2Scheduler.initialState(now), -1, now)
            }
        }

        @Test
        fun `质量分三为通过边界`() {
            val state = Sm2Scheduler.initialState(now)
            val at2 = Sm2Scheduler.nextByQuality(state, 2, now)
            val at3 = Sm2Scheduler.nextByQuality(state, 3, now)
            assertEquals(0, at2.repetition)
            assertEquals(1, at3.repetition)
        }

        @Test
        fun `到期判断仅依赖 UTC 毫秒`() {
            val state = Sm2State(repetition = 1, intervalDays = 1, easiness = 2.5, dueAtUtc = now)
            assertFalse(Sm2Scheduler.isDue(state, now - 1))
            assertTrue(Sm2Scheduler.isDue(state, now))
            assertTrue(Sm2Scheduler.isDue(state, now + 1))
            // 与本地时区无关：只比较 UTC 毫秒差。
            assertEquals(0L, (state.dueAtUtc - now))
        }
    }

    @Nested
    @DisplayName("间隔序列可复现")
    inner class Sequence {
        @Test
        fun `连续六次想通产生确定的间隔序列`() {
            var state = Sm2Scheduler.initialState(now)
            val intervals = mutableListOf<Int>()
            repeat(6) {
                state = Sm2Scheduler.next(state, RecallSelfResult.GOT_IT, now)
                intervals.add(state.intervalDays)
            }
            // 1, 6, round(6 * 2.5) = 15, round(15 * 2.5) = 38 ...
            assertEquals(1, intervals[0])
            assertEquals(6, intervals[1])
            assertEquals(15, intervals[2])
            assertEquals(38, intervals[3])
            assertTrue(intervals.zipWithNext().all { (a, b) -> b > a }, "间隔应单调递增：$intervals")
        }
    }
}
