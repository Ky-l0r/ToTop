package com.totop.app.domain.model

/**
 * 掌握度历史快照（开发方案 5.2：mastery 保留最近 90 天快照，供趋势图使用）。
 *
 * [score] 为百分制 0..100，与产品阈值（红 <40 / 黄 40-70 / 绿 >70、薄弱 <60）一致。
 */
data class MasterySnapshot(
    /** UTC 日序号（自 1970-01-01 起的天数），与时区无关。 */
    val dateEpochDayUtc: Long,
    val score: Double,
) {
    init {
        require(score.isFinite() && score in 0.0..100.0) { "掌握度快照必须位于 0..100，实际为：$score" }
    }
}

/**
 * 知识点掌握度，对应开发方案 5.1 `mastery` 表。
 *
 * 注意：掌握度由复习自评 + 错题频次 + 覆盖度计算，不引入任何机器打分口径（FR-3.1）。
 */
data class Mastery(
    /** 受控知识点 ID。 */
    val kpointId: String,
    /** 掌握度百分制 0..100。 */
    val score: Double,
    /** 历史快照（最多保留 90 天）。 */
    val history: List<MasterySnapshot> = emptyList(),
    /** 最近一次重算时间（UTC 毫秒）。 */
    val updatedAtUtc: Long,
) {
    init {
        require(score.isFinite() && score in 0.0..100.0) { "掌握度必须位于 0..100，实际为：$score" }
    }

    /** 色阶。 */
    val band: MasteryBand
        get() =
            when {
                score < RED_BELOW -> MasteryBand.RED
                score <= GREEN_ABOVE -> MasteryBand.YELLOW
                else -> MasteryBand.GREEN
            }

    /** 是否属于需要优先补强（学习路径准入阈值 <60）。 */
    val isWeak: Boolean get() = score < WEAK_BELOW

    val normalized: Double get() = score / 100.0

    companion object {
        /** 红色阈值：<40。 */
        const val RED_BELOW: Double = 40.0

        /** 绿色阈值：>70。 */
        const val GREEN_ABOVE: Double = 70.0

        /** 薄弱阈值：<60（LearningPathPlanner 使用）。 */
        const val WEAK_BELOW: Double = 60.0
    }
}
