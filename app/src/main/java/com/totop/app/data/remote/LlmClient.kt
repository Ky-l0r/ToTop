package com.totop.app.data.remote

import com.totop.app.data.prefs.LlmCredentials
import com.totop.app.data.prefs.LlmEndpoints
import com.totop.app.util.UtcClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * 模型调用网关（开发方案 6.2 统一请求封装、6.3 分阶段提示词、12.1 硬性指令）。
 *
 * 能力：
 * 1. SSE 流式（stream=true），返回 Flow<LlmChunk>，增量文本 + 结束 usage；
 * 2. 15s 连接超时 / 120s 读超时（见 [LlmRequestFactory]）；
 * 3. Call.cancel 传播：Flow 被取消或页面调用 [LlmCallRegistry.cancelAll] 时中断 socket；
 * 4. 异常分类：401 -> Key 无效、402/429 -> 余额/限流、404 -> 模型名或 Base URL、网络不通、超时；
 *    每条错误都带中文原因（[LlmError.userMessage]），禁止只报「请求失败」；
 * 5. 退避重试 1s/2s/4s 最多 3 次，仅对幂等请求且「尚未吐出任何增量」时生效，
 *    避免重复计费与流式内容重复渲染；
 * 6. 连通性校验：[probe]，max_tokens=8 的最小请求。
 */
