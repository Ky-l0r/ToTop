package com.totop.app.data.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

/**
 * 异常分类映射测试（开发方案 6.1：失败必须给出具体原因，禁止只报「请求失败」）。
 */
class LlmErrorMapperTest {
    @ParameterizedTest(name = "HTTP {0} -> {1}")
    @CsvSource(
        "401, invalid_key",
        "403, invalid_key",
        "402, insufficient_balance",
        "429, rate_limited",
        "500, server_error",
        "503, server_error",
    )
    @DisplayName("HTTP 状态码分类矩阵")
    fun httpStatusMapping(
        status: Int,
        expectedKind: String,
    ) {
        val error = LlmErrorMapper.fromHttp(status = status, body = "{}")
        assertEquals(expectedKind, error.kindCode)
    }

    @Test
    @DisplayName("429 且响应体含额度关键字 -> 余额不足（不可重试）")
    fun rateLimitedVersusQuota() {
        val quota = LlmErrorMapper.fromHttp(429, "{\"error\":{\"code\":\"insufficient_quota\"}}")
        assertTrue(quota is LlmError.InsufficientBalance)
        assertFalse(quota.isRetryable, "余额不足重试无意义")

        val plainRateLimit = LlmErrorMapper.fromHttp(429, "{\"error\":{\"message\":\"too many requests\"}}")
        assertTrue(plainRateLimit is LlmError.RateLimited)
        assertTrue(plainRateLimit.isRetryable, "限流应退避重试")
    }

    @Test
    @DisplayName("404 + model 关键字 -> 模型名错误；否则 -> Base URL 错误")
    fun notFoundDisambiguation() {
        val modelError =
            LlmErrorMapper.fromHttp(
                status = 404,
                body = "{\"error\":{\"message\":\"The model qwen-x does not exist\"}}",
                model = "qwen-x",
                baseUrl = "https://api.example.com/v1",
            )
        assertTrue(modelError is LlmError.ModelNotFound)
        assertTrue(modelError.userMessage.contains("qwen-x"), modelError.userMessage)

        val urlError =
            LlmErrorMapper.fromHttp(
                status = 404,
                body = "<html>404 Not Found</html>",
                model = "qwen-plus",
                baseUrl = "https://api.example.com/v1",
            )
        assertTrue(urlError is LlmError.BaseUrlInvalid)
        assertTrue(urlError.userMessage.contains("https://api.example.com/v1"), urlError.userMessage)
    }

    @Test
    @DisplayName("每条中文原因都具体可执行，不出现「请求失败」这类空话")
    fun messagesAreSpecific() {
        val errors =
            listOf(
                LlmErrorMapper.fromHttp(401, ""),
                LlmErrorMapper.fromHttp(402, ""),
                LlmErrorMapper.fromHttp(404, ""),
                LlmErrorMapper.fromHttp(500, ""),
                LlmErrorMapper.fromThrowable(SocketTimeoutException("connect timed out")),
                LlmErrorMapper.fromThrowable(UnknownHostException("api.example.com")),
                LlmErrorMapper.fromThrowable(ConnectException("Connection refused")),
                LlmErrorMapper.fromThrowable(SSLHandshakeException("handshake failed")),
                LlmErrorMapper.fromThrowable(IOException("unexpected end of stream")),
            )
        errors.forEach { error ->
            assertFalse(error.userMessage.isBlank())
            assertFalse(error.userMessage.contains("请求失败"), error.userMessage)
            assertTrue(error.userMessage.length >= 10, error.userMessage)
        }
    }

    @Test
    @DisplayName("超时分类：connect 关键字 -> 网络不通；否则 -> 读超时")
    fun timeoutClassification() {
        val connect = LlmErrorMapper.fromThrowable(SocketTimeoutException("connect timed out"))
        assertTrue(connect is LlmError.NetworkUnavailable)
        assertTrue(connect.isRetryable)

        val read = LlmErrorMapper.fromThrowable(SocketTimeoutException("timeout"))
        assertTrue(read is LlmError.Timeout)
        assertTrue(read.isRetryable)
    }

    @Test
    @DisplayName("DNS 解析失败 -> Base URL 错误（而不是笼统的网络不通）")
    fun unknownHost() {
        val error = LlmErrorMapper.fromThrowable(UnknownHostException("api.wrong-host.com"))
        assertTrue(error is LlmError.BaseUrlInvalid)
        assertTrue(error.userMessage.contains("/v1"), error.userMessage)
    }

    @Test
    @DisplayName("用户取消不重试，且提示文案中性")
    fun cancellation() {
        val fromCancellation = LlmErrorMapper.fromThrowable(CancellationException("cancelled"))
        assertTrue(fromCancellation is LlmError.Cancelled)
        assertFalse(fromCancellation.isRetryable)

        val fromInterrupted = LlmErrorMapper.fromThrowable(InterruptedIOException("interrupted"))
        assertTrue(fromInterrupted is LlmError.Cancelled)
    }

    @Test
    @DisplayName("LlmException 透传原始分类，不二次包装")
    fun llmExceptionPassthrough() {
        val original = LlmError.InvalidKey(status = 401)
        val mapped = LlmErrorMapper.fromThrowable(LlmException(original))
        assertEquals(original, mapped)
    }

