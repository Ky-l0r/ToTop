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
    /**
     * 阶段 A 判定的题型（见 [ChoiceAnswerGuard]）。
     *
     * 不写进 question 表：题型只用于校验「单选/多选与答案个数是否一致」，
     * 属于本次解答的元信息，不参与错题的持久化与统计，避免为它加列加迁移。
     */
    val choiceType: String = ChoiceAnswerGuard.UNKNOWN,
    /**
     * 卷面上识别到手写过程的程度：`none` / `partial` / `full`（阶段 A 产出）。
     *
     * 与 [choiceType] 同理，不写进 question 表：它属于「这一次看懂了什么」的元信息，
     * 用来说明「学生自己做过」，不参与统计；错题状态在落库时按它定一次即可。
     */
    val handwriting: String? = null,
    /** 手写过程的转写（只含算式、关键步骤与结论）。 */
    val handwritingText: String? = null,
    /** 逐问进度：写到 / 卡住 / 没写。 */
    val progress: List<QuestionProgress> = emptyList(),
) {
    /** 首个错误步骤的领域类型；未定位到错误返回 null。 */
    val errorTypeOrNull: ErrorType? get() = firstError?.type

    /** 是否看到学生自己写的过程（手写转写或卷面上确有笔迹）。 */
    val sawStudentWork: Boolean
        get() =
            !handwritingText.isNullOrBlank() ||
                handwriting?.equals("none", ignoreCase = true) == false

    /** 被定位到的错误步骤在 studentSteps 中的下标；未找到返回 null。 */
    val errorStepIndexInStudentSteps: Int?
        get() {
            val idx = firstError?.stepIdx ?: return null
            val position = studentSteps.indexOfFirst { it.idx == idx }
            return if (position >= 0) position else null
        }
}