class LlmClient(
    private val httpClient: OkHttpClient = LlmRequestFactory.defaultHttpClient(),
    private val retryPolicy: RetryPolicy = RetryPolicy.DEFAULT,
    private val sleeper: Sleeper = Sleeper.REAL,
    private val clock: UtcClock = UtcClock.SYSTEM,
) : LlmStreamSource, LlmTransport {
    /** 接口实现：不绑定页面生命周期，允许对幂等调用重试。 */
    override fun stream(
        credentials: LlmCredentials,
        config: ChatRequestConfig,
        requestId: String,
    ): Flow<LlmChunk> = stream(credentials, config, requestId, registry = null, allowRetry = true)

    /**
     * 流式调用。
     *
     * @param registry 页面级 Call 注册表；ViewModel.onCleared() 调 cancelAll() 即中断
     * @param allowRetry 是否允许退避重试（幂等请求为 true）
     */
    override fun stream(
        credentials: LlmCredentials,
        config: ChatRequestConfig,
        requestId: String,
        registry: LlmCallRegistry?,
        allowRetry: Boolean,
    ): Flow<LlmChunk> =
        channelFlow {
            if (!credentials.canCall) {
                throw LlmException(
                    LlmError.Unknown(
                        detail =
                            if (credentials.apiKey.isBlank() && !credentials.isLocalEndpoint) {
                                "尚未填写 API Key，已进入本地模式。"
                            } else {
                                "Base URL 或模型名未配置。"
                            },
                    ),
                )
            }

            // Flow 被取消（页面销毁 / 用户点停止）时立刻 cancel Call，让阻塞读退出
            var activeCall: Call? = null
            val cancellationHook =
                coroutineContext[Job]?.invokeOnCompletion { cause ->
                    if (cause != null) runCatching { activeCall?.cancel() }
                }

            val totalAttempts = if (allowRetry) retryPolicy.maxRetries + 1 else 1
            var attempt = 1
            var emittedAny = false
            var lastError: LlmError? = null
            var finished = false

            try {
                while (attempt <= totalAttempts && !finished && isActive) {
                    try {
                        runAttempt(
                            credentials = credentials,
                            config = config,
                            requestId = requestId,
                            registry = registry,
                            onCallCreated = { call -> activeCall = call },
                            onChunk = { chunk ->
                                // 只有正文增量才算「已经吐给用户」；思考链不算，
                                // 否则重试会被无谓地禁掉。
                                if (chunk is LlmChunk.Delta) emittedAny = true
                                if (chunk is LlmChunk.Finished) finished = true
                                trySend(chunk)
                            },
                        )
                        return@channelFlow
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (error: LlmException) {
                        lastError = error.error
                        val canRetry = attempt < totalAttempts && error.error.isRetryable && !emittedAny
                        if (!canRetry) throw error
                        sleeper.sleep(retryPolicy.delayForAttempt(attempt))
                        attempt++
                    }
                }
            } finally {
                cancellationHook?.dispose()
            }
            throw LlmException(lastError ?: LlmError.Unknown(detail = "重试耗尽"))
        }.flowOn(Dispatchers.IO)

    /**
     * 单次尝试：建立连接、逐行解析 SSE、把增量块交给 [onChunk]。
     *
     * 刻意写成非挂起函数：整段是阻塞 I/O（channelFlow 已在 IO 调度器上运行），
     * 取消通过「取消挂接点 cancel Call」与 OkHttp 的 socket 关闭协作完成。
     */
    private fun runAttempt(
        credentials: LlmCredentials,
        config: ChatRequestConfig,
        requestId: String,
        registry: LlmCallRegistry?,
        onCallCreated: (Call) -> Unit,
        onChunk: (LlmChunk) -> Unit,
    ) {
        val startedAt = clock.nowUtcMillis()
        val call = httpClient.newCall(LlmRequestFactory.build(credentials, config, requestId))
        onCallCreated(call)
        registry?.register(call)
        val parser = SseLineParser()
        var usage: TokenUsage? = null
        var finishReason = ""
        var response: okhttp3.Response? = null
        var failure: LlmException? = null

        try {
            response = call.execute()
            if (!response.isSuccessful) {
                val bodyText = readBodyQuietly(response)
                throw LlmException(
                    LlmErrorMapper.fromHttp(
                        status = response.code,
                        body = bodyText,
                        model = config.model,
                        baseUrl = credentials.baseUrl,
                    ),
                )
            }
            val body = response.body
            if (body == null) {
                throw LlmException(LlmError.MalformedResponse(detail = "响应体为空"))
            }
            val source = body.source()
            var line = source.readUtf8Line()
            while (line != null) {
                val frame = parser.feed(line)
                if (frame is ParsedFrame.Done) {
                    finishReason = finishReason.ifEmpty { "stop" }
                    break
                }
                if (frame is ParsedFrame.Failed) {
                    throw LlmException(frame.error)
                }
                if (frame is ParsedFrame.Data) {
                    val chunk = OpenAiWire.parseChunk(frame.payload)
                    if (chunk is LlmChunk.Delta || chunk is LlmChunk.Reasoning) {
                        onChunk(chunk)
                    } else if (chunk is LlmChunk.Finished) {
                        if (chunk.usage.totalTokens > 0) usage = chunk.usage
                        if (chunk.finishReason.isNotEmpty()) finishReason = chunk.finishReason
                    } else if (chunk is LlmChunk.Failed) {
                        throw LlmException(chunk.error)
                    }
                }
                line = source.readUtf8Line()
            }
            // 部分厂商不发 [DONE]，用残留缓冲兜底
            val tailFrame = parser.finish()
            if (tailFrame is ParsedFrame.Data) {
                val tailChunk = OpenAiWire.parseChunk(tailFrame.payload)
                if (tailChunk is LlmChunk.Finished && tailChunk.usage.totalTokens > 0) usage = tailChunk.usage
            }
            onChunk(
                LlmChunk.Finished(usage = usage ?: TokenUsage.EMPTY, finishReason = finishReason.ifEmpty { "stop" }),
            )
        } catch (cancellation: CancellationException) {
            // 取消是正常控制流：转成业务错误并保留原始异常，便于上层区分「用户取消」与「真失败」
            failure =
                LlmException(
                    LlmError.Cancelled(detail = "页面已离开或在 " + (clock.nowUtcMillis() - startedAt) + " ms 后被取消"),
                    cancellation,
                )
        } catch (llm: LlmException) {
            failure = llm
        } catch (throwable: Throwable) {
            failure = LlmException(LlmErrorMapper.fromThrowable(throwable), throwable)
        } finally {
            registry?.unregister(call)
            runCatching { response?.close() }
        }
        failure?.let { throw it }
    }

    /** 读取错误响应体；失败时返回空串，绝不因读取失败覆盖原始错误分类。 */
    private fun readBodyQuietly(response: okhttp3.Response): String =
        try {
            response.body?.string().orEmpty()
        } catch (
            @Suppress("SwallowedException") io: IOException,
        ) {
            // 读取错误响应体失败不影响错误分类：调用方只用于补充 detail，
            // 拿不到就退化为空串，绝不因为「读不到错误详情」而掩盖主错误。
            ""
        }

    /**
     * 连通性校验（开发方案 6.1：保存前发最小请求 max_tokens=8，返回结构化结果）。
     *
     * 使用非流式请求以降低兼容性风险；任何失败都返回 [ConnectivityResult.Failed]，
     * 由设置页直接展示 [LlmError.userMessage]。
     */
    suspend fun probe(
        credentials: LlmCredentials,
        requestId: String,
        model: String = credentials.effectiveTextModel,
        probeText: String = DEFAULT_PROBE_TEXT,
    ): ConnectivityResult {
        val startedAt = clock.nowUtcMillis()
        if (!LlmEndpoints.isPlausibleBaseUrl(credentials.baseUrl)) {
            return ConnectivityResult.Failed(
                error = LlmError.BaseUrlInvalid(baseUrl = credentials.baseUrl, detail = "Base URL 不是合法的 http(s) 地址"),
                latencyMs = 0L,
            )
        }
        if (model.isBlank()) {
            return ConnectivityResult.Failed(
                error = LlmError.Unknown(detail = "未填写模型名，例如 qwen-plus 或 deepseek-chat"),
                latencyMs = 0L,
            )
        }
        // 探测请求的三个关键选择（每一条都有实测依据）：
        // 1. 走【流式】：部分厂商（如百炼的思考模式模型）明确要求流式，
        //    非流式会返回 400「parameter.enable_thinking must be set to false for non-streaming calls」；
        // 2. max_tokens 提到 64：思考模型会先消耗大量 reasoning_content，
        //    max_tokens=8 连思考链都开不了头，content 必然为空；
        // 3. 显式关闭思考：探测只关心「通不通」，不需要推理，省钱且快。
        val config =
            ChatRequestConfig(
                model = model,
                messages = listOf(ChatMessage.user(probeText)),
                maxTokens = ChatRequestConfig.PROBE_MAX_TOKENS,
                temperature = 0.0,
                jsonObject = false,
                includeUsage = true,
                stream = true,
                thinkingMode = ChatRequestConfig.THINKING_DISABLED,
            )
        val call = httpClient.newCall(LlmRequestFactory.build(credentials, config, requestId))
        return try {
            // 【关键】must切到 IO：performProbe 内部走的是阻塞式 call.execute()。
            // 设置页在 viewModelScope（Main.immediate）里调用本函数，
            // 若不切调度器，Android 会抛 NetworkOnMainThreadException，
            // 且该异常发生在发出请求之前，拿不到任何 HTTP 状态码，
            // 界面只能显示「未归类问题」——这正是历史版本测试连接必然失败的根因。
            withContext(Dispatchers.IO) {
                performProbe(call, credentials, model, startedAt)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            ConnectivityResult.Failed(
                error = LlmErrorMapper.fromThrowable(throwable),
                latencyMs = clock.nowUtcMillis() - startedAt,
            )
        }
    }

    /**
     * 执行一次探测请求并解释响应。
     *
     * 由于探测走的是流式请求，这里按 SSE 逐帧解析，把正文与思考链分别聚合。
     *
     * 关键修正（历史缺陷）：旧实现只要拿到 usage 就判成功，于是「正文全空」
     * 也会显示连接成功，属于伪造成功。现在正文为空一律判失败，并区分三种成因：
     * 被截断（finish_reason=length）、只吐了思考链、厂商偶发返回空。
     */
    private fun performProbe(
        call: Call,
        credentials: LlmCredentials,
        model: String,
        startedAt: Long,
    ): ConnectivityResult {
        var response: okhttp3.Response? = null
        try {
            response = call.execute()
            val latency = clock.nowUtcMillis() - startedAt
            if (!response.isSuccessful) {
                val bodyText = readBodyQuietly(response)
                return ConnectivityResult.Failed(
                    error =
                        LlmErrorMapper.fromHttp(
                            status = response.code,
                            body = bodyText,
                            model = model,
                            baseUrl = credentials.baseUrl,
                        ),
                    latencyMs = latency,
                )
            }
            val source =
                response.body?.source()
                    ?: return ConnectivityResult.Failed(
                        error = LlmError.MalformedResponse(detail = "响应体为空"),
                        latencyMs = latency,
                    )

            val parser = SseLineParser()
            val content = StringBuilder()
            val reasoning = StringBuilder()
            var usage: TokenUsage? = null
            var finishReason = ""
            var sawAnyFrame = false
            var streamError: LlmError? = null

            var line = source.readUtf8Line()
            while (line != null && streamError == null) {
                when (val frame = parser.feed(line)) {
                    is ParsedFrame.Done -> {
                        finishReason = finishReason.ifEmpty { "stop" }
                        break
                    }
                    is ParsedFrame.Failed -> streamError = frame.error
                    is ParsedFrame.Data -> {
                        sawAnyFrame = true
                        when (val chunk = OpenAiWire.parseChunk(frame.payload)) {
                            is LlmChunk.Delta -> content.append(chunk.text)
                            is LlmChunk.Reasoning -> reasoning.append(chunk.text)
                            is LlmChunk.Failed -> streamError = chunk.error
                            is LlmChunk.Finished -> {
                                if (chunk.usage.totalTokens > 0) usage = chunk.usage
                                if (chunk.finishReason.isNotEmpty()) finishReason = chunk.finishReason
                            }
                            null -> Unit
                        }
                    }
                    null -> Unit
                }
                line = source.readUtf8Line()
            }
            streamError?.let { return ConnectivityResult.Failed(error = it, latencyMs = latency) }

            val reply = content.toString().trim()
            if (reply.isEmpty()) {
                // 正文为空绝不能算成功。区分成因，给出可执行的下一步。
                return ConnectivityResult.Failed(
                    error =
                        LlmError.EmptyCompletion(
                            finishReason = finishReason,
                            thinkingOnly = reasoning.isNotBlank(),
                            detail =
                                "连通成功但正文为空（收到思考链 " + reasoning.length + " 字，" +
                                    "finish_reason=" + finishReason.ifEmpty { "未知" } + "，" +
                                    "累计 " + (usage?.totalTokens ?: 0) + " tokens）",
                        ),
                    latencyMs = latency,
                )
            }
            if (!sawAnyFrame) {
                return ConnectivityResult.Failed(
                    error =
                        LlmError.MalformedResponse(
                            detail = "服务器返回 200，但没有收到任何 SSE 数据帧，可能 Base URL 指向了网站首页而不是 API 端点",
                        ),
                    latencyMs = latency,
                )
            }
            return ConnectivityResult.Ok(
                model = model,
                latencyMs = latency,
                replyPreview = reply.take(40),
                usage = usage ?: TokenUsage.EMPTY,
            )
        } finally {
            runCatching { response?.close() }
        }
    }

    /**
     * 查询端点可用的模型名列表（OpenAI 兼容 GET /models）。
     *
     * 设计约束：
     * - **必须切到 IO 线程**：与 [probe] 同理，阻塞调用不能放主线程；
     * - 失败不抛异常，返回空列表：该接口并非所有厂商都实现，
     *   查不到只损失一条提示，不能影响主流程；
     * - 返回的模型名按字典序排序，便于在错误提示里稳定展示。
     */
    suspend fun listModels(
        credentials: LlmCredentials,
        requestId: String,
    ): List<String> =
        withContext(Dispatchers.IO) {
            val url = LlmEndpoints.models(credentials.baseUrl)
            if (url.isBlank()) {
                emptyList()
            } else {
                val request =
                    Request.Builder()
                        .url(url)
                        .get()
                        .header(HEADER_ACCEPT, "application/json")
                        .apply {
                            if (credentials.apiKey.isNotBlank()) {
                                header("Authorization", "Bearer " + credentials.apiKey)
                            }
                            header(LlmRequestFactory.HEADER_REQUEST_ID, requestId)
                            header(LlmRequestFactory.HEADER_APP, "ToTop")
                        }
                        .build()
                runCatching {
                    httpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) return@use emptyList()
                        val body = response.body?.string().orEmpty()
                        parseModelIds(body)
                    }
                }.getOrElse { emptyList() }
            }
        }

    /**
     * 从 /models 响应体提取模型 ID。
     *
     * 兼容两种常见形态：
     * 1. OpenAI：{"data":[{"id":"model-name",...}, ...]}
     * 2. 部分厂商直接返回 {"models":["a","b"]} 或裸数组。
     */
    private fun parseModelIds(body: String): List<String> {
        if (body.isBlank()) return emptyList()
        val root = runCatching { OpenAiWire.json.parseToJsonElement(body) }.getOrNull() ?: return emptyList()
        val ids = mutableListOf<String>()
        val array =
            when (root) {
                is kotlinx.serialization.json.JsonArray -> root
                is JsonObject ->
                    (root["data"] as? kotlinx.serialization.json.JsonArray)
                        ?: (root["models"] as? kotlinx.serialization.json.JsonArray)
                else -> null
            }
        array?.forEach { element ->
            when (element) {
                is JsonObject -> (element["id"] as? JsonPrimitive)?.content?.let { ids.add(it) }
                is JsonPrimitive -> if (element.isString) ids.add(element.content)
                else -> Unit
            }
        }
        return ids.filter { it.isNotBlank() }.distinct().sorted()
    }

    companion object {
        /** 连通性校验用的最小提问：要求返回一个字符，尽量省 token。 */
        const val DEFAULT_PROBE_TEXT: String = "ping，请只回复 OK"

        /** 模型列表接口的 Accept 头。 */
        private const val HEADER_ACCEPT: String = "Accept"

        /** 取消所有进行中的调用（ViewModel 生命周期兜底）。 */
        fun cancelAll(calls: MutableSet<Call>) {
            calls.toList().forEach { runCatching { it.cancel() } }
            calls.clear()
        }
    }
}
