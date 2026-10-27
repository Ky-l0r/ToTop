package com.totop.app.data.remote

import com.totop.app.data.prefs.InMemorySecretStore
import com.totop.app.data.prefs.LlmCredentials
import com.totop.app.data.prefs.LlmEndpoints
import com.totop.app.data.prefs.ProviderTemplates
import com.totop.app.domain.model.AiStage
import com.totop.app.util.InMemoryAiTraceStore
import com.totop.app.util.MutableUtcClock
import com.totop.app.util.TraceRecorder
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * LlmClient 端到端测试（MockWebServer，无需真实网络）。
 *
 * 覆盖：SSE 增量文本、usage 收尾、401/402/404/429/5xx 分类、
 * 连接失败、连通性校验、Request 头与超时配置。
 */
class LlmClientMockWebServerTest {
    private lateinit var server: MockWebServer

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun credentials(baseUrl: String = server.url("/v1").toString()) =
        LlmCredentials(
            baseUrl = baseUrl,
            apiKey = "sk-test-key",
            model = "qwen-plus",
            textModel = "qwen-plus",
            visionModel = "qwen-vl-plus",
        )

    private fun config(stream: Boolean = true) =
        ChatRequestConfig(
            model = "qwen-plus",
            messages = listOf(ChatMessage.system("sys"), ChatMessage.user("hi")),
            maxTokens = 64,
            stream = stream,
        )

