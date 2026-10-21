package com.totop.app.data.remote

/**
 * 模型调用异常分类（开发方案 6.1：失败必须给出具体原因，禁止只报「请求失败」）。
 *
 * 分类与判定依据：
 * | 分类 | 触发条件 | 是否可退避重试 |
 * | --- | --- | --- |
 * | [InvalidKey] | HTTP 401 / 403，或厂商返回 invalid_api_key | 否 |
 * | [InsufficientBalance] | HTTP 402 / 429，或返回额度/欠费关键字 | 402 否 / 429 是 |
 * | [ModelNotFound] | HTTP 404，且响应体含 model 关键字 | 否 |
 * | [BaseUrlInvalid] | HTTP 404 且不含 model 关键字、非 http(s)、UnknownHost、路径拼错 | 否 |
 * | [NetworkUnavailable] | 连接超时 / 无网络 / DNS 失败 | 是 |
 * | [Timeout] | 读超时（120s 内没有数据） | 是 |
 * | [ServerError] | HTTP 5xx | 是 |
 * | [MalformedResponse] | 响应不是合法 SSE 或 JSON 结构不符 | 是 |
 * | [Cancelled] | 页面销毁触发的 Call.cancel() | 否 |
 *
 * 「退避重试 1s/2s/4s 最多 3 次」只对 [isRetryable] 为 true 的分类生效，
 * 且调用方必须允许幂等重试（[com.totop.app.data.remote.RequestPolicy]）。
 */
