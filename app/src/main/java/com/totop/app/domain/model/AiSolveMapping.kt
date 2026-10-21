package com.totop.app.domain.model

/**
 * DTO → 领域模型的映射结果。
 *
 * @param content 领域聚合内容（已过滤非法知识点 ID、已归一错因）
 * @param warnings 非致命的清洗告警（例如 AI 返回了受控 ID 之外的自造 ID、错因落入兜底），
 *                 由用例层写入日志，绝不静默忽略
 */
data class AiSolveMapping(
    val content: AiSolvedContent,
    val warnings: List<String> = emptyList(),
) {
    val hasWarnings: Boolean get() = warnings.isNotEmpty()
}