    private fun sseResponse(): MockResponse =
        MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody(
                "data: {\"choices\":[{\"delta\":{\"content\":\"第一\"}}]}\n\n" +
                    "data: {\"choices\":[{\"delta\":{\"content\":\"步\"}}]}\n\n" +
                    "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]," +
                    "\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":7}}\n\n" +
                    "data: [DONE]\n\n",
            )

    @Test
    @DisplayName("SSE 流式：增量文本按序到达，最后一条为 Finished 且带 usage")
    fun streamsIncrementalText() =
        runTest {
            server.enqueue(sseResponse())
            val client = LlmClient(sleeper = Sleeper.NOOP)
            val chunks = client.stream(credentials(), config(), "req-1").toList()

            val deltas = chunks.filterIsInstance<LlmChunk.Delta>().map { it.text }
            assertEquals(listOf("第一", "步"), deltas)
            val finished = chunks.last() as LlmChunk.Finished
            assertEquals(11, finished.usage.inputTokens)
            assertEquals(7, finished.usage.outputTokens)
            assertEquals("stop", finished.finishReason)
        }

    @Test
    @DisplayName("请求头携带 Authorization 与 client_request_id，路径自动补 /chat/completions")
    fun requestHeadersAndPath() =
        runTest {
            server.enqueue(sseResponse())
            val client = LlmClient(sleeper = Sleeper.NOOP)
            client.stream(credentials(), config(), "req-abc").toList()

            val recorded = server.takeRequest()
            assertEquals("/v1/chat/completions", recorded.path)
            assertEquals("Bearer sk-test-key", recorded.getHeader("Authorization"))
            assertEquals("req-abc", recorded.getHeader(LlmRequestFactory.HEADER_REQUEST_ID))
            assertTrue(recorded.getHeader("Accept").orEmpty().contains("text/event-stream"))
            val body = recorded.body.readUtf8()
            assertTrue(body.contains("\"stream\":true"), body)
            assertTrue(body.contains("\"response_format\""), body)
        }

    @Test
    @DisplayName("HTTP 401 分类为 Key 无效，且中文原因可读")
    fun invalidKey() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(401).setBody("{\"error\":\"bad key\"}"))
            val client = LlmClient(sleeper = Sleeper.NOOP)
            val error = runCatching { client.stream(credentials(), config(), "req").toList() }.exceptionOrNull()
            val llm = error as LlmException
            assertTrue(llm.error is LlmError.InvalidKey)
            assertTrue(llm.error.userMessage.contains("API Key"))
            assertEquals(1, server.requestCount, "401 不可重试，只发一次")
        }

    @Test
    @DisplayName("HTTP 402 分类为余额不足，不重试")
    fun insufficientBalance() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(402).setBody("{\"error\":\"no money\"}"))
            val client = LlmClient(sleeper = Sleeper.NOOP)
            val error = runCatching { client.stream(credentials(), config(), "req").toList() }.exceptionOrNull()
            assertTrue((error as LlmException).error is LlmError.InsufficientBalance)
            assertEquals(1, server.requestCount)
        }

    @Test
    @DisplayName("HTTP 429 限流：按 1s/2s/4s 退避重试，最多 4 次请求")
    fun rateLimitedRetries() =
        runTest {
            repeat(4) { server.enqueue(MockResponse().setResponseCode(429).setBody("{\"error\":\"rate limited\"}")) }
            val client = LlmClient(sleeper = Sleeper.NOOP)
            val error = runCatching { client.stream(credentials(), config(), "req").toList() }.exceptionOrNull()
            assertTrue((error as LlmException).error is LlmError.RateLimited)
            assertEquals(4, server.requestCount, "首次 + 3 次重试")
        }

    @Test
    @DisplayName("HTTP 404 带 model 关键字 -> 模型名错误")
    fun modelNotFound() =
        runTest {
            server.enqueue(
                MockResponse().setResponseCode(404)
                    .setBody("{\"error\":{\"message\":\"The model qwen-plus does not exist\"}}"),
            )
            val client = LlmClient(sleeper = Sleeper.NOOP)
            val error = runCatching { client.stream(credentials(), config(), "req").toList() }.exceptionOrNull()
            assertTrue((error as LlmException).error is LlmError.ModelNotFound)
        }

    @Test
    @DisplayName("HTTP 404 不含 model 关键字 -> Base URL 错误")
    fun baseUrlInvalid() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(404).setBody("<html>404</html>"))
            val client = LlmClient(sleeper = Sleeper.NOOP)
            val error = runCatching { client.stream(credentials(), config(), "req").toList() }.exceptionOrNull()
            assertTrue((error as LlmException).error is LlmError.BaseUrlInvalid)
        }

    @Test
    @DisplayName("HTTP 503 服务端错误：退避重试后仍失败")
    fun serverErrorRetries() =
        runTest {
            repeat(4) { server.enqueue(MockResponse().setResponseCode(503).setBody("oops")) }
            val client = LlmClient(sleeper = Sleeper.NOOP)
            val error = runCatching { client.stream(credentials(), config(), "req").toList() }.exceptionOrNull()
            assertTrue((error as LlmException).error is LlmError.ServerError)
            assertEquals(4, server.requestCount)
        }

    @Test
    @DisplayName("Base URL 指向不可达地址 -> 网络不通（可读中文原因）")
    fun networkUnavailable() =
        runTest {
            val deadUrl = "http://127.0.0.1:1/v1"
            val client = LlmClient(sleeper = Sleeper.NOOP)
            val error =
                runCatching {
                    client.stream(credentials(deadUrl).copy(apiKey = "sk-x"), config(), "req").toList()
                }.exceptionOrNull()
            assertNotNull(error)
            val llmError = (error as LlmException).error
            assertTrue(
                llmError is LlmError.NetworkUnavailable || llmError is LlmError.BaseUrlInvalid,
                llmError.kindCode,
            )
            assertTrue(
                llmError.userMessage.contains("无法连接") || llmError.userMessage.contains("Base URL"),
                llmError.userMessage,
            )
        }

    @Test
    @DisplayName("连通性校验成功：返回结构化 Ok 与耗时")
    fun probeOk() =
        runTest {
            // 探测已改为流式，响应必须是 SSE 帧（每帧 data: 前缀 + 空行结尾）
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setBody(
                        "data: {\"choices\":[{\"delta\":{\"content\":\"OK\"}}]}\n\n" +
                            "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]," +
                            "\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":1}}\n\n" +
                            "data: [DONE]\n\n",
                    ),
            )
            val client = LlmClient(sleeper = Sleeper.NOOP)
            val result = client.probe(credentials(), "probe-1")
            assertTrue(result is ConnectivityResult.Ok, result.toString())
            val ok = result as ConnectivityResult.Ok
            assertEquals("OK", ok.replyPreview)
            assertEquals(5, ok.usage.inputTokens)

            val recorded = server.takeRequest()
            val body = recorded.body.readUtf8()
            assertTrue(
                body.contains("\"max_tokens\":" + ChatRequestConfig.PROBE_MAX_TOKENS),
                "连通性校验必须使用约定预算：" + body,
            )
            assertTrue(body.contains("\"stream\":true"), "探测应走流式：" + body)
        }

    @Test
    @DisplayName("连通性校验失败：返回结构化 Failed 与具体原因")
    fun probeFailed() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(401).setBody("{\"error\":\"bad key\"}"))
            val client = LlmClient(sleeper = Sleeper.NOOP)
            val result = client.probe(credentials(), "probe-2")
            assertTrue(result is ConnectivityResult.Failed)
            assertTrue((result as ConnectivityResult.Failed).error is LlmError.InvalidKey)
            assertEquals(1, server.requestCount)
        }

    @Test
    @DisplayName("连通性校验前先校验 Base URL 形态，非法地址不发请求")
    fun probeInvalidBaseUrl() =
        runTest {
            val client = LlmClient(sleeper = Sleeper.NOOP)
            val result = client.probe(credentials("ftp://weird"), "probe-3")
            assertTrue(result is ConnectivityResult.Failed)
            assertTrue((result as ConnectivityResult.Failed).error is LlmError.BaseUrlInvalid)
            assertEquals(0, server.requestCount)
        }

    @Test
    @DisplayName("未配置 Key 时立即给出「本地模式」提示，不发请求")
    fun missingCredentials() =
        runTest {
            val client = LlmClient(sleeper = Sleeper.NOOP)
            val error =
                runCatching {
                    client.stream(LlmCredentials.EMPTY, config(), "req").toList()
                }.exceptionOrNull()
            assertNotNull(error)
            assertTrue((error as LlmException).error.detail.orEmpty().contains("本地模式"))
            assertEquals(0, server.requestCount)
        }

    @Test
    @DisplayName("Call 注册表：cancelAll 只取消在途调用，注册表仍可复用；封存后才拒绝新调用")
    fun callRegistryCancels() =
        runTest {
            val registry = LlmCallRegistry()
            registry.cancelAll()
            // 语义变更（0.4.0）：cancelAll 不再永久封存。
            // 旧语义会把「新建对话 / 切换会话 / 点停止」之后的所有新请求都就地取消，
            // 表现为「新建对话后再提问，AI 没有任何输出」。
            assertFalse(registry.isSealed, "cancelAll 后注册表必须仍可用")
            assertEquals(0, registry.size())

            registry.cancelAllAndSeal()
            assertTrue(registry.isSealed)
            assertEquals(0, registry.size())
        }

    @Test
    @DisplayName("默认 OkHttpClient 超时为 15s 连接 / 120s 读")
    fun defaultTimeouts() {
        val client = LlmRequestFactory.defaultHttpClient()
        assertEquals(15_000, client.connectTimeoutMillis)
        assertEquals(120_000, client.readTimeoutMillis)
        assertFalse(client.retryOnConnectionFailure, "重试统一由 RetryPolicy 控制")
    }
}

