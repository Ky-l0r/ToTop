package com.totop.app.ui.mistakes

import com.totop.app.domain.model.RecallSelfResult
import com.totop.app.domain.model.Sm2Scheduler
import com.totop.app.domain.model.Sm2State
import com.totop.app.work.ReviewSchedule

/**
 * 一道待复习的错题（复习会话的输入）。
 *
 * @param reviewHistory 该错题的历史自评（按时间升序），用于重放 SM-2 状态
 * @param hintsUsed 复习前已解锁的提示级别（0 表示没用提示），参与「全靠提示」判定
 */
data class ReviewCandidate(
    val mistakeId: String,
    val questionId: String,
    val stemText: String,
    val subject: String,
    val errorTypeLabel: String,
    val kpointNames: List<String>,
    val finalAnswer: String,
    val solutionSteps: List<String>,
    val studentSteps: List<String>,
    val recurrenceIndex: Int,
    val reviewHistory: List<ReviewPoint>,
    val hintsUsed: Int,
    val createdAtUtc: Long,
) {
    /** 重放得到的 SM-2 状态：间隔与到期时间都由本地记录确定性地重建。 */
    val schedule: Sm2State
        get() =
            ReviewSchedule.replay(
                reviewedAtUtcList = reviewHistory.map { it.reviewedAtUtc },
                gotItList = reviewHistory.map { it.gotIt },
            )

    /** 已复习次数。 */
    val reviewCount: Int get() = reviewHistory.size

    /** 题干摘要。 */
    val stemSummary: String get() = summarize(stemText, 80)
}

/** 历史复习点（时间 + 自评）。 */
data class ReviewPoint(val reviewedAtUtc: Long, val gotIt: Boolean)

/** 一次自评的即时反馈（不写入任何数据，仅用于界面提示）。 */
data class ReviewFeedback(
    val selfResult: RecallSelfResult,
    val intervalDays: Int,
    val dueAtUtc: Long,
    val nextReviewHint: String,
) {
    /** 是否为「想通了」。 */
    val passed: Boolean get() = selfResult == RecallSelfResult.GOT_IT
}

/**
 * 复习会话的纯逻辑状态（FR-4.2 / FR-4.4）。
 *
 * 关键约定：
 * - **自评零 token**：勾选「做对了 / 还是不会」只写一条 review_log 并推进 SM-2，
 *   不发起任何模型调用；
 * - 会话结束条件只有一个：待复习队列走完；
 * - 顶部徽标只展示「想通 / 还要再看」的条数，不引入任何比率式评分。
 */
data class ReviewSession(
    val candidates: List<ReviewCandidate> = emptyList(),
    val index: Int = 0,
    val results: List<ReviewOutcomeItem> = emptyList(),
    val submitting: Boolean = false,
    val finished: Boolean = false,
) {
    /** 当前题；队列为空或已结束返回 null。 */
    val current: ReviewCandidate? get() = candidates.getOrNull(index)

    /** 剩余题数（含当前）。 */
    val remaining: Int get() = (candidates.size - index).coerceAtLeast(0)

    /** 本轮已想通的题数。 */
    val passedCount: Int get() = results.count { it.selfResult == RecallSelfResult.GOT_IT }

    /** 本轮仍不会的题数。 */
    val stillLostCount: Int get() = results.count { it.selfResult == RecallSelfResult.STILL_LOST }

    /** 顶部徽标文案：显示本轮进度与「想通 / 还要再看」的数量。 */
    val badgeText: String
        get() =
            "本轮 " + results.size + "/" + candidates.size +
                " · 想通 " + passedCount + " · 还要再看 " + stillLostCount

    /** 进度 0..1。 */
    val progress: Float
        get() = if (candidates.isEmpty()) 0f else results.size.toFloat() / candidates.size.toFloat()

    /** 是否已经一道题都没有。 */
    val isEmpty: Boolean get() = candidates.isEmpty()

    /** 结算文案。 */
    val summaryText: String
        get() =
            when {
                results.isEmpty() -> "本轮还没有自评记录。"
                stillLostCount == 0 -> "这一轮 " + passedCount + " 道全部想通了，它们会按更长的间隔再来一次。"
                else ->
                    "这一轮 " + passedCount + " 道想通，" + stillLostCount +
                        " 道还要再看。没想通的题明天会再排一次，不丢人，重复本身就是学习。"
            }
}

/** 单条自评记录（界面展示 + 落库）。 */
data class ReviewOutcomeItem(
    val mistakeId: String,
    val stemSummary: String,
    val selfResult: RecallSelfResult,
    val intervalDays: Int,
    val dueAtUtc: Long,
) {
    /** 自评中文标签。 */
    val label: String get() = selfResult.label

    /** 下次复习的说明文案。 */
    val dueText: String get() = "下次复习：" + dueDescription(intervalDays)

    companion object {
        /** 把间隔天数翻译成学生能理解的文案。 */
        fun dueDescription(intervalDays: Int): String =
            when {
                intervalDays <= 1 -> "明天"
                intervalDays <= 3 -> intervalDays.toString() + " 天后"
                intervalDays <= 7 -> "一周内（" + intervalDays + " 天后）"
                else -> intervalDays.toString() + " 天后"
            }
    }
}

/**
 * 自评 → 新 SM-2 状态（纯函数）。
 *
 * 直接把「做对了 / 还是不会」映射成 quality 4 / 1，交给 [Sm2Scheduler]，
 * 保证间隔推进规则只有一处实现（domain 层）。
 */
fun nextStateOf(
    candidate: ReviewCandidate,
    selfResult: RecallSelfResult,
    nowUtc: Long,
): Sm2State = Sm2Scheduler.next(candidate.schedule, selfResult, nowUtc)

/** 自评的即时反馈文案。 */
fun feedbackOf(
    candidate: ReviewCandidate,
    selfResult: RecallSelfResult,
    nowUtc: Long,
): ReviewFeedback {
    val next = nextStateOf(candidate, selfResult, nowUtc)
    return ReviewFeedback(
        selfResult = selfResult,
        intervalDays = next.intervalDays,
        dueAtUtc = next.dueAtUtc,
        nextReviewHint =
            if (selfResult == RecallSelfResult.GOT_IT) {
                "记下了，这道题 " + ReviewOutcomeItem.dueDescription(next.intervalDays) + "再见。"
            } else {
                "没关系，这道题明天会再出现一次。"
            },
    )
}

/**
 * 是否到期（含「首次进入复习队列」）。
 *
 * 从未复习过的错题若已存在超过 [FIRST_DUE_DAYS] 天，也算到期：
 * 学生刚做错的题当天不需要复习，隔一天再看效果最好。
 */
fun isDue(
    candidate: ReviewCandidate,
    nowUtc: Long,
    firstDueDays: Int = FIRST_DUE_DAYS,
): Boolean {
    if (candidate.reviewHistory.isEmpty()) {
        val ageMillis = nowUtc - candidate.createdAtUtc
        return ageMillis >= firstDueDays.toLong() * Sm2Scheduler.DAY_MS
    }
    return Sm2Scheduler.isDue(candidate.schedule, nowUtc)
}

/** 首次复习的等待天数。 */
const val FIRST_DUE_DAYS: Int = 1
