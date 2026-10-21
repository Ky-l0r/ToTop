package com.totop.app.domain.usecase

import com.totop.app.domain.model.AiSolveResultCodec
import com.totop.app.domain.model.AiSolveResultMapper
import com.totop.app.domain.model.MistakeRecord
import com.totop.app.domain.model.Question
import com.totop.app.domain.model.QuestionSourceType
import com.totop.app.domain.model.TeachingMode
import com.totop.app.domain.usecase.port.CandidateKpointRef
import com.totop.app.domain.usecase.port.DomainClock
import com.totop.app.domain.usecase.port.DomainSettings
import com.totop.app.domain.usecase.port.IdGenerator
import com.totop.app.domain.usecase.port.ImageCompressOutcome
import com.totop.app.domain.usecase.port.ImageCompressor
import com.totop.app.domain.usecase.port.ImageSource
import com.totop.app.domain.usecase.port.KpointDirectory
import com.totop.app.domain.usecase.port.ModelProfile
import com.totop.app.domain.usecase.port.OcrOutcome
import com.totop.app.domain.usecase.port.OcrReader
import com.totop.app.domain.usecase.port.OcrReading
import com.totop.app.domain.usecase.port.PromptVersionProvider
import com.totop.app.domain.usecase.port.RouteSelector
import com.totop.app.domain.usecase.port.SolveGateway
import com.totop.app.domain.usecase.port.SolveGatewayOutcome
import com.totop.app.domain.usecase.port.SolveGatewayRequest
import com.totop.app.domain.usecase.port.SolveImage
import com.totop.app.domain.usecase.port.SolveResultCache
import com.totop.app.domain.usecase.port.SolvedDraft
import com.totop.app.domain.usecase.port.SolvedRecordWriter
import com.totop.app.domain.usecase.port.TokenUsageCount

/**
 * 一次解题请求（UI -> UseCase 的唯一入口参数）。
 *
 * @param studentRawText 学生自己写的解题过程。**为空也允许**：此时跳过错因定位，
 *        题目与标准解答照常入库，但不会在错题本里出现（不是错题）。
 * @param mode 讲解模式，只影响下发裁剪，不影响落库内容与 token 用量
 */
data class SolveQuestionRequest(
    /** 图片来源；为 null 表示纯手输题干（[manualStem] 必须非空）。 */
    val image: ImageSource? = null,
    /** 手输题干（无图时必填）。 */
    val manualStem: String = "",
    val manualSubject: String = "math",
    val manualGrade: String = "未知",
    /** 图片/题干来源，用于 question.sourceType。 */
    val sourceType: QuestionSourceType = QuestionSourceType.CAMERA,
    /** 学生手写或键盘输入的过程；阶段 C 使用，阶段 B 绝不会看到。 */
    val studentRawText: String = "",
    /** 学生补充说明，例如「第(2)问不要求」。 */
    val supplement: String = "",
    /** 讲解模式。 */
    val mode: TeachingMode = TeachingMode.DEFAULT,
    /** 是否在成功后写入完整结果缓存（默认 true，命中后 0 token）。 */
    val useCache: Boolean = true,
    /** 幂等 ID；为空时自动生成。同一个 requestId 重复调用会被网关忽略。 */
    val requestId: String = "",
    /** 输出上限；为 null 时按设置的「简洁 / 详细」自动选择。 */
    val maxTokens: Int? = null,
)

/**
 * 解题主用例（开发方案 3.2 完整链路 [1]-[12]）。
 *
 * 编排顺序严格照抄方案，任何一步失败都**直接返回失败且不写库**：
 *
 * ```
 * 压缩 -> 缓存命中判断 -> OCR -> 路由 -> (阶段 A/B/C/D 由网关编排) -> 落库 -> 按模式裁剪下发
 * ```
 *
 * 关键设计：
 * 1. **缓存判定前置**：与方案一致（第 [3] 步在 OCR 之前），命中则 0 token、不调 OCR、不发网络请求；
 *    缓存键含 promptVersion、model 与 mode，避免换提示词/换模型/切模式后串用旧结果。
 * 2. **失败不入库**：只有网关返回 [SolveGatewayOutcome.Success] 且 DTO 通过严格校验后才写库；
 *    任何 DTO 缺字段、解析失败、网络错误都返回 [SolveOutcome.Failed]，本用例不写一行数据。
 * 3. **落库内容与模式无关**：标准答案与完整步骤始终入库（用户点「我已想通，看全解」时无需再花钱），
 *    只有**下发**被 [TeachingModeTrimmer] 裁剪。
 * 4. **本地模式**：未配置 Key 时返回 [SolveOutcome.LlmNotConfigured]，一次引导到设置页，
 *    不静默失败、不弹多轮对话框（FR-6.5）。
 */