/** 端点与凭据的纯函数测试。 */
class LlmEndpointsTest {
    @Test
    @DisplayName("Base URL 归一化：去尾斜杠、收敛重复斜杠、自动补 /chat/completions")
    fun normalizeAndCompose() {
        assertEquals("https://api.deepseek.com/v1", LlmEndpoints.normalizeBaseUrl("https://api.deepseek.com/v1/"))
        assertEquals(
            "https://api.deepseek.com/v1/chat/completions",
            LlmEndpoints.chatCompletions("https://api.deepseek.com/v1/"),
        )
        assertEquals(
            "https://api.deepseek.com/v1/chat/completions",
            LlmEndpoints.chatCompletions("https://api.deepseek.com/v1/chat/completions"),
        )
        assertEquals(
            "https://api.deepseek.com/v1/chat/completions",
            LlmEndpoints.chatCompletions("https://api.deepseek.com//v1"),
        )
    }

    @Test
    @DisplayName("Base URL 形态校验：只接受 http/https")
    fun plausibility() {
        assertTrue(LlmEndpoints.isPlausibleBaseUrl("https://api.moonshot.cn/v1"))
        assertTrue(LlmEndpoints.isPlausibleBaseUrl("http://127.0.0.1:11434/v1"))
        assertFalse(LlmEndpoints.isPlausibleBaseUrl("ftp://api.x.com/v1"))
        assertFalse(LlmEndpoints.isPlausibleBaseUrl(""))
        assertFalse(LlmEndpoints.isPlausibleBaseUrl("api.deepseek.com/v1"))
    }

