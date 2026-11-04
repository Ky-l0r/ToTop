package com.totop.app.data.remote

import com.totop.app.data.prefs.LlmCredentials
import com.totop.app.data.prefs.ModelPool
import com.totop.app.domain.model.AiQuestion
import com.totop.app.domain.model.AiSolutionStep
import com.totop.app.domain.model.AiSolveResult
import com.totop.app.domain.model.AiSolveResultCodec
import com.totop.app.domain.model.AiSolveResultMapper
import com.totop.app.domain.model.AiStage
import com.totop.app.domain.model.AiStandardSolution
import com.totop.app.domain.model.ChoiceAnswerGuard
import com.totop.app.domain.model.LearnedMaterial
import com.totop.app.domain.model.Question
import com.totop.app.domain.model.QuestionProgress
import com.totop.app.domain.model.QuestionProgressState
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
import kotlinx.serialization.json.JsonNull
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
    /**
     * 学生自己收集的资料（别人的解析）。
     *
     * 由上层按「最近若干条」取出，这里按阶段 A 识别出的科目再收敛一次——
     * 物理题混进语文解析比不给资料更糟。全部不匹配时退回原列表，
     * 宁可参考得杂一点，也不要在学生刚存完资料时一条都不用。
     */
    val materials: List<LearnedMaterial> = emptyList(),
    /** 「简洁 / 详细」两档输出上限。 */
    val maxTokens: Int = ChatRequestConfig.MAX_TOKENS_CONCISE,
    /** 幂等请求 ID；为空时自动生成。 */
    val requestId: String = "",
)

