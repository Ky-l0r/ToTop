package com.totop.app.data.remote

import com.totop.app.data.prefs.LlmCredentials
import com.totop.app.data.prefs.ModelPool
import com.totop.app.domain.model.AiQuestion
import com.totop.app.domain.model.AiSolutionStep
import com.totop.app.domain.model.AiSolveResultCodec
import com.totop.app.domain.model.AiSolveResultMapper
import com.totop.app.domain.model.AiStage
import com.totop.app.domain.model.AiStandardSolution
import com.totop.app.domain.model.Question
import com.totop.app.domain.model.Subjects
import com.totop.app.ml.ModelRouter
import com.totop.app.ml.OcrSnapshot
import com.totop.app.ml.RouteDecision
import com.totop.app.util.EncodedImage
import com.totop.app.util.ImageMath
import com.totop.app.util.TraceRecorder
import com.totop.app.util.UtcClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 解题请求（ui-solve 到 AiGateway 的唯一入口参数）。 */
data class SolveRequest(
    /** OCR 结果，决定路由档位。 */
    val ocr: OcrSnapshot,
    /** 预处理后的图片；走视觉档时必有（单页或第 1 页）。 */
    val image: EncodedImage? = null,
    /**
     * 多页题目的其余页（第 2 页起）。
     *
     * 顺序即页序，第 1 页在 [image] 中。
     * 这些页与 [image] 一起放进同一条 user 消息，并标注「第 i/N 页」，
     * 让模型知道它们是同一道题的连续内容。
     */
    val extraImages: List<EncodedImage> = emptyList(),
    /** 学生手写/输入的解题过程；阶段 C 使用，阶段 B 绝不使用。 */
    val studentRawText: String = "",
    /** 学生补充说明（例如「第(2)问不要求」）。 */
    val supplement: String = "",
    /** 阶段 D 的候选知识点清单，由 data 层检索内置知识点树得到。 */
    val candidateKpoints: List<CandidateKpoint> = emptyList(),
    /** 「简洁 / 详细」两档输出上限。 */
    val maxTokens: Int = ChatRequestConfig.MAX_TOKENS_CONCISE,
    /** 幂等请求 ID；为空时自动生成。 */
    val requestId: String = "",
)

/** 阶段进度事件（供 UI 展示进度与增量渲染）。 */
sealed class SolveEvent {
    /** 路由决策完成，UI 可展示「本次使用视觉模型（原因：…）」。 */
    data class Routed(val decision: RouteDecision) : SolveEvent()

    /** 进入某阶段；cached 为 true 表示命中本地缓存，0 token。 */
    data class StageStarted(val stage: AiStage, val cached: Boolean = false) : SolveEvent()

    /** 该阶段首个增量文本到达（FR-1.9 首字响应 < 3s 的观测点）。 */
    data class FirstToken(val stage: AiStage, val elapsedMs: Long) : SolveEvent()

    /** 增量文本。 */
    data class Delta(val stage: AiStage, val text: String) : SolveEvent()

    /** 阶段完成。 */
    data class StageCompleted(val stage: AiStage, val summary: String) : SolveEvent()

    /** 全部完成（成功）。 */
    data class Completed(
        val content: com.totop.app.domain.model.AiSolvedContent,
        val warnings: List<String>,
        val usage: TokenUsage,
    ) : SolveEvent()

    /** 失败（绝不入库，UI 展示可读原因与「重试」按钮）。 */
    data class Failed(
        val stage: AiStage,
        val error: LlmError,
        val partialWarnings: List<String> = emptyList(),
    ) : SolveEvent()
}

/** 单阶段调用结果。 */
data class StageCallResult(
    val raw: String,
    val usage: TokenUsage,
)

/**
 * 四阶段编排网关（开发方案 3.2 [6]-[12]、6.2 强制结构化输出、FR-1.10 校验重试）。
 *
 * 编排规则（不得随意调整）：
 * 1. 路由：先按 [ModelRouter] 决定文本档还是视觉档；
 * 2. 阶段 A 结构化识题 -> 阶段 B 独立求解（只给题目） -> 阶段 C 错因定位（注入学生过程） ->
 *    阶段 D 知识点抽取（注入候选清单）；
 * 3. 每阶段输出必须是合法 JSON：解析失败自动以更严格提示词重试 1 次，
 *    仍失败则整次调用失败（[SolveEvent.Failed]），绝不带残缺数据返回；
 * 4. 每次调用（含失败与重试）都写 ai_trace；
 * 5. 阶段 A 的输出按缓存键 sha256 + promptVersion + model 缓存，命中时 0 token。
 */
