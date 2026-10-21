package com.totop.app.data.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** token 用量（OpenAI 兼容 usage 字段）。 */
data class TokenUsage(
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
) {
    val totalTokens: Int get() = inputTokens + outputTokens

    companion object {
        val EMPTY: TokenUsage = TokenUsage()
    }
}

/**
 * 流式增量块（开发方案 3.2 第 [11] 步：SSE 流式回传，分步渲染）。
 *
 * 调用方只需消费 [Delta] 做增量渲染，[Finished] 做收尾（写 usage、关闭 Loading）。
 */
sealed class LlmChunk {
    /** 增量正文。 */
    data class Delta(val text: String) : LlmChunk()

    /**
     * 思考链增量（DeepSeek 的 reasoning_content）。
     *
     * 与 [Delta] 严格区分：思考内容不是答案，不能拼进正文交给 JSON 解析，
     * 但必须让 UI 知道「模型在思考」而不是「模型没输出」。
     * 历史版本完全丢弃该字段，导致思考模式下 content 为空时误报「输出为空」。
     */
    data class Reasoning(val text: String) : LlmChunk()

    /**
     * 结束事件：携带 usage 与 finish_reason。
     *
     * @param finishReason "stop" 正常结束；"length" 表示被 max_tokens 截断，
     *        UI 必须据此提示「话说一半被截断」，而不是笼统报解析失败
     */
    data class Finished(
        val usage: TokenUsage = TokenUsage.EMPTY,
        val finishReason: String = "",
    ) : LlmChunk()

    /** 厂商在流中回传的错误（例如 key 在流中失效）。 */
    data class Failed(val error: LlmError) : LlmChunk()
}

/** 一条 chat 消息：角色 + 多个内容片段。 */
data class ChatMessage(
    val role: String,
    val parts: List<ChatPart>,
) {
    constructor(role: String, text: String) : this(role, listOf(ChatPart.Text(text)))

    fun toJson(): JsonObject =
        buildJsonObject {
            put("role", role)
            put(
                "content",
                buildJsonArray {
                    parts.forEach { part ->
                        when (part) {
                            is ChatPart.Text ->
                                add(
                                    buildJsonObject {
                                        put("type", "text")
                                        put("text", part.text)
                                    },
                                )
                            is ChatPart.Image ->
                                add(
                                    buildJsonObject {
                                        put("type", "image_url")
                                        put(
                                            "image_url",
                                            buildJsonObject {
                                                put("url", "data:" + part.mimeType + ";base64," + part.base64)
                                            },
                                        )
                                    },
                                )
                        }
                    }
                },
            )
        }

    companion object {
        const val ROLE_SYSTEM: String = "system"
        const val ROLE_USER: String = "user"
        const val ROLE_ASSISTANT: String = "assistant"

        fun system(text: String): ChatMessage = ChatMessage(ROLE_SYSTEM, text)

        fun user(text: String): ChatMessage = ChatMessage(ROLE_USER, text)

        fun userWithImage(
            text: String,
            base64Jpeg: String,
            mimeType: String = "image/jpeg",
        ): ChatMessage = ChatMessage(ROLE_USER, listOf(ChatPart.Text(text), ChatPart.Image(base64Jpeg, mimeType)))

        /**
         * 一条消息携带多张图片（多页题目）。
         *
         * 顺序即页序：图片按传入顺序排列，文本在前、图片在后。
         * 这是 OpenAI 兼容协议的标准多图形态，DeepSeek 官方限制为
         * 单请求最多 600 张图 / 64MiB，多页题目远低于该上限。
         *
         * @param pageHints 每张图对应的一句说明（如「第 1/3 页」），
         *        长度不足时不影响发送，仅少一句上下文
         */
        fun userWithImages(
            text: String,
            base64Jpegs: List<String>,
            pageHints: List<String> = emptyList(),
            mimeType: String = "image/jpeg",
        ): ChatMessage {
            require(base64Jpegs.isNotEmpty()) { "多图消息至少需要一张图片" }
            val parts = mutableListOf<ChatPart>()
            parts.add(ChatPart.Text(text))
            base64Jpegs.forEachIndexed { index, base64 ->
                val hint = pageHints.getOrNull(index)
                if (!hint.isNullOrBlank()) parts.add(ChatPart.Text(hint))
                parts.add(ChatPart.Image(base64, mimeType))
            }
            return ChatMessage(ROLE_USER, parts)
        }
    }
}

