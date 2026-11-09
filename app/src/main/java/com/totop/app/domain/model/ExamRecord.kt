package com.totop.app.domain.model

/**
 * 考试类型（学情页「大考 / 小考」两档）。
 *
 * 刻意只保留两档：周测与随堂练习归小考，月考、期中、期末、模考归大考。
 * 档位再多就要在录入页做选择器，而它在界面上唯一的用途是点的样式与筛选。
 */
enum class ExamKind(
    val wire: String,
    val label: String,
) {
    MINOR("minor", "小考"),
    MAJOR("major", "大考"),
    ;

    companion object {
        /** 未知值按小考处理：误标成大考会让图上冒出醒目的点，误导判断。 */
        fun fromWire(raw: String?): ExamKind = entries.firstOrNull { it.wire == raw } ?: MINOR
    }
}

/**
 * 一次考试中某一科的成绩。
 *
 * [fullScore] 为 0 或负数表示不可计分（缺考、只记了名次没记分），
 * 这类记录不参与平均，也不会把折线拉到 0。
 */
data class ExamScore(
    val subject: String,
    val score: Double,
    val fullScore: Double,
    /** 该科的年级排名（选填）。 */
    val gradeRank: Int? = null,
    /** 该科的班级排名（选填）。 */
    val classRank: Int? = null,
) {
    /** 得分率 0..100。 */
    val rate: Double get() = if (countable) score / fullScore * 100.0 else 0.0

    /** 是否可计分。 */
    val countable: Boolean get() = fullScore > 0.0 && score >= 0.0
}

/**
 * 一次考试（场次）及其各科成绩。
 *
 * 排名分两级：[gradeRank] / [classRank] 是**总分**排名（学校公布的那个名次），
 * 每科自己的排名在 [ExamScore] 里。两者不能混用，界面上必须写明是哪一个。
 */
data class ExamRecord(
    val id: String,
    /** UTC 日序号（自 1970-01-01 起的天数），与 daily_stats.date 同口径。 */
    val occurredOn: Long,
    val kind: ExamKind,
    /** 学生自填的名称（如「10月月考」）；为空表示没填，不编造占位。 */
    val name: String? = null,
    val gradeSize: Int? = null,
    val classSize: Int? = null,
    val gradeRank: Int? = null,
    val classRank: Int? = null,
    val scores: List<ExamScore> = emptyList(),
    val createdAtUtc: Long = 0L,
    val updatedAtUtc: Long = 0L,
) {
    /** 可计分的科目成绩，按录入顺序。 */
    val countableScores: List<ExamScore> get() = scores.filter { it.countable }

    /** 某科的可计分成绩；没有则返回 null。 */
    fun scoreOf(subject: String): ExamScore? = scores.firstOrNull { it.subject == subject && it.countable }

    /**
     * 各科得分率的等权平均；没有可计分科目时返回 null。
     *
     * 等权而不是按满分加权：150 分制与 100 分制科目在学习水平上应当同权，
     * 加权会让高分科目主导结果（详见 docs/学情与成绩趋势.md 4.3）。
     */
    val averageRate: Double?
        get() {
            val list = countableScores
            if (list.isEmpty()) return null
            return list.sumOf { it.rate } / list.size
        }

    /** 该场次是否覆盖了给定的全部科目（「完整场次」判定）。 */
    fun covers(subjects: Set<String>): Boolean = subjects.isNotEmpty() && subjects.all { code -> scoreOf(code) != null }

    /** 展示名：没填就是空串，由界面决定是否显示。 */
    val displayName: String get() = name?.trim().orEmpty()
}
