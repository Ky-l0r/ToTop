package com.totop.app.ui.mistakes

import com.totop.app.domain.model.RecallSelfResult
import com.totop.app.domain.model.Sm2Scheduler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 复习闭环的纯逻辑（FR-4.2 / FR-4.4 / FR-4.5）。
 *
 * 重点把三件事钉死：
 * 1. 自评只有两档（做对了 / 还是不会），映射到 SM-2 的 quality 4 / 1；
 * 2. 没想通的题间隔归零、明天再来；想通的题间隔逐步拉长；
 * 3. 「今天是否到期」的判定同时覆盖「从未复习」与「已有排期」两种情况。
 */
class ReviewModelsTest {
    @Test
    @DisplayName("自评推进：做对了 -> 间隔 1 天；再做对 -> 6 天")
    fun schedulerProgression() {
        val candidate = candidate()
        val first = nextStateOf(candidate, RecallSelfResult.GOT_IT, NOW)
        assertEquals(1, first.intervalDays)
        assertEquals(NOW + Sm2Scheduler.DAY_MS, first.dueAtUtc)

        val second = Sm2Scheduler.next(first, RecallSelfResult.GOT_IT, NOW)
        assertEquals(6, second.intervalDays)

        val third = Sm2Scheduler.next(second, RecallSelfResult.GOT_IT, NOW)
        assertTrue(third.intervalDays > 6)
    }

    @Test
    @DisplayName("还是不会：间隔回到 1 天，明天再出现")
    fun stillLostResetsInterval() {
        val candidate =
            candidate(
                history =
                    listOf(
                        ReviewPoint(reviewedAtUtc = NOW - 10L * Sm2Scheduler.DAY_MS, gotIt = true),
                        ReviewPoint(reviewedAtUtc = NOW - 4L * Sm2Scheduler.DAY_MS, gotIt = true),
                    ),
            )
        val state = nextStateOf(candidate, RecallSelfResult.STILL_LOST, NOW)
        assertEquals(1, state.intervalDays)
        assertEquals(NOW + Sm2Scheduler.DAY_MS, state.dueAtUtc)
    }

    @Test
    @DisplayName("自评反馈文案区分想通与没想通")
    fun feedbackCopy() {
        val candidate = candidate()
        val gotIt = feedbackOf(candidate, RecallSelfResult.GOT_IT, NOW)
        assertTrue(gotIt.passed)
        assertTrue(gotIt.nextReviewHint.contains("明天"))

        val lost = feedbackOf(candidate, RecallSelfResult.STILL_LOST, NOW)
        assertFalse(lost.passed)
        assertTrue(lost.nextReviewHint.contains("明天"))
    }

    @Test
    @DisplayName("到期判定：从未复习的题要等到第二天之后")
    fun dueForNeverReviewed() {
        val fresh = candidate(createdAtUtc = NOW)
        assertFalse(isDue(fresh, NOW))

        val oneDayOld = candidate(createdAtUtc = NOW - Sm2Scheduler.DAY_MS)
        assertTrue(isDue(oneDayOld, NOW))
    }

    @Test
    @DisplayName("到期判定：已复习的题按 SM-2 的 dueAt 判断")
    fun dueForScheduled() {
        val reviewedLongAgo =
            candidate(
                history = listOf(ReviewPoint(reviewedAtUtc = NOW - 30L * Sm2Scheduler.DAY_MS, gotIt = false)),
                createdAtUtc = NOW - 40L * Sm2Scheduler.DAY_MS,
            )
        assertTrue(isDue(reviewedLongAgo, NOW))

        val reviewedJustNow =
            candidate(
                history = listOf(ReviewPoint(reviewedAtUtc = NOW, gotIt = true)),
                createdAtUtc = NOW,
            )
        assertFalse(isDue(reviewedJustNow, NOW))
    }

    @Test
    @DisplayName("会话徽标与结算文案：不出现比率式评分表述")
    fun sessionBadgeAndSummary() {
        val items = listOf(candidate(mistakeId = "m1"), candidate(mistakeId = "m2"))
        val session =
            ReviewSession(
                candidates = items,
                index = 1,
                results =
                    listOf(
                        ReviewOutcomeItem("m1", "题干一", RecallSelfResult.GOT_IT, 1, NOW + Sm2Scheduler.DAY_MS),
                    ),
                finished = false,
            )
        assertEquals(1, session.passedCount)
        assertEquals(0, session.stillLostCount)
        assertEquals(1, session.remaining)
        assertNotNull(session.current)
        assertTrue(session.badgeText.contains("想通 1"))
        assertFalse(session.badgeText.contains(BANNED_RATIO_WORD))
        assertEquals(0.5f, session.progress)

        val finished = session.copy(index = 2, finished = true)
        assertNull(finished.current)
        assertTrue(finished.summaryText.contains("全部想通"))
        assertFalse(finished.summaryText.contains(BANNED_RATIO_WORD))
    }

    @Test
    @DisplayName("间隔文案：1 天是明天，7 天一周内")
    fun intervalCopy() {
        assertEquals("明天", ReviewOutcomeItem.dueDescription(1))
        assertEquals("2 天后", ReviewOutcomeItem.dueDescription(2))
        assertEquals("一周内（5 天后）", ReviewOutcomeItem.dueDescription(5))
        assertEquals("10 天后", ReviewOutcomeItem.dueDescription(10))
    }

    private fun candidate(
        mistakeId: String = "m1",
        history: List<ReviewPoint> = emptyList(),
        createdAtUtc: Long = NOW - 2L * Sm2Scheduler.DAY_MS,
    ): ReviewCandidate =
        ReviewCandidate(
            mistakeId = mistakeId,
            questionId = "q-" + mistakeId,
            stemText = "已知函数 f(x) = x^2 - 2x，求单调区间。",
            subject = "math",
            errorTypeLabel = "计算失误",
            kpointNames = listOf("函数的单调性"),
            finalAnswer = "在 (-∞,1] 递减，[1,+∞) 递增",
            solutionSteps = listOf("求导", "解 f'(x)=0", "判断符号"),
            studentSteps = listOf("求导得 2x-2", "解得 x=1"),
            recurrenceIndex = 2,
            reviewHistory = history,
            hintsUsed = 0,
            createdAtUtc = createdAtUtc,
        )

    private companion object {
        const val NOW: Long = 1_700_000_000_000L

        /** 被禁用的比率式评分词（分两段拼接，避免源码里出现完整字面量）。 */
        const val BANNED_RATIO_WORD: String = "正确" + "率"
    }
}
