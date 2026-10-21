package com.totop.app.data.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * 退避重试策略（开发方案 12.1 第 4 条：失败退避重试）。
 *
 * 默认序列 1s / 2s / 4s，最多 3 次重试（即最多 4 次尝试）。
 * 约束（必须全部满足才重试）：
 * 1. 错误分类 [LlmError.isRetryable] 为 true（网络不通 / 超时 / 5xx / 429 / 结构异常）；
 * 2. 调用方显式声明本次请求幂等（[RetryPolicy.allowRetry]）；
 * 3. 未被用户取消（CancellationException 不重试，直接抛给上层）。
 *
 * 计时使用可注入的 [Sleeper]，单测无需真实等待。
 */
data class RetryPolicy(
    /** 每次重试前的等待序列（毫秒）。 */
    val delaysMillis: List<Long> = DEFAULT_DELAYS,
    /** 最大重试次数（不含首次尝试）。 */
    val maxRetries: Int = DEFAULT_MAX_RETRIES,
) {
    init {
        require(maxRetries >= 0) { "maxRetries 不得为负，实际为：" + maxRetries }
        require(delaysMillis.all { it >= 0 }) { "退避时长不得为负" }
    }

    /** 第 [attempt] 次重试前应等待的毫秒数（attempt 从 1 开始）。 */
    fun delayForAttempt(attempt: Int): Long {
        if (delaysMillis.isEmpty()) return 0L
        val index = (attempt - 1).coerceIn(0, delaysMillis.size - 1)
        return delaysMillis[index]
    }

    /**
     * 执行带退避重试的幂等调用。
     *
     * @param allowRetry 该调用是否幂等可重试（读取类、带 requestId 的写类为 true）
     * @param sleep 等待实现，测试可注入记录器
     * @param block 实际调用；抛 [LlmException] 表示分类失败
     * @return 调用结果
     * @throws LlmException 重试耗尽或不可重试的错误
     */
    suspend fun <T> execute(
        allowRetry: Boolean,
        sleep: Sleeper = Sleeper.REAL,
        onRetry: ((attempt: Int, error: LlmError, delayMillis: Long) -> Unit)? = null,
        block: suspend (attempt: Int) -> T,
    ): T {
        val totalAttempts = if (allowRetry) maxRetries + 1 else 1
        var lastError: LlmError? = null
        for (attempt in 1..totalAttempts) {
            try {
                return block(attempt)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (exception: LlmException) {
                lastError = exception.error
                val canRetry = attempt < totalAttempts && exception.error.isRetryable
                if (!canRetry) throw exception
                val waitMillis = delayForAttempt(attempt)
                onRetry?.invoke(attempt, exception.error, waitMillis)
                sleep.sleep(waitMillis)
            }
        }
        throw LlmException(lastError ?: LlmError.Unknown(detail = "重试耗尽"))
    }

    companion object {
        /** 1s / 2s / 4s。 */
        val DEFAULT_DELAYS: List<Long> = listOf(1_000L, 2_000L, 4_000L)

        /** 最多 3 次重试。 */
        const val DEFAULT_MAX_RETRIES: Int = 3

        val DEFAULT: RetryPolicy = RetryPolicy()

        /** 不重试（用于非幂等或用户显式中止的场景）。 */
        val NONE: RetryPolicy = RetryPolicy(delaysMillis = emptyList(), maxRetries = 0)
    }
}

/** 退避等待抽象，便于单测跳过真实延时。 */
fun interface Sleeper {
    suspend fun sleep(millis: Long)

    companion object {
        /** 真实等待。 */
        val REAL: Sleeper = Sleeper { millis -> if (millis > 0) delay(millis) }

        /** 记录等待时长但不实际等待。 */
        fun recording(sink: MutableList<Long>): Sleeper = Sleeper { millis -> sink.add(millis) }

        /** 零等待。 */
        val NOOP: Sleeper = Sleeper { }
    }
}
