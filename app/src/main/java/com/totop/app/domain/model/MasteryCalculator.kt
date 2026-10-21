package com.totop.app.domain.model

import kotlin.math.exp

/** 一次复习自评事件。时间为 UTC 毫秒。 */
data class RecallEvent(
    /** 复习发生时间（UTC 毫秒）。 */
    val reviewedAtUtc: Long,
    /** 自评结果：做对了 +1 / 还是不会 -1。 */
    val selfResult: RecallSelfResult,
)

/**
 * 掌握度计算入参。所有时间为 UTC 毫秒，算法不使用任何时区与系统时钟。
 */
data class MasteryInput(
    /** 复习自评事件（可乱序，内部按 UTC 时间处理）。 */
    val recallEvents: List<RecallEvent> = emptyList(),
    /** 近 30 天错题的日期戳（UTC 毫秒，仅取日期窗口，不看时刻）。 */
    val mistakeAtUtc: List<Long> = emptyList(),
    /** 计算基准时刻（UTC 毫秒）。 */
    val nowUtc: Long,
    /** 覆盖度 0..1：该知识点是否已被充分练习。 */
    val coverage: Double = 0.0,
    /** 是否「全靠提示想通」（FR-3.2：全靠提示想通 x0.6；纯自主想通 x1.0）。 */
    val hintOnly: Boolean = false,
    /** 错题频次归一化参考值：达到该次数即计为 1.0。 */
    val mistakeNormalizeReference: Int = MasteryCalculator.DEFAULT_MISTAKE_NORMALIZE_REFERENCE,
)

/**
 * 知识点掌握度算法（开发方案 4.3.2 的复习自评加权版：只看自评与错题频次，不做任何机器打分）。
 *
 * ```
 * mastery = clamp01(
 *     0.55 * recencyWeightedRecall
 *   + 0.30 * (1 - normalize(mistakeCount30d))
 *   + 0.15 * coverage
 * ) * hintPenalty
 * ```
 *
 * - recencyWeightedRecall：自评「会」+1、「还是不会」-1，按 exp(-deltaDays/21) 衰减累加，
 *   近 14 天额外 x1.5，最后除以 [RECALL_SCALE] 归一到 0..1（负值截断为 0）；
 * - normalize(mistakeCount30d)：近 30 天错题数 / [MasteryInput.mistakeNormalizeReference]，截断到 0..1；
 * - hintPenalty：全靠提示想通 x0.6，否则 x1.0。
 *
 * 本对象是纯 Kotlin：不引用 Android API、不读系统时钟、不做网络与数据库访问，
 * 可在 JVM 单测中完全复现。
 */
object MasteryCalculator {
    /** 时间衰减半衰参数（天）。 */
    const val DECAY_DAYS: Double = 21.0

    /** 「近 14 天加权」窗口（天）。 */
    const val RECENT_WINDOW_DAYS: Long = 14L

    /** 近期事件权重倍数。 */
    const val RECENT_WEIGHT: Double = 1.5

    /** 错题统计窗口（天）。 */
    const val MISTAKE_WINDOW_DAYS: Long = 30L

    /** 全靠提示想通的惩罚系数。 */
    const val HINT_ONLY_PENALTY: Double = 0.6

    /** 纯自主想通的系数。 */
    const val AUTO_RECALL_PENALTY: Double = 1.0

    /** 复习项权重。 */
    const val WEIGHT_RECALL: Double = 0.55

    /** 错题频次项权重。 */
    const val WEIGHT_MISTAKE: Double = 0.30

    /** 覆盖度项权重。 */
    const val WEIGHT_COVERAGE: Double = 0.15

    /** 错题频次归一化参考值（次）。 */
    const val DEFAULT_MISTAKE_NORMALIZE_REFERENCE: Int = 10

    /** recall 分量饱和上限：加权分值累计到该值即视为「完全回想起」。 */
    const val RECALL_SCALE: Double = 5.0

    /** 一天的毫秒数（UTC，跨时区无关）。 */
    const val DAY_MS: Long = 86_400_000L

    /** 返回 0..1 的掌握度。 */
    fun computeNormalized(input: MasteryInput): Double {
        require(input.mistakeNormalizeReference > 0) { "mistakeNormalizeReference 必须为正数" }
        val recall = recencyWeightedRecall(input.recallEvents, input.nowUtc)
        val mistakePenalty = 1.0 - normalizeMistakeCount(input.mistakeAtUtc, input.nowUtc)
        val coverage = input.coverage.coerceIn(0.0, 1.0)
        val weighted = WEIGHT_RECALL * recall + WEIGHT_MISTAKE * mistakePenalty + WEIGHT_COVERAGE * coverage
        return clamp01(weighted) * if (input.hintOnly) HINT_ONLY_PENALTY else AUTO_RECALL_PENALTY
    }

    /** 返回 0..100 的百分制掌握度。 */
    fun compute(input: MasteryInput): Double = computeNormalized(input) * 100.0

    /** 便捷构造 [Mastery] 领域对象（不含历史快照）。 */
    fun toMastery(
        kpointId: String,
        input: MasteryInput,
    ): Mastery =
        Mastery(
            kpointId = kpointId,
            score = compute(input),
            history = emptyList(),
            updatedAtUtc = input.nowUtc,
        )

    /**
     * 复习自评的近期加权分，归一到 0..1。
     *
     * 事件晚于 [nowUtc]（脏数据）按 deltaDays = 0 处理，不产生负数衰减；
     * 结果落在 [0,1]，负分整体截断为 0。
     */
    fun recencyWeightedRecall(
        events: List<RecallEvent>,
        nowUtc: Long,
    ): Double {
        if (events.isEmpty()) return 0.0
        val sum =
            events.sumOf { event ->
                val deltaDays = elapsedDays(event.reviewedAtUtc, nowUtc).toDouble()
                val decay = exp(-deltaDays / DECAY_DAYS)
                val recency = if (deltaDays <= RECENT_WINDOW_DAYS) RECENT_WEIGHT else 1.0
                event.selfResult.score * decay * recency
            }
        return (sum / RECALL_SCALE).coerceIn(0.0, 1.0)
    }

    /** 近 30 天错题数（不含 30 天前、忽略未来时间戳）。 */
    fun mistakeCount30d(
        mistakeAtUtc: List<Long>,
        nowUtc: Long,
    ): Int =
        mistakeAtUtc.count { at ->
            val delta = elapsedDays(at, nowUtc)
            delta in 0 until MISTAKE_WINDOW_DAYS
        }

    /** 错题频次归一化到 0..1。 */
    fun normalizeMistakeCount(
        mistakeAtUtc: List<Long>,
        nowUtc: Long,
        reference: Int = DEFAULT_MISTAKE_NORMALIZE_REFERENCE,
    ): Double {
        require(reference > 0) { "reference 必须为正数" }
        return (mistakeCount30d(mistakeAtUtc, nowUtc).toDouble() / reference).coerceIn(0.0, 1.0)
    }

    /** UTC 毫秒差值换算为整天数；未来时间戳视为 0。 */
    fun elapsedDays(
        atUtc: Long,
        nowUtc: Long,
    ): Long = (nowUtc - atUtc).coerceAtLeast(0L) / DAY_MS

    private fun clamp01(value: Double): Double =
        when {
            value.isNaN() -> 0.0
            value < 0.0 -> 0.0
            value > 1.0 -> 1.0
            else -> value
        }
}