    @Test
    @DisplayName("响应体脱敏：sk- 开头的 Key 与 Bearer 头内容被抹掉")
    fun redaction() {
        val raw = "{\"error\":\"invalid key sk-abcdefgh12345678\",\"header\":\"Bearer abcdefgh12345678\"}"
        val redacted = LlmErrorMapper.redact(raw)
        assertFalse(redacted.contains("sk-abcdefgh12345678"), redacted)
        assertFalse(redacted.contains("Bearer abcdefgh12345678"), redacted)
    }

    @Test
    @DisplayName("errorMsg 写入 trace 时带分类码，便于统计成功率")
    fun traceMessageContainsKind() {
        val error = LlmErrorMapper.fromHttp(401, "")
        assertTrue(error.traceMessage().startsWith("invalid_key："), error.traceMessage())
    }

    @Test
    @DisplayName("可重试分类集合固定：401/402/404/取消 不重试，其余可重试")
    fun retryableMatrix() {
        assertFalse(LlmError.InvalidKey().isRetryable)
        assertFalse(LlmError.InsufficientBalance(status = 402).isRetryable)
        assertFalse(LlmError.ModelNotFound(model = "m").isRetryable)
        assertFalse(LlmError.BaseUrlInvalid(baseUrl = "u").isRetryable)
        assertFalse(LlmError.Cancelled().isRetryable)
        assertTrue(LlmError.NetworkUnavailable().isRetryable)
        assertTrue(LlmError.Timeout().isRetryable)
        assertTrue(LlmError.ServerError(status = 500).isRetryable)
        assertTrue(LlmError.RateLimited().isRetryable)
        assertTrue(LlmError.MalformedResponse().isRetryable)
    }
}

/** 退避重试测试（1s/2s/4s 最多 3 次，仅幂等请求）。 */
class RetryPolicyTest {
    @Test
    @DisplayName("可重试错误按 1s/2s/4s 退避，共 4 次尝试后成功")
    fun retriesThenSucceeds() {
        val sleeps = mutableListOf<Long>()
        var attempts = 0
        runTest {
            // runTest 的返回值是 TestResult，因此断言写在协程内部，直接断言 execute 的返回值
            val value =
                RetryPolicy.DEFAULT.execute(
                    allowRetry = true,
                    sleep = Sleeper.recording(sleeps),
                ) { attempt ->
                    attempts = attempt
                    if (attempt < 4) throw LlmException(LlmError.NetworkUnavailable())
                    "ok"
                }
            assertEquals("ok", value)
            assertEquals(4, attempts)
            assertEquals(listOf(1_000L, 2_000L, 4_000L), sleeps)
        }
    }

    @Test
    @DisplayName("重试耗尽后抛出最后一次错误")
    fun exhaustsRetries() {
        val sleeps = mutableListOf<Long>()
        val failure =
            org.junit.jupiter.api.Assertions.assertThrows(LlmException::class.java) {
                runTest {
                    RetryPolicy.DEFAULT.execute(allowRetry = true, sleep = Sleeper.recording(sleeps)) {
                        throw LlmException(LlmError.ServerError(status = 503))
                    }
                }
            }
        assertTrue(failure.error is LlmError.ServerError)
        assertEquals(listOf(1_000L, 2_000L, 4_000L), sleeps)
    }

    @Test
    @DisplayName("不可重试错误立即失败，不退避")
    fun nonRetryableStopsImmediately() {
        val sleeps = mutableListOf<Long>()
        var attempts = 0
        val failure =
            org.junit.jupiter.api.Assertions.assertThrows(LlmException::class.java) {
                runTest {
                    RetryPolicy.DEFAULT.execute(allowRetry = true, sleep = Sleeper.recording(sleeps)) {
                        attempts++
                        throw LlmException(LlmError.InvalidKey())
                    }
                }
            }
        assertTrue(failure.error is LlmError.InvalidKey)
        assertEquals(1, attempts)
        assertTrue(sleeps.isEmpty())
    }

    @Test
    @DisplayName("非幂等调用只尝试一次")
    fun nonIdempotentSingleAttempt() {
        var attempts = 0
        val failure =
            org.junit.jupiter.api.Assertions.assertThrows(LlmException::class.java) {
                runTest {
                    RetryPolicy.DEFAULT.execute(allowRetry = false, sleep = Sleeper.NOOP) {
                        attempts++
                        throw LlmException(LlmError.NetworkUnavailable())
                    }
                }
            }
        assertTrue(failure.error is LlmError.NetworkUnavailable)
        assertEquals(1, attempts)
    }

    @Test
    @DisplayName("取消异常直接向上传播，不重试不包装")
    fun cancellationPropagates() {
        var attempts = 0
        org.junit.jupiter.api.Assertions.assertThrows(CancellationException::class.java) {
            runTest {
                RetryPolicy.DEFAULT.execute(allowRetry = true, sleep = Sleeper.NOOP) {
                    attempts++
                    throw CancellationException("cancelled")
                }
            }
        }
        assertEquals(1, attempts)
    }

    @Test
    @DisplayName("退避序列不足时复用最后一个值，不越界")
    fun delayForAttemptSaturates() {
        val policy = RetryPolicy(delaysMillis = listOf(500L), maxRetries = 3)
        assertEquals(500L, policy.delayForAttempt(1))
        assertEquals(500L, policy.delayForAttempt(9))
    }

    @Test
    @DisplayName("RetryPolicy.NONE 不重试")
    fun nonePolicy() {
        assertEquals(0, RetryPolicy.NONE.maxRetries)
        assertEquals(0L, RetryPolicy.NONE.delayForAttempt(1))
    }
}