    @Test
    @DisplayName("凭据脱敏：toString 与 maskedApiKey 不泄漏完整 Key")
    fun credentialsMasked() {
        val credentials =
            LlmCredentials(
                baseUrl = "https://api.deepseek.com/v1",
                apiKey = "sk-1234567890abcdef",
                model = "deepseek-chat",
            )
        assertFalse(credentials.toString().contains("sk-1234567890abcdef"))
        assertEquals("sk-1********cdef", credentials.maskedApiKey)
        val short = credentials.copy(apiKey = "short")
        assertEquals("*****", short.maskedApiKey)
    }

    @Test
    @DisplayName("模型池：文本/视觉为空时回退到默认模型；本地端点允许空 Key")
    fun modelPoolAndLocal() {
        val single = LlmCredentials(baseUrl = "https://api.x.com/v1", apiKey = "k", model = "m1")
        assertEquals("m1", single.effectiveTextModel)
        assertEquals("m1", single.effectiveVisionModel)
        assertTrue(single.isConfigured)
        assertTrue(single.canCall)

        val local = LlmCredentials(baseUrl = "http://127.0.0.1:11434/v1", apiKey = "", model = "qwen2.5")
        assertTrue(local.isLocalEndpoint)
        assertTrue(local.canCall, "本地端点允许空 Key")

        val remoteNoKey = LlmCredentials(baseUrl = "https://api.x.com/v1", apiKey = "", model = "m")
        assertFalse(remoteNoKey.canCall)
    }

    @Test
    @DisplayName("预置厂商模板齐全且 Base URL 合法")
    fun providerTemplates() {
        assertTrue(ProviderTemplates.ALL.size >= 5, "至少覆盖文档列出的五家")
        ProviderTemplates.ALL.forEach { template ->
            assertTrue(LlmEndpoints.isPlausibleBaseUrl(template.baseUrl), template.id)
            assertTrue(template.textModel.isNotBlank(), template.id)
            assertTrue(template.visionModel.isNotBlank(), template.id)
        }
        val ollama = ProviderTemplates.byId("ollama")
        assertNotNull(ollama)
        assertTrue(ollama!!.local)

        val applied = ProviderTemplates.applyTo(ProviderTemplates.ALL.first(), LlmCredentials(apiKey = "keep-me"))
        assertEquals("keep-me", applied.apiKey, "套用模板必须保留用户已填的 Key")
    }
}

/** 密钥存储与 trace 的纯逻辑测试（不触达 Android Keystore）。 */
class SecretAndTraceTest {
    @Test
    @DisplayName("内存密钥存储：保存归一化、清除 Key 保留其他字段、清除全部")
    fun inMemorySecretStore() {
        val store = InMemorySecretStore()
        store.save(
            LlmCredentials(
                baseUrl = " https://api.deepseek.com/v1/ ",
                apiKey = " sk-abc ",
                model = " deepseek-chat ",
            ),
        )
        val saved = store.read()
        assertEquals("https://api.deepseek.com/v1", saved.baseUrl, "Base URL 必须归一化")
        assertEquals("sk-abc", saved.apiKey, "Key 必须去空白（复制常带换行）")
        assertEquals("deepseek-chat", saved.model)
        assertTrue(store.hasApiKey())

        store.clearApiKey()
        assertFalse(store.hasApiKey())
        assertEquals("https://api.deepseek.com/v1", store.read().baseUrl, "清 Key 不应清掉 Base URL")

        store.setLocalOnlyAcknowledged(true)
        assertTrue(store.isLocalOnlyAcknowledged())

        store.clearAll()
        assertEquals(LlmCredentials.EMPTY, store.read())
        assertEquals(1, store.saveCount, "只有一次 save；清 Key / 标记 / 清空都不计入保存次数")
    }

