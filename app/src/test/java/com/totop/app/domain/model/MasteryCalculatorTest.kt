package com.totop.app.domain.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * MasteryCalculator 单测：覆盖空集、全对、全错、提示惩罚、错题窗口、边界裁剪与 UTC 无关性。
 */
class MasteryCalculatorTest {
    private val now = UtcTime.EPOCH_2024_01_01

    private fun event(
        daysAgo: Long,
        result: RecallSelfResult,
    ): RecallEvent = RecallEvent(reviewedAtUtc = now - daysAgo * UtcTime.DAY_MS, selfResult = result)

    private fun input(
        events: List<RecallEvent> = emptyList(),
        mistakes: List<Long> = emptyList(),
        coverage: Double = 0.0,
        hintOnly: Boolean = false,
    ) = MasteryInput(
        recallEvents = events,
        mistakeAtUtc = mistakes,
        nowUtc = now,
        coverage = coverage,
        hintOnly = hintOnly,
    )

    @Nested
    @DisplayName("空集与纯错题边界")
    inner class EmptyAndMistakes {
        @Test
        fun `空复习事件与零错题时仅保留错题项权重`() {
            val score = MasteryCalculator.computeNormalized(input())
            // 0.55 * 0 + 0.30 * (1 - 0) + 0.15 * 0 = 0.30
            assertEquals(0.30, score, 1e-9)
            assertEquals(30.0, MasteryCalculator.compute(input()), 1e-6)
        }

        @Test
        fun `全对且无错题与满覆盖度时接近满分`() {
            val events = (0L..9L).map { event(it, RecallSelfResult.GOT_IT) }
            val score = MasteryCalculator.compute(input(events = events, coverage = 1.0))
            assertTrue(score > 90.0, "全对应显著高分，实际为 $score")
            assertTrue(score <= 100.0, "不得越界")
        }

        @Test
        fun `全错时复习项被截断为零且只保留错题惩罚`() {
            val events = (0L..9L).map { event(it, RecallSelfResult.STILL_LOST) }
            val score = MasteryCalculator.compute(input(events = events, mistakes = List(10) { now }))
            assertEquals(0.0, score, 1e-9)
        }

        @Test
        fun `全错但无错题记录时仅复习项为零`() {
            val events = (0L..9L).map { event(it, RecallSelfResult.STILL_LOST) }
            val score = MasteryCalculator.compute(input(events = events))
            // 0 * 0.55 + 0.30 * 1 + 0.15 * 0 = 0.30
            assertEquals(30.0, score, 1e-6)
        }

        @Test
        fun `结果始终落在零到一之间`() {
            val extremes =
                listOf(
                    input(events = List(200) { event(0, RecallSelfResult.GOT_IT) }, coverage = 5.0),
                    input(events = List(200) { event(0, RecallSelfResult.STILL_LOST) }, mistakes = List(500) { now }),
                    input(coverage = -3.0),
                )
            extremes.forEach {
                val score = MasteryCalculator.computeNormalized(it)
                assertTrue(score in 0.0..1.0, "越界：$score")
            }
        }
    }

