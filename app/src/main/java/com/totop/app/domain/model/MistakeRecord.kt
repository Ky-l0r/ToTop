package com.totop.app.domain.model

/**
 * 错题记录领域模型，对应开发方案 5.1 `mistake_record` 表。
 *
 * 时间戳统一为 UTC 毫秒 Long。
 */
data class MistakeRecord(
    val id: String,
    val questionId: String,
    /** 学生过程步骤。 */
    val studentSteps: List<StudentStep> = emptyList(),
    /** 首个错误步骤序号；未定位到错误时为 null。 */
    val firstErrorStepIdx: Int? = null,
    /** 错因类型（固定七类之一）。 */
    val errorType: ErrorType,
    /** 错因说明。 */
    val errorExplain: String = "",
    /** 标准解答（正确路径）。 */
    val correctSolution: StandardSolution = StandardSolution(),
    /** 最终答案。 */
    val finalAnswer: String = "",
    /** 当时的讲解模式。 */
    val mode: TeachingMode = TeachingMode.DEFAULT,
    /** 入库时间（UTC 毫秒）。 */
    val createdAtUtc: Long,
    /** 同知识点第 N 次出错（FR-4.5 复发提示），首次为 1。 */
    val recurrenceIndex: Int = 1,
    /** 易错题状态；缺省按「错误」，兼容 0.6.0 之前的调用点。 */
    val status: MistakeStatus = MistakeStatus.DEFAULT,
    /** 来源：solve / check / manual。用于区分「拍题进来」与「核对后进来」。 */
    val source: String = "solve",
) {
    /** 是否属于复发（第 2 次及以后）。 */
    val isRecurrence: Boolean get() = recurrenceIndex > 1

    val hasLocatedError: Boolean get() = firstErrorStepIdx != null
}