    @Test
    @DisplayName("trace 写入含 requestId/stage/model/promptVer/tokens/latency/ok/errorMsg")
    fun traceRecordFields() =
        runTest {
            val store = InMemoryAiTraceStore()
            val clock = MutableUtcClock(1_700_000_000_000L)
            val recorder = TraceRecorder(store, clock)

            val trace =
                recorder.record(
                    requestId = "req-1",
                    stage = AiStage.C,
                    model = "qwen-plus",
                    promptVer = "1.0.0",
                    inputTokens = 100,
                    outputTokens = 50,
                    latencyMs = 1234,
                    ok = false,
                    errorMsg = "invalid_key：API Key 无效",
                )
            assertEquals("req-1", trace.requestId)
            assertEquals(AiStage.C, trace.stage)
            assertEquals("qwen-plus", trace.model)
            assertEquals("1.0.0", trace.promptVer)
            assertEquals(150, trace.totalTokens)
            assertEquals(1234, trace.latencyMs)
            assertFalse(trace.ok)
            assertTrue(trace.errorMsg!!.startsWith("invalid_key"))
            assertEquals(1, store.byRequestId("req-1").size)
        }

    @Test
    @DisplayName("近 30 天用量汇总：按阶段与按日聚合，成功率正确")
    fun usageSummary() =
        runTest {
            val store = InMemoryAiTraceStore()
            val clock = MutableUtcClock(1_700_000_000_000L)
            val recorder = TraceRecorder(store, clock)

            // 第 1 天：A 成功
            recorder.record("r1", AiStage.A, "m", "1.0.0", 10, 20, 100, ok = true)
            clock.advanceBy(86_400_000L)
            // 第 2 天：B 成功、C 失败
            recorder.record("r1", AiStage.B, "m", "1.0.0", 30, 40, 200, ok = true)
            recorder.record("r1", AiStage.C, "m", "1.0.0", 0, 0, 300, ok = false, errorMsg = "timeout：超时")

            val summary = recorder.usageWithin(days = 30)
            assertEquals(3, summary.calls)
            assertEquals(2, summary.okCalls)
            assertEquals(1, summary.failedCalls)
            assertEquals(40, summary.inputTokens)
            assertEquals(60, summary.outputTokens)
            assertEquals(100, summary.totalTokens)
            // 3 条记录分布在 2 个 UTC 日（第 1 天 1 条、第 2 天 2 条）
            assertEquals(2, summary.byDay.size)
            assertEquals(3, summary.byStage.size, "A/B/C 三个阶段各有一条记录")
            assertEquals(AiStage.A, summary.byStage[0].stage)
            assertTrue(summary.successRate in 0.66..0.67, summary.successRate.toString())
        }

    @Test
    @DisplayName("窗口外的记录不计入近 30 天用量")
    fun usageWindow() =
        runTest {
            val store = InMemoryAiTraceStore()
            val clock = MutableUtcClock(1_700_000_000_000L)
            val recorder = TraceRecorder(store, clock)
            recorder.record("old", AiStage.A, "m", "1.0.0", 1, 1, 1)
            clock.advanceBy(31L * 86_400_000L)
            recorder.record("new", AiStage.A, "m", "1.0.0", 2, 2, 2)

            val summary = recorder.usageWithin(days = 30)
            assertEquals(1, summary.calls, "31 天前的记录应被排除")
        }

    @Test
    @DisplayName("空 trace 的用量汇总不抛异常，全部为 0")
    fun emptyUsage() =
        runTest {
            val recorder = TraceRecorder(InMemoryAiTraceStore(), MutableUtcClock(1_700_000_000_000L))
            val summary = recorder.usageWithin()
            assertEquals(0, summary.calls)
            assertEquals(0.0, summary.successRate)
            assertTrue(summary.byStage.isEmpty())
            assertTrue(summary.byDay.isEmpty())
        }
}
