package com.totop.app.domain.model

/** 摘要卡（开发方案 6.4 summaryCard）。 */
data class SummaryCard(
    val title: String,
    val bullets: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = title.isBlank() && bullets.isEmpty()
}

/**
 * 知识点使用情况（开发方案 6.4 knowledgePoints：id / name / confidence / usage）。
 *
 * [kpointId] 必须为受控 ID，非法 ID 由 AiSolveResultMapper 过滤后不会进入本模型。
 */
data class KpointAssignment(
    val kpointId: String,
    val name: String,
    /** 置信度 0..1。 */
    val confidence: Double,
    /** 本题中的具体用法。 */
    val usage: String,
) {
    init {
        require(confidence.isFinite() && confidence in 0.0..1.0) {
            "知识点置信度必须位于 0..1，实际为：$confidence"
        }
    }

    val hasControlledId: Boolean get() = KnowledgeIdConvention.isValid(kpointId)
}

/**
 * 一次四阶段解题的领域聚合结果（阶段 A-D 输出 + DTO 校验后落库前的形态）。
 */
data class AiSolvedContent(
    val question: Question,
    val standardSolution: StandardSolution,
    val studentSteps: List<StudentStep>,
    val firstError: FirstError?,
    val kpointAssignments: List<KpointAssignment>,
    val summaryCard: SummaryCard?,
    /** 该结果是否由 AI 生成（展示层必须标注）。 */
    val aiGenerated: Boolean,
) {
    /** 首个错误步骤的领域类型；未定位到错误返回 null。 */
    val errorTypeOrNull: ErrorType? get() = firstError?.type

    /** 被定位到的错误步骤在 studentSteps 中的下标；未找到返回 null。 */
    val errorStepIndexInStudentSteps: Int?
        get() {
            val idx = firstError?.stepIdx ?: return null
            val position = studentSteps.indexOfFirst { it.idx == idx }
            return if (position >= 0) position else null
        }
}
