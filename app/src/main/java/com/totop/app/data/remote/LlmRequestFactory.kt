package com.totop.app.data.remote

import com.totop.app.data.prefs.LlmCredentials
import com.totop.app.data.prefs.LlmEndpoints
import kotlinx.coroutines.flow.Flow
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 请求头与请求体的构造（纯函数，可单测，不发起网络请求）。
 *
 * 关键约束：
 * - 只走 OpenAI 兼容 /chat/completions（开发方案 6.2）；
 * - 必须携带 client_request_id（开发方案 12.1 第 4 条）；
 * - Authorization 头是本项目中唯一出现明文 Key 的地方，
 *   因此 [build] 产出的 Request 不得整体打印。
 */
object LlmRequestFactory {
    /** 自定义请求头：把幂等 ID 一并带给厂商，便于对账。 */
    const val HEADER_REQUEST_ID: String = "X-Client-Request-Id"

    /** 自定义请求头：App 标识，不含任何用户信息。 */
    const val HEADER_APP: String = "X-Client-App"

    /** 连接超时（开发方案 6.2：15s）。 */
    const val CONNECT_TIMEOUT_SECONDS: Long = 15L

    /** 读超时（开发方案 6.2：120s）。 */
    const val READ_TIMEOUT_SECONDS: Long = 120L

    /** 写超时：图片 base64 上传可能较慢，与读超时同档。 */
    const val WRITE_TIMEOUT_SECONDS: Long = 120L

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    /** 构造 chat/completions 请求。 */
    fun build(
        credentials: LlmCredentials,
        config: ChatRequestConfig,
        requestId: String,
    ): Request {
        require(credentials.baseUrl.isNotBlank()) { "Base URL 不得为空" }
        require(config.model.isNotBlank()) { "模型名不得为空" }
        val url = LlmEndpoints.chatCompletions(credentials.baseUrl)
        val builder =
            Request.Builder()
                .url(url)
                .post(config.toJson().toString().toRequestBody(JSON_MEDIA_TYPE))
                .header("Content-Type", "application/json")
                .header("Accept", if (config.stream) "text/event-stream" else "application/json")
                .header(HEADER_REQUEST_ID, requestId)
                .header(HEADER_APP, "ToTop")
        // 本地 Ollama 允许空 Key，此时不发 Authorization 头
        if (credentials.apiKey.isNotBlank()) {
            builder.header("Authorization", "Bearer " + credentials.apiKey)
        }
        return builder.build()
    }

    /** 默认 OkHttpClient：15s 连接、120s 读，重试统一交给 [RetryPolicy]。 */
    fun defaultHttpClient(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
}

/**
 * Call 注册表，把 OkHttp 的 Call 生命周期挂到 ViewModel 生命周期上。
 *
 * 用法（ui-solve）：在 ViewModel 中持有一个实例，onCleared() 时调用 [cancelAll]；
 * 页面离开即中断 socket，停止继续消耗 token（开发方案 3.4）。
 */
class LlmCallRegistry {
    private val calls = LinkedHashSet<Call>()

    /** 是否已被取消（取消后拒绝新调用）。 */
    @Volatile
    var isCancelled: Boolean = false
        private set

    /** 登记一个 Call；若注册表已取消，立即取消该 Call。 */
    @Synchronized
    fun register(call: Call) {
        if (isCancelled) {
            call.cancel()
            return
        }
        calls.add(call)
    }

    /** 某个 Call 结束后注销，避免长期持有引用。 */
    @Synchronized
    fun unregister(call: Call) {
        calls.remove(call)
    }

    /** 取消全部进行中的调用（页面销毁 / 用户点停止）。 */
    @Synchronized
    fun cancelAll() {
        isCancelled = true
        calls.toList().forEach { runCatching { it.cancel() } }
        calls.clear()
    }

    /** 允许复用（ViewModel 重建时）。 */
    @Synchronized
    fun reset() {
        isCancelled = false
        calls.clear()
    }

    @Synchronized
    fun size(): Int = calls.size
}

/** 连通性校验结果（开发方案 6.1：保存前发最小请求，失败必须给出具体原因）。 */
sealed class ConnectivityResult {
    /** 校验通过。 */
    data class Ok(
        val model: String,
        val latencyMs: Long,
        /** 模型回复的前 40 个字符，用于向用户证明「确实通了」。 */
        val replyPreview: String,
        val usage: TokenUsage = TokenUsage.EMPTY,
    ) : ConnectivityResult() {
        val summary: String
            get() = "连接成功（" + latencyMs + " ms），模型 " + model + " 已就绪。"
    }

    /** 校验失败，[error] 携带可读中文原因。 */
    data class Failed(
        val error: LlmError,
        val latencyMs: Long,
    ) : ConnectivityResult() {
        val summary: String get() = error.userMessage
    }
}

/** 流式调用来源抽象，使 [LlmClient] 与假实现可互换（单测无真网络）。 */
fun interface LlmStreamSource {
    fun stream(
        credentials: LlmCredentials,
        config: ChatRequestConfig,
        requestId: String,
    ): Flow<LlmChunk>
}

/**
 * 编排层依赖的传输抽象（[LlmClient] 的完整能力子集）。
 *
 * 单独抽出来，使 [com.totop.app.data.remote.AiGateway] 可以在单测中用假实现替换真实网络，
 * 从而把「四阶段编排 + 解析重试 + 缓存 + trace」与 I/O 解耦。
 */
interface LlmTransport {
    fun stream(
        credentials: LlmCredentials,
        config: ChatRequestConfig,
        requestId: String,
        registry: LlmCallRegistry?,
        allowRetry: Boolean = true,
    ): Flow<LlmChunk>
}