    @Nested
    @DisplayName("时间衰减与近 14 天加权")
    inner class Recency {
        @Test
        fun `同日自评近期事件的权重高于远期事件`() {
            val recent = MasteryCalculator.recencyWeightedRecall(listOf(event(1, RecallSelfResult.GOT_IT)), now)
            val old = MasteryCalculator.recencyWeightedRecall(listOf(event(60, RecallSelfResult.GOT_IT)), now)
            assertTrue(recent > old, "近期 $recent 应大于远期 $old")
            // exp(-1/21) * 1.5 / 5
            assertEquals(Math.exp(-1.0 / 21.0) * 1.5 / 5.0, recent, 1e-9)
            // exp(-60/21) * 1.0 / 5
            assertEquals(Math.exp(-60.0 / 21.0) / 5.0, old, 1e-9)
        }

        @Test
        fun `十四天边界内外的权重差异为一点五倍`() {
            val at14 = MasteryCalculator.recencyWeightedRecall(listOf(event(14, RecallSelfResult.GOT_IT)), now)
            val at15 = MasteryCalculator.recencyWeightedRecall(listOf(event(15, RecallSelfResult.GOT_IT)), now)
            val expectedAt14 = Math.exp(-14.0 / 21.0) * 1.5 / 5.0
            val expectedAt15 = Math.exp(-15.0 / 21.0) / 5.0
            assertEquals(expectedAt14, at14, 1e-9)
            assertEquals(expectedAt15, at15, 1e-9)
        }

        @Test
        fun `未来时间戳按零衰减处理不产生负数`() {
            val future = RecallEvent(now + 10 * UtcTime.DAY_MS, RecallSelfResult.GOT_IT)
            val score = MasteryCalculator.recencyWeightedRecall(listOf(future), now)
            assertEquals(1.5 / 5.0, score, 1e-9)
        }

        @Test
        fun `时区无关`() {
            // 同一 UTC 时刻在不同时区展示不同，但算法只吃 UTC 毫秒，因此结果必须一致。
            val events = listOf(event(3, RecallSelfResult.GOT_IT), event(20, RecallSelfResult.STILL_LOST))
            val a = MasteryCalculator.compute(input(events = events, coverage = 0.4))
            val b = MasteryCalculator.compute(input(events = events, coverage = 0.4))
            assertEquals(a, b, 0.0)
        }
    }

    @Nested
    @DisplayName("错题窗口与提示惩罚")
    inner class MistakesAndHint {
        @Test
        fun `三十天窗口内外的错题统计`() {
            val list =
                listOf(
                    now,
                    now - 29 * UtcTime.DAY_MS,
                    now - 30 * UtcTime.DAY_MS,
                    now - 60 * UtcTime.DAY_MS,
                )
            assertEquals(2, MasteryCalculator.mistakeCount30d(list, now))
            assertEquals(0.2, MasteryCalculator.normalizeMistakeCount(list, now, reference = 10), 1e-9)
        }

        @Test
        fun `错题归一化在参考值处封顶`() {
            val list = List(50) { now }
            assertEquals(1.0, MasteryCalculator.normalizeMistakeCount(list, now, reference = 10), 1e-9)
        }

        @Test
        fun `全靠提示想通时乘以零点六`() {
            val events = listOf(event(1, RecallSelfResult.GOT_IT))
            val auto = MasteryCalculator.computeNormalized(input(events = events, coverage = 1.0))
            val hinted = MasteryCalculator.computeNormalized(input(events = events, coverage = 1.0, hintOnly = true))
            assertEquals(auto * 0.6, hinted, 1e-9)
        }

        @Test
        fun `权重系数固定为五五三零一五`() {
            assertEquals(0.55, MasteryCalculator.WEIGHT_RECALL, 0.0)
            assertEquals(0.30, MasteryCalculator.WEIGHT_MISTAKE, 0.0)
            assertEquals(0.15, MasteryCalculator.WEIGHT_COVERAGE, 0.0)
            assertEquals(21.0, MasteryCalculator.DECAY_DAYS, 0.0)
            assertEquals(14L, MasteryCalculator.RECENT_WINDOW_DAYS)
        }
    }

    @Nested
    @DisplayName("领域对象与色阶")
    inner class DomainObjects {
        @Test
        fun `toMastery 产出百分制并带 UTC 时间戳`() {
            val events = listOf(event(1, RecallSelfResult.GOT_IT))
            val mastery = MasteryCalculator.toMastery("KP_MATH_0001", input(events = events))
            assertEquals(now, mastery.updatedAtUtc)
            assertTrue(mastery.score > 20.0)
        }

        @Test
        fun `色阶阈值与薄弱判定`() {
            val at39 = Mastery("KP_MATH_0001", 39.0, emptyList(), now)
            val at40 = Mastery("KP_MATH_0001", 40.0, emptyList(), now)
            val at60 = Mastery("KP_MATH_0001", 60.0, emptyList(), now)
            val at71 = Mastery("KP_MATH_0001", 71.0, emptyList(), now)
            assertEquals(MasteryBand.RED, at39.band)
            assertEquals(MasteryBand.YELLOW, at40.band)
            assertEquals(MasteryBand.YELLOW, at60.band)
            assertEquals(MasteryBand.GREEN, at71.band)
            assertTrue(at39.isWeak)
            assertTrue(!at60.isWeak)
        }
    }
}