/** 只识别题目的结果（补拍更新题干用）。 */
data class IdentifyOutcome(
    val question: AiQuestion,
    val usage: TokenUsage,
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
     * 合并四阶段输出为 6.4 契约，并做严格校验。
     *
     * 注意：此处已不属于任何单个阶段，是四阶段结果的合并校验。旧实现把 stage 设为 D，
     * 导致「合并校验失败」被误报成「阶段 D 失败」，掩盖了真实原因
     * （例如其实是阶段 C 没给 studentSteps）。
     */
    private fun mergeStages(
        question: AiQuestion,
        rawSolution: String,
        stageC: JsonObject,
        rawD: String,
        warnings: MutableList<String>,
    ): AiSolveResult {
        val contractJson =
            buildContractJson(
                question = question,
                solutionObject = decodeJsonObject(rawSolution),
                stageCObject = stageC,
                stageDObject = decodeJsonObject(rawD),
            )
        val dto =
            AiSolveResultCodec.decode(contractJson).getOrElse { failure ->
                throw LlmException(
                    LlmError.MalformedResponse(
                        detail = "合并四阶段结果后仍不满足契约：" + failure.message,
                        // 合并阶段在重试循环之外，这里失败并没有再试一次
                        afterRetries = false,
                    ),
                )
            }
        val missing = dto.missingStructure()
        if (missing.isNotEmpty()) {
            // FR-1.10：结构性缺失（题干或标准解答）视为校验失败，绝不入库
            throw LlmException(
                LlmError.MalformedResponse(
                    detail = "必要的契约字段缺失：" + missing.joinToString("、"),
                    afterRetries = false,
                ),
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
        return dto
    }

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
                // 档位由「有没有带图」决定：只按 OCR 置信度判断会把「图里才有的条件」整包丢掉，
                // 多页题目的第 2 页起更是一张都不会被看到。
                val hasImages = request.image != null || request.extraImages.isNotEmpty()
                val decision = router.route(request.ocr, hasImages = hasImages)
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
                // 命中也要过一遍结构校验：旧版本（校验更松）可能在缓存里留下不合规的 raw，
                // 直接复用会让「再解一次」永远拿到同一份坏数据——表现就是修了像没修。
                val usableCached = cachedRaw?.takeIf { validateStageJson(AiStage.A, it) == null }
                if (cachedRaw != null && usableCached == null && cacheKey != null) {
                    cache?.invalidate(cacheKey)
                }
                var rawA = usableCached
                if (usableCached != null) {
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
                            // 阶段 A 必须一次看到全部页面，否则续页里的第 (2) 问与图形会丢
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
                val question = parseStageAQuestion(rawStageA, warnings)
                send(SolveEvent.StageCompleted(AiStage.A, if (usableCached != null) "命中缓存" else "识题完成"))

                // ---------- 阶段 B：独立求解（绝不注入学生过程） ----------
                stage = AiStage.B
                send(SolveEvent.StageStarted(stage))
                // 参考资料：按科目收敛到最多 3 条，每条渲染成不超过 400 字的文本块。
                // 它们进的是阶段 B——这是用户明确要求的（「不加进去第一次还是会错」），
                // 代价是阶段 B 不再是纯独立求解，因此提示词里写死了「冲突时说明差异，不许照抄」。
                val referenceBlocks = selectMaterials(request.materials, question.subject)
                val solvePrompt =
                    promptRepository.assemble(
                        StageInput.Solve(question, referenceBlocks),
                        requestId = requestId,
                    )
                var rawB =
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
                val firstSolution = decodeStandardSolution(rawB.raw)
                if (firstSolution.steps.isEmpty()) throw LlmException(LlmError.MalformedResponse("阶段 B 未产出任何解答步骤"))
                // 题型与答案个数对不上时重答一次（见 ChoiceEnforcement 里的说明）
                val enforced =
                    enforceChoiceAnswer(question, solvePrompt, firstSolution, warnings) { retryPrompt ->
                        callStage(
                            credentials = credentials,
                            requestId = requestId,
                            stage = AiStage.B,
                            model = decision.model,
                            promptVersion = solvePrompt.promptVersion,
                            prompt = retryPrompt,
                            image = null,
                            maxTokens = request.maxTokens,
                            registry = registry,
                            onDelta = { text -> send(SolveEvent.Delta(AiStage.B, text)) },
                            onFirstToken = { elapsed -> send(SolveEvent.FirstToken(AiStage.B, elapsed)) },
                        )
                    }
                totalUsage = totalUsage.plus(enforced.usage)
                // 重答通过校验才替换，否则沿用第一次的结果（差异已写进 warnings）
                val solution = enforced.solution ?: firstSolution
                val rawBText = enforced.raw ?: rawB.raw
                send(SolveEvent.StageCompleted(AiStage.B, "解得 " + solution.steps.size + " 步"))
                // ---------- 阶段 C：错因定位（必须注入学生过程） ----------
                stage = AiStage.C
                send(SolveEvent.StageStarted(stage))
                // 学生过程有两个来源：他自己打的字，和卷面上的手写。
                //
                // 「审批」这条需求的核心就在这里：学生把写了过程的卷子拍下来、一个字都不打，
                // 也要能拿到「错在哪一步、下一步做什么」。阶段 A 已经从图上读出了手写内容，
                // 直接把那份转写当作学生过程交给阶段 C——不需要额外一次调用，也不需要他复述。
                val studentText = composeStudentText(request.studentRawText, question.handwritingText)
                val noStudentText = studentText.isBlank()
                val locatePrompt =
                    promptRepository.assemble(
                        StageInput.LocateError(
                            question = question,
                            studentRawText = studentText.ifBlank { "（学生未提供解题过程）" },
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
                    }
                        // 候选清单现在按科目各挑一份（客户端侧），过滤后仍要限条数：
                        // 进提示词的每一条都是钱，60 条约 1.5K token 已到预算上限。
                        .take(MAX_STAGE_CANDIDATES)
                        .ifEmpty { request.candidateKpoints.take(MAX_STAGE_CANDIDATES) }
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

                // 知识点没带 id 时 sanitizer 会整条丢掉，结果按「未匹配到」处理。
                // 这是模型的形状问题，重发一次通常还是不带 id——降级 + 告警，
                // 比让整次解答失败更省一次调用（这才是「不重试」的正确用法）。
                if (hasCandidates && kpointsMissingId(rawD)) {
                    warnings.add("模型给出的知识点没有带受控 ID，本次按「未匹配到知识点」处理")
                }

                // ---------- 合并四阶段输出为 6.4 契约并严格校验 ----------
                // 这一段单独成函数：solve 已经贴着 detekt 的 260 行上限，
                // 合并校验（解码 + 结构校验 + 非致命缺失降级）放进来只会让它更难读。
                val dto =
                    mergeStages(
                        question = question,
                        rawSolution = rawBText,
                        stageC = stageC,
                        rawD = rawD,
                        warnings = warnings,
                    )
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
     * 只跑阶段 A：补拍后重新识题，用于更新错题本里的题干。
     *
     * 为什么只跑 A，而不重跑 B/C/D：
     * 补拍是「同一道题的补充照片」，不是换题——解答与错因仍然成立，
     * 需要修正的只是「原题到底写了什么」。重跑 B 会得到一份新的解答，
     * 与已有的错因、提示记录、复发计数全部对不上。
     *
     * 不写缓存：补拍的照片是新的 sha256，缓存里不会有它；写进去也只会占空间。
     *
     * @throws LlmException 模型未配置、调用失败或输出无法解析
     */
    suspend fun identifyOnly(
        credentials: LlmCredentials,
        request: SolveRequest,
        registry: LlmCallRegistry? = null,
    ): IdentifyOutcome {
        if (!credentials.canCall) {
            throw LlmException(
                LlmError.Unknown(detail = "尚未配置模型（Base URL / API Key / 模型名），请到设置页填写"),
            )
        }
        val requestId = request.requestId.ifBlank { RequestId.newId() }
        val hasImages = request.image != null || request.extraImages.isNotEmpty()
        val decision = ModelRouter(modelPool(credentials)).route(request.ocr, hasImages = hasImages)
        val prompt =
            promptRepository.assemble(
                StageInput.Identify(
                    ocrText = request.ocr.text,
                    supplement = request.supplement,
                    attachImage = decision.attachImage,
                ),
                requestId = requestId,
            )
        val result =
            callStage(
                credentials = credentials,
                requestId = requestId,
                stage = AiStage.A,
                model = decision.model,
                promptVersion = prompt.promptVersion,
                prompt = prompt,
                image = if (decision.attachImage) request.image else null,
                extraImages = if (decision.attachImage) request.extraImages else emptyList(),
                maxTokens = request.maxTokens,
                registry = registry,
                onDelta = {},
                onFirstToken = {},
            )
        val obj = decodeJsonObject(result.raw)
        val question =
            AiQuestion(
                subject = obj.stringOr("subject", "math"),
                grade = obj.stringOr("grade", "未知"),
                stem = obj.stringOr("stem", ""),
                known = obj.stringListOr("known"),
                ask = obj.stringOr("ask", ""),
                options = obj.stringListOr("options"),
                hasFigure = obj.booleanOr("hasFigure", false),
            )
        if (question.stem.isNullOrBlank()) {
            throw LlmException(LlmError.MalformedResponse(detail = "补拍后仍未识别出题干"))
        }
        return IdentifyOutcome(question = question, usage = result.usage)
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
        // 用户选的档位是基准，各阶段再按自己的输出体量做上下修正（见 [stageMaxTokens]）。
        // 放在这里而不是每个调用点：调用点有五处，漏一处就会出现「某一个阶段总被截断」。
        val stageBudget = stageMaxTokens(stage, maxTokens)
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
                    maxTokens = stageBudget,
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
        /** 是否把输出预算加倍：上一轮被 max_tokens 截断过。 */
        val budgetBoosted: Boolean = false,
    ) {
        /**
         * 依据本次重试原因推进到下一轮状态。
         *
         * 所有标志位只允许从 false 变 true：降级是单向的，
         * 一旦确认某个参数不被支持（或预算不够），就不该在后续重试里再退回去。
         */
        fun next(retry: Attempt.Retry): CallState =
            CallState(
                strict = strict || retry.retryAsStrict,
                disableThinking = disableThinking || retry.disableThinkingNext,
                disableJsonObject = disableJsonObject || retry.disableJsonObjectNext,
                budgetBoosted = budgetBoosted || retry.boostBudgetNext,
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
         * @param boostBudgetNext 下次是否加大输出预算（这次被截断了）
         */
        data class Retry(
            val error: LlmError,
            val retryAsStrict: Boolean = true,
            val disableThinkingNext: Boolean = false,
            val disableJsonObjectNext: Boolean = false,
            val boostBudgetNext: Boolean = false,
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
        // 上一轮被截断过就加倍预算：同样的上限只会再被截一次，
        // 而重试本身已经付过一次全价，抠这点输出预算没有意义。
        val budget = if (state.budgetBoosted) minOf(maxTokens * 2, MAX_TOKENS_CEILING) else maxTokens
        val config =
            ChatRequestConfig(
                model = model,
                messages = buildMessages(effectivePrompt, image, extraImages),
                maxTokens = budget,
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
            // 关掉 json_object 会浪费预算，因此改成「关思考 + 收紧提示词 + 下一轮加预算」再试
            val truncated = finishReason == FINISH_REASON_LENGTH
            return Attempt.Retry(
                empty,
                disableThinkingNext = truncated && !state.disableJsonObject,
                boostBudgetNext = truncated && !state.budgetBoosted,
            )
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
        // 截断导致的解析失败与「模型就不按 JSON 输出」要分开处理：
        // 前者只需加预算，后者要收紧提示词，两者同时做也不会互相干扰。
        // 截断判定不能只看 finish_reason：流式中转常把它写成 stop 或干脆省略，
        // 残 JSON 就会被当成「不按 JSON 输出」——重试却不加预算，同一个上限再截一次。
        val truncatedJson = finishReason == FINISH_REASON_LENGTH || JsonSalvage.looksTruncated(raw)
        val boostBudget = truncatedJson && !state.budgetBoosted
        val malformed = LlmError.MalformedResponse(detail = parseError, truncated = truncatedJson)
        // 严格提示词用过、预算也加过：同一个请求再发一次不会变好，只会再收一次钱。
        // 例外是截断——加大预算确实换了输入，值得再试一轮。
        if (state.strict && !boostBudget) throw LlmException(malformed)
        return Attempt.Retry(malformed, boostBudgetNext = boostBudget)
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

        /**
         * 阶段 D 单次最多送进提示词的知识点候选条数。
         *
         * 与客户端 [KpointCandidates.MAX_CANDIDATES] 对齐：60 条约 1.5K token。
         * 候选清单在客户端可能按科目各存一份（总量数百条），但真正进请求的只有这一份。
         */
        private const val MAX_STAGE_CANDIDATES: Int = 60

        /** 其余阶段的温度：略高，让错因说明与知识点用途的措辞更自然。 */
        private const val STAGE_DEFAULT_TEMPERATURE: Double = 0.2

        /** 厂商表示「输出被 max_tokens 截断」的 finish_reason 取值。 */
        private const val FINISH_REASON_LENGTH: String = "length"

        /** 诊断日志里最多列几个顶层字段名。 */
        private const val MAX_LOGGED_KEYS: Int = 8

        /**
         * 阶段 B（独立求解）的最低输出预算。
         *
         * 「简洁」档 2048 对一道多小问的解答常常不够：JSON 被拦腰截断后整份作废，
         * 还得再花一次调用重来，比一开始就给够更贵。求解是这份解答的主体，因此抬高下限；
         * 是否继续加大仍由用户选的档位决定（详细档本身就是 8192）。
         */
        private const val STAGE_B_MIN_TOKENS: Int = 4096

        /** 阶段 C（错因定位）的最低输出预算：步骤 + 错因 + 小结，比识题长。 */
        private const val STAGE_C_MIN_TOKENS: Int = 3072

        /** 阶段 D 只需要一小组 ID，给多了纯属浪费。 */
        private const val STAGE_D_MAX_TOKENS: Int = 2048

        /** 被截断时加压的上限：再加也是浪费，超长输出本身就说明该换档位。 */
        private const val MAX_TOKENS_CEILING: Int = 16384

        /**
         * 按阶段给出本次请求的输出上限。
         *
         * 用户选的「简洁 / 详细」是**基准**，不是每个阶段的精确值：
         * 识题（A）字段少，用基准；求解（B）与错因（C）给下限保护；
         * 知识点（D）只回一小组 ID，压到基准以内。
         */
        internal fun stageMaxTokens(
            stage: AiStage,
            requested: Int,
        ): Int =
            when (stage) {
                AiStage.A -> requested
                AiStage.B -> maxOf(requested, STAGE_B_MIN_TOKENS)
                AiStage.C -> maxOf(requested, STAGE_C_MIN_TOKENS)
                AiStage.D -> minOf(requested, STAGE_D_MAX_TOKENS)
            }

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

        /**
         * 校验某阶段输出是否可用：先是合法 JSON 对象，再是该阶段必须具备的字段。
         *
         * 为什么要连字段一起校验：
         * 旧实现只判「是不是 JSON」，于是「JSON 合法但字段名不对」会被放到阶段解析时
         * 直接抛错，**不会触发严格提示词重试**——明明还有一次机会就白白浪费了。
         * 现在把「形状不对」也归到解析失败，和「不是 JSON」走同一条重试路径。
         *
         * 注意：detail 会写进 ai_trace，属于日志，**不得包含模型正文**
         * （可能含题干原文与学生作答），只记结构特征。
         */
        fun validateStageJson(
            stage: AiStage,
            raw: String,
        ): String? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return "阶段 " + stage.wire + " 输出为空"
            val obj = JsonSalvage.parseObject(trimmed)
            if (obj == null) {
                return "阶段 " + stage.wire + " 输出不是合法 JSON" + shapeHintOf(trimmed)
            }
            val present = requiredKeysOf(stage).filter { obj.containsKey(it) }
            if (present.isEmpty()) {
                return "阶段 " + stage.wire + " 缺少约定字段（需要其一：" +
                    requiredKeysOf(stage).joinToString("、") + "；实际顶层字段：" +
                    obj.keys.take(MAX_LOGGED_KEYS).joinToString("、") + "）"
            }
            // 求解阶段「没有步骤」等于没答：下游只会直接抛错，
            // 在这里判失败至少还能用严格提示词再试一次。
            // 口径要和 sanitizer 一致：字符串元素也算一步，否则
            // 「steps 写成一句话数组」会被判死，而它一步都不用重试。
            if (stage == AiStage.B && !hasUsableStep(obj["steps"])) {
                return "阶段 B 的 steps 为空或没有可用的步骤"
            }
            // 识题阶段标题为空同理：宁可再问一次，也不要带着空题干往下走
            if (stage == AiStage.A && (obj["stem"] as? JsonPrimitive)?.content.isNullOrBlank()) {
                return "阶段 A 的 stem 为空"
            }
            // 再往下是嵌套形状：能就地修的（见 [SolveWireSanitizer]）不算错，
            // 修不了的必须在这里判失败——**合并阶段不重试**，
            // 拖到那时才报错，用户看到的就是「试都试过了还是不行」。
            return shapeErrorOf(stage, obj)
        }

        /**
         * 嵌套形状校验（只看能不能被修复，不看业务语义）。
         *
         * 判定口径必须与 [SolveWireSanitizer] 逐条对齐：能被它纠正的偏差
         * （字符串步骤、纯量 firstError）不算错，纠正不了的容器类型才算错。
         * 这两处是本项目最容易改歪的地方——改一处必须同时看另一处。
         */
        private fun shapeErrorOf(
            stage: AiStage,
            obj: JsonObject,
        ): String? =
            when (stage) {
                // 阶段 B 已在上面按同一口径判过 steps
                AiStage.B -> null

                AiStage.C -> {
                    // 字符串：整段文字，sanitizer 会按换行切；数字/布尔：模型跑偏了，
                    // 与其把「123」当成一步学生过程，不如用严格提示词再要一次。
                    val studentSteps = obj["studentSteps"]
                    val bad =
                        when (studentSteps) {
                            null, is JsonNull, is JsonArray, is JsonObject -> false
                            is JsonPrimitive -> !studentSteps.isString
                            else -> true
                        }
                    if (bad) "阶段 C 的 studentSteps 既不是数组也不是一段文字" else null
                }

                // 阶段 D 的 kpoints 里没有 id 时不判失败：受控 ID 不能编，
                // sanitizer 会丢掉这些条目，结果按「未匹配到知识点」降级（有告警）。
                // 为了一条没有 id 的知识点重发整次请求，是拿用户的钱换一次同样的错。
                AiStage.D -> {
                    val kpoints = obj["kpoints"]
                    val bad =
                        when (kpoints) {
                            null, is JsonNull, is JsonArray -> false
                            // 整段文字还能按换行拆；数字/布尔不是知识点列表，重试一次更划算
                            is JsonPrimitive -> !kpoints.isString
                            else -> true
                        }
                    if (bad) "阶段 D 的 kpoints 既不是数组也不是一段文字" else null
                }

                else -> null
            }

        /**
         * 「有没有可用的步骤」：字符串元素也算，因为 sanitizer 会把它补成一步。
         *
         * 与 [SolveWireSanitizer.sanitizeSteps] 同口径：那边留下什么，这里就认什么。
         */
        private fun hasUsableStep(element: kotlinx.serialization.json.JsonElement?): Boolean {
            if (element == null || element is JsonNull) return false
            if (element is JsonPrimitive) return element.content.isNotBlank()
            val array = element as? JsonArray ?: return false
            return array.any { item ->
                when (item) {
                    is JsonObject -> !SolveWireSanitizer.textOf(item["content"]).isNullOrBlank()
                    is JsonPrimitive -> item.content.isNotBlank()
                    else -> false
                }
            }
        }

        /**
         * 阶段 D 是否「给了知识点但一条都没带 id」。
         *
         * 只用于补一条可读告警：sanitizer 会把它们全丢掉，结果里知识点为空，
         * 用户看到的是「没有匹配到」，与实际原因（模型没按受控 ID 输出）不一致。
         */
        fun kpointsMissingId(raw: String): Boolean {
            val obj = JsonSalvage.parseObject(raw) ?: return false
            val array = obj["kpoints"] as? JsonArray ?: return false
            return array.isNotEmpty() &&
                array.none { item ->
                    item is JsonObject && !SolveWireSanitizer.textOf(item["id"]).isNullOrBlank()
                }
        }

        /**
         * 各阶段「至少出现一个」的顶层字段。
         *
         * 用「之一」而不是「全部」：模型经常省略值为 null 的字段（firstError 就是典型），
         * 要求全齐会把一批本来能用的输出判成失败。
         */
        private fun requiredKeysOf(stage: AiStage): List<String> =
            when (stage) {
                // 识题没有题干就等于没识别出来，下游会直接抛错，因此这里只认 stem
                AiStage.A -> listOf("stem")
                AiStage.B -> listOf("steps", "finalAnswer")
                AiStage.C -> listOf("studentSteps", "firstError", "summaryCard")
                AiStage.D -> listOf("kpoints", "primaryId")
            }

        /** 结构特征（长度、是否有花括号/围栏），用于诊断而不泄露正文。 */
        private fun shapeHintOf(text: String): String {
            val fenced = text.startsWith("```") || text.contains("```")
            val braces = text.count { it == '{' } to text.count { it == '}' }
            return "（长度 " + text.length + "，含围栏 " + fenced +
                "，花括号 " + braces.first + "/" + braces.second + "）"
        }

        /** 抽 JSON 对象：容忍围栏、前置说明、未转义换行与多余逗号（见 [JsonSalvage]）。 */
        fun decodeJsonObject(raw: String): JsonObject =
            JsonSalvage.parseObject(raw) ?: throw LlmException(
                LlmError.MalformedResponse(detail = "输出中没有可用的 JSON 对象" + shapeHintOf(raw.trim())),
            )

        /**
         * 解析阶段 A 的输出。
         *
         * 抽出来是为了把 [solve] 压在 detekt 的长度阈值内；
         * 顺带把「老缓存没有新字段」的兜底集中在一处：手写与进度缺省即「没看到手写」。
         */
        fun parseStageAQuestion(
            raw: String,
            warnings: MutableList<String>,
        ): AiQuestion {
            // 先修形状再取值：选项被写成数字数组、已知条件只有一个字符串时，
            // 直接 stringListOr 会把它们整条丢掉，而丢选项比丢字段更隐蔽。
            val obj = SolveWireSanitizer.sanitizeQuestion(decodeJsonObject(raw))
            val question =
                AiQuestion(
                    subject = obj.stringOr("subject", "math"),
                    grade = obj.stringOr("grade", "未知"),
                    stem = obj.stringOr("stem", ""),
                    known = obj.stringListOr("known"),
                    ask = obj.stringOr("ask", ""),
                    options = obj.stringListOr("options"),
                    hasFigure = obj.booleanOr("hasFigure", false),
                    // 题型：拿不到就按「未知」处理，不做数量校验，避免误伤老缓存
                    choiceType = obj.stringOrNull("choiceType"),
                    handwriting = obj.stringOrNull("handwriting"),
                    handwritingText = obj.stringOrNull("handwritingText"),
                    progress = obj.progressOr("progress"),
                )
            if (question.stem.isNullOrBlank()) {
                throw LlmException(LlmError.MalformedResponse(detail = "阶段 A 未识别出题干（stem 为空）"))
            }
            warnings.addAll(obj.stringListOr("warnings"))
            return question
        }

        /**
         * 挑出这次解题要参考的资料，并渲染成文本块。
         *
         * 三条规则：按科目优先（同一科优先，全不匹配时退回全部）、最多 3 条、
         * 每条由 [LearnedMaterial.promptBlock] 截到 400 字。合起来约 1200 字，
         * 大约是一次解答预算的 15%，再高就会把题干和知识点候选挤掉。
         */
        fun selectMaterials(
            materials: List<LearnedMaterial>,
            subject: String?,
        ): List<String> {
            if (materials.isEmpty()) return emptyList()
            val normalized = Subjects.normalize(subject)
            val matched = materials.filter { Subjects.normalize(it.subject) == normalized }
            val chosen = (matched.ifEmpty { materials }).take(MAX_REFERENCE_MATERIALS)
            return chosen.map { it.promptBlock() }
        }

        /** 单次解答最多参考几条资料。 */
        const val MAX_REFERENCE_MATERIALS: Int = 3

        /**
         * 拼出交给阶段 C 的「学生过程」。
         *
         * 两种来源并存时以打字内容为主、手写转写附在后面，并明确标注来源——
         * 阶段 C 的提示词要求对「识别结果」容错（不因为认错一个字就判他写错）。
         * 抽成独立函数是为了把 [solve] 控制在 detekt 的长度与复杂度阈值内。
         */
        fun composeStudentText(
            typed: String,
            handwritingText: String?,
        ): String {
            val typedTrimmed = typed.trim()
            val handwritingTrimmed = handwritingText.orEmpty().trim()
            return when {
                typedTrimmed.isNotEmpty() && handwritingTrimmed.isNotEmpty() ->
                    typedTrimmed + "\n\n（卷面上的手写内容，识别结果如下）\n" + handwritingTrimmed
                typedTrimmed.isNotEmpty() -> typedTrimmed
                else -> handwritingTrimmed
            }
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
            }
                // 合并完成后再统一修一次形状：各阶段自己的 sanitize 只管本阶段，
                // 组装时仍可能遇到「单值 vs 数组」「数字 vs 字符串」这类跨阶段拼装偏差。
                .let { SolveWireSanitizer.sanitizeContract(it) }
                .toString()

        private fun questionObject(question: AiQuestion): JsonObject =
            buildJsonObject {
                put("subject", question.subject ?: "math")
                put("grade", question.grade ?: "未知")
                put("stem", question.stem.orEmpty())
                put("known", buildJsonArray { question.known.forEach { add(JsonPrimitive(it)) } })
                put("ask", question.ask.orEmpty())
                put("options", buildJsonArray { question.options.forEach { add(JsonPrimitive(it)) } })
                put("hasFigure", question.hasFigure)
                // 题型跟着 question 一起进契约：阶段 C/D 与最终 DTO 都需要它，
                // 少写这一项会让「单选/多选校验」在合并后的结果里丢掉依据。
                put("choiceType", ChoiceAnswerGuard.normalize(question.choiceType))
                // 手写过程与逐问进度同样要带过去：它们决定错题状态（待校对）与进度条，
                // 不写进契约就等于阶段 A 白读了这张卷子。
                put("handwriting", question.handwriting ?: "none")
                put("handwritingText", question.handwritingText.orEmpty())
                put(
                    "progress",
                    buildJsonArray {
                        question.progress.forEach { item ->
                            add(
                                buildJsonObject {
                                    if (item.no != null) {
                                        put("no", item.no)
                                    } else {
                                        put("no", kotlinx.serialization.json.JsonNull)
                                    }
                                    put("state", item.state.wire)
                                },
                            )
                        }
                    },
                )
            }

        /** 解析阶段 B 的输出为 [AiStandardSolution]。 */
        fun decodeStandardSolution(raw: String): AiStandardSolution {
            // steps 写成字符串数组时，这里补成 {idx, content}，避免整份解答被判空
            val obj = SolveWireSanitizer.sanitizeSolution(decodeJsonObject(raw))
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

/**
 * 读「逐问进度」数组：`[{"no":1,"state":"done"}, ...]`。
 *
 * 宽松处理两条，都是为了让老响应也能用：
 * - `no` 缺失或不是数字 → 当作「整题不分小问」（null）；
 * - `state` 认不出 → 丢弃这一条，不猜成「没写」。
 */
internal fun JsonObject.progressOr(key: String): List<QuestionProgress> =
    (this[key] as? JsonArray)
        .orEmpty()
        .mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val state = QuestionProgressState.ofWire(item.stringOrNull("state")) ?: return@mapNotNull null
            val no = (item["no"] as? JsonPrimitive)?.content?.toIntOrNull()
            QuestionProgress(no = no, state = state)
        }

/** 生成「更严格」版本提示词（FR-1.10 重试）。 */
fun AssembledPrompt.asStrict(): AssembledPrompt =
    if (strict) {
        this
    } else {
        copy(
            userPrompt = userPrompt + "\n\n" + strictSuffixOf(stage),
            strict = true,
        )
    }

/**
 * 严格重试的追加要求。
 *
 * 与旧版「一段通用要求」的区别：现在**点名这一阶段允许的顶层字段**，
 * 并要求数组元素也必须是对象。小模型出错的位置很集中——不是「不肯输出 JSON」，
 * 而是「JSON 里塞了自造的键」或「该是对象的地方写了一句话」。
 * 泛泛说「必须满足 Schema」它看不见，把键名列出来才看得见。
 *
 * 这段文本不参与缓存键（缓存存的是阶段输出，不是提示词），
 * 但它确实改变了请求内容，改动时仍应同步评估 [PromptRepository] 的 promptVersion。
 */
private fun strictSuffixOf(stage: AiStage): String =
    "上一次输出无法被解析。本次务必严格满足：\n" +
        "1. 整个回复只包含一个 JSON 对象，第一个字符必须是 {，最后一个字符必须是 }，" +
        "前后不得有任何文字、Markdown 代码围栏或注释；\n" +
        "2. 顶层只能出现这些键：" + strictKeysOf(stage) + "，不要加别的键；\n" +
        "3. 该是对象的地方必须是对象，不能写成一整句话；该是数组的地方必须是数组，" +
        "数组里的每一项也必须是对象（例如 steps 的每一步写成 {idx, content, why}）；\n" +
        "4. 不要出现省略号、占位符或未闭合的数组；字符串不得为空；\n" +
        "5. 数字字段只写数字，不加引号、不加单位。"

/** 各阶段严格重试时点名的顶层键（与 assets 里各阶段 Schema 一致）。 */
private fun strictKeysOf(stage: AiStage): String =
    when (stage) {
        AiStage.A ->
            "stem、subject、grade、known、ask、options、hasFigure、choiceType、" +
                "handwriting、handwritingText、progress、warnings"
        AiStage.B -> "steps（数组，每步含 idx、content、why）、finalAnswer、method、check、confidence"
        AiStage.C -> "studentSteps（数组）、firstError（对象或 null）、summaryCard（对象）"
        AiStage.D -> "kpoints（数组，每条含 id、name）、primaryId、unmatched"
    }
