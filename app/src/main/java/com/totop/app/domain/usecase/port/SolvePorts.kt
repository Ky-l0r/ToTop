package com.totop.app.domain.usecase.port

import com.totop.app.domain.model.AiSolveResult
import com.totop.app.domain.model.AiStage

/*
 * 领域层端口：解题链路需要的外部能力。
 *
 * 设计原则（docs/架构.md 第二节「依赖方向自上而下，禁止反向依赖」）：
 * - 本文件只声明接口，不含任何实现，因此 `domain` 仍可在纯 JVM 单测中运行；
 * - 端口只使用领域模型与 Kotlin 标准类型，**不出现** Retrofit / OkHttp / Android UI / Room 类型；
 * - 具体实现由 `data` 与 `ml` 层提供并在 DI 层注入，UseCase 不感知实现细节。
 *
 * 与 data 层已有类型的对应关系（实现方按此适配，一行转发即可）：
 * | 端口 | data / ml 层实现来源 |
 * | --- | --- |
 * | [ImageCompressor] | ml 层 ImagePreprocess（长边 1280 / JPEG 0.78 / EXIF 纠正 / sha256） |
 * | [OcrReader] | ml 层 OcrEngine（ML Kit bundled，离线） |
 * | [RouteSelector] | ml 层 ModelRouter（印刷体 + 置信度 >= 0.80 + 无图 -> 文本档） |
 * | [SolveGateway] | data/remote 的 AiGateway（四阶段编排 + 校验重试 + ai_trace） |
 * | [SolveResultCache] | data/remote 的 LlmCache（sha256 + promptVersion + model） |
 */

/**
 * 压缩并编码后的图片（开发方案 3.2 第 [2] 步、3.4 成本控制）。
 *
 * 刻意不使用 `android.graphics.Bitmap`：领域层只关心「已经压好的字节 + 摘要」，
 * 这样压缩环节可以在 JVM 单测里用假实现替换。
 *
 * @param base64 JPEG 的 base64（视觉档随请求发送）
 * @param sha256 图片内容摘要，缓存与去重锚点（question.imageSha256 唯一）
 * @param width 压缩后宽度（像素）
 * @param height 压缩后高度（像素）
 * @param localPath 本机已落盘的绝对路径；未落盘时为 null
 */
data class SolveImage(
    val base64: String,
    val sha256: String,
    val width: Int = 0,
    val height: Int = 0,
    val localPath: String? = null,
) {
    init {
        require(sha256.isNotBlank()) { "图片 sha256 不得为空" }
        require(base64.isNotBlank()) { "图片 base64 不得为空，压缩失败应返回 Failure 而不是空图" }
    }

    /** 估算的传输体积（base64 约为原始字节的 4/3）。 */
    val approxBytes: Long get() = base64.length.toLong() / 4L * 3L
}

/** 图片来源：拍照临时文件、相册 Uri、已有字节，或已经压好的图片。 */
sealed interface ImageSource {
    /** CameraX ImageCapture 输出的临时文件路径。 */
    data class FilePath(val path: String) : ImageSource

    /** 相册读取到的字节（ActivityResultContracts.PickVisualMedia）。 */
    class Bytes(val bytes: ByteArray, val mimeType: String = "image/jpeg") : ImageSource

    /** 相册 content:// Uri（实现方用 ContentResolver 读取）。 */
    data class ContentUri(val uriString: String) : ImageSource

    /** 调用方已经压缩过，跳过压缩步骤（重试时避免重复压缩）。 */
    data class Precompressed(val image: SolveImage) : ImageSource
}

/** 压缩结果。 */
sealed interface ImageCompressOutcome {
    /** 压缩成功。 */
    data class Success(val image: SolveImage) : ImageCompressOutcome

    /**
     * 压缩失败。
     *
     * 失败必须给出可读中文原因（开发方案 6.1「禁止只报请求失败」的同一原则），
     * 例如「图片文件不可读」「解码失败，可能是 HEIC 格式」「内存不足」。
     */
    data class Failure(val userMessage: String, val detail: String? = null) : ImageCompressOutcome
}

/** 图片压缩器端口（实现：ml 层 ImagePreprocess）。 */
interface ImageCompressor {
    /**
     * 压缩并编码。
     *
     * 约定：长边 <= 1280、JPEG 质量 0.78、EXIF 旋转纠正、同一 sha256 不重复落盘。
     */
    suspend fun compress(source: ImageSource): ImageCompressOutcome
}

