package com.totop.app.domain.model

import kotlinx.serialization.Serializable

/**
 * 阶段 A：结构化识题结果（开发方案 3.2 [6]、6.4 `question`）。
 *
 * 字段名与嵌套结构与 6.4 JSON 契约完全一致；全部字段可空，避免厂商模型漏字段时整体解析失败。
 */
@Serializable
data class AiQuestion(
    val subject: String? = null,
    val grade: String? = null,
    val stem: String? = null,
    val known: List<String> = emptyList(),
    val ask: String? = null,
    val options: List<String> = emptyList(),
    val hasFigure: Boolean = false,
)

/** 阶段 B：标准解答的单步（6.4 `standardSolution.steps[]`）。 */
@Serializable
data class AiSolutionStep(
    val idx: Int? = null,
    val content: String? = null,
    val why: String? = null,
)

/** 阶段 B：独立求解结果（6.4 `standardSolution`）。 */
@Serializable
data class AiStandardSolution(
    val steps: List<AiSolutionStep> = emptyList(),
    val finalAnswer: String? = null,
    val method: String? = null,
)

/** 学生过程单步（6.4 `studentSteps[]`，status 取值 correct|wrong|missing）。 */
@Serializable
data class AiStudentStep(
    val idx: Int? = null,
    val content: String? = null,
    /** 保留原始字符串，避免未知取值导致反序列化失败；由 StudentStepStatus.fromWire 兜底。 */
    val status: String? = null,
)

/** 阶段 C：首个错误步骤定位（6.4 `firstError`）。 */
@Serializable
data class AiFirstError(
    val stepIdx: Int? = null,
    /**
     * 出错步骤所属的小问编号（1 起，与标准解答里 `(1)` `(2)` 的编号一致）。
     *
     * 阶段 C 契约新增字段：`stepIdx` 只能说明「学生第几步错」，说不出「属于哪一问」，
     * 而提示要按标准步骤的小问切段。为 null 时按旧逻辑用 stepIdx 推算。
     */
    val subQuestion: Int? = null,
    /** 错因中文名或机器码，由 ErrorTypeMapper 归一为固定七类。 */
    val type: String? = null,
    val explain: String? = null,
    val correctThinking: String? = null,
)

/** 阶段 D：知识点归纳（6.4 `knowledgePoints[]`，id 必须是受控 ID）。 */
@Serializable
data class AiKnowledgePoint(
    val id: String? = null,
    val name: String? = null,
    val confidence: Double? = null,
    val usage: String? = null,
)

/** 摘要卡（6.4 `summaryCard`）。 */
@Serializable
data class AiSummaryCard(
    val title: String? = null,
    val bullets: List<String> = emptyList(),
)

/**
 * AI 四阶段统一输出 DTO，严格对齐开发方案 6.4「AI 输出 JSON 契约（客户端 DTO 必须一致）」。
 *
 * 契约（字段名与嵌套结构不可改动）：
 * ```json
 * {
 *   "question": {"subject","grade","stem","known","ask","options","hasFigure"},
 *   "standardSolution": {"steps":[{"idx","content","why"}],"finalAnswer","method"},
 *   "studentSteps": [{"idx","content","status"}],
 *   "firstError": {"stepIdx","subQuestion","type","explain","correctThinking"},
 *   "knowledgePoints": [{"id","name","confidence","usage"}],
 *   "summaryCard": {"title","bullets"},
 *   "aiGenerated": true
 * }
 * ```
 *
 * 解析策略见 [AiSolveResultCodec]：ignoreUnknownKeys = true，未知字段一律忽略。
 */
@Serializable
data class AiSolveResult(
    val question: AiQuestion? = null,
    val standardSolution: AiStandardSolution? = null,
    val studentSteps: List<AiStudentStep> = emptyList(),
    val firstError: AiFirstError? = null,
    val knowledgePoints: List<AiKnowledgePoint> = emptyList(),
    val summaryCard: AiSummaryCard? = null,
    val aiGenerated: Boolean = false,
) {
    /**
     * 严格校验：契约要求的嵌套结构是否齐全。
     *
     * FR-1.10 要求 AI 输出「必须经 kotlinx.serialization 严格校验」，
     * 结构性缺失（例如没有 standardSolution.steps）应判为校验失败并触发一次重试，
     * 而不是带着残缺数据入库。
     *
     * @return 缺失项清单；为空表示结构完整
     */
    fun missingStructure(): List<String> =
        buildList {
            if (question == null || question.stem.isNullOrBlank()) add("question")
            if (standardSolution == null) {
                add("standardSolution")
            } else if (standardSolution.steps.isEmpty()) {
                add("standardSolution.steps")
            }
        }

    /**
     * 非要害字段的缺失（不阻断出结果，只产生告警）。
     *
     * 为什么必须和 [missingStructure] 分开：
     * - 学生没写解题过程时，阶段 C 按契约返回 firstError = null、studentSteps 为空，
     *   这是**正确行为**而不是 AI 故障；
     * - 阶段 D 在候选清单覆盖不到本题时会按契约返回 unmatched = true、kpoints 为空；
     * - 非数学科目（化学、物理等）目前没有内置知识点树，候选清单天然为空，
     *   阶段 D 必然返回空数组。
     *
     * 旧实现把这两项算作"契约缺失"并直接判失败，导致：
     * 1. 不填解题过程永远解题失败；
     * 2. 任何非数学题永远解题失败。
     * 这类缺失降级为告警，结果照常展示，知识点区块为空即可。
     */
    fun missingOptionalStructure(): List<String> =
        buildList {
            if (studentSteps.isEmpty()) add("studentSteps")
            if (knowledgePoints.isEmpty()) add("knowledgePoints")
        }

    /** 结构是否完整（不校验业务语义，语义校验见 AiSolveResultMapper 的 warnings）。 */
    fun hasCompleteStructure(): Boolean = missingStructure().isEmpty()

    companion object {
        /** 契约要求的顶层字段名，顺序与 6.4 一致（用于校验与提示词 schema 生成）。 */
        val CONTRACT_FIELDS: List<String> =
            listOf(
                "question",
                "standardSolution",
                "studentSteps",
                "firstError",
                "knowledgePoints",
                "summaryCard",
                "aiGenerated",
            )
    }
}
