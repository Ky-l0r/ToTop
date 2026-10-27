package com.totop.app.domain.model

/**
 * 第一个错误步骤的定位结果，对应开发方案 FR-1.4 / 6.4 firstError。
 *
 * [type] 必为固定七类之一；未知 AI 文本由 ErrorTypeMapper 归入 [ErrorType.FALLBACK]。
 */
data class FirstError(
    /** 出错的步骤序号，对应 studentSteps.idx。 */
    val stepIdx: Int,
    /**
     * 出错步骤所属的小问编号（1 起），对应标准解答里 `(1)` `(2)` 的编号。
     *
     * [stepIdx] 是**学生步骤**的序号，标准步骤是另一套编号，两者只在「同一问内步序大致对应」时
     * 才凑巧一致；提示要按标准步骤切段，所以需要这个字段独立表达「属于哪一问」。
     * 为 null 表示整题不分问或模型未能判断，调用方应退回按 [stepIdx] 推算的旧行为。
     */
    val subQuestion: Int? = null,
    /** 错因类型（固定七类之一）。 */
    val type: ErrorType,
    /** 错因解释。 */
    val explain: String,
    /** 本题应有的思考入口，以及为什么学生思路走不通。 */
    val correctThinking: String,
) {
    val isFallbackType: Boolean get() = type == ErrorType.FALLBACK
}