class AiGateway(
    private val llmClient: LlmTransport,
    private val promptRepository: PromptRepository,
    private val traceRecorder: TraceRecorder,
    private val cache: LlmCache? = null,
    private val clock: UtcClock = UtcClock.SYSTEM,
    private val idempotencyGuard: IdempotencyGuard = IdempotencyGuard(),
) {
    /**
     * 执行一次完整解题，返回事件流。
     *
     * 调用方（ViewModel）必须传入 [registry] 并在 onCleared() 中调用 cancelAll()。
     */
    fun solve(
        credentials: LlmCredentials,
        request: SolveRequest,
        router: ModelRouter = ModelRouter(modelPool(credentials)),
        registry: LlmCallRegistry? = null,
    ): Flow<SolveEvent> =
        channelFlow {
            val requestId = request.requestId.ifBlank { RequestId.newId() }
            if (!idempotencyGuard.begin(requestId)) {
                send(
                    SolveEvent.Failed(
                        stage = AiStage.A,
                        error = LlmError.Unknown(detail = "重复请求已忽略（requestId=" + requestId + "）"),
                    ),
                )
                return@channelFlow
            }

            var stage: AiStage = AiStage.A
            val warnings = mutableListOf<String>()
            var totalUsage = TokenUsage.EMPTY

            try {
                // ---------- 路由 ----------
                val decision = router.route(request.ocr)
                send(SolveEvent.Routed(decision))

                // ---------- 阶段 A：结构化识题 ----------
                stage = AiStage.A
                send(SolveEvent.StageStarted(stage))
                val identifyPrompt =
                    promptRepository.assemble(
                        StageInput.Identify(
                            ocrText = request.ocr.text,
                            supplement = request.supplement,
                            attachImage = decision.attachImage,
                        ),
                        requestId = requestId,
                    )
                val cacheKey = cacheKeyOf(decision, request, identifyPrompt.promptVersion)
                val cachedRaw = cacheKey?.let { cache?.get(it) }
                var rawA = cachedRaw
                if (cachedRaw != null) {
                    send(SolveEvent.StageStarted(AiStage.A, cached = true))
                } else {
                    val result =
                        callStage(
                            credentials = credentials,
                            requestId = requestId,
                            stage = AiStage.A,
                            model = decision.model,
                            promptVersion = identifyPrompt.promptVersion,
                            prompt = identifyPrompt,
                            image = if (decision.attachImage) request.image else null,
                            // 多页题目：只有阶段 A（识题）需要看全部页面，
                            // B/C/D 只处理已结构化的题干文本，无需重复上传图片。
                            extraImages = if (decision.attachImage) request.extraImages else emptyList(),
                            maxTokens = request.maxTokens,
                            registry = registry,
                            onDelta = { text -> send(SolveEvent.Delta(AiStage.A, text)) },
                            onFirstToken = { elapsed -> send(SolveEvent.FirstToken(AiStage.A, elapsed)) },
                        )
                    totalUsage = totalUsage.plus(result.usage)
                    rawA = result.raw
                    // 阶段 A 的输入只有「题干图片/OCR 文本」，与学生过程无关，
                    // 因此阶段 A 的输出可以安全地按 sha256 + promptVersion + model 缓存（开发方案 3.2 第 [3] 步）。
                    if (cacheKey != null) cache?.put(cacheKey, result.raw)
                }
                val rawStageA: String =
                    rawA
                        ?: throw LlmException(LlmError.MalformedResponse(detail = "阶段 A 未产出任何内容"))
                val questionObject = decodeJsonObject(rawStageA)
                val question =
                    AiQuestion(
                        subject = questionObject.stringOr("subject", "math"),
                        grade = questionObject.stringOr("grade", "未知"),
                        stem = questionObject.stringOr("stem", ""),
                        known = questionObject.stringListOr("known"),
                        ask = questionObject.stringOr("ask", ""),
                        options = questionObject.stringListOr("options"),
                        hasFigure = questionObject.booleanOr("hasFigure", false),
                    )
                if (question.stem.isNullOrBlank()) {
                    throw LlmException(LlmError.MalformedResponse(detail = "阶段 A 未识别出题干（stem 为空）"))
                }
                if (questionObject.stringListOr("warnings").isNotEmpty()) {
                    warnings.addAll(questionObject.stringListOr("warnings"))
                }
                send(SolveEvent.StageCompleted(AiStage.A, if (cachedRaw != null) "命中缓存" else "识题完成"))

                // ---------- 阶段 B：独立求解（绝不注入学生过程） ----------
                stage = AiStage.B
                send(SolveEvent.StageStarted(stage))
                val solvePrompt = promptRepository.assemble(StageInput.Solve(question), requestId = requestId)
                val rawB =
                    callStage(
                        credentials = credentials,
                        requestId = requestId,
                        stage = AiStage.B,
                        model = decision.model,
                        promptVersion = solvePrompt.promptVersion,
                        prompt = solvePrompt,
                        image = null,
                        maxTokens = request.maxTokens,
                        registry = registry,
                        onDelta = { text -> send(SolveEvent.Delta(AiStage.B, text)) },
                        onFirstToken = { elapsed -> send(SolveEvent.FirstToken(AiStage.B, elapsed)) },
                    )
                totalUsage = totalUsage.plus(rawB.usage)
                val solution = decodeStandardSolution(rawB.raw)
                if (solution.steps.isEmpty()) {
                    throw LlmException(LlmError.MalformedResponse(detail = "阶段 B 未产出任何解答步骤"))
                }
                send(SolveEvent.StageCompleted(AiStage.B, "解得 " + solution.steps.size + " 步"))

                // ---------- 阶段 C：错因定位（必须注入学生过程） ----------
                stage = AiStage.C
                send(SolveEvent.StageStarted(stage))
                val noStudentText = request.studentRawText.isBlank()
                val locatePrompt =
                    promptRepository.assemble(
                        StageInput.LocateError(
                            question = question,
                            studentRawText = request.studentRawText.ifBlank { "（学生未提供解题过程）" },
                            standardSolution = solution,
                        ),
                        requestId = requestId,
                    )
                val rawC =
                    callStage(
                        credentials = credentials,
                        requestId = requestId,
                        stage = AiStage.C,
                        model = decision.model,
                        promptVersion = locatePrompt.promptVersion,
                        prompt = locatePrompt,
                        image = null,
                        maxTokens = request.maxTokens,
                        registry = registry,
                        onDelta = { text -> send(SolveEvent.Delta(AiStage.C, text)) },
                        onFirstToken = { elapsed -> send(SolveEvent.FirstToken(AiStage.C, elapsed)) },
                    )
                totalUsage = totalUsage.plus(rawC.usage)
                val stageC = decodeJsonObject(rawC.raw)
                if (noStudentText) warnings.add("未提供学生过程，已按「无法定位错因」处理")
                send(SolveEvent.StageCompleted(AiStage.C, "错因定位完成"))

                // ---------- 阶段 D：知识点抽取（必须注入候选清单） ----------
                stage = AiStage.D
                send(SolveEvent.StageStarted(stage))
                // 按阶段 A 识别出的科目收敛候选清单。
                //
                // 为什么必须收敛：知识点树已扩到十科 700+ 节点，若不收敛，
                // 一道物理题的候选里会混进语文、英语的节点，模型很容易给出跨科目的错误归属，
                // 而错误的归属会直接污染掌握度统计（本项目明令禁止）。
                // 收敛失败（该科目没有节点）时退回全量，宁可候选嘈杂也不让阶段 D 白跑。
                val subjectOfQuestion = Subjects.normalize(question.subject)
                val scopedCandidates =
                    request.candidateKpoints.filter { candidate ->
                        candidate.subject.isBlank() || candidate.subject.equals(subjectOfQuestion, ignoreCase = true)
                    }.ifEmpty { request.candidateKpoints }
                // 候选清单为空时不调用模型，直接得到「无知识点」的结构化结果。
                // 这不是静默降级：会下发 StageCompleted 与一条告警，UI 明确显示原因。
                val hasCandidates = scopedCandidates.isNotEmpty()
                if (!hasCandidates) {
                    warnings.add(
                        "这道题没有匹配到内置知识点（科目：" +
                            Subjects.label(subjectOfQuestion) + "），知识点区块留空",
                    )
                    send(SolveEvent.StageCompleted(AiStage.D, "跳过（暂无该科目的知识点树）"))
                }
                val rawD =
                    if (!hasCandidates) {
                        EMPTY_KPOINTS_JSON
                    } else {
                        val kpointPrompt =
                            promptRepository.assemble(
                                StageInput.AssignKpoints(
                                    question = question,
                                    standardSolution = solution,
                                    candidates = scopedCandidates,
                                ),
                                requestId = requestId,
                            )
                        val result =
                            callStage(
                                credentials = credentials,
                                requestId = requestId,
                                stage = AiStage.D,
                                model = decision.model,
                                promptVersion = kpointPrompt.promptVersion,
                                prompt = kpointPrompt,
                                image = null,
                                maxTokens = request.maxTokens,
                                registry = registry,
                                onDelta = { text -> send(SolveEvent.Delta(AiStage.D, text)) },
                                onFirstToken = { elapsed -> send(SolveEvent.FirstToken(AiStage.D, elapsed)) },
                            )
                        totalUsage = totalUsage.plus(result.usage)
                        send(SolveEvent.StageCompleted(AiStage.D, "知识点抽取完成"))
                        result.raw
                    }

                // ---------- 合并四阶段输出为 6.4 契约并严格校验 ----------
                // 注意：此处已不属于任何单个阶段，是四阶段结果的合并校验。
                // 旧实现把 stage 设为 D，导致「合并校验失败」被误报成「阶段 D 失败」，
                // 掩盖了真实原因（例如其实是阶段 C 没给 studentSteps）。合并阶段沿用 D 的序号
                // 会让用户以为知识点抽取出错，实际可能只是阶段 C 的输出为空。
                val contractJson =
                    buildContractJson(
                        question = question,
                        solutionObject = decodeJsonObject(rawB.raw),
                        stageCObject = stageC,
                        stageDObject = decodeJsonObject(rawD),
                    )
                val dto =
                    AiSolveResultCodec.decode(contractJson).getOrElse { failure ->
                        throw LlmException(
                            LlmError.MalformedResponse(detail = "合并四阶段结果后仍不满足契约：" + failure.message),
                        )
                    }
                val missing = dto.missingStructure()
                if (missing.isNotEmpty()) {
                    // FR-1.10：结构性缺失（题干或标准解答）视为校验失败，绝不入库
                    throw LlmException(
                        LlmError.MalformedResponse(detail = "必要的契约字段缺失：" + missing.joinToString("、")),
                    )
                }
                // 非致命缺失只降级为告警，不再判整次失败：
                // - 未填解题过程 -> studentSteps 为空是契约允许的正确行为；
                // - 候选知识点覆盖不到（非数学科目没有内置知识树）-> kpoints 为空同理。
                val optionalMissing = dto.missingOptionalStructure()
                if (optionalMissing.isNotEmpty()) {
                    optionalMissing.forEach { field ->
                        warnings.add(
                            when (field) {
                                "studentSteps" -> "本次没有学生解题过程，已跳过错因定位，只给标准解答"
                                else -> "本题没有匹配到内置知识点，知识点区块留空（其它结果不受影响）"
                            },
                        )
                    }
                }
                val domainQuestion =
                    Question(
                        id = requestId,
                        // 科目归一：模型可能返回「数学」「Math」「初中数学」，
                        // 一律收敛到受控代码，否则错题本按科目筛选会失效。
                        subject = Subjects.normalize(dto.question?.subject),
                        grade = dto.question?.grade ?: "未知",
                        stemText = dto.question?.stem.orEmpty(),
                        known = dto.question?.known.orEmpty(),
                        ask = dto.question?.ask.orEmpty(),
                        options = dto.question?.options.orEmpty(),
                        hasFigure = dto.question?.hasFigure ?: false,
                        imagePath = null,
                        imageSha256 = request.image?.sha256,
                        createdAtUtc = clock.nowUtcMillis(),
                    )
                val mapping = AiSolveResultMapper.map(dto, domainQuestion)
                warnings.addAll(mapping.warnings)
                idempotencyGuard.complete(requestId)
                send(SolveEvent.Completed(content = mapping.content, warnings = warnings.toList(), usage = totalUsage))
            } catch (cancellation: CancellationException) {
                idempotencyGuard.abort(requestId)
                throw cancellation
            } catch (exception: LlmException) {
                idempotencyGuard.abort(requestId)
                send(SolveEvent.Failed(stage = stage, error = exception.error, partialWarnings = warnings.toList()))
            } catch (throwable: Throwable) {
                idempotencyGuard.abort(requestId)
                send(
                    SolveEvent.Failed(
                        stage = stage,
                        error = LlmErrorMapper.fromThrowable(throwable),
                        partialWarnings = warnings.toList(),
                    ),
                )
            }
        }

    /**
     * 执行单阶段，含「解析失败以更严格提示词重试 1 次」与 ai_trace 落库。
     *
     * 结构说明（0.2.0 重构）：把「一次尝试」抽成 [attemptOnce]，主循环只负责
     * 按 [Attempt] 决策推进重试计数。这样 while 里只剩一个 continue，
     * 「最多试几次、每次为什么重试」变成可以直接读出来的表，而不是散在五个分支里的跳转。
     */
    @Suppress("LongMethod")
    private suspend fun callStage(
        credentials: LlmCredentials,
        requestId: String,
        stage: AiStage,
        model: String,
        promptVersion: String,
        prompt: AssembledPrompt,
        image: EncodedImage?,
        extraImages: List<EncodedImage> = emptyList(),
        maxTokens: Int,
        registry: LlmCallRegistry?,
        onDelta: suspend (String) -> Unit,
        onFirstToken: suspend (Long) -> Unit,
    ): StageCallResult {
        var attempt = 0
        var lastError: LlmError? = null
        // 三档参数降级：某些厂商不支持 thinking / response_format，
        // 遇到「参数不支持」就逐级关掉后重试，而不是直接失败。
        var state = CallState()
        while (attempt < MAX_STAGE_ATTEMPTS) {
            val outcome =
                attemptOnce(
                    credentials = credentials,
                    requestId = requestId,
                    stage = stage,
                    model = model,
                    promptVersion = promptVersion,
                    prompt = prompt,
                    image = image,
                    extraImages = extraImages,
                    maxTokens = maxTokens,
                    registry = registry,
                    onDelta = onDelta,
                    onFirstToken = onFirstToken,
                    state = state,
                )
            when (outcome) {
                is Attempt.Succeeded -> return outcome.result
                is Attempt.Retry -> {
                    lastError = outcome.error
                    state = state.next(outcome)
                }
            }
            attempt++
        }
        throw LlmException(
            lastError ?: LlmError.MalformedResponse(detail = "阶段 " + stage.wire + " 重试后仍不可解析"),
        )
    }

    /**
     * 重试状态：这一轮用什么姿势发请求。
     *
     * 之所以打包成一个对象而不是三个并列的布尔参数：
     * 1. [attemptOnce] 的参数个数本来就接近阈值，再加三个布尔值会超出可读范围；
     * 2. 「严格提示词 / 关思考 / 去 response_format」是同一件事的三个维度（这一轮怎么发），
     *    放在一起才能保证升级是**单调**的（只关不开，不会来回抖）。
     */
    private data class CallState(
        /** 是否使用更严格的提示词（stricter schema + 只输出 JSON 的硬约束）。 */
        val strict: Boolean = false,
        /** 是否关闭思考模式（发送 THINKING_UNSPECIFIED 而不是 DISABLED）。 */
        val disableThinking: Boolean = false,
        /** 是否去掉 response_format=json_object。 */
        val disableJsonObject: Boolean = false,
    ) {
        /**
         * 依据本次重试原因推进到下一轮状态。
         *
         * 所有标志位只允许从 false 变 true：降级是单向的，
         * 一旦确认某个参数不被支持，就不该在后续重试里再打开它。
         */
        fun next(retry: Attempt.Retry): CallState =
            CallState(
                strict = strict || retry.retryAsStrict,
                disableThinking = disableThinking || retry.disableThinkingNext,
                disableJsonObject = disableJsonObject || retry.disableJsonObjectNext,
            )
    }

    /** 一次尝试的结果：要么拿到可解析的正文，要么给出「为什么该重试、下次要改什么」。 */
    private sealed class Attempt {
        /** 成功：正文已通过该阶段的结构校验。 */
        data class Succeeded(val result: StageCallResult) : Attempt()

        /**
         * 需要重试。
         *
         * @param error 本次失败原因（最终全部失败时报给用户的就是最后一条）
         * @param retryAsStrict 下次是否改用更严格的提示词
         * @param disableThinkingNext 下次是否关闭思考模式
         * @param disableJsonObjectNext 下次是否去掉 response_format
         */
        data class Retry(
            val error: LlmError,
            val retryAsStrict: Boolean = true,
            val disableThinkingNext: Boolean = false,
            val disableJsonObjectNext: Boolean = false,
        ) : Attempt()
    }

    /**
     * 发起一次调用并判定结果。
     *
     * 这里集中了「流式收集 → 空正文诊断 → 结构校验 → 参数降级判定」四件事，
     * 且每种失败都**只返回一个 Retry**，不再自己决定要不要继续重试——
     * 重试预算由调用方的循环统一管理。
     */
    @Suppress("LongMethod", "LongParameterList")
    private suspend fun attemptOnce(
        credentials: LlmCredentials,
        requestId: String,
        stage: AiStage,
        model: String,
        promptVersion: String,
        prompt: AssembledPrompt,
        image: EncodedImage?,
        extraImages: List<EncodedImage>,
        maxTokens: Int,
        registry: LlmCallRegistry?,
        onDelta: suspend (String) -> Unit,
        onFirstToken: suspend (Long) -> Unit,
        state: CallState,
    ): Attempt {
        val startedAt = clock.nowUtcMillis()
        val buffer = StringBuilder()
        val reasoningBuffer = StringBuilder()
        var firstTokenAt = 0L
        var usage = TokenUsage.EMPTY
        var finishReason = ""
        val effectivePrompt = if (state.strict) prompt.asStrict() else prompt
        val config =
            ChatRequestConfig(
                model = model,
                messages = buildMessages(effectivePrompt, image, extraImages),
                maxTokens = maxTokens,
                temperature = if (stage == AiStage.B) STAGE_B_TEMPERATURE else STAGE_DEFAULT_TEMPERATURE,
                // json_object 与思考链天然冲突（正文被挤空），
                // 因此默认关闭思考；模型不支持该字段时再降级为「不发送」。
                jsonObject = !state.disableJsonObject,
                includeUsage = true,
                stream = true,
                thinkingMode =
                    if (state.disableThinking) {
                        ChatRequestConfig.THINKING_UNSPECIFIED
                    } else {
                        ChatRequestConfig.THINKING_DISABLED
                    },
            )
        try {
            var streamError: LlmError? = null
            llmClient.stream(credentials, config, requestId, registry, allowRetry = true)
                .collect { chunk ->
                    when (chunk) {
                        is LlmChunk.Delta -> {
                            if (firstTokenAt == 0L) {
                                firstTokenAt = clock.nowUtcMillis()
                                onFirstToken(firstTokenAt - startedAt)
                            }
                            buffer.append(chunk.text)
                            onDelta(chunk.text)
                        }
                        // 思考链不拼进正文，只记账，用于诊断「为什么正文为空」
                        is LlmChunk.Reasoning -> reasoningBuffer.append(chunk.text)
                        is LlmChunk.Finished -> {
                            usage = chunk.usage
                            finishReason = chunk.finishReason
                        }
                        is LlmChunk.Failed -> streamError = chunk.error
                    }
                }
            streamError?.let { throw LlmException(it) }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (exception: LlmException) {
            traceRecorder.record(
                requestId = requestId,
                stage = stage,
                model = model,
                promptVer = promptVersion,
                latencyMs = clock.nowUtcMillis() - startedAt,
                ok = false,
                errorMsg = exception.error.traceMessage(),
            )
            // 参数类错误：先尝试降级再重试，而非直接失败
            if (exception.error is LlmError.ParameterNotSupported) {
                when (nextDowngrade(state.disableThinking, state.disableJsonObject)) {
                    Downgrade.DisableThinking ->
                        return Attempt.Retry(exception.error, disableThinkingNext = true)
                    Downgrade.DisableJsonObject ->
                        return Attempt.Retry(exception.error, disableJsonObjectNext = true)
                    Downgrade.None -> Unit
                }
            }
            if (!exception.error.isRetryable) throw exception
            return Attempt.Retry(exception.error)
        }

        val raw = buffer.toString()
        // 正文为空时给出精确成因，不再笼统报「输出为空」：
        // length 表示被 max_tokens 截断，thinkingOnly 表示只吐了思考链。
        if (raw.isBlank()) {
            val empty =
                LlmError.EmptyCompletion(
                    finishReason = finishReason,
                    thinkingOnly = reasoningBuffer.isNotBlank(),
                    detail =
                        "阶段 " + stage.wire + " 正文为空（思考链 " + reasoningBuffer.length +
                            " 字，finish_reason=" + finishReason.ifEmpty { "未知" } + "）",
                )
            traceRecorder.record(
                requestId = requestId,
                stage = stage,
                model = model,
                promptVer = promptVersion,
                inputTokens = usage.inputTokens,
                outputTokens = usage.outputTokens,
                latencyMs = clock.nowUtcMillis() - startedAt,
                ok = false,
                errorMsg = empty.traceMessage(),
            )
            // 被 max_tokens 截断：优先怀疑思考链吃掉了预算，
            // 关掉 json_object 会浪费预算，因此改成「关思考 + 收紧提示词」再试
            val truncated = finishReason == FINISH_REASON_LENGTH && !state.disableJsonObject
            return Attempt.Retry(empty, disableThinkingNext = truncated)
        }
        val parseError = validateStageJson(stage, raw)
        traceRecorder.record(
            requestId = requestId,
            stage = stage,
            model = model,
            promptVer = promptVersion,
            inputTokens = usage.inputTokens,
            outputTokens = usage.outputTokens,
            latencyMs = clock.nowUtcMillis() - startedAt,
            ok = parseError == null,
            errorMsg = parseError?.let { "malformed_response：" + it },
        )
        if (parseError == null) return Attempt.Succeeded(StageCallResult(raw = raw, usage = usage))
        return Attempt.Retry(LlmError.MalformedResponse(detail = parseError))
    }

    /**
     * 组装 system + user 消息；视觉档把图片附在 user 消息里。
     *
     * 多页处理：所有页放在**同一条** user 消息中按页序排列，
     * 并在每张图前插入「第 i/N 页」提示，让模型知道这是同一道题的连续内容，
     * 而不是若干道互不相关的题。这比逐页分别提问更准确，且只消耗一次调用。
     */
    private fun buildMessages(
        prompt: AssembledPrompt,
        image: EncodedImage?,
        extraImages: List<EncodedImage> = emptyList(),
    ): List<ChatMessage> {
        val allImages = listOfNotNull(image) + extraImages
        if (allImages.isEmpty()) return prompt.asMessages()
        if (allImages.size == 1) {
            return listOf(
                ChatMessage.system(prompt.systemPrompt),
                ChatMessage.userWithImage(prompt.userPrompt, allImages.first().base64),
            )
        }
        val total = allImages.size
        val hints =
            allImages.indices.map { index ->
                "下面这张是同一道题的第 " + (index + 1) + "/" + total + " 页，请与其余页合并理解后作答。"
            }
        return listOf(
            ChatMessage.system(prompt.systemPrompt),
            ChatMessage.userWithImages(
                text = prompt.userPrompt,
                base64Jpegs = allImages.map { it.base64 },
                pageHints = hints,
            ),
        )
    }

    /**
     * 缓存键：sha256(图片或题干) + promptVersion + model（开发方案 3.4）。
     *
     * 阶段 A 的输入只有「图片 + OCR 文本 + 学生补充说明」，不包含学生解题过程，
     * 因此其输出可安全复用。为保证「同一题干但补充说明不同」不会串用缓存，
     * 无图时把补充说明一并纳入 sha256 的原文。
     */
    private fun cacheKeyOf(
        decision: RouteDecision,
        request: SolveRequest,
        promptVersion: String,
    ): CacheKey? {
        val sha =
            request.image?.sha256
                ?: request.ocr.text.takeIf { it.isNotBlank() }?.let {
                    sha256Of(
                        it + CACHE_SEPARATOR + request.supplement,
                    )
                }
                ?: return null
        return CacheKey(sha256 = sha, promptVersion = promptVersion, model = decision.model)
    }

    private fun sha256Of(text: String): String = ImageMath.sha256Hex(text.toByteArray(Charsets.UTF_8))

    /**
     * 参数降级的下一步动作。
     *
     * 之所以抽成枚举 + 纯函数：原来这段逻辑用连续的 if + continue 表达，
     * 一个 while 里出现 4 个跳转语句，读代码时很难一眼看出「最多重试几次、每次关掉哪个参数」。
     * 现在动作集合是封闭的，判定也是纯函数，可直接单测。
     */
    internal enum class Downgrade { DisableThinking, DisableJsonObject, None }

    /** 判定还能不能继续降级；已全部关闭时返回 [Downgrade.None]。 */
    internal fun nextDowngrade(
        thinkingDisabled: Boolean,
        jsonObjectDisabled: Boolean,
    ): Downgrade =
        when {
            !thinkingDisabled -> Downgrade.DisableThinking
            !jsonObjectDisabled -> Downgrade.DisableJsonObject
            else -> Downgrade.None
        }

    companion object {
        /**
         * 单阶段最多尝试次数。
         *
         * 由 2 提到 4：除 FR-1.10 的「更严格提示词重试」外，还需要容纳两类降级重试——
         * 关思考模式、去 response_format。降级只发生在「参数不支持」或「被截断」时，
         * 不会对正常请求产生额外开销。
         */
        const val MAX_STAGE_ATTEMPTS: Int = 4

        /** 阶段 B（独立求解）的温度：偏低以保证同一道题多次求解结果一致。 */
        private const val STAGE_B_TEMPERATURE: Double = 0.1

        /** 其余阶段的温度：略高，让错因说明与知识点用途的措辞更自然。 */
        private const val STAGE_DEFAULT_TEMPERATURE: Double = 0.2

        /** 厂商表示「输出被 max_tokens 截断」的 finish_reason 取值。 */
        private const val FINISH_REASON_LENGTH: String = "length"

        /** 缓存键原文分隔符，避免「题干尾部 + 补充说明」拼出相同字符串。 */
        private const val CACHE_SEPARATOR: String = "\u0000"

        /**
         * 无候选知识点时阶段 D 的结构化空结果（字段与提示词契约完全一致）。
         *
         * 刻意构造合法的 JSON 而不是留空字符串：
         * 后续合并与校验会把它当作「模型返回了空 kpoints」，走正常解析路径，
         * 避免出现「空字符串被当成解析失败」这种伪故障。
         */
        private const val EMPTY_KPOINTS_JSON: String =
            "{\"kpoints\":[],\"primaryId\":null,\"unmatched\":true}"

        /** 由凭据推导模型池。 */
        fun modelPool(credentials: LlmCredentials): ModelPool = credentials.modelPool

        /** 校验某阶段输出是否是合法 JSON 对象。 */
        fun validateStageJson(
            stage: AiStage,
            raw: String,
        ): String? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return "阶段 " + stage.wire + " 输出为空"
            val start = trimmed.indexOf('{')
            val end = trimmed.lastIndexOf('}')
            if (start < 0 || end <= start) return "阶段 " + stage.wire + " 输出中没有 JSON 对象"
            val candidate = trimmed.substring(start, end + 1)
            return runCatching { OpenAiWire.json.parseToJsonElement(candidate) }.fold(
                onSuccess = { null },
                onFailure = { "阶段 " + stage.wire + " 输出不是合法 JSON：" + it.message },
            )
        }

        /** 抽取 JSON 对象（容忍 Markdown 代码围栏与前置说明文字）。 */
        fun decodeJsonObject(raw: String): JsonObject {
            val trimmed = raw.trim()
            val start = trimmed.indexOf('{')
            val end = trimmed.lastIndexOf('}')
            require(start >= 0 && end > start) { "输出中没有 JSON 对象" }
            return OpenAiWire.json.parseToJsonElement(trimmed.substring(start, end + 1)) as JsonObject
        }

        /** 合并四阶段结果为 6.4 契约 JSON。 */
        fun buildContractJson(
            question: AiQuestion,
            solutionObject: JsonObject,
            stageCObject: JsonObject,
            stageDObject: JsonObject,
        ): String =
            buildJsonObject {
                put("question", questionObject(question))
                put("standardSolution", solutionObject)
                put("studentSteps", stageCObject["studentSteps"] ?: JsonArray(emptyList()))
                put("firstError", stageCObject["firstError"] ?: kotlinx.serialization.json.JsonNull)
                put("knowledgePoints", stageDObject["kpoints"] ?: JsonArray(emptyList()))
                put("summaryCard", stageCObject["summaryCard"] ?: kotlinx.serialization.json.JsonNull)
                put("aiGenerated", true)
            }.toString()

        private fun questionObject(question: AiQuestion): JsonObject =
            buildJsonObject {
                put("subject", question.subject ?: "math")
                put("grade", question.grade ?: "未知")
                put("stem", question.stem.orEmpty())
                put("known", buildJsonArray { question.known.forEach { add(JsonPrimitive(it)) } })
                put("ask", question.ask.orEmpty())
                put("options", buildJsonArray { question.options.forEach { add(JsonPrimitive(it)) } })
                put("hasFigure", question.hasFigure)
            }

        /** 解析阶段 B 的输出为 [AiStandardSolution]。 */
        fun decodeStandardSolution(raw: String): AiStandardSolution {
            val obj = decodeJsonObject(raw)
            val stepsArray = obj["steps"] as? JsonArray
            val steps =
                stepsArray.orEmpty().mapNotNull { element ->
                    val step = element as? JsonObject ?: return@mapNotNull null
                    AiSolutionStep(
                        idx = step["idx"]?.let { runCatching { it.toString().toInt() }.getOrNull() },
                        content = step.stringOrNull("content"),
                        why = step.stringOrNull("why"),
                    )
                }
            return AiStandardSolution(
                steps = steps,
                finalAnswer = obj.stringOr("finalAnswer", ""),
                method = obj.stringOr("method", ""),
            )
        }

        private fun TokenUsage.plus(other: TokenUsage): TokenUsage =
            TokenUsage(inputTokens + other.inputTokens, outputTokens + other.outputTokens)
    }
}

