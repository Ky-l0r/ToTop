package com.totop.app.domain.model

import kotlin.math.ceil

/** 趋势筛选条件。 */
data class TrendFilter(
    /** 选中的科目；null 表示「全部科目」。 */
    val subject: String? = null,
    /** 学生勾选的科目集合（「完整场次」的判定依据）。 */
    val requiredSubjects: Set<String> = emptySet(),
    /** 类型筛选；null 表示全部。 */
    val kind: ExamKind? = null,
    /** 目标得分率（百分比）；null 表示没设目标。 */
    val target: Double? = null,
)

/** 折线上的一个点。 */
data class ScorePoint(
    val examId: String,
    val occurredOn: Long,
    /** 第几次（1 起），X 轴等距排布用它。 */
    val index: Int,
    val name: String,
    val kind: ExamKind,
    /** 得分率 0..100。 */
    val rate: Double,
    /** 参与计算的科目数：全部视图下用于标注「3 科」，避免误读。 */
    val subjectCount: Int,
    val gradeRank: Int?,
    val classRank: Int?,
    val gradeSize: Int?,
    val classSize: Int?,
) {
    /** 年级百分位（前 X%）；人数缺失时为 null。 */
    val gradePercentile: Int?
        get() {
            val size = gradeSize ?: return null
            val rank = gradeRank ?: return null
            if (size <= 0 || rank <= 0) return null
            return Math.round(rank.toDouble() / size.toDouble() * 100.0).toInt().coerceIn(1, 100)
        }

    /** 班级百分位（前 X%）。 */
    val classPercentile: Int?
        get() {
            val size = classSize ?: return null
            val rank = classRank ?: return null
            if (size <= 0 || rank <= 0) return null
            return Math.round(rank.toDouble() / size.toDouble() * 100.0).toInt().coerceIn(1, 100)
        }
}

/** 空态原因。 */
enum class TrendEmptyReason {
    /** 该学期一场考试都没录。 */
    NO_RECORDS,

    /** 有考试，但没有一次考全所选科目（仅「全部科目」视图会出现）。 */
    NO_COMPLETE_EXAM,
}

/** 趋势计算结果。 */
data class ScoreTrend(
    val points: List<ScorePoint> = emptyList(),
    val latest: ScorePoint? = null,
    val previous: ScorePoint? = null,
    /** 最近一次与上一次的差值（百分点）。 */
    val delta: Double? = null,
    /** 全部点的平均值。 */
    val average: Double? = null,
    val best: Double? = null,
    /** 每次考试的平均变化量；点不足 2 个时为 null。 */
    val slope: Double? = null,
    val target: Double? = null,
    /** 按当前斜率估算还差几次到目标；无法估算时为 null。 */
    val remainingToTarget: Int? = null,
    val emptyReason: TrendEmptyReason? = null,
    /** 该学期全部场次数（未按科目过滤前）。 */
    val termExamCount: Int = 0,
) {
    val hasPoints: Boolean get() = points.isNotEmpty()
}

/**
 * 学情趋势计算（纯函数，无 Android 依赖，可单测）。
 *
 * 三条口径见 docs/学情与成绩趋势.md：
 * 1. 单科视图取该科全部场次；
 * 2. 全部视图只取「勾选科目全部录入」的场次，得分率各科等权平均；
 * 3. X 轴按第几次等距，排序按真实日期升序。
 */
object ScoreTrendAnalyzer {
    /** 少于该点数不显示趋势判断（斜率、预测都没意义）。 */
    const val MIN_POINTS_FOR_SLOPE: Int = 3

    /** 少于该点数不做目标预测；两三个点预测「还差几次」是误导。 */
    const val MIN_POINTS_FOR_FORECAST: Int = 4

    /** 斜率小于该值视为持平（百分点 / 次）。 */
    private const val FLAT_SLOPE: Double = 0.01