/** 消息内容片段：文本或图片（data URL）。 */
sealed class ChatPart {
    data class Text(val text: String) : ChatPart()

    data class Image(val base64: String, val mimeType: String = "image/jpeg") : ChatPart()
}

/** 请求参数（成本控制：temperature、max_tokens 上限，开发方案 3.4）。 */
data class ChatRequestConfig(
    val model: String,
    val messages: List<ChatMessage>,
    /** 输出上限；设置页「简洁/详细」两档映射到不同上限。 */
    val maxTokens: Int = 2048,
    val temperature: Double = 0.2,
    /** 是否要求 JSON 对象输出；部分本地模型不支持该字段，失败时会自动去掉重试。 */
    val jsonObject: Boolean = true,
    /** 是否请求 usage 统计（stream_options.include_usage）。 */
    val includeUsage: Boolean = true,
    val stream: Boolean = true,
    /**
     * 思考模式控制（DeepSeek 的 thinking 字段，非 OpenAI 标准）。
     *
     * 取值：
     * - [THINKING_UNSPECIFIED]：不发送该字段，沿用厂商默认；
     * - [THINKING_DISABLED]：显式关闭（探测与 JSON 输出场景使用）；
     * - [THINKING_ENABLED]：显式开启。
     *
     * 为什么重要：思考模式默认开启，且思考链与正文共享 max_tokens。
     * 预算不足时正文会完全为空，表现为「模型没输出」，极易误判为模型故障。
     */
    val thinkingMode: String = THINKING_UNSPECIFIED,
) {
    init {
        require(model.isNotBlank()) { "模型名不得为空" }
        require(messages.isNotEmpty()) { "消息列表不得为空" }
        require(maxTokens in 1..32768) { "maxTokens 必须在 1..32768，实际为：" + maxTokens }
    }

    /** 构造 OpenAI 兼容请求体。 */
    fun toJson(): JsonObject =
        buildJsonObject {
            put("model", model)
            put("messages", buildJsonArray { messages.forEach { add(it.toJson()) } })
            put("stream", stream)
            put("temperature", temperature)
            put("max_tokens", maxTokens)
            if (jsonObject) {
                put("response_format", buildJsonObject { put("type", "json_object") })
            }
            if (stream && includeUsage) {
                put("stream_options", buildJsonObject { put("include_usage", true) })
            }
            // 仅在显式指定时发送，避免给不认识该字段的厂商添麻烦
            if (thinkingMode == THINKING_ENABLED || thinkingMode == THINKING_DISABLED) {
                put(
                    "thinking",
                    buildJsonObject { put("type", if (thinkingMode == THINKING_ENABLED) "enabled" else "disabled") },
                )
            }
        }

    /**
     * 连通性校验用的最小请求（max_tokens=8，开发方案 6.1）。
     *
     * 采用非流式：检验的是「Key / Base URL / 模型名是否可用」，
     * 非流式响应体更小、兼容性更好，也更省 token。
     */
    fun asProbe(): ChatRequestConfig =
        copy(
            maxTokens = PROBE_MAX_TOKENS,
            temperature = 0.0,
            jsonObject = false,
            includeUsage = true,
            // 改走流式：部分厂商（含思考模式模型）拒绝非流式请求，
            // 用非流式探测会把「厂商限制」误报成「连接失败」。
            stream = true,
            // 探测只关心通不通，不需要推理，显式关闭思考模式省钱且更快
            thinkingMode = THINKING_DISABLED,
        )

    companion object {
        /**
         * 保存前连通性校验的输出长度。
         *
         * 原值 8 在思考模式下必然失败：思考链会把预算吃光，正文为空。
         * 提到 64 后，即使厂商忽略 thinking 字段，也能留出正文空间。
         */
        const val PROBE_MAX_TOKENS: Int = 64

        /**
         * 「简洁」档输出上限。
         *
         * 原值 1024 是阶段 B「输出为空」的直接原因之一：DeepSeek 思考模式默认开启，
         * 思考链与正文共享该预算，1024 连一题的分步解答都不够。
         */
        const val MAX_TOKENS_CONCISE: Int = 2048

        /** 「详细」档输出上限（几何解答步骤多，需要更大余量）。 */
        const val MAX_TOKENS_DETAILED: Int = 8192

        /** 不指定思考模式，沿用厂商默认。 */
        const val THINKING_UNSPECIFIED: String = ""

        /** 显式开启思考模式。 */
        const val THINKING_ENABLED: String = "enabled"

        /** 显式关闭思考模式。 */
        const val THINKING_DISABLED: String = "disabled"
    }
}

