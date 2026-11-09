package com.totop.app.ui.reviewmaterial

import com.totop.app.data.prefs.LlmCredentials
import com.totop.app.data.remote.FakeTransport
import com.totop.app.data.remote.LlmChunk
import com.totop.app.data.remote.LlmError
import com.totop.app.data.remote.TokenUsage
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 复习资料网关测试。
 *
 * 重点锁定三个历史缺陷的回归：
 * 1. 模型带 `\sqrt` 等 LaTeX 的输出曾被直接判为「不是合法 JSON」；
 * 2. 本地解析失败从不重试，界面却声称「已自动重试」；
 * 3. 截断输出被笼统报成解析失败，换提示词重试只会再截一次。
 */
class ReviewMaterialGatewayTest {
    private fun credentials() =
        LlmCredentials(
            baseUrl = "https://api.example.com/v1",
            apiKey = "sk-test",
            model = "qwen-plus",
            textModel = "qwen-plus",
            visionModel = "qwen-vl-plus",
        )

    /** 生成并取唯一结果（网关只 send 一次）。 */
    private suspend fun singleOutcome(
        transport: FakeTransport,
        systemPrompt: String = "复习资料教研模板",
    ): ReviewMaterialResult {
        val gateway = ReviewMaterialGateway(transport)
        val outcomes = mutableListOf<ReviewMaterialResult>()
        gateway
            .generate(
                credentials = credentials(),
                subjectLabel = "数学",
                mistakes = emptyList(),
                requestId = "req-review",
                systemPrompt = systemPrompt,
            ).collect { outcomes.add(it) }
        assertEquals(1, outcomes.size, "网关最终只应产出一次结果")
        return outcomes.single()
    }

    @Test
    @DisplayName("字符串里的 \\sqrt 非法转义被抢救成功，公式原文保留且只调用一次")
    fun salvagesLatexEscapes() =
        runTest {
            val transport = FakeTransport()
            // 普通字符串里 "\\sqrt" 的运行时值是「单反斜杠 + s」，正是模型写 LaTeX
            // 时会犯的非法转义；旧实现整份被判非法，抢救器应把公式原样救回来
            transport.enqueueText(
                "{\"summary\":\"本章要点\",\"sections\":[{\"title\":\"二次根式\"," +
                    "\"blocks\":[{\"style\":\"paragraph\",\"lines\":[\"利用 \\sqrt{2} 化简，注意分母有理化\"]}]}]}",
            )
            val outcome = singleOutcome(transport)
            val material = (outcome as ReviewMaterialResult.Success).material
            assertTrue(material.sections.first().blocks.first().lines.first().contains("\\sqrt{2}"))
            assertEquals(1, transport.calls.size, "抢救成功就不应再花第二次钱")
        }

    @Test
    @DisplayName("对象尾逗号被抢救成功，只调用一次")
    fun salvagesTrailingComma() =
        runTest {
            val transport = FakeTransport()
            // 用普通字符串拆两行（三引号里切分会以引号开头，反而难读）；
            // sections 数组末尾多一个逗号，是模型最常见的尾逗号缺陷
            transport.enqueueText(
                "{\"summary\":\"要点\",\"sections\":[{\"title\":\"数列\"," +
                    "\"blocks\":[{\"style\":\"paragraph\",\"lines\":[\"等差数列求和\"]}]}],}",
            )
            val outcome = singleOutcome(transport)
            assertTrue(outcome is ReviewMaterialResult.Success, outcome.toString())
            assertEquals(1, transport.calls.size)
        }

    @Test
    @DisplayName("首次输出纯文本时追加严格 JSON 要求真重试一次；第二次请求其余参数不变")
    fun strictJsonRetryOnce() =
        runTest {
            val transport = FakeTransport()
            transport.enqueueText("好的，下面为你整理这份复习资料……（不是 JSON）")
            transport.enqueueText(
                """{"summary":"要点","sections":[{"title":"函数","blocks":[{"style":"paragraph","lines":["先看定义域"]}]}]}""",
            )
            val outcome = singleOutcome(transport)

            assertTrue(outcome is ReviewMaterialResult.Success, "第二次应成功")
            assertEquals(2, transport.calls.size, "严格 JSON 重试恰好一次")
            val second = transport.calls[1]
            assertTrue(
                second.systemPrompt.endsWith(ReviewMaterialGateway.STRICT_JSON_SUFFIX.trimStart()),
                "第二次请求必须带上严格 JSON 后缀",
            )
            assertTrue(
                second.systemPrompt.startsWith("复习资料教研模板"),
                "严格重试只是追加要求，原模板不能被替换",
            )
        }

    @Test
    @DisplayName("两次都不可解析：恰好两次调用，最终如实报告已重试")
    fun doubleJsonFailureStops() =
        runTest {
            val transport = FakeTransport()
            transport.enqueueText("还是不是 JSON", "依然不是 JSON")
            val outcome = singleOutcome(transport)

            assertTrue(outcome is ReviewMaterialResult.Failure)
            val error = (outcome as ReviewMaterialResult.Failure).error
            assertTrue(error is LlmError.MalformedResponse)
            assertTrue(error.userMessage.contains("已自动"), "确实重试过，文案如实说明")
            assertEquals(2, transport.calls.size, "不再原样第三次重发")
        }

    @Test
    @DisplayName("finish_reason=length：不换提示词重试（同预算只会再截一次），如实告知截断")
    fun truncationDoesNotRetry() =
        runTest {
            val transport = FakeTransport()
            transport.scripts.add {
                flow {
                    emit(LlmChunk.Delta("""{"summary":"很长的开头","sections":[{"title":"函数"""))
                    emit(LlmChunk.Finished(TokenUsage(inputTokens = 100, outputTokens = 9000), "length"))
                }
            }
            val outcome = singleOutcome(transport)

            assertTrue(outcome is ReviewMaterialResult.Failure)
            val error = (outcome as ReviewMaterialResult.Failure).error
            assertTrue(error is LlmError.MalformedResponse, error.toString())
            assertTrue((error as LlmError.MalformedResponse).truncated)
            assertFalse(error.afterRetries, "复习资料没有加预算重试，不得谎称已重试")
            assertEquals(1, transport.calls.size)
        }

    @Test
    @DisplayName("括号配平兜底：finish_reason 不可靠时仍能识别截断，不浪费严格重试")
    fun silentTruncationDetected() =
        runTest {
            val transport = FakeTransport()
            // 不少中转把 length 写成 stop；残 JSON 靠 looksTruncated 识别
            transport.enqueueText("""{"summary":"要点","sections":[{"title":"函数","blocks":[{"lines":["断在半路""")
            val outcome = singleOutcome(transport)

            val error = (outcome as ReviewMaterialResult.Failure).error
            assertTrue(error is LlmError.MalformedResponse, error.toString())
            assertTrue((error as LlmError.MalformedResponse).truncated)
            assertEquals(1, transport.calls.size, "截断不做严格 JSON 重试")
        }
}
