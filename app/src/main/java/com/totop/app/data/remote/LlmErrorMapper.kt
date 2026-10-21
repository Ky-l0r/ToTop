package com.totop.app.data.remote

import android.annotation.SuppressLint
import android.os.NetworkOnMainThreadException
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * HTTP / 网络异常 → [LlmError] 的纯函数映射（可单测，不发真实请求）。
 *
 * 判定优先级（与 [LlmError] 文档表一致）：
 * 1. 401/403 -> Key 无效；
 * 2. 402 -> 余额不足；
 * 3. 429 -> 若响应体含额度/欠费关键字判为余额不足（不可重试），否则判为限流（可重试）；
 * 4. 404 -> 响应体含 model 关键字判为模型名错误，否则判为 Base URL 错误；
 * 5. 其余 4xx -> [LlmError.Unknown]（带 HTTP 码与响应片段，绝不只说「请求失败」）；
 * 6. 5xx -> 服务端错误（可重试）；
 * 7. 异常 -> 超时 / 网络不通 / 证书 / 取消。
 *
 * 响应片段经 [redact] 处理，剔除任何形似 API Key 的字符串，保证可安全写入日志与追踪。
 */
object LlmErrorMapper {
    /** 响应体折叠到 errorMsg 的最大长度。 */
    const val MAX_BODY_SNIPPET: Int = 240

    /** 额度类关键字：命中即视为余额问题而非限流。 */
    private val BALANCE_KEYWORDS =
        listOf(
            "insufficient_quota",
            "insufficient balance",
            "exceeded your current quota",
            "余额不足",
            "欠费",
            "额度",
            "quota",
        )

    /** 模型名字关键字：404 时用于区分「模型名错」与「Base URL 错」。 */
    private val MODEL_KEYWORDS =
        listOf(
            "model",
            "模型",
            "does not exist",
            "not found the model",
        )

    /**
     * 「模型不存在」的强特征。
     *
     * 厂商表达方式差异很大，以下为实测归纳：
     * - DeepSeek 官方：Model Not Exist / The model ... does not exist
     * - 阿里云百炼：Model not exist. / Model.NotFound / invalid_parameter_error
     * - OpenAI 兼容网关：The model ... does not exist or you do not have access to it
     * - Moonshot：model_not_found
     */
    private val MODEL_MISSING_KEYWORDS =
        listOf(
            "model not exist",
            "model_not_exist",
            "model not found",
            "model_not_found",
            "model.notfound",
            "does not exist or you do not have access",
            "not found the model",
            "unknown model",
            "invalid model",
            "模型不存在",
            "模型名称错误",
        )

    /** 参数不被支持的强特征。 */
    private val PARAMETER_KEYWORDS =
        listOf(
            "invalid_parameter",
            "invalid parameter",
            "unsupported parameter",
            "unrecognized request argument",
            "extra inputs are not permitted",
            "must be set to false",
            "only support stream",
            "response_format",
            "json_object",
            "thinking",
            "max_tokens",
            "temperature",
        )

    /** 图片输入不被支持的强特征。 */
    private val IMAGE_UNSUPPORTED_KEYWORDS =
        listOf(
            "image is not supported",
            "does not support image",
            "image input is not supported",
            "image_url",
            "vision is not supported",
            "not a multimodal",
            "multimodal is not supported",
            "不支持图片",
            "不支持图像",
        )

    /** 图片本身不合规（格式/体积/尺寸）。 */
    private val IMAGE_REJECTED_KEYWORDS =
        listOf(
            "invalid image",
            "image format",
            "unsupported image",
            "image too large",
            "image dimension",
            "图片格式",
            "图片过大",
        )

    /** 上下文/输出长度超限。 */
    private val CONTEXT_KEYWORDS =
        listOf(
            "context length",
            "maximum context",
            "too many tokens",
            "token limit",
            "reduce the length",
            "context_length_exceeded",
            "输入过长",
            "超出最大长度",
        )

    /** 从 HTTP 状态码 + 响应体映射错误。 */
    fun fromHttp(
        status: Int,
        body: String?,
        model: String = "",
        baseUrl: String = "",
        pageCount: Int = 1,
    ): LlmError {
        val snippet = body?.let { redact(it) }?.take(MAX_BODY_SNIPPET)
        return when (status) {
            401, 403 -> LlmError.InvalidKey(status = status, detail = snippet)

            402 -> LlmError.InsufficientBalance(status = 402, detail = snippet)

            429 ->
                if (containsAny(body, BALANCE_KEYWORDS)) {
                    LlmError.InsufficientBalance(status = 429, detail = snippet, quotaExhausted = true)
                } else {
                    LlmError.RateLimited(status = 429, detail = snippet)
                }

            404 ->
                if (containsAny(body, MODEL_KEYWORDS)) {
                    LlmError.ModelNotFound(model = model, status = 404, detail = snippet)
                } else {
                    LlmError.BaseUrlInvalid(baseUrl = baseUrl, status = 404, detail = snippet)
                }

            // 400 / 422：参数类错误。必须按响应体细分，否则界面只能显示「未归类问题」。
            // 阿里云百炼的「模型不存在」正是 400 + invalid_parameter_error（不是 404）。
            400, 422 -> classifyBadRequest(body = body, model = model, status = status, pageCount = pageCount, snippet = snippet)

            in 500..599 -> LlmError.ServerError(status = status, detail = snippet)

            else -> LlmError.Unknown(detail = snippet ?: ("HTTP " + status), status = status)
        }
    }

