package com.totop.app.domain.usecase

import com.totop.app.domain.model.AiFirstError
import com.totop.app.domain.model.AiStudentStep
import com.totop.app.domain.model.ErrorType
import com.totop.app.domain.model.ErrorTypeMapper
import com.totop.app.domain.model.FirstError
import com.totop.app.domain.model.SolutionStep
import com.totop.app.domain.model.StandardSolution
import com.totop.app.domain.model.StudentStep
import com.totop.app.domain.model.StudentStepStatus
import com.totop.app.domain.usecase.port.ModelProfile
import com.totop.app.domain.usecase.port.OcrReading
import com.totop.app.domain.usecase.port.RouteChoice
import com.totop.app.domain.usecase.port.RouteTarget
import com.totop.app.domain.usecase.port.SolveGateway
import com.totop.app.domain.usecase.port.SolveGatewayOutcome
import com.totop.app.domain.usecase.port.SolveGatewayRequest
import com.totop.app.domain.usecase.port.TokenUsageCount

/** 错因定位请求。 */
data class LocateErrorRequest(
    /** 幂等 ID（与本次解题共用同一条 requestId，便于 ai_trace 串联）。 */
    val requestId: String,
    /** 题干（阶段 C 输入之一）。 */
    val stem: String,
    val subject: String = "math",
    val grade: String = "未知",
    /** 学生原始过程文本（手写转写或键盘输入）；为空时无法定位错因。 */
    val studentRawText: String,
    /** 标准解答（落库数据，始终完整）。 */
    val standardSolution: StandardSolution,
    val maxTokens: Int = DEFAULT_MAX_TOKENS,
) {
    init {
        require(requestId.isNotBlank()) { "requestId 不得为空" }
        require(maxTokens > 0) { "maxTokens 必须为正数" }
    }

    companion object {
        /** 阶段 C 的输出量不大，默认上限即可。 */
        const val DEFAULT_MAX_TOKENS: Int = 1536
    }
}

/** 错因定位结果。 */
sealed interface LocateErrorOutcome {
    /**
     * 定位完成。
     *
     * @param studentSteps 切分后的学生步骤
     * @param firstError 首个错误；学生全对时为 null
     * @param warnings 非致命告警（错因落入兜底、步骤序号越界等），必须落日志
     */
    data class Located(
        val studentSteps: List<StudentStep>,
        val firstError: FirstError?,
        val warnings: List<String> = emptyList(),
        val usage: TokenUsageCount = TokenUsageCount.EMPTY,
    ) : LocateErrorOutcome {
        /** 是否成功定位到错因。 */
        val hasError: Boolean get() = firstError != null

        /** 需要落库的首个错误步骤序号。 */
        val firstErrorStepIdx: Int? get() = firstError?.stepIdx

        /** 需要落库的错因类型；未定位到错误时为 null。 */
        val errorType: ErrorType? get() = firstError?.type
    }

    /** 学生未提供过程，无法定位（这不是错误，UI 应提示「补充过程可定位错因」）。 */
    data object NoStudentProcess : LocateErrorOutcome

    /** 调用失败；调用方不得据此写库。 */
    data class Failure(val userMessage: String, val retryable: Boolean = false) : LocateErrorOutcome
}

/**
 * 错因定位用例（开发方案 FR-1.4 / FR-1.6、3.2 阶段 C）。
 *
 * 三件事：
 * 1. **步骤切分**：把学生自由文本切成有序步骤（本地完成，不花 token）；
 * 2. **对齐**：调用网关的阶段 C，拿到每步状态与首个错误；
 * 3. **落地**：把 firstErrorStepIdx / errorType / explain / correctThinking 转成领域对象返回。
 *    真正的落库由 [SolveQuestionUseCase] 在同一个事务里完成，避免「错题已入库但错因丢失」。
 *
 * 关键取舍：
 * - 学生过程为空时**不调用模型**（省 token），直接返回 [LocateErrorOutcome.NoStudentProcess]；
 * - 学生全对（没有 wrong / missing）时 firstError 为 null，而不是硬凑一个错因；
 * - 重试与 ai_trace 全部留在网关内部，UseCase 不再自行重试，避免重复计费。
 */