/**
 * 离线 OCR 读数（开发方案 3.2 第 [4] 步：text + avgConfidence + hasFigure）。
 *
 * @param text 整页文本，行间以换行分隔
 * @param avgConfidence 平均置信度 0..1；引擎未给出时为 0.0
 * @param hasFigure 是否疑似含图形
 * @param isHandwritten 是否手写体（ML Kit 不直接给，由 ml 层启发式判定）
 */
data class OcrReading(
    val text: String = "",
    val avgConfidence: Double = 0.0,
    val hasFigure: Boolean = false,
    val isHandwritten: Boolean = false,
) {
    init {
        require(avgConfidence.isFinite()) { "OCR 置信度必须为有限值" }
    }

    /** 置信度裁剪到 0..1，兼容个别机型返回百分制的情况。 */
    val normalizedConfidence: Double get() =
        (if (avgConfidence > 10.0) avgConfidence / 100.0 else avgConfidence).coerceIn(
            0.0,
            1.0,
        )

    /** 文本是否可用（少于 2 个字符视为识别失败）。 */
    val hasUsableText: Boolean get() = text.trim().length >= 2

    companion object {
        /** 空读数（OCR 失败但图片仍可交给视觉模型时的兜底）。 */
        val EMPTY: OcrReading = OcrReading()
    }
}

/** OCR 结果。 */
sealed interface OcrOutcome {
    data class Success(val reading: OcrReading) : OcrOutcome

    /** 识别失败（图片不可读 / 引擎不可用 / 超时）。 */
    data class Failure(val userMessage: String, val detail: String? = null) : OcrOutcome
}

/** OCR 端口（实现：ml 层 OcrEngine，ML Kit bundled 离线识别）。 */
interface OcrReader {
    /** 识别已落盘的图片；[localPath] 为空时由实现决定是否使用 [image] 的字节。 */
    suspend fun read(image: SolveImage): OcrOutcome
}

/** 路由档位（开发方案 3.3 分级调用）。 */
enum class RouteTarget(val wire: String, val label: String) {
    TEXT("text", "文本模型（便宜档）"),
    VISION("vision", "视觉模型（带图）"),
}

/**
 * 一次路由决策。
 *
 * @param model 实际模型名（来自 ModelPool）
 * @param attachImage 是否把 base64 图片放进请求
 * @param reason 中文原因，直接展示给用户（「本次为什么更贵」必须说清）
 */
data class RouteChoice(
    val target: RouteTarget,
    val model: String,
    val attachImage: Boolean,
    val reason: String,
) {
    init {
        require(model.isNotBlank()) { "路由结果必须带模型名" }
    }

    val isVision: Boolean get() = target == RouteTarget.VISION
}

/** 路由端口（实现：ml 层 ModelRouter）。 */
interface RouteSelector {
    /**
     * 印刷体 且 置信度 >= 0.80 且 无图 -> 文本档；否则 -> 视觉档。
     *
     * @param visionAvailable 用户所选厂商是否支持视觉模型（DeepSeek 等不支持时退化为文本档）
     */
    fun select(
        reading: OcrReading,
        visionAvailable: Boolean = true,
    ): RouteChoice
}

/** 阶段 D 的候选知识点（开发方案 6.3：id/name/chapter）。 */
data class CandidateKpointRef(
    val id: String,
    val name: String,
    val chapter: String = "",
)

/** token 用量（不依赖 data 层的 TokenUsage，避免反向依赖）。 */
data class TokenUsageCount(
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
) {
    init {
        require(inputTokens >= 0 && outputTokens >= 0) { "token 用量不得为负" }
    }

    val totalTokens: Int get() = inputTokens + outputTokens

    fun plus(other: TokenUsageCount): TokenUsageCount =
        TokenUsageCount(inputTokens + other.inputTokens, outputTokens + other.outputTokens)

    companion object {
        val EMPTY: TokenUsageCount = TokenUsageCount()
    }
}

/**
 * 一次四阶段调用的入参。
 *
 * 阶段隔离不在这个结构里做（那由网关内部的 StageInput 类型保证）：
 * 这里把「网关需要知道的一切」一次性传下去，避免 UseCase 反复拼装。
 *
 * @param studentRawText 学生手写/输入的解题过程；仅阶段 C 使用
 * @param candidateKpoints 阶段 D 的候选清单，由 [KpointDirectory] 按题干检索
 */
