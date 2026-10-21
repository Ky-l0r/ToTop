package com.totop.app.ui.reviewmaterial

/** 复习资料生成状态。 */
sealed class ReviewMaterialState {
    /** 还没开始。 */
    data object Idle : ReviewMaterialState()

    /** 正在生成（界面显示 loading，可取消）。 */
    data object Generating : ReviewMaterialState()

    /** 生成成功。 */
    data class Success(val material: ReviewMaterial) : ReviewMaterialState()

    /** 失败：必须给出可读原因与重试入口，不能只说「失败」。 */
    data class Failed(val message: String, val retryable: Boolean) : ReviewMaterialState()
}

/**
 * 一份考前复习资料。
 *
 * 字段全部面向「打印出来看」，因此都是纯文本或短列表，
 * 不包含任何需要交互才能理解的内容。
 */
data class ReviewMaterial(
    val subjectLabel: String,
    val examDate: String,
    /** 一句总体说明，例如「这次重点看二次函数与相似三角形」。 */
    val summary: String,
    /** 还需要多练的知识点。 */
    val weakKpoints: List<ReviewKpoint>,
    /** 典型错题（题干 + 错因 + 正确思路）。 */
    val typicalMistakes: List<ReviewMistake>,
    /** 同类练习。 */
    val exercises: List<ReviewExercise>,
    /** 考前复习建议（3-5 条）。 */
    val tips: List<String>,
    /** 生成时间（UTC 毫秒），用于历史记录排序。 */
    val createdAtUtc: Long = 0L,
)

data class ReviewKpoint(
    val name: String,
    /** 错过几次，打印出来比百分比更直观。 */
    val mistakeCount: Int,
    /** 一句话提醒，例如「注意定义域优先」。 */
    val reminder: String = "",
)

data class ReviewMistake(
    val stem: String,
    val errorSummary: String,
    val correctIdea: String,
)

data class ReviewExercise(
    val stem: String,
    val answer: String,
    val analysis: String,
)
