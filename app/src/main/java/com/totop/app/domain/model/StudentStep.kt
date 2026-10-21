package com.totop.app.domain.model

/**
 * 学生过程切分后的单步，对应开发方案 6.4 studentSteps。
 *
 * status 使用 [StudentStepStatus]，未知值由 [StudentStepStatus.fromWire] 兜底为「缺失」。
 */
data class StudentStep(
    val idx: Int,
    val content: String,
    val status: StudentStepStatus = StudentStepStatus.WRONG,
) {
    val isMissing: Boolean get() = status == StudentStepStatus.MISSING
}