    /**
     * 计算趋势。
     *
     * @param records 已按学期过滤好的场次（函数内部会再排序，不依赖调用方顺序）
     */
    fun analyze(
        records: List<ExamRecord>,
        filter: TrendFilter,
    ): ScoreTrend {
        val termCount = records.count { it.countableScores.isNotEmpty() }
        if (termCount == 0) {
            return ScoreTrend(termExamCount = 0, emptyReason = TrendEmptyReason.NO_RECORDS)
        }

        val filtered = records.filter { record -> filter.kind == null || record.kind == filter.kind }
        val picked =
            filtered
                .mapNotNull { record -> toPoint(record, filter) }
                .sortedWith(compareBy({ it.occurredOn }, { it.examId }))
                .mapIndexed { index, point -> point.copy(index = index + 1) }

        if (picked.isEmpty()) {
            val reason =
                if (filter.subject == null) TrendEmptyReason.NO_COMPLETE_EXAM else TrendEmptyReason.NO_RECORDS
            return ScoreTrend(termExamCount = termCount, emptyReason = reason)
        }

        val latest = picked.last()
        val previous = picked.getOrNull(picked.size - 2)
        val delta = if (previous != null) latest.rate - previous.rate else null
        val average = picked.sumOf { it.rate } / picked.size
        val best = picked.maxOf { it.rate }
        val slope = if (picked.size >= MIN_POINTS_FOR_SLOPE) slopeOf(picked) else null
        val target = filter.target
        val remaining =
            if (target != null && picked.size >= MIN_POINTS_FOR_FORECAST) {
                estimateRemaining(latest.rate, target, slope)
            } else {
                null
            }

        return ScoreTrend(
            points = picked,
            latest = latest,
            previous = previous,
            delta = delta,
            average = average,
            best = best,
            slope = slope,
            target = target,
            remainingToTarget = remaining,
            emptyReason = null,
            termExamCount = termCount,
        )
    }

    /** 单场 -> 折线点；不满足口径时返回 null。 */
    private fun toPoint(
        record: ExamRecord,
        filter: TrendFilter,
    ): ScorePoint? {
        val subject = filter.subject
        if (subject != null) {
            val score = record.scoreOf(subject) ?: return null
            return ScorePoint(
                examId = record.id,
                occurredOn = record.occurredOn,
                index = 0,
                name = record.displayName,
                kind = record.kind,
                rate = score.rate,
                subjectCount = 1,
                gradeRank = score.gradeRank ?: record.gradeRank,
                classRank = score.classRank ?: record.classRank,
                gradeSize = record.gradeSize,
                classSize = record.classSize,
            )
        }
        if (!record.covers(filter.requiredSubjects)) return null
        val rate = record.averageRate ?: return null
        return ScorePoint(
            examId = record.id,
            occurredOn = record.occurredOn,
            index = 0,
            name = record.displayName,
            kind = record.kind,
            rate = rate,
            subjectCount = record.countableScores.size,
            gradeRank = record.gradeRank,
            classRank = record.classRank,
            gradeSize = record.gradeSize,
            classSize = record.classSize,
        )
    }

    /** 最小二乘法斜率（每前进一次考试的得分率变化）。 */
    private fun slopeOf(points: List<ScorePoint>): Double {
        val n = points.size
        val meanX = (1..n).average()
        val meanY = points.sumOf { it.rate } / n
        var numerator = 0.0
        var denominator = 0.0
        points.forEachIndexed { i, point ->
            val dx = (i + 1) - meanX
            numerator += dx * (point.rate - meanY)
            denominator += dx * dx
        }
        return if (denominator == 0.0) 0.0 else numerator / denominator
    }

    /**
     * 到目标还差几次。
     *
     * 已经达标返回 0；趋势持平或下降返回 null（不能靠「再考一次」达成）。
     */
    private fun estimateRemaining(
        latest: Double,
        target: Double,
        slope: Double?,
    ): Int? {
        if (latest >= target) return 0
        if (slope == null || slope <= FLAT_SLOPE) return null
        return ceil((target - latest) / slope).toInt().coerceAtLeast(1)
    }
}