class LocateErrorUseCase(
    private val gateway: SolveGateway,
    private val modelProfile: ModelProfile = ModelProfile.PLACEHOLDER,
) {
    /** 端到端定位（含一次模型调用）。 */
    suspend fun locate(request: LocateErrorRequest): LocateErrorOutcome {
        val raw = request.studentRawText.trim()
        if (raw.isEmpty()) return LocateErrorOutcome.NoStudentProcess

        val outcome =
            gateway.solve(
                SolveGatewayRequest(
                    requestId = request.requestId,
                    ocr = OcrReading(text = request.stem, avgConfidence = 1.0),
                    image = null,
                    studentRawText = raw,
                    supplement = "",
                    candidateKpoints = emptyList(),
                    route =
                        RouteChoice(
                            target = RouteTarget.TEXT,
                            model = modelProfile.textModel,
                            attachImage = false,
                            reason = "错因定位只需文本，不发图片",
                        ),
                    maxTokens = request.maxTokens,
                ),
            )
        return when (outcome) {
            is SolveGatewayOutcome.Failure ->
                LocateErrorOutcome.Failure(
                    userMessage = outcome.userMessage,
                    retryable = outcome.retryable,
                )

            is SolveGatewayOutcome.Success ->
                align(
                    studentStepsFromModel = outcome.result.studentSteps,
                    rawFirstError = outcome.result.firstError,
                    usage = outcome.usage,
                    warnings = outcome.warnings.toMutableList(),
                )
        }
    }

    /**
     * 纯对齐（不调用模型，可在单测中直接驱动）。
     *
     * @param studentStepsFromModel 阶段 C 给出的学生步骤
     * @param rawFirstError 阶段 C 给出的首个错误；null 表示学生全对
     */
    fun align(
        studentStepsFromModel: List<AiStudentStep>,
        rawFirstError: AiFirstError?,
        usage: TokenUsageCount = TokenUsageCount.EMPTY,
        warnings: MutableList<String> = mutableListOf(),
    ): LocateErrorOutcome.Located {
        val steps = splitStudentSteps(studentStepsFromModel)
        val firstError = alignFirstError(rawFirstError, steps, warnings)
        return LocateErrorOutcome.Located(
            studentSteps = steps,
            firstError = firstError,
            warnings = warnings.toList(),
            usage = usage,
        )
    }

    /**
     * 把学生自由文本切成有序步骤（本地切分，零 token）。
     *
     * 优先级：
     * 1. 显式序号（`1.` `1、` `(1)` `①`）—— 出现 2 个以上才认为整篇用了序号体系；
     * 2. 换行（一行一步）；
     * 3. 分号 / 句号（一行里写了多步，且该行足够长时）。
     *
     * 空行、只有序号的残行被丢弃；单步超长截断，防止把整段贴进提示词烧 token。
     */
    fun splitRawText(raw: String): List<String> {
        val text = raw.trim()
        if (text.isEmpty()) return emptyList()
        val numbered = numberedSplit(text)
        val base = if (numbered.size >= 2) numbered else lineSplit(text)
        return base
            .flatMap { sentenceSplit(it) }
            .map { cleanPiece(it) }
            .filter { it.length >= MIN_STEP_CHARS }
            .map { if (it.length > MAX_STEP_CHARS) it.take(MAX_STEP_CHARS) else it }
    }

    /** 由模型返回的学生步骤构造领域步骤（status 未知取值由枚举兜底为「缺失」）。 */
    fun splitStudentSteps(fromModel: List<AiStudentStep>): List<StudentStep> =
        fromModel.mapIndexed { index, step ->
            StudentStep(
                idx = step.idx ?: (index + 1),
                content = step.content?.trim().orEmpty(),
                status = StudentStepStatus.fromWire(step.status),
            )
        }

    /**
     * 对齐首个错误。
     *
     * 规则：
     * - 模型没给 firstError -> null（学生全对 / 未定位到）；
     * - 错因字符串经 [ErrorTypeMapper] 归一；未知值落 [ErrorType.FALLBACK] 并告警（固定七类，不得新增）；
     * - stepIdx 缺失或越界时，回退到第一个 wrong 步骤，其次第一个 missing 步骤；都没有则视为无错因。
     */
    fun alignFirstError(
        rawFirstError: AiFirstError?,
        studentSteps: List<StudentStep>,
        warnings: MutableList<String> = mutableListOf(),
    ): FirstError? {
        if (rawFirstError == null) return null
        val mapping = ErrorTypeMapper.map(rawFirstError.type)
        if (mapping.isFallback) {
            warnings += "错因未命中固定七类，已归入兜底：" + (rawFirstError.type?.takeIf { it.isNotBlank() } ?: "<空>")
        }
        val declared = rawFirstError.stepIdx
        val resolvedIdx =
            when {
                declared != null && studentSteps.any { it.idx == declared } -> declared
                else -> {
                    if (declared != null) {
                        warnings += "错因步骤序号 " + declared + " 不在学生步骤中，已回退到第一个错误步骤"
                    }
                    studentSteps.firstOrNull { it.status == StudentStepStatus.WRONG }?.idx
                        ?: studentSteps.firstOrNull { it.status == StudentStepStatus.MISSING }?.idx
                }
            }
        if (resolvedIdx == null) {
            warnings += "学生步骤中没有错误或缺失步骤，已忽略首个错误"
            return null
        }
        return FirstError(
            stepIdx = resolvedIdx,
            type = mapping.errorType,
            explain = rawFirstError.explain?.trim().orEmpty(),
            correctThinking = rawFirstError.correctThinking?.trim().orEmpty(),
        )
    }

    /**
     * 标准步骤与学生步骤的对照（供 UI 高亮，不参与落库）。
     */
    fun alignment(
        solution: StandardSolution,
        studentSteps: List<StudentStep>,
        firstError: FirstError?,
    ): List<StepAlignment> {
        if (solution.steps.isEmpty() && studentSteps.isEmpty()) return emptyList()
        val size = maxOf(solution.steps.size, studentSteps.size)
        return (0 until size).map { position ->
            val standard = solution.steps.getOrNull(position)
            val student = studentSteps.getOrNull(position)
            StepAlignment(
                position = position + 1,
                standardStep = standard,
                studentStep = student,
                isFirstError = firstError != null && student != null && student.idx == firstError.stepIdx,
            )
        }
    }

    private fun cleanPiece(piece: String): String = piece.trim().trimEnd('；', ';', '。', '.', '，', ',').trim()

    private fun numberedSplit(text: String): List<String> {
        val matches = NUMBERED_PATTERN.findAll(text).toList()
        if (matches.isEmpty()) return emptyList()
        val pieces = mutableListOf<String>()
        for ((index, match) in matches.withIndex()) {
            val start = match.range.last + 1
            val end = if (index + 1 < matches.size) matches[index + 1].range.first else text.length
            if (start < end) pieces += text.substring(start, end)
        }
        return pieces.filter { it.isNotBlank() }
    }

    private fun lineSplit(text: String): List<String> = text.split("\r\n", "\n").filter { it.isNotBlank() }

    private fun sentenceSplit(line: String): List<String> {
        val trimmed = line.trim()
        if (trimmed.length <= SENTENCE_SPLIT_THRESHOLD) return listOf(trimmed)
        return trimmed.split('；', ';').filter { it.isNotBlank() }
    }

    /** 一步对照。 */
    data class StepAlignment(
        val position: Int,
        val standardStep: SolutionStep?,
        val studentStep: StudentStep?,
        val isFirstError: Boolean,
    )

    companion object {
        /** 单步最短长度（过滤「1.」这类只有序号的行）。 */
        const val MIN_STEP_CHARS: Int = 2

        /** 单步最长长度，超出截断。 */
        const val MAX_STEP_CHARS: Int = 400

        /** 超过该长度的行才按分号再切。 */
        const val SENTENCE_SPLIT_THRESHOLD: Int = 80

        /** 显式序号：1. / 1、/ (1) / ①～⑩。 */
        private val NUMBERED_PATTERN =
            Regex(
                "(?:^|\\n)\\s*(?:[0-9]{1,2}\\s*[.、)）:：]|[(（]\\s*[0-9]{1,2}\\s*[)）]|[\\u2460-\\u2469])\\s*",
            )
    }
}