/** 单条 SSE 帧的解析结果（由 [SseLineParser] 产出，与 OpenAI 语义解耦，便于单测）。 */
sealed class ParsedFrame {
    /** 普通数据帧，携带原始 data 文本。 */
    data class Data(val payload: String) : ParsedFrame()

    /** data: [DONE] 结束帧。 */
    object Done : ParsedFrame()

    /** 厂商错误帧（HTTP 200 但在流里回传 error）。 */
    data class Failed(val error: LlmError) : ParsedFrame()
}

/**
 * OpenAI 兼容 SSE 行解析器。
 *
 * 规范要点（SSE 标准 + 各厂商实际行为）：
 * - 以空行分隔事件；同一事件的多个 data: 行用换行拼接；
 * - 以冒号开头的行为注释（心跳），忽略；
 * - event / id / retry 字段解析但当前不使用；
 * - 允许 data: 后有无空格；
 * - CRLF 与 LF 均可；
 * - data: [DONE] 表示流结束。
 *
 * 本类只做行到帧的纯解析，不做网络 I/O，可直接单测。
 */
class SseLineParser {
    private val dataBuffer = StringBuilder()
    private var pendingEvent: String? = null
    private var sawDataInEvent: Boolean = false

    /** 已解析帧数，便于诊断。 */
    var framesParsed: Int = 0
        private set

    /** 喂入一行（不含换行符）；返回完整帧或 null。 */
    fun feed(rawLine: String): ParsedFrame? {
        val line = rawLine.trimEnd('\r')
        if (line.isEmpty()) return flush()
        if (line.startsWith(":")) return null

        val colon = line.indexOf(':')
        val field = if (colon < 0) line else line.substring(0, colon)
        val value = if (colon < 0) "" else line.substring(colon + 1).trimStart()

        return when (field) {
            "data" -> {
                if (sawDataInEvent) dataBuffer.append('\n')
                dataBuffer.append(value)
                sawDataInEvent = true
                null
            }
            "event" -> {
                pendingEvent = value
                null
            }
            "id", "retry" -> null
            else -> null
        }
    }

    /** 流结束时冲刷残留缓冲。 */
    fun finish(): ParsedFrame? = flush()

    private fun flush(): ParsedFrame? {
        if (!sawDataInEvent) {
            pendingEvent = null
            return null
        }
        val payload = dataBuffer.toString()
        dataBuffer.setLength(0)
        sawDataInEvent = false
        pendingEvent = null
        framesParsed++
        val trimmed = payload.trim()
        if (trimmed == DONE_SENTINEL) return ParsedFrame.Done
        if (trimmed.isEmpty()) return null
        return ParsedFrame.Data(trimmed)
    }

    companion object {
        const val DONE_SENTINEL: String = "[DONE]"
    }
}

/**
 * OpenAI 兼容报文解析。
 *
 * 解析策略对厂商差异保持宽容：缺字段、多余字段、delta 为空都不抛异常，
 * 只有完全无法识别为 chat.completion.chunk 结构才判为 [LlmError.MalformedResponse]。
 */