class SolveQuestionUseCase(
    private val compressor: ImageCompressor,
    private val ocr: OcrReader,
    private val router: RouteSelector,
    private val gateway: SolveGateway,
    private val cache: SolveResultCache,
    private val cacheKeyProvider: PromptVersionProvider,
    private val kpoints: KpointDirectory,
    private val writer: SolvedRecordWriter,
    private val settings: DomainSettings = DomainSettings.DEFAULTS,
    private val modelProfile: ModelProfile = ModelProfile.PLACEHOLDER,
    private val clock: DomainClock = DomainClock.SYSTEM,
    private val ids: IdGenerator = IdGenerator.UUID_GENERATOR,
) {
    /** 最近一次执行的诊断信息（路由原因、OCR 置信度），供 UI 展示「本次为什么更贵」。 */
    var lastDiagnostics: SolveDiagnostics? = null
        private set

    /**
     * 执行一次完整解题。
     *
     * 返回的 [SolveOutcome] 已含裁剪后的下发视图，UI 不必再自己判断模式。
     */
    suspend fun solve(request: SolveQuestionRequest): SolveOutcome {
        lastDiagnostics = null
        val requestId = request.requestId.ifBlank { ids.newId() }
        val nowUtc = clock.nowUtcMillis()

        // ---------- 0. 本地模式：未配置 Key 直接短路 ----------
        if (!settings.llmConfigured()) return SolveOutcome.LlmNotConfigured()

        // ---------- 1. 压缩 ----------
        val compressed: SolveImage? =
            when (val source = request.image) {
                null -> null
                is ImageSource.Precompressed -> source.image
                else ->
                    when (val outcome = compressor.compress(source)) {
                        is ImageCompressOutcome.Success -> outcome.image
                        is ImageCompressOutcome.Failure -> return SolveOutcome.ImageRejected(
                            userMessage = outcome.userMessage,
                            detail = outcome.detail,
                        )
                    }
            }
        val manualStem = request.manualStem.trim()
        if (compressed == null && manualStem.isEmpty()) {
            return SolveOutcome.ImageRejected(userMessage = "没有可识别的题目内容：请拍照/导入图片，或直接输入题干。")
        }

        // ---------- 2. 缓存命中判断（方案第 [3] 步，在 OCR 之前） ----------
        val promptVersion = runCatching { cacheKeyProvider.currentVersion() }.getOrDefault(UNKNOWN_PROMPT_VERSION)
        val sha = compressed?.sha256 ?: Sha256.hex(manualStem + CACHE_TEXT_SEPARATOR + request.supplement)
        val cacheKey =
            SolveCacheKey(
                sha256 = sha,
                promptVersion = promptVersion,
                model = modelProfile.profileId,
                mode = request.mode,
            )
        if (request.useCache) {
            val cachedJson = runCatching { cache.get(cacheKey.storageKey) }.getOrNull()
            if (cachedJson != null) {
                val hit =
                    fromContractJson(
                        resultJson = cachedJson,
                        request = request,
                        requestId = requestId,
                        nowUtc = nowUtc,
                        manualStem = manualStem,
                        compressed = compressed,
                        cacheHit = true,
                    )
                if (hit != null) return hit
                // 缓存内容坏了：当作未命中继续走真实链路，不阻塞用户
            }
        }

        // ---------- 3. OCR ----------
        val reading: OcrReading =
            if (compressed != null) {
                when (val outcome = ocr.read(compressed)) {
                    is OcrOutcome.Success -> outcome.reading
                    is OcrOutcome.Failure -> OcrReading.EMPTY // 识别失败但还有图：交给视觉档兜底
                }
            } else {
                OcrReading(text = manualStem, avgConfidence = 1.0, hasFigure = false)
            }
        val stem = reading.text.ifBlank { manualStem }
        if (stem.isBlank() && compressed == null) {
            return SolveOutcome.ImageRejected(userMessage = "没有可识别的题目内容，请重拍或手动输入题干。")
        }

        // ---------- 4. 分级路由（方案 3.3） ----------
        val route = router.select(reading, visionAvailable = modelProfile.visionAvailable)
        if (route.attachImage && compressed == null) {
            return SolveOutcome.ImageRejected(userMessage = "该题需要图片才能识别，但图片未能压缩，请重试。")
        }

        // ---------- 5. 候选知识点（阶段 D 必须给受控候选清单） ----------
        val candidates =
            runCatching {
                kpoints.candidatesFor(stem, manualSubjectOrReading(request, reading))
            }.getOrDefault(emptyList()).map {
                CandidateKpointRef(id = it.id, name = it.name, chapter = it.chapter)
            }

        // ---------- 6. 四阶段调用（网关内部负责重试、trace、结构校验） ----------
        val maxTokens = request.maxTokens ?: if (settings.detailedOutput()) MAX_TOKENS_DETAILED else MAX_TOKENS_CONCISE
        val gatewayOutcome =
            gateway.solve(
                SolveGatewayRequest(
                    requestId = requestId,
                    ocr = reading,
                    image = if (route.attachImage) compressed else null,
                    studentRawText = request.studentRawText,
                    supplement = request.supplement,
                    candidateKpoints = candidates,
                    route = route,
                    maxTokens = maxTokens,
                ),
            )
        lastDiagnostics =
            SolveDiagnostics(
                cacheKey = cacheKey,
                routeLabel = route.target.label,
                routeReason = route.reason,
                ocrConfidence = reading.normalizedConfidence,
                ocrHasFigure = reading.hasFigure,
            )

        val success =
            when (gatewayOutcome) {
                is SolveGatewayOutcome.Failure -> return SolveOutcome.Failed(
                    userMessage = gatewayOutcome.userMessage,
                    retryable = gatewayOutcome.retryable,
                    warnings = gatewayOutcome.warnings,
                )

                is SolveGatewayOutcome.Success -> gatewayOutcome
            }

        // ---------- 7. 严格结构校验：不完整绝不入库 ----------
        val missing = success.result.missingStructure()
        if (missing.isNotEmpty()) {
            return SolveOutcome.Failed(
                userMessage = "模型返回的内容不完整（缺少：" + missing.joinToString("、") + "），已终止入库以免污染统计。请重试。",
                retryable = true,
                warnings = success.warnings,
            )
        }

        return persistAndTrim(
            resultJson = AiSolveResultCodec.encode(success.result),
            request = request,
            requestId = requestId,
            nowUtc = nowUtc,
            manualStem = manualStem,
            compressed = compressed,
            reading = reading,
            usage = success.usage,
            warnings = success.warnings,
            cacheHit = false,
            writeCache = request.useCache,
            cacheKey = cacheKey,
        )
    }

    /**
     * 由已校验的 6.4 契约 JSON 生成落库草案、原子写入、并返回裁剪后的视图。
     *
     * 缓存命中与首次调用共用这一段，保证两条路径的裁剪与落库行为**完全一致**
     * （否则会出现「缓存命中时 GUIDED 漏答案 / 首次调用时多写一条错题」这类难查的不一致）。
     */
    private suspend fun fromContractJson(
        resultJson: String,
        request: SolveQuestionRequest,
        requestId: String,
        nowUtc: Long,
        manualStem: String,
        compressed: SolveImage?,
        cacheHit: Boolean,
    ): SolveOutcome? {
        val dto = AiSolveResultCodec.decode(resultJson).getOrNull() ?: return null
        if (dto.missingStructure().isNotEmpty()) return null
        return persistAndTrim(
            resultJson = resultJson,
            request = request,
            requestId = requestId,
            nowUtc = nowUtc,
            manualStem = manualStem,
            compressed = compressed,
            reading = OcrReading(text = dto.question?.stem ?: manualStem, avgConfidence = 1.0),
            usage = TokenUsageCount.EMPTY,
            warnings = emptyList(),
            cacheHit = cacheHit,
            writeCache = false,
            cacheKey = null,
        )
    }

    private suspend fun persistAndTrim(
        resultJson: String,
        request: SolveQuestionRequest,
        requestId: String,
        nowUtc: Long,
        manualStem: String,
        compressed: SolveImage?,
        reading: OcrReading,
        usage: TokenUsageCount,
        warnings: List<String>,
        cacheHit: Boolean,
        writeCache: Boolean,
        cacheKey: SolveCacheKey?,
    ): SolveOutcome {
        val dto =
            AiSolveResultCodec.decode(resultJson).getOrElse { failure ->
                return SolveOutcome.Failed(
                    userMessage = "模型返回的内容无法解析，已终止入库。请重试。",
                    retryable = true,
                    warnings = warnings + "decode_failed：" + (failure.message ?: ""),
                )
            }

        // 题目：以阶段 A 的结构化结果为准，缺失字段用手输内容兜底
        val question =
            Question(
                id = requestId,
                subject = dto.question?.subject?.takeIf { it.isNotBlank() } ?: request.manualSubject,
                grade = dto.question?.grade?.takeIf { it.isNotBlank() } ?: request.manualGrade,
                stemText = dto.question?.stem?.takeIf { it.isNotBlank() } ?: manualStem.ifBlank { reading.text },
                known = dto.question?.known.orEmpty(),
                ask = dto.question?.ask.orEmpty(),
                options = dto.question?.options.orEmpty(),
                hasFigure = dto.question?.hasFigure ?: reading.hasFigure,
                imagePath = compressed?.localPath,
                imageSha256 = compressed?.sha256,
                sourceType = if (compressed != null) request.sourceType else QuestionSourceType.MANUAL,
                createdAtUtc = nowUtc,
            )

        val mapping = AiSolveResultMapper.map(dto, question)
        val allWarnings = warnings + mapping.warnings
        val content = mapping.content

        // 只有「确实存在错误」才写入错题本
        val mistake: MistakeRecord? =
            content.firstError?.let { firstError ->
                MistakeRecord(
                    id = requestId + MISTAKE_ID_SUFFIX,
                    questionId = question.id,
                    studentSteps = content.studentSteps,
                    firstErrorStepIdx = firstError.stepIdx,
                    errorType = firstError.type,
                    errorExplain = firstError.explain,
                    correctSolution = content.standardSolution,
                    finalAnswer = content.standardSolution.finalAnswer,
                    mode = request.mode,
                    createdAtUtc = nowUtc,
                )
            }

        val persisted =
            try {
                writer.persistAtomically(
                    SolvedDraft(
                        question = question,
                        mistake = mistake,
                        assignments = content.kpointAssignments,
                    ),
                )
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                // 取消必须原样向上抛，否则协程作用域无法正确终止
                throw cancellation
            } catch (error: Throwable) {
                return SolveOutcome.Failed(
                    userMessage = "结果已生成但保存失败（" + (error.message ?: "数据库错误") + "），未写入任何数据，请重试。",
                    retryable = true,
                    warnings = allWarnings,
                )
            }

        if (writeCache && cacheKey != null) {
            runCatching { cache.put(cacheKey.storageKey, resultJson) }
        }

        val view =
            TeachingModeTrimmer.trim(
                mode = request.mode,
                question = persisted.question,
                solution = content.standardSolution,
                firstError = content.firstError,
                kpoints = content.kpointAssignments,
                summaryCard = content.summaryCard,
                aiGenerated = content.aiGenerated,
            )
        return SolveOutcome.Success(
            view = view,
            question = persisted.question,
            record = persisted.mistake,
            cacheHit = cacheHit,
            usage = usage,
            warnings = allWarnings,
        )
    }

    private fun manualSubjectOrReading(
        request: SolveQuestionRequest,
        reading: OcrReading,
    ): String =
        request.manualSubject.ifBlank {
            if (reading.text.contains(
                    "函数",
                ) || reading.text.contains("方程")
            ) {
                "math"
            } else {
                "math"
            }
        }

    companion object {
        /** 简洁档输出上限。 */
        const val MAX_TOKENS_CONCISE: Int = 2048

        /** 详细档输出上限。 */
        const val MAX_TOKENS_DETAILED: Int = 4096

        /** 提示词版本读取失败时的兜底标记（会让缓存键带上 unknown，宁可不用缓存也不误用旧缓存）。 */
        const val UNKNOWN_PROMPT_VERSION: String = "unknown"

        /** 纯文本缓存键原文分隔符，避免「题干尾部 + 补充说明」拼出相同字符串。 */
        const val CACHE_TEXT_SEPARATOR: String = "\u0000"

        /** 错题记录 ID 后缀（与题目 ID 同源，便于按 requestId 反查）。 */
        const val MISTAKE_ID_SUFFIX: String = "-mistake"
    }
}
