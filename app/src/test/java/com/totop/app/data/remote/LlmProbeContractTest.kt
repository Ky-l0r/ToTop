package com.totop.app.data.remote

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import com.totop.app.data.prefs.LlmCredentials

/**
 * 连通性校验（probe）的行为回归测试。
 *
 * 这里锁定的是历史上真实发生过的两类缺陷：
 * 1. 只要拿到 usage 就判成功 —— 正文全空也显示「连接成功」，属于伪造成功；
 * 2. 探测走非流式 + max_tokens=8 —— 思考模式模型必然失败。
 */
class LlmProbeContractTest {
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

    private fun credentials(baseUrl: String = server.url("/v1").toString()): LlmCredentials =
        LlmCredentials(baseUrl = baseUrl, apiKey = "sk-test", model = "deepseek-flash")

    private fun sse(vararg lines: String): String = lines.joinToString("") { "data: " + it + "\n\n" }

    @Test
    @DisplayName("正文有内容才算连通成功")
    fun probeSuccess() =
        runTest {
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setBody(
                        sse(
                            "{\"choices\":[{\"delta\":{\"content\":\"OK\"}}]}",
                            "{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":1}}",
                            "[DONE]",
                        ),
                    ),
            )
            val result = LlmClient(sleeper = Sleeper.NOOP).probe(credentials(), "p-ok")
            assertTrue(result is ConnectivityResult.Ok, result.toString())
            assertEquals("OK", (result as ConnectivityResult.Ok).replyPreview)
        }

    @Test
    @DisplayName("只回思考链、正文为空 —— 必须判失败，不能伪造成功")
    fun probeThinkingOnlyIsFailure() =
        runTest {
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setBody(
                        sse(
                            "{\"choices\":[{\"delta\":{\"reasoning_content\":\"hmm thinking\"}}]}",
                            "{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":40}}",
                            "[DONE]",
                        ),
                    ),
            )
            val result = LlmClient(sleeper = Sleeper.NOOP).probe(credentials(), "p-think")
            assertTrue(result is ConnectivityResult.Failed, "正文为空绝不能判成功：" + result)
            val error = (result as ConnectivityResult.Failed).error
            assertTrue(error is LlmError.EmptyCompletion, "实际类型：" + error::class.simpleName)
            assertTrue((error as LlmError.EmptyCompletion).thinkingOnly, "应识别出「只吐了思考链」")
        }

    @Test
    @DisplayName("拿到 usage 但正文为空 —— 依然判失败")
    fun probeUsageOnlyIsFailure() =
        runTest {
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setBody(
                        sse(
                            "{\"choices\":[{\"delta\":{},\"finish_reason\":\"length\"}],\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":64}}",
                            "[DONE]",
                        ),
                    ),
            )
            val result = LlmClient(sleeper = Sleeper.NOOP).probe(credentials(), "p-empty")
            assertTrue(result is ConnectivityResult.Failed)
            val error = (result as ConnectivityResult.Failed).error
            assertTrue(error is LlmError.EmptyCompletion, "实际类型：" + error::class.simpleName)
            assertEquals("length", (error as LlmError.EmptyCompletion).finishReason)
        }

    @Test
    @DisplayName("探测请求必须是流式，并显式关闭思考模式")
    fun probeRequestShape() =
        runTest {
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setBody(sse("{\"choices\":[{\"delta\":{\"content\":\"OK\"}}]}", "[DONE]")),
            )
            LlmClient(sleeper = Sleeper.NOOP).probe(credentials(), "p-shape")
            val body = server.takeRequest().body.readUtf8()
            assertTrue(body.contains("\"stream\":true"), "探测必须走流式：" + body)
            assertTrue(body.contains("\"type\":\"disabled\""), "探测必须关闭思考模式：" + body)
            assertFalse(body.contains("\"max_tokens\":8,"), "max_tokens 不能还是 8：" + body)
        }

    @Test
    @DisplayName("400 模型不存在时给出可用模型提示")
    fun probeModelMissingListsModels() =
        runTest {
            server.enqueue(
                MockResponse().setResponseCode(400)
                    .setBody("{\"error\":{\"message\":\"Model not exist.\"}}"),
            )
            val result = LlmClient(sleeper = Sleeper.NOOP).probe(credentials(), "p-400")
            assertTrue(result is ConnectivityResult.Failed)
            val error = (result as ConnectivityResult.Failed).error
            assertTrue(error is LlmError.ModelNameInvalid, "实际类型：" + error::class.simpleName)
            assertFalse(error.userMessage.contains("未归类"))
        }

    @Test
    @DisplayName("GET /models 能解析出模型 id 列表")
    fun listModelsParsesIds() =
        runTest {
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setBody(
                        "{\"data\":[{\"id\":\"deepseek-flash\"},{\"id\":\"deepseek-v4-pro\"}]}",
                    ),
            )
            val models = LlmClient(sleeper = Sleeper.NOOP).listModels(credentials(), "p-models")
            assertEquals(listOf("deepseek-flash", "deepseek-v4-pro"), models)
            val path = server.takeRequest().path
            assertTrue(path!!.endsWith("/v1/models"), "应请求 /models 端点，实际：" + path)
        }
}
