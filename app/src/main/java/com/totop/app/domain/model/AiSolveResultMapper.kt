package com.totop.app.domain.model

/**
 * AI DTO → 领域模型的映射器。
 *
 * 职责（与开发方案 3.2 第 [10]-[12] 步、6.2「禁止让 AI 的错误输出污染知识点统计」对应）：
 * 1. 缺字段情况下的安全缺省（步骤序号补齐、空文本过滤）；
 * 2. 错因字符串统一经 [ErrorTypeMapper] 归一为固定七类，未知值落兜底并产生告警；
 * 3. 知识点只保留满足 [KnowledgeIdConvention] 的受控 ID，自造 ID 一律丢弃并告警；
 * 4. 置信度裁剪到 0..1。
 *
 * 本映射器不抛异常、不做 I/O，是纯函数，可单测。
 */
object AiSolveResultMapper {
    /**
     * 映射为领域聚合。
     *
     * @param result AI 校验通过的 DTO
     * @param question 已落库/待落库的题目（由阶段 A 结构化识题结果构建，携带本地 id 与 UTC 时间戳）
     */
    fun map(
        result: AiSolveResult,
        question: Question,
    ): AiSolveMapping {
        val warnings = mutableListOf<String>()

        val standardSolution = mapStandardSolution(result.standardSolution)
        if (standardSolution.steps.isEmpty()) warnings.add("standardSolution.steps 为空")

        val studentSteps = mapStudentSteps(result.studentSteps)
        if (studentSteps.isEmpty()) warnings.add("studentSteps 为空")

        val firstError = mapFirstError(result.firstError, studentSteps, warnings)
        val kpoints = mapKnowledgePoints(result.knowledgePoints, warnings)
        val summary = mapSummary(result.summaryCard)

        val content =
            AiSolvedContent(
                question = question,
                standardSolution = standardSolution,
                studentSteps = studentSteps,
                firstError = firstError,
                kpointAssignments = kpoints,
                summaryCard = summary,
                aiGenerated = result.aiGenerated,
                choiceType = ChoiceAnswerGuard.normalize(result.question?.choiceType),
            )
        return AiSolveMapping(content = content, warnings = warnings)
    }

    /**
     * 映射标准解答。
     * 空步骤与空 why 的条目会被丢弃，剩余步骤序号按出现顺序重排为 1..n，
     * 保证 [StandardSolution.hasContiguousStepIndex] 恒为 true。
     */
    fun mapStandardSolution(dto: AiStandardSolution?): StandardSolution {
        val raw = dto?.steps.orEmpty()
        val kept =
            raw.mapNotNull { step ->
                val content = step.content?.trim().orEmpty()
                val why = step.why?.trim().orEmpty()
                if (content.isEmpty() && why.isEmpty()) null else SolutionStep(idx = 0, content = content, why = why)
            }
        val steps = kept.mapIndexed { index, step -> step.copy(idx = index + 1) }
        return StandardSolution(
            steps = steps,
            finalAnswer = dto?.finalAnswer?.trim().orEmpty(),
            method = dto?.method?.trim().orEmpty(),
        )
    }

    /** 映射学生步骤；status 未知取值由 [StudentStepStatus.fromWire] 兜底为「缺失」。 */
    fun mapStudentSteps(dto: List<AiStudentStep>): List<StudentStep> =
        dto.mapIndexed { index, step ->
            StudentStep(
                idx = step.idx ?: (index + 1),
                content = step.content?.trim().orEmpty(),
                status = StudentStepStatus.fromWire(step.status),
            )
        }

    /**
     * 映射首个错误。
     * - 错因未命中固定七类时归入 [ErrorType.FALLBACK] 并记录告警；
     * - [AiFirstError.stepIdx] 缺失时取学生第一步；
     * - stepIdx 不在学生步骤中时保留原值并记录告警（由用例层决定是否丢弃）；
     * - subQuestion 小于 1 是非法的问号（1 起），丢弃并告警，让提示起点退回按序号推算的旧逻辑。
     */
    fun mapFirstError(
        dto: AiFirstError?,
        studentSteps: List<StudentStep>,
        warnings: MutableList<String> = mutableListOf(),
    ): FirstError? {
        if (dto == null) return null
        val mapping = ErrorTypeMapper.map(dto.type)
        if (mapping.isFallback) {
            warnings.add("firstError.type 未命中固定七类，已归入兜底：" + (dto.rawOrEmpty()))
        }
        val stepIdx = dto.stepIdx ?: studentSteps.firstOrNull()?.idx ?: 0
        if (dto.stepIdx != null && studentSteps.none { it.idx == dto.stepIdx }) {
            warnings.add("firstError.stepIdx=" + dto.stepIdx + " 不在 studentSteps 中")
        }
        val subQuestion = dto.subQuestion
        if (subQuestion != null && subQuestion < 1) {
            warnings.add("firstError.subQuestion=" + subQuestion + " 不是合法的问号，已忽略")
        }
        return FirstError(
            stepIdx = stepIdx,
            subQuestion = subQuestion?.takeIf { it >= 1 },
            type = mapping.errorType,
            explain = dto.explain?.trim().orEmpty(),
            correctThinking = dto.correctThinking?.trim().orEmpty(),
        )
    }

    /** 映射知识点：只保留受控 ID，自造 ID 丢弃并告警，重复 ID 去重。 */
    fun mapKnowledgePoints(
        dto: List<AiKnowledgePoint>,
        warnings: MutableList<String> = mutableListOf(),
    ): List<KpointAssignment> {
        val accepted = ArrayList<KpointAssignment>(dto.size)
        for (item in dto) {
            val id = item.id?.trim().orEmpty()
            if (!KnowledgeIdConvention.isValid(id)) {
                warnings.add("knowledgePoints 含非受控 ID 已丢弃：" + (if (id.isEmpty()) "<空>" else id))
                continue
            }
            if (accepted.any { it.kpointId == id }) {
                warnings.add("knowledgePoints 含重复 ID 已去重：" + id)
                continue
            }
            val name = item.name?.trim().orEmpty().ifEmpty { id }
            val confidence = (item.confidence ?: 0.0).let { if (!it.isFinite()) 0.0 else it.coerceIn(0.0, 1.0) }
            accepted.add(
                KpointAssignment(
                    kpointId = id,
                    name = name,
                    confidence = confidence,
                    usage = item.usage?.trim().orEmpty(),
                ),
            )
        }
        return accepted
    }

    /** 映射摘要卡；标题与条目都为空时返回 null。 */
    fun mapSummary(dto: AiSummaryCard?): SummaryCard? {
        if (dto == null) return null
        val card =
            SummaryCard(
                title = dto.title?.trim().orEmpty(),
                bullets = dto.bullets.map { it.trim() }.filter { it.isNotEmpty() },
            )
        return card.takeIf { !it.isEmpty }
    }

    private fun AiFirstError.rawOrEmpty(): String = type?.takeIf { it.isNotBlank() } ?: "<空>"
}
