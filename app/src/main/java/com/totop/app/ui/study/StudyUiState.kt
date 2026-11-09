package com.totop.app.ui.study

import com.totop.app.domain.model.ExamKind
import com.totop.app.domain.model.ExamRecord
import com.totop.app.domain.model.ScoreTrend
import com.totop.app.domain.model.Subjects
import com.totop.app.domain.model.Term

/** 本学期学习概览（来自 daily_stats，与成绩无关）。 */
data class ExamOverview(
    val mistakeCount: Int = 0,
    val reviewDoneCount: Int = 0,
    val studySeconds: Long = 0L,
    val activeDays: Int = 0,
    val kpointCount: Int = 0,
) {
    /** 学习时长文案：不足 1 小时给分钟，避免「0.1 小时」。 */
    val studyText: String
        get() {
            val minutes = studySeconds / 60
            return when {
                minutes <= 0L -> "0 分钟"
                minutes < 60 -> minutes.toString() + " 分钟"
                else -> {
                    val hours = minutes / 60
                    val rest = minutes % 60
                    if (rest == 0L) hours.toString() + " 小时" else hours.toString() + " 小时 " + rest + " 分"
                }
            }
        }

    /** 是否一条都没有（用于空态判断）。 */
    val isEmpty: Boolean
        get() = mistakeCount == 0 && reviewDoneCount == 0 && studySeconds == 0L && kpointCount == 0
}

/** AI 解读状态。 */
sealed interface StudyAiState {
    /** 尚未生成（进页面不自动调用）。 */
    data object Idle : StudyAiState

    data object Running : StudyAiState

    data class Done(val text: String) : StudyAiState

    data class Failed(val message: String) : StudyAiState
}

/**
 * 学情页状态。
 *
 * 全页只有一个时间维度（[terms] + [termIndex]），科目与类型都只是视图筛选，
 * 不改动任何数据的存储口径。
 */
data class StudyUiState(
    val loading: Boolean = true,
    /** 学生在设置里勾选的科目（有序）。 */
    val mySubjects: List<String> = emptyList(),
    /** 有成绩记录的科目。 */
    val recordSubjects: Set<String> = emptySet(),
    /**
     * 「完整场次」判定用的科目集。
     *
     * 取「学生勾选的科目」与「本学期出现过记录的科目」的交集：
     * 学生默认勾满十科时，若直接拿十科去判，任何一场考试都不算完整，折线永远是空的。
     */
    val effectiveSubjects: Set<String> = emptySet(),
    /** 选中的科目；null 表示「全部科目」。 */
    val selectedSubject: String? = null,
    val terms: List<Term> = emptyList(),
    val termIndex: Int = 0,
    val kindFilter: ExamKind? = null,
    val trend: ScoreTrend = ScoreTrend(),
    val overview: ExamOverview = ExamOverview(),
    val exams: List<ExamRecord> = emptyList(),
    val globalTarget: Double? = null,
    val subjectTargets: Map<String, Double> = emptyMap(),
    val defaultFullScores: Map<String, Double> = emptyMap(),
    val classSize: Int? = null,
    val gradeSize: Int? = null,
    /** 学生改过的本学期开学日（UTC 日序号）。 */
    val termStartOverride: Long? = null,
    val ai: StudyAiState = StudyAiState.Idle,
    val errorMessage: String? = null,
) {
    /** 当前学期。 */
    val selectedTerm: Term? get() = terms.getOrNull(termIndex)

    /** 科目下拉的可选项：学生勾选的科目 ∪ 有记录的科目，按统一顺序。 */
    val subjectOptions: List<String>
        get() {
            val union = (mySubjects + recordSubjects).toSet()
            return Subjects.ORDER.filter { union.contains(it) }
        }

    /** 「完整场次」判定用的科目集（见 [effectiveSubjects]）。 */
    val requiredSubjects: Set<String> get() = effectiveSubjects

    /** 当前目标：单科看该科目标，全部科目看全局目标。 */
    val currentTarget: Double?
        get() = selectedSubject?.let { subjectTargets[it] } ?: globalTarget

    /** 科目下拉的展示文案。 */
    val subjectLabel: String
        get() = selectedSubject?.let { Subjects.label(it) } ?: "全部科目"

    /** 该科默认满分（录入预填）。 */
    fun fullScoreOf(subject: String): Double =
        defaultFullScores[subject] ?: com.totop.app.domain.model.ExamDefaults.defaultFullScore(subject)
}
