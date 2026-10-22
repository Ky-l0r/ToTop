package com.totop.app.data.remote

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 错误分类回归测试。
 *
 * 覆盖的核心缺陷：
 * 1. 百炼等厂商用 400 表达「模型不存在」，旧代码只识别 404，导致掉进 Unknown
 *    并显示「未归类问题」；
 * 2. 图片能力相关的 400 必须能区分「模型不能看图」与「图片本身不合规」；
 * 3. 主线程网络异常必须独立分类，不能被 Unknown 吞掉。
 */
class LlmErrorClassificationTest {
    /** 百炼原文：Model not exist. */
    @Test
    @DisplayName("400 + Model not exist 判为模型名错误，而不是未归类")
    fun bailianModelMissing() {
        val error =
            LlmErrorMapper.fromHttp(
                status = 400,
                body = "{\"error\":{\"code\":\"invalid_parameter_error\",\"message\":\"Model not exist.\"}}",
                model = "deepseek-flash",
            )
        assertTrue(error is LlmError.ModelNameInvalid, "实际类型：" + error::class.simpleName)
        val detail = error as LlmError.ModelNameInvalid
        assertEquals("deepseek-flash", detail.model)
        assertFalse(detail.isRetryable, "模型名错误重试无意义")
        // 文案必须带上模型名，且不能出现「未归类」这类无信息量表述
        assertTrue(detail.userMessage.contains("deepseek-flash"))
        assertFalse(detail.userMessage.contains("未归类"))
    }

    /** DeepSeek 官方原文：Model Not Exist。 */
    @Test
    @DisplayName("400 + Model Not Exist（DeepSeek 官方措辞）同样能识别")
    fun deepseekModelMissing() {
        val error =
            LlmErrorMapper.fromHttp(
                status = 400,
                body = "{\"error\":{\"message\":\"Model Not Exist\"}}",
                model = "deepseek-flsh",
            )
        assertTrue(error is LlmError.ModelNameInvalid, "实际类型：" + error::class.simpleName)
    }

    @Test
    @DisplayName("400 + 非流式思考模式限制 判为参数不支持")
    fun thinkingRequiresStream() {
        val error =
            LlmErrorMapper.fromHttp(
                status = 400,
                body =
                    "{\"error\":{\"message\":\"parameter.enable_thinking must be set to " +
                        "false for non-streaming calls\"}}",
            )
        assertTrue(error is LlmError.ParameterNotSupported, "实际类型：" + error::class.simpleName)
        assertTrue(error.isRetryable, "参数类错误应允许降级重试")
    }

    @Test
    @DisplayName("400 + 模型不支持图片 判为图片输入不支持，且多页时提示页数")
    fun imageNotSupported() {
        val error =
            LlmErrorMapper.fromHttp(
                status = 400,
                body = "{\"error\":{\"message\":\"This model does not support image input\"}}",
                model = "deepseek-chat",
                pageCount = 3,
            )
        assertTrue(error is LlmError.ImageInputNotSupported, "实际类型：" + error::class.simpleName)
        assertEquals(3, (error as LlmError.ImageInputNotSupported).pageCount)
        assertTrue(error.userMessage.contains("3"), "多页时必须告知用户页数")
    }

    @Test
    @DisplayName("400 + 图片格式问题 判为图片不合规，与「模型不能看图」区分开")
    fun imageFormatRejected() {
        val error =
            LlmErrorMapper.fromHttp(
                status = 400,
                body = "{\"error\":{\"message\":\"invalid image format\"}}",
            )
        assertTrue(error is LlmError.ImageFormatRejected, "实际类型：" + error::class.simpleName)
    }

    @Test
    @DisplayName("400 + 上下文超限 判为长度超限")
    fun contextTooLong() {
        val error =
            LlmErrorMapper.fromHttp(
                status = 400,
                body = "{\"error\":{\"message\":\"maximum context length exceeded\"}}",
            )
        assertTrue(error is LlmError.ContextLengthExceeded, "实际类型：" + error::class.simpleName)
    }

    @Test
    @DisplayName("400 但无可识别特征：仍保留 HTTP 码，不静默丢失信息")
    fun unknownBadRequestKeepsStatus() {
        val error =
            LlmErrorMapper.fromHttp(status = 400, body = "{\"error\":{\"message\":\"weird\"}}")
        assertTrue(error is LlmError.Unknown)
        assertEquals(400, error.httpStatus, "必须带上 HTTP 码供排查")
        assertTrue(error.userMessage.contains("400"), "文案必须显示 HTTP 码")
    }

    @Test
    @DisplayName("主线程网络异常 必须独立分类，不能落入未归类")
    fun mainThreadNetwork() {
        val error = LlmErrorMapper.fromThrowable(android.os.NetworkOnMainThreadException())
        assertTrue(error is LlmError.NetworkOnMainThread, "实际类型：" + error::class.simpleName)
        assertFalse(error.isRetryable)
    }

    @Test
    @DisplayName("空回复分类带出截断与只思考两种成因")
    fun emptyCompletionMessages() {
        val truncated = LlmError.EmptyCompletion(finishReason = "length")
        assertTrue(truncated.userMessage.contains("截断"))
        val thinkingOnly = LlmError.EmptyCompletion(thinkingOnly = true)
        assertTrue(thinkingOnly.userMessage.contains("思考"))
        assertTrue(truncated.isRetryable, "空回复应允许重试")
    }
}