object OpenAiWire {
    /** 宽松 Json：厂商常附加额外字段；explicitNulls 需要 opt-in。 */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    val json: Json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            explicitNulls = false
        }

    /**
     * 解析一个 SSE data 帧。
     *
     * @return 增量文本、结束事件、错误或 null（该帧无有效内容）
     */
    fun parseChunk(data: String): LlmChunk? {
        val root =
            runCatching { json.parseToJsonElement(data).jsonObject }.getOrElse {
                return LlmChunk.Failed(LlmError.MalformedResponse(detail = "无法解析为 JSON：" + data.take(120)))
            }

        val errorElement = root["error"]
        if (errorElement != null) {
            return LlmChunk.Failed(errorFromPayload(errorElement))
        }

        val usage = parseUsage(root["usage"])
        val choices = root["choices"] as? JsonArray
        val firstChoice = choices?.firstOrNull()?.let { runCatching { it.jsonObject }.getOrNull() }

        if (firstChoice == null) {
            // 只带 usage 的收尾帧（stream_options.include_usage 的最后一帧）
            return if (usage != null) LlmChunk.Finished(usage = usage) else null
        }

        val delta = firstChoice["delta"]?.let { runCatching { it.jsonObject }.getOrNull() }
        val text = delta?.get("content")?.let { contentToText(it) }.orEmpty()
        // 思考链：DeepSeek 思考模式把推理过程放在 reasoning_content（与 content 同级）。
        // 必须单独取出，既不能拼进正文（会破坏 JSON 解析），也不能丢弃（会误判为「没输出」）。
        val reasoning = delta?.get("reasoning_content")?.let { contentToText(it) }.orEmpty()
        val finishReason = firstChoice["finish_reason"]?.let { primOrNull(it) }.orEmpty()

        if (finishReason.isNotEmpty()) {
            return LlmChunk.Finished(usage = usage ?: TokenUsage.EMPTY, finishReason = finishReason)
        }
        if (text.isNotEmpty()) return LlmChunk.Delta(text)
        if (reasoning.isNotEmpty()) return LlmChunk.Reasoning(reasoning)
        return null
    }

    /** 非流式响应体（连通性校验或降级情况下使用）的纯文本提取。 */
    fun extractMessageText(body: String): String {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return ""
        val choices = root["choices"] as? JsonArray ?: return ""
        val first = choices.firstOrNull()?.let { runCatching { it.jsonObject }.getOrNull() } ?: return ""
        val message = first["message"]?.let { runCatching { it.jsonObject }.getOrNull() }
        val content =
            message?.get("content") ?: first["delta"]?.let { element ->
                runCatching { element.jsonObject }.getOrNull()?.get("content")
            }
        return content?.let { contentToText(it) }.orEmpty()
    }

    /** 非流式响应的 usage 提取。 */
    fun extractUsage(body: String): TokenUsage? {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        return parseUsage(root["usage"])
    }

    /** 判断响应体是否为厂商错误（HTTP 200 但业务失败）。 */
    fun errorInBody(body: String): LlmError? {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        return root["error"]?.let { errorFromPayload(it) }
    }

    private fun parseUsage(element: JsonElement?): TokenUsage? {
        val obj = element?.let { runCatching { it.jsonObject }.getOrNull() } ?: return null
        val prompt = obj["prompt_tokens"]?.intOrNull() ?: 0
        val completion = obj["completion_tokens"]?.intOrNull() ?: 0
        if (prompt == 0 && completion == 0 && obj["total_tokens"] == null) return null
        return TokenUsage(inputTokens = prompt, outputTokens = completion)
    }

    private fun errorFromPayload(element: JsonElement): LlmError {
        val obj = runCatching { element.jsonObject }.getOrNull()
        val code = obj?.get("code")?.let { primOrNull(it) }.orEmpty()
        val type = obj?.get("type")?.let { primOrNull(it) }.orEmpty()
        val message = obj?.get("message")?.let { primOrNull(it) }.orEmpty()
        val raw = listOf(code, type, message).filter { it.isNotEmpty() }.joinToString(" | ")
        val lower = raw.lowercase()
        return when {
            lower.contains("invalid_api_key") || lower.contains("invalid api key") || lower.contains("unauthorized") ->
                LlmError.InvalidKey(detail = LlmErrorMapper.redact(raw))
            lower.contains("quota") || lower.contains("balance") || lower.contains("余额") ->
                LlmError.InsufficientBalance(detail = LlmErrorMapper.redact(raw))
            lower.contains("model") && (lower.contains("not") || lower.contains("exist")) ->
                LlmError.ModelNotFound(model = "", detail = LlmErrorMapper.redact(raw))
            lower.contains("rate_limit") || lower.contains("rate limit") ->
                LlmError.RateLimited(detail = LlmErrorMapper.redact(raw))
            else -> LlmError.Unknown(detail = LlmErrorMapper.redact(raw))
        }
    }

    private fun contentToText(element: JsonElement): String =
        when (element) {
            is JsonPrimitive -> if (element is JsonNull) "" else element.content
            else -> element.toString()
        }

    private fun primOrNull(element: JsonElement): String? =
        when (element) {
            is JsonPrimitive -> if (element is JsonNull) null else element.content
            else -> null
        }

    private fun JsonElement.intOrNull(): Int? = runCatching { jsonPrimitive.content.toInt() }.getOrNull()
}
