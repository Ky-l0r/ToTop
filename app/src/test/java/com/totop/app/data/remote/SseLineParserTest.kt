package com.totop.app.data.remote

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * SSE 行解析测试（开发方案 6.2：stream=true SSE）。
 *
 * 覆盖各家厂商真实行为差异：CRLF、无空格 data:、注释心跳、多行 data、
 * [DONE] 哨兵、usage 收尾帧、流内 error 帧。
 */
class SseLineParserTest {
    private fun parseAll(lines: List<String>): List<ParsedFrame> {
        val parser = SseLineParser()
        val out = mutableListOf<ParsedFrame>()
        lines.forEach { line -> parser.feed(line)?.let { out.add(it) } }
        parser.finish()?.let { out.add(it) }
        return out
    }

    @Test
    @DisplayName("标准 data 帧 + 空行分隔 + [DONE]")
    fun standardFrames() {
        val frames =
            parseAll(
                listOf(
                    "data: {\"choices\":[{\"delta\":{\"content\":\"你\"}}]}",
                    "",
                    "data: {\"choices\":[{\"delta\":{\"content\":\"好\"}}]}",
                    "",
                    "data: [DONE]",
                    "",
                ),
            )
        assertEquals(3, frames.size)
        assertTrue(frames[0] is ParsedFrame.Data)
        assertTrue(frames[2] is ParsedFrame.Done)
    }

    @Test
    @DisplayName("data: 后无空格同样可解析（部分厂商省略空格）")
    fun noSpaceAfterColon() {
        val frames = parseAll(listOf("data:{\"a\":1}", ""))
        assertEquals(1, frames.size)
        assertEquals("{\"a\":1}", (frames[0] as ParsedFrame.Data).payload)
    }

    @Test
    @DisplayName("CRLF 行尾被正确剥离")
    fun crlfLines() {
        val parser = SseLineParser()
        assertNull(parser.feed("data: {\"a\":1}\r"))
        val frame = parser.feed("\r")
        assertNotNull(frame)
        assertEquals("{\"a\":1}", (frame as ParsedFrame.Data).payload)
    }

    @Test
    @DisplayName("冒号开头为注释心跳，不产生帧")
    fun commentsIgnored() {
        val frames = parseAll(listOf(": keep-alive", "", ":ping", ""))
        assertTrue(frames.isEmpty())
    }

    @Test
    @DisplayName("同一事件内的多行 data 用换行拼接")
    fun multilineData() {
        val frames = parseAll(listOf("data: line1", "data: line2", ""))
        assertEquals(1, frames.size)
        assertEquals("line1\nline2", (frames[0] as ParsedFrame.Data).payload)
    }

    @Test
    @DisplayName("event/id/retry 字段可被容忍且不干扰 data")
    fun otherFieldsTolerated() {
        val frames = parseAll(listOf("event: message", "id: 42", "retry: 1000", "data: {\"x\":1}", ""))
        assertEquals(1, frames.size)
        assertEquals("{\"x\":1}", (frames[0] as ParsedFrame.Data).payload)
    }

    @Test
    @DisplayName("流结束前无空行时，finish() 冲刷残留数据")
    fun flushWithoutTrailingBlankLine() {
        val parser = SseLineParser()
        assertNull(parser.feed("data: {\"a\":1}"))
        val frame = parser.finish()
        assertNotNull(frame)
        assertEquals("{\"a\":1}", (frame as ParsedFrame.Data).payload)
        assertNull(parser.finish(), "重复 finish 不得重复产出")
    }

    @Test
    @DisplayName("空 data 帧被忽略，不产生空载荷")
    fun emptyPayloadIgnored() {
        val frames = parseAll(listOf("data:", ""))
        assertTrue(frames.isEmpty())
    }

    @Test
    @DisplayName("[DONE] 前后有空白也能识别")
    fun doneWithWhitespace() {
        val frames = parseAll(listOf("data:  [DONE] ", ""))
        assertTrue(frames[0] is ParsedFrame.Done)
    }

    @Test
    @DisplayName("framesParsed 计数随帧递增，便于诊断")
    fun framesCounter() {
        val parser = SseLineParser()
        parser.feed("data: a")
        parser.feed("")
        parser.feed("data: b")
        parser.feed("")
        assertEquals(2, parser.framesParsed)
    }
}

/** OpenAI 兼容报文解析测试。 */
class OpenAiWireTest {
    @Test
    @DisplayName("增量 delta.content 被解析为 Delta")
    fun parseDelta() {
        val chunk = OpenAiWire.parseChunk("{\"choices\":[{\"delta\":{\"content\":\"所以\"},\"index\":0}]}")
        assertEquals(LlmChunk.Delta("所以"), chunk)
    }

    @Test
    @DisplayName("finish_reason 非空时产出 Finished 并带上 usage")
    fun parseFinished() {
        val json =
            "{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]," +
                "\"usage\":{\"prompt_tokens\":120,\"completion_tokens\":80}}"
        val chunk = OpenAiWire.parseChunk(json) as LlmChunk.Finished
        assertEquals("stop", chunk.finishReason)
        assertEquals(120, chunk.usage.inputTokens)
        assertEquals(80, chunk.usage.outputTokens)
    }