sealed class LlmError(
    /** 面向用户的中文原因，直接展示在设置页/解题页，禁止出现「请求失败」这类无语义文案。 */
    val userMessage: String,
    /** 是否允许退避重试（仅对幂等请求生效）。 */
    val isRetryable: Boolean,
    /** HTTP 状态码，非 HTTP 错误为 null。 */
    val httpStatus: Int? = null,
    /** 技术细节，仅用于 ai_trace.errorMsg 与开发者排查，不含 Key。 */
    open val detail: String? = null,
) {
    /** Key 无效（401/403）。 */
    data class InvalidKey(
        val status: Int = 401,
        override val detail: String? = null,
    ) : LlmError(
            userMessage =
                "API Key 无效或已过期（HTTP " + status + "）。请到厂商控制台复制完整的 Key，" +
                    "注意不要带多余空格或换行，然后重新保存。",
            isRetryable = false,
            httpStatus = status,
            detail = detail,
        )

    /**
     * 余额不足（402；部分厂商用 429 表达配额/欠费）。
     *
     * @param quotaExhausted 429 且响应体指明配额耗尽时为 true：此时是账户层面的长期问题，
     *        退避重试不会自愈，因此与 402 一样不可重试。
     */
    data class InsufficientBalance(
        val status: Int = 402,
        override val detail: String? = null,
        val quotaExhausted: Boolean = false,
    ) : LlmError(
            userMessage =
                if (status == 429) {
                    "调用被限流或账户额度不足（HTTP 429）。请稍后重试；若长期出现，请到厂商控制台确认余额与限流档位。"
                } else {
                    "账户余额不足（HTTP 402）。请到厂商控制台充值后重试。"
                },
            // 402 与「429 + 配额耗尽」都是账户层面的问题，重试无意义；
            // 只有在 429 但未明确配额耗尽时（瞬时限流）才允许退避
            isRetryable = status == 429 && !quotaExhausted,
            httpStatus = status,
            detail = detail,
        )

    /** 模型名错误（404 且响应体指明 model）。 */
    data class ModelNotFound(
        val model: String,
        val status: Int = 404,
        override val detail: String? = null,
    ) : LlmError(
            userMessage =
                "模型名「" + model + "」在该端点不存在（HTTP " + status + "）。" +
                    "请核对厂商文档中的模型标识，例如通义千问为 qwen-plus / qwen-vl-plus。",
            isRetryable = false,
            httpStatus = status,
            detail = detail,
        )

    /** Base URL 错误（404 且与 model 无关、协议非法、DNS 失败、路径拼错）。 */
    data class BaseUrlInvalid(
        val baseUrl: String,
        val status: Int? = null,
        override val detail: String? = null,
    ) : LlmError(
            userMessage =
                "Base URL 不可用：" + baseUrl + "。" +
                    "OpenAI 兼容端点通常以 /v1 结尾（例如 https://api.deepseek.com/v1），" +
                    "本应用会自动补 /chat/completions，请不要重复填写该路径。",
            isRetryable = false,
            httpStatus = status,
            detail = detail,
        )

    /** 网络不通（无网络、DNS 失败、连接超时 15s）。 */
    data class NetworkUnavailable(
        override val detail: String? = null,
    ) : LlmError(
            userMessage = "无法连接服务器，请检查手机网络；若使用本地 Ollama，请确认电脑已启动服务且与手机在同一局域网。",
            isRetryable = true,
            httpStatus = null,
            detail = detail,
        )

    /** 读超时（120s 无数据返回）。 */
    data class Timeout(
        override val detail: String? = null,
    ) : LlmError(
            userMessage = "服务器响应超时（120 秒内没有返回任何内容）。可能是模型排队过慢，请稍后重试或改用更快的模型。",
            isRetryable = true,
            httpStatus = null,
            detail = detail,
        )

    /** 5xx 服务端错误。 */
    data class ServerError(
        val status: Int,
        override val detail: String? = null,
    ) : LlmError(
            userMessage = "模型服务端暂时异常（HTTP " + status + "）。这通常是厂商侧问题，稍后会自动重试，请稍等。",
            isRetryable = true,
            httpStatus = status,
            detail = detail,
        )

    /** 429 限流（厂商侧配额，与余额无关时也走这里）。 */
    data class RateLimited(
        val status: Int = 429,
        override val detail: String? = null,
    ) : LlmError(
            userMessage = "请求过于频繁，已被厂商限流（HTTP 429）。将自动退避重试；若持续出现，请降低并发或稍后再用。",
            isRetryable = true,
            httpStatus = status,
            detail = detail,
        )

    /** 响应结构不合法（SSE 行损坏、JSON 缺字段）。 */
    data class MalformedResponse(
        override val detail: String? = null,
    ) : LlmError(
            userMessage = "模型返回的内容不是约定的 JSON 结构，已自动用更严格的提示词重试一次；若仍失败，本次结果不会写入错题本。",
            isRetryable = true,
            httpStatus = null,
            detail = detail,
        )

    /**
     * 模型名在该端点不存在（HTTP 400 且响应体指明 model 不存在）。
     *
     * 与 [ModelNotFound] 的区别：部分厂商（如阿里云百炼）用 **400 + invalid_parameter_error**
     * 表达「模型不存在」，而不是 404。若不单独识别，这类失败会掉进 [Unknown]，
     * 界面只能显示「未归类问题」，用户完全无法自救。
     */
    data class ModelNameInvalid(
        val model: String,
        val status: Int = 400,
        val availableModels: List<String> = emptyList(),
        override val detail: String? = null,
    ) : LlmError(
            userMessage =
                buildString {
                    append("当前端点下找不到模型「")
                    append(model)
                    append("」（HTTP ")
                    append(status)
                    append("）。请核对厂商文档里的模型标识，注意大小写与连字符。")
                    if (availableModels.isNotEmpty()) {
                        append("该 Key 实际可用的模型有：")
                        append(availableModels.take(MAX_MODEL_HINTS).joinToString("、"))
                        append("。")
                    }
                },
            isRetryable = false,
            httpStatus = status,
            detail = detail,
        )

    /**
     * 参数不被该模型支持（HTTP 400/422：thinking、response_format、max_tokens、temperature 等）。
     *
     * @param parameter 出问题的参数名（可为空）
     * @param fallbackTried 是否已自动去掉该参数重试过；true 表示重试后仍失败
     */
    data class ParameterNotSupported(
        val parameter: String = "",
        val status: Int = 400,
        val fallbackTried: Boolean = false,
        override val detail: String? = null,
    ) : LlmError(
            userMessage =
                buildString {
                    append("模型不接受请求里的某个参数")
                    if (parameter.isNotBlank()) {
                        append("（")
                        append(parameter)
                        append("）")
                    }
                    append("。")
                    if (fallbackTried) {
                        append("已自动去掉该参数重试一次，仍然失败，请换一个模型或改用更通用的模型名。")
                    } else {
                        append("应用会自动去掉该参数重试。")
                    }
                },
            isRetryable = true,
            httpStatus = status,
            detail = detail,
        )

    /**
     * 当前模型不支持图片输入。
     *
     * 典型场景：视觉档误用了纯文本模型（例如把 deepseek-chat 当作视觉模型），
     * 或厂商模型本身不含多模态。必须明确告知用户，禁止静默降质。
     *
     * @param model 实际使用的模型名
     * @param pageCount 本次尝试提交的图片张数
     */
    data class ImageInputNotSupported(
        val model: String = "",
        val pageCount: Int = 1,
        val status: Int = 400,
        override val detail: String? = null,
    ) : LlmError(
            userMessage =
                if (pageCount > 1) {
                    "模型「" + model + "」无法一次接收 " + pageCount + " 张图片。可以改用支持多图的模型，" +
                        "或减少到一张；也可以先只拍主要的那一页。"
                } else {
                    "模型「" + model + "」不支持图片输入。请在设置页把「视觉模型」换成支持看图的模型，" +
                        "或改用手打题干文字。"
                },
            isRetryable = false,
            httpStatus = status,
            detail = detail,
        )

    /** 图片本身被拒绝（格式、体积、尺寸不符合厂商要求）。 */
    data class ImageFormatRejected(
        val reason: String = "",
        val status: Int = 400,
        override val detail: String? = null,
    ) : LlmError(
            userMessage =
                "图片不符合模型要求" + (if (reason.isNotBlank()) "（" + reason + "）" else "") +
                    "。请改用 JPG/PNG 格式，或重新拍一张光线更均匀的照片。",
            isRetryable = false,
            httpStatus = status,
            detail = detail,
        )

    /** 上下文或输出长度超限（多页图 + 长题干、或输出被截断）。 */
    data class ContextLengthExceeded(
        val status: Int = 400,
        override val detail: String? = null,
    ) : LlmError(
            userMessage = "内容太长了，超出了模型一次能处理的范围。可以减少题目页数，或在拍题时只框选要问的那一小题。",
            isRetryable = false,
            httpStatus = status,
            detail = detail,
        )

    /**
     * 模型返回了 200，但正文为空。
     *
     * 关键：**这绝不能算成功**。DeepSeek 官方 JSON 模式文档明确写明
     * 「有概率会返回空的 content」；思考模式下输出预算被思考链吃光时也会出现。
     * 若判为成功，界面会显示一条空白回复，属于伪造成功。
     *
     * @param finishReason 若为 "length" 说明是被 max_tokens 截断
     * @param thinkingOnly 是否只收到了思考内容而没有正文
     */
    data class EmptyCompletion(
        val finishReason: String = "",
        val thinkingOnly: Boolean = false,
        override val detail: String? = null,
    ) : LlmError(
            userMessage =
                when {
                    finishReason == "length" ->
                        "模型的话没说完就被长度上限截断了，所以没有拿到可用结果。已自动放宽上限重试；若仍出现，请在设置里改成「详细」档。"
                    thinkingOnly ->
                        "模型只输出了思考过程，没有给出正式回答。已自动关闭思考模式重试。"
                    else ->
                        "模型这次返回了空白内容（这是厂商侧的已知偶发问题）。请点重试；若反复出现，换一个模型或改用详细档。"
                },
            isRetryable = true,
            httpStatus = 200,
            detail = detail,
        )

    /** 主线程上发起了阻塞式网络请求（开发期缺陷，必须明确暴露而不是笼统报错）。 */
    data class NetworkOnMainThread(
        override val detail: String? = null,
    ) : LlmError(
            userMessage = "应用内部错误：网络请求被放在了界面线程上，系统直接拦截了。这是代码缺陷，请把这条反馈给开发者。",
            isRetryable = false,
            httpStatus = null,
            detail = detail,
        )

    /** 用户或页面销毁导致的中断，不提示错误。 */
    data class Cancelled(
        override val detail: String? = null,
    ) : LlmError(
            userMessage = "请求已取消。",
            isRetryable = false,
            httpStatus = null,
            detail = detail,
        )

    /** 未归类的兜底，仍带上 HTTP 码与响应片段，避免「请求失败」。 */
    data class Unknown(
        override val detail: String? = null,
        val status: Int? = null,
    ) : LlmError(
            userMessage =
                "调用模型时发生未预期的问题" + (status?.let { "（HTTP " + it + "）" } ?: "") +
                    "，请查看下方技术细节并反馈。",
            isRetryable = false,
            httpStatus = status,
            detail = detail,
        )

    /** ai_trace.errorMsg 写入内容（分类名 + 中文原因，不含 Key）。 */
    fun traceMessage(): String = kindCode + "：" + userMessage + (detail?.let { "（" + it + "）" } ?: "")

    /** 稳定分类码，写入 ai_trace 便于统计。 */
    val kindCode: String
        get() =
            when (this) {
                is InvalidKey -> "invalid_key"
                is InsufficientBalance -> "insufficient_balance"
                is ModelNotFound -> "model_not_found"
                is ModelNameInvalid -> "model_name_invalid"
                is ParameterNotSupported -> "parameter_not_supported"
                is ImageInputNotSupported -> "image_input_not_supported"
                is ImageFormatRejected -> "image_format_rejected"
                is ContextLengthExceeded -> "context_length_exceeded"
                is EmptyCompletion -> "empty_completion"
                is NetworkOnMainThread -> "network_on_main_thread"
                is BaseUrlInvalid -> "base_url_invalid"
                is NetworkUnavailable -> "network_unavailable"
                is Timeout -> "timeout"
                is ServerError -> "server_error"
                is RateLimited -> "rate_limited"
                is MalformedResponse -> "malformed_response"
                is Cancelled -> "cancelled"
                is Unknown -> "unknown"
            }

    companion object {
        /** 错误文案里最多列出多少个可用模型名，避免提示过长。 */
        const val MAX_MODEL_HINTS: Int = 6
    }
}

/** 携带 [LlmError] 的受检异常，保证 Flow 上的错误可被结构化处理。 */
class LlmException(
    val error: LlmError,
    cause: Throwable? = null,
) : Exception(error.userMessage, cause)
