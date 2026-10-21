package com.totop.app.domain.model

/**
 * 第一个错误步骤的定位结果，对应开发方案 FR-1.4 / 6.4 firstError。
 *
 * [type] 必为固定七类之一；未知 AI 文本由 ErrorTypeMapper 归入 [ErrorType.FALLBACK]。
 */
data class FirstError(
    /** 出错的步骤序号，对应 studentSteps.idx。 */
    val stepIdx: Int,
    /** 错因类型（固定七类之一）。 */
    val type: ErrorType,
    /** 错因解释。 */
    val explain: String,
    /** 本题应有的思考入口，以及为什么学生思路走不通。 */
    val correctThinking: String,
) {
    val isFallbackType: Boolean get() = type == ErrorType.FALLBACK
}