    @Test
    @DisplayName("只带 usage 的收尾帧（stream_options.include_usage）")
    fun usageOnlyFrame() {
        val chunk = OpenAiWire.parseChunk("{\"choices\":[],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":20}}")
        assertTrue(chunk is LlmChunk.Finished)
        assertEquals(30, (chunk as LlmChunk.Finished).usage.totalTokens)
    }

    @Test
    @DisplayName("空 delta 且无 finish_reason 返回 null，不产生噪声事件")
    fun emptyDeltaIgnored() {
        assertNull(OpenAiWire.parseChunk("{\"choices\":[{\"delta\":{}}]}"))
        assertNull(OpenAiWire.parseChunk("{\"choices\":[]}"))
    }

    @Test
    @DisplayName("非法 JSON 判为 MalformedResponse，不抛异常")
    fun malformedJson() {
        val chunk = OpenAiWire.parseChunk("not-a-json") as LlmChunk.Failed
        assertTrue(chunk.error is LlmError.MalformedResponse)
    }

    @Test
    @DisplayName("流内 error 帧按关键字分类")
    fun errorFrameClassification() {
        val invalidKey =
            OpenAiWire.parseChunk(
                "{\"error\":{\"code\":\"invalid_api_key\",\"message\":\"Incorrect API key provided\"}}",
            ) as LlmChunk.Failed
        assertTrue(invalidKey.error is LlmError.InvalidKey)

        val quota =
            OpenAiWire.parseChunk(
                "{\"error\":{\"code\":\"insufficient_quota\",\"message\":\"You exceeded your current quota\"}}",
            ) as LlmChunk.Failed
        assertTrue(quota.error is LlmError.InsufficientBalance)

        val rate =
            OpenAiWire.parseChunk(
                "{\"error\":{\"type\":\"rate_limit_exceeded\",\"message\":\"slow down\"}}",
            ) as LlmChunk.Failed
        assertTrue(rate.error is LlmError.RateLimited)
    }

    @Test
    @DisplayName("非流式响应体可提取文本与 usage")
    fun nonStreamingBody() {
        val body =
            "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"OK\"}}]," +
                "\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":1}}"
        assertEquals("OK", OpenAiWire.extractMessageText(body))
        assertEquals(5, OpenAiWire.extractUsage(body)?.inputTokens)
        assertNull(OpenAiWire.errorInBody(body))
    }

    @Test
    @DisplayName("非流式 200 但带 error 视为失败")
    fun errorInBody() {
        val body = "{\"error\":{\"code\":\"invalid_api_key\",\"message\":\"bad key\"}}"
        assertTrue(OpenAiWire.errorInBody(body) is LlmError.InvalidKey)
    }

    @Test
    @DisplayName("请求体包含 stream/usage/model 且消息结构为多模态数组")
    fun requestBodyShape() {
        val config =
            ChatRequestConfig(
                model = "qwen-vl-plus",
                messages =
                    listOf(
                        ChatMessage.system("系统提示"),
                        ChatMessage.userWithImage("看图", "BASE64"),
                    ),
                maxTokens = 1024,
            )
        val json = config.toJson()
        assertEquals(true, (json["stream"] as kotlinx.serialization.json.JsonPrimitive).content.toBoolean())
        assertEquals("qwen-vl-plus", (json["model"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertNotNull(json["stream_options"])
        assertNotNull(json["response_format"])

        val messages = json["messages"] as JsonArray
        assertEquals(2, messages.size)
        val userContent = (messages[1] as JsonObject)["content"] as JsonArray
        assertEquals(2, userContent.size)
        val imageUrl = (userContent[1] as JsonObject)["image_url"] as JsonObject
        assertEquals(
            "data:image/jpeg;base64,BASE64",
            (imageUrl["url"] as kotlinx.serialization.json.JsonPrimitive).content,
        )
    }

    @Test
    @DisplayName("连通性校验请求：流式、关闭思考、无 response_format、预算足够")
    fun probeConfig() {
        val base = ChatRequestConfig(model = "m", messages = listOf(ChatMessage.user("hi")), maxTokens = 2048)
        val probe = base.asProbe()
        // max_tokens 曾为 8，思考模式会把它吃光导致正文为空，误判为连接失败
        assertEquals(ChatRequestConfig.PROBE_MAX_TOKENS, probe.maxTokens)
        assertTrue(probe.maxTokens >= 64, "探测预算必须够思考链之外留出正文")
        // 非流式会被部分厂商直接拒绝
        assertTrue(probe.stream, "探测必须走流式")
        assertFalse(probe.jsonObject)
        assertEquals(0.0, probe.temperature)
        assertEquals(ChatRequestConfig.THINKING_DISABLED, probe.thinkingMode, "探测应关闭思考模式")
    }
}