// ---------- JSON 小工具（宽松解析用） ----------

internal fun JsonObject.stringOr(
    key: String,
    fallback: String,
): String = stringOrNull(key) ?: fallback

internal fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString || it.content.isNotEmpty() }?.content

internal fun JsonObject.stringListOr(key: String): List<String> =
    (this[key] as? JsonArray).orEmpty().mapNotNull { element -> (element as? JsonPrimitive)?.content }

internal fun JsonObject.booleanOr(
    key: String,
    fallback: Boolean,
): Boolean = (this[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: fallback

/** 生成「更严格」版本提示词（FR-1.10 重试）。 */
fun AssembledPrompt.asStrict(): AssembledPrompt =
    if (strict) {
        this
    } else {
        copy(
            userPrompt = userPrompt + "\n\n" + STRICT_SUFFIX,
            strict = true,
        )
    }

private const val STRICT_SUFFIX: String =
    "上一次输出无法被解析。本次务必严格满足：\n" +
        "1. 整个回复只包含一个 JSON 对象，前后不得有任何文字、Markdown 代码围栏或注释；\n" +
        "2. 不要出现省略号、占位符或未闭合的数组；\n" +
        "3. Schema 中 required 的字段必须全部出现，字符串不得为空；\n" +
        "4. 数字字段只写数字，不加引号、不加单位。"