    /**
     * 400 / 422 的语义细分。
     *
     * 判定顺序即优先级（先命中先返回，禁止调换）：
     * 1. 模型不存在 -> [LlmError.ModelNameInvalid]；
     * 2. 图片输入不被支持 -> [LlmError.ImageInputNotSupported]；
     * 3. 图片本身不合规 -> [LlmError.ImageFormatRejected]；
     * 4. 上下文/长度超限 -> [LlmError.ContextLengthExceeded]；
     * 5. 参数不支持 -> [LlmError.ParameterNotSupported]；
     * 6. 其余 -> [LlmError.Unknown]，但仍保留 HTTP 码与脱敏片段。
     *
     * 之所以必须细分：百炼等厂商用 400 表达「模型不存在」，若不识别就会掉进
     * Unknown，界面只能显示「未归类问题」，用户无法自救。
     */
    fun classifyBadRequest(
        body: String?,
        model: String = "",
        status: Int = 400,
        pageCount: Int = 1,
        snippet: String? = null,
    ): LlmError {
        val detail = snippet ?: body?.let { redact(it).take(MAX_BODY_SNIPPET) }
        return when {
            containsAny(body, MODEL_MISSING_KEYWORDS) ->
                LlmError.ModelNameInvalid(model = model, status = status, detail = detail)

            containsAny(body, IMAGE_UNSUPPORTED_KEYWORDS) ->
                LlmError.ImageInputNotSupported(model = model, pageCount = pageCount, status = status, detail = detail)

            containsAny(body, IMAGE_REJECTED_KEYWORDS) ->
                LlmError.ImageFormatRejected(status = status, detail = detail)

            containsAny(body, CONTEXT_KEYWORDS) ->
                LlmError.ContextLengthExceeded(status = status, detail = detail)

            containsAny(body, PARAMETER_KEYWORDS) ->
                LlmError.ParameterNotSupported(status = status, detail = detail)

            // 400 且无可识别特征：仍要带 HTTP 码与片段，不能只说「未归类」
            else -> LlmError.Unknown(detail = detail ?: ("HTTP " + status + " 未识别的参数错误"), status = status)
        }
    }
    /** 从网络异常映射错误。 */
    fun fromThrowable(throwable: Throwable): LlmError =
        when (throwable) {
            is LlmException -> throwable.error

            // 主线程网络请求被系统拦截：这是代码缺陷，必须单独暴露，
            // 否则会落进 Unknown 显示「未归类问题」，把真正的 bug 藏起来。
            is NetworkOnMainThreadException ->
                LlmError.NetworkOnMainThread(
                    detail = "NetworkOnMainThreadException：网络请求发生在界面线程，已由系统拦截。" +
                        "该异常发生在发出请求之前，因此没有 HTTP 状态码。",
                )

            is CancellationException -> LlmError.Cancelled(detail = throwable.message)

            is SocketTimeoutException ->
                if (isConnectTimeout(throwable)) {
                    LlmError.NetworkUnavailable(detail = "连接超时（15 秒）：" + throwable.message.describe())
                } else {
                    LlmError.Timeout(detail = "读超时（120 秒）：" + throwable.message.describe())
                }

            is InterruptedIOException ->
                if (throwable is SocketTimeoutException) {
                    LlmError.Timeout(detail = throwable.message.describe())
                } else {
                    LlmError.Cancelled(detail = throwable.message.describe())
                }

            is UnknownHostException ->
                LlmError.BaseUrlInvalid(
                    baseUrl = throwable.host.orEmpty(),
                    detail = "域名无法解析：" + throwable.message.describe(),
                )

            is ConnectException ->
                LlmError.NetworkUnavailable(detail = "连接被拒绝：" + throwable.message.describe())

            is SSLException ->
                LlmError.NetworkUnavailable(detail = "TLS 握手失败（可能是证书或系统时间问题）：" + throwable.message.describe())

            is IOException -> LlmError.NetworkUnavailable(detail = throwable.message.describe())

            else -> LlmError.Unknown(detail = throwable.javaClass.simpleName + "：" + throwable.message.describe())
        }

    /** SocketTimeoutException 在 OkHttp 中连接超时与读超时都以 message 区分。 */
    private fun isConnectTimeout(throwable: SocketTimeoutException): Boolean =
        throwable.message?.contains("connect", ignoreCase = true) == true

    /** 从 UnknownHostException 的消息中取出主机名（形如 "api.example.com: Name not resolved"）。 */
    private val UnknownHostException.host: String?
        get() = message?.substringBefore(':')?.takeIf { it.isNotBlank() }

    private fun String?.describe(): String = this?.take(MAX_BODY_SNIPPET) ?: "无详细信息"

    private fun containsAny(
        body: String?,
        keywords: List<String>,
    ): Boolean {
        if (body.isNullOrBlank()) return false
        val lower = body.lowercase()
        return keywords.any { lower.contains(it.lowercase()) }
    }

    /**
     * 脱敏：剔除 sk- 开头的常见 Key 形态与 Bearer 头内容。
     * 服务端错误页偶尔会回显请求头，必须在写入日志前处理。
     */
    fun redact(raw: String): String =
        raw
            .replace(Regex("sk-[A-Za-z0-9_\\-]{8,}"), "sk-****")
            .replace(Regex("(?i)bearer\\s+[A-Za-z0-9_\\-.=+/]{8,}"), "Bearer ****")
            .replace(Regex("(?i)(\"api[_-]?key\"\\s*:\\s*\")[^\"]+"), "\$1****")
}