data class SolveGatewayRequest(
    val requestId: String,
    val ocr: OcrReading,
    /** 视觉档需要；文本档可为 null。 */
    val image: SolveImage?,
    val studentRawText: String,
    val supplement: String,
    val candidateKpoints: List<CandidateKpointRef>,
    val route: RouteChoice,
    val maxTokens: Int,
) {
    init {
        require(requestId.isNotBlank()) { "requestId 是幂等键，不得为空" }
        require(maxTokens > 0) { "maxTokens 必须为正数" }
        require(!route.attachImage || image != null) { "视觉档必须带图片" }
    }
}

/** 网关调用结果。 */
sealed interface SolveGatewayOutcome {
    /**
     * 成功：已通过 DTO 严格校验。
     *
     * @param result 6.4 契约 DTO（调用方再用 AiSolveResultMapper 转领域模型）
     * @param warnings 非致命告警（错因兜底、非受控知识点 ID 被丢弃等），必须落日志
     */
    data class Success(
        val result: AiSolveResult,
        val usage: TokenUsageCount,
        val warnings: List<String> = emptyList(),
    ) : SolveGatewayOutcome

    /**
     * 失败：**调用方绝不能据此写库**。
     *
     * @param stage 出错的阶段
     * @param userMessage 面向用户的中文原因（例如「API Key 无效」）
     */
    data class Failure(
        val stage: AiStage,
        val userMessage: String,
        val retryable: Boolean = false,
        val warnings: List<String> = emptyList(),
    ) : SolveGatewayOutcome
}

/**
 * 四阶段编排端口（实现：data/remote 的 AiGateway）。
 *
 * 契约：实现内部完成「解析失败以更严格提示词重试 1 次」，仍失败返回 [SolveGatewayOutcome.Failure]，
 * 并且每次调用（含失败与重试）都以同一条 requestId 写入 ai_trace。
 */
interface SolveGateway {
    /** 执行一次完整四阶段调用（非流式收敛结果；流式展示由 UI 层另走 SSE 通道）。 */
    suspend fun solve(request: SolveGatewayRequest): SolveGatewayOutcome
}

/** 提示词版本提供者（缓存键组成之一：sha256 + promptVersion + model）。 */
interface PromptVersionProvider {
    /** 当前提示词版本，例如 1.0.0。 */
    suspend fun currentVersion(): String
}

/** 完整结果缓存端口（第二层缓存由 data 层 Room cache_entry 实现）。 */
interface SolveResultCache {
    /**
     * 查询缓存。
     *
     * @param storageKey 由 [com.totop.app.domain.usecase.SolveCacheKey] 生成
     * @return 命中的 6.4 契约 JSON；未命中返回 null
     */
    suspend fun get(storageKey: String): String?

    /**
     * 写入缓存。
     *
     * 只允许写入**已通过严格校验**的结果：残缺结果不进缓存，避免脏数据反复命中。
     */
    suspend fun put(
        storageKey: String,
        resultJson: String,
    )
}

/** 当前配置的模型档位（缓存键的 model 段与路由可用性判定都依赖它）。 */
data class ModelProfile(
    val textModel: String,
    val visionModel: String,
) {
    init {
        require(textModel.isNotBlank()) { "文本档模型名不得为空" }
        require(visionModel.isNotBlank()) { "视觉档模型名不得为空" }
    }

    /** 该厂商是否具备视觉能力（DeepSeek 等纯文本厂商为 false，此时路由退化为文本档）。 */
    val visionAvailable: Boolean
        get() = visionModel.isNotBlank() && visionModel != DISABLED_MARKER

    /**
     * 缓存键中的 model 段。
     *
     * 用「配置组合」而不是「本次实际路由到的单个模型」：
     * 缓存判定发生在 OCR 与路由之前（开发方案 3.2 第 [3] 步在 [4][5] 之前），
     * 此时还不知道会走哪一档；只要配置不变，同一 sha256 的结果就应稳定复用。
     * 配置一改（换模型）profileId 立刻变化，旧缓存自然不再命中。
     */
    val profileId: String
        get() = textModel + "/" + visionModel

    companion object {
        /** 纯文本厂商的视觉档占位标记。 */
        const val DISABLED_MARKER: String = "disabled"

        /** 单测与预览用的占位档位。 */
        val PLACEHOLDER: ModelProfile = ModelProfile("text-model", "vision-model")

        /** 纯文本厂商。 */
        fun textOnly(model: String): ModelProfile = ModelProfile(model, DISABLED_MARKER)
    }
}
