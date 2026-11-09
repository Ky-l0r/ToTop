package com.totop.app.domain.model

/**
 * 成绩录入的默认值与校验（纯函数，可单测）。
 *
 * 默认满分只是「预填」，学生随时可改：小考的满分经常不是 150 或 100。
 * 校验只输出可读的中文原因，界面直接展示，不做二次翻译。
 */
object ExamDefaults {
    /** 语数英默认满分。 */
    const val MAIN_FULL_SCORE: Double = 150.0

    /** 其余科目默认满分。 */
    const val OTHER_FULL_SCORE: Double = 100.0

    /** 每科默认满分。 */
    fun defaultFullScore(subject: String): Double =
        when (subject) {
            "chinese", "math", "english" -> MAIN_FULL_SCORE
            else -> OTHER_FULL_SCORE
        }

    /**
     * 校验一场考试。
     *
     * @return 错误列表；空表示可以保存
     */
    fun validate(record: ExamRecord): List<String> {
        val errors = ArrayList<String>()
        if (record.scores.isEmpty()) {
            errors.add("至少录一科的分数")
        }
        val duplicated = record.scores.groupBy { it.subject }.filterValues { it.size > 1 }.keys
        if (duplicated.isNotEmpty()) {
            errors.add("同一科目只能录一次")
        }
        record.scores.forEach { score ->
            val label = Subjects.label(score.subject)
            if (score.fullScore <= 0.0) {
                errors.add(label + " 的满分必须大于 0")
            }
            if (score.score < 0.0) {
                errors.add(label + " 的得分不能是负数")
            }
            if (score.fullScore > 0.0 && score.score > score.fullScore) {
                errors.add(label + " 的得分不能超过满分")
            }
            validateRank(errors, label, "班级", score.classRank, record.classSize)
            validateRank(errors, label, "年级", score.gradeRank, record.gradeSize)
        }
        validateRank(errors, "总分", "班级", record.classRank, record.classSize)
        validateRank(errors, "总分", "年级", record.gradeRank, record.gradeSize)
        return errors
    }

    private fun validateRank(
        errors: MutableList<String>,
        label: String,
        scope: String,
        rank: Int?,
        size: Int?,
    ) {
        if (rank == null) return
        if (rank <= 0) {
            errors.add(label + " 的" + scope + "排名必须大于 0")
            return
        }
        if (size != null && size > 0 && rank > size) {
            errors.add(label + " 的" + scope + "排名不能超过" + scope + "人数")
        }
    }
}
