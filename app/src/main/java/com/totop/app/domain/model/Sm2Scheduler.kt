package com.totop.app.domain.model

/**
 * 简化 SM-2 调度状态（开发方案 FR-4.4：间隔重复提醒，简化 SM-2 实现）。
 *
 * 所有时间字段为 UTC 毫秒。
 */
data class Sm2State(
    /** 连续答对次数（quality >= 3 的连续次数）。 */
    val repetition: Int = 0,
    /** 当前间隔（天）。 */
    val intervalDays: Int = 0,
    /** 难度因子（easiness factor）。 */
    val easiness: Double = Sm2Scheduler.DEFAULT_EASINESS,
    /** 下次复习时间（UTC 毫秒）。 */
    val dueAtUtc: Long = 0L,
) {
    init {
        require(repetition >= 0) { "repetition 不得为负" }
        require(intervalDays >= 0) { "intervalDays 不得为负" }
        require(easiness.isFinite()) { "easiness 必须为有限值" }
    }
}

/**
 * 简化 SM-2 间隔重复调度器。
 *
 * 规则（与 SuperMemo SM-2 一致的精简版）：
 * 1. 记忆质量 quality ∈ 0..5；quality >= 3 视为「想通」；
 * 2. 想通：repetition 0 → 间隔 1 天；repetition 1 → 间隔 6 天；之后 → 上一间隔 × easiness（四舍五入，至少 1 天）；
 * 3. 还是不会（quality < 3）：repetition 归零，间隔重新从 1 天开始；
 * 4. easiness' = easiness + (0.1 - (5-q) * (0.08 + (5-q) * 0.02))，下限 1.3、上限 3.0；
 * 5. 下次复习 = 当前时间 + 间隔天数。
 *
 * 产品只有两档自评（FR-4.2），[next] 负责把自评映射到 quality：
 * 「做对了」→ 4，「还是不会」→ 1。需要更细的评分时使用 [nextByQuality]。
 */
object Sm2Scheduler {
    const val MIN_EASINESS: Double = 1.3
    const val MAX_EASINESS: Double = 3.0
    const val DEFAULT_EASINESS: Double = 2.5

    /** 首轮间隔（天）。 */
    const val FIRST_INTERVAL_DAYS: Int = 1

    /** 第二轮间隔（天）。 */
    const val SECOND_INTERVAL_DAYS: Int = 6

    /** 一天的毫秒数。 */
    const val DAY_MS: Long = 86_400_000L

    /** 自评「做对了」映射的 quality。 */
    const val QUALITY_GOT_IT: Int = 4

    /** 自评「还是不会」映射的 quality。 */
    const val QUALITY_STILL_LOST: Int = 1

    /** 初始状态（尚未复习）。 */
    fun initialState(nowUtc: Long): Sm2State =
        Sm2State(
            repetition = 0,
            intervalDays = 0,
            easiness = DEFAULT_EASINESS,
            dueAtUtc = nowUtc,
        )

    /** 按两档自评推进状态。 */
    fun next(
        state: Sm2State,
        selfResult: RecallSelfResult,
        nowUtc: Long,
    ): Sm2State =
        nextByQuality(state, if (selfResult == RecallSelfResult.GOT_IT) QUALITY_GOT_IT else QUALITY_STILL_LOST, nowUtc)

    /**
     * 按 SM-2 质量分推进状态。
     *
     * @param quality 0..5
     */
    fun nextByQuality(
        state: Sm2State,
        quality: Int,
        nowUtc: Long,
    ): Sm2State {
        require(quality in 0..5) { "quality 必须位于 0..5，实际为：$quality" }
        val passed = quality >= 3
        val repetition = if (passed) state.repetition + 1 else 0
        val intervalDays =
            when {
                !passed -> FIRST_INTERVAL_DAYS
                repetition == 1 -> FIRST_INTERVAL_DAYS
                repetition == 2 -> SECOND_INTERVAL_DAYS
                else -> {
                    val previous = state.intervalDays.coerceAtLeast(FIRST_INTERVAL_DAYS)
                    kotlin.math.round(previous * state.easiness).toInt().coerceAtLeast(FIRST_INTERVAL_DAYS)
                }
            }
        val easiness = updateEasiness(state.easiness, quality)
        return Sm2State(
            repetition = repetition,
            intervalDays = intervalDays,
            easiness = easiness,
            dueAtUtc = nowUtc + intervalDays.toLong() * DAY_MS,
        )
    }

    /** SM-2 难度因子更新，结果落在 [MIN_EASINESS, MAX_EASINESS]。 */
    fun updateEasiness(
        easiness: Double,
        quality: Int,
    ): Double {
        require(quality in 0..5) { "quality 必须位于 0..5，实际为：$quality" }
        val delta = 5 - quality
        val updated = easiness + (0.1 - delta * (0.08 + delta * 0.02))
        return updated.coerceIn(MIN_EASINESS, MAX_EASINESS)
    }

    /** 是否到期（UTC 毫秒比较，不受时区影响）。 */
    fun isDue(
        state: Sm2State,
        nowUtc: Long,
    ): Boolean = nowUtc >= state.dueAtUtc
}
