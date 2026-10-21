package com.totop.app.data

import com.totop.app.data.repo.OutboxRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** 离线补传指数退避的 JVM 单测（FR-8.2：1s / 2s / 4s，最多 3 次）。 */
class OutboxBackoffTest {
    @Test
    @DisplayName("第 1/2/3 次失败分别退避 1s / 2s / 4s")
    fun exponentialBackoff() {
        assertEquals(1_000L, OutboxRepository.backoffDelayMs(1))
        assertEquals(2_000L, OutboxRepository.backoffDelayMs(2))
        assertEquals(4_000L, OutboxRepository.backoffDelayMs(3))
        assertEquals(8_000L, OutboxRepository.backoffDelayMs(4))
    }

    @Test
    @DisplayName("最大尝试次数为 3")
    fun maxAttempts() {
        assertEquals(3, OutboxRepository.MAX_ATTEMPTS)
    }

    @Test
    @DisplayName("非法失败次数回退到基数")
    fun invalidAttempts() {
        assertEquals(OutboxRepository.BASE_DELAY_MS, OutboxRepository.backoffDelayMs(0))
        assertEquals(OutboxRepository.BASE_DELAY_MS, OutboxRepository.backoffDelayMs(-3))
    }

    @Test
    @DisplayName("退避有上限，不会因次位移溢出")
    fun cappedDelay() {
        assertEquals(OutboxRepository.MAX_DELAY_MS, OutboxRepository.backoffDelayMs(64))
        assertEquals(true, OutboxRepository.backoffDelayMs(10_000) > 0)
    }
}
