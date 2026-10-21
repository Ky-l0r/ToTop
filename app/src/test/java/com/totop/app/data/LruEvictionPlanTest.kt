package com.totop.app.data

import com.totop.app.data.repo.CacheKey
import com.totop.app.data.repo.CacheRepository
import com.totop.app.data.repo.LruEvictionPlan
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** 缓存 LRU 淘汰与缓存键的 JVM 单测（100MB 上限、TTL、提示词版本失效）。 */
class LruEvictionPlanTest {
    private fun candidate(
        name: String,
        size: Long,
    ) = LruEvictionPlan.Candidate(sha256 = name, promptVer = "1.0.0", model = "m", sizeBytes = size)

    @Test
    @DisplayName("未超过上限时不淘汰")
    fun noEvictionUnderLimit() {
        val plan =
            LruEvictionPlan.plan(
                totalBytes = 90,
                maxBytes = 100,
                targetBytes = 90,
                candidates = listOf(candidate("a", 50), candidate("b", 50)),
            )
        assertTrue(plan.victims.isEmpty())
        assertEquals(90L, plan.remainingBytes)
        assertEquals(0L, plan.freedBytes)
    }

    @Test
    @DisplayName("超过上限时按 LRU 顺序淘汰到目标值")
    fun evictsLeastRecentlyUsedFirst() {
        val plan =
            LruEvictionPlan.plan(
                totalBytes = 300,
                maxBytes = 100,
                targetBytes = 90,
                candidates = listOf(candidate("oldest", 60), candidate("middle", 60), candidate("newest", 60)),
            )
        // 300 -> 240 -> 180 -> 120，候选耗尽仍未回到目标 90，按最久未访问优先全部淘汰
        assertEquals(listOf("oldest", "middle", "newest"), plan.victims.map { it.sha256 })
        assertEquals(180L, plan.freedBytes)
        assertEquals(120L, plan.remainingBytes)
        assertEquals(3, plan.evicted)
    }

    @Test
    @DisplayName("目标值高于上限时被夹到上限")
    fun targetClampedToMax() {
        val plan =
            LruEvictionPlan.plan(
                totalBytes = 150,
                maxBytes = 100,
                targetBytes = 500,
                candidates = listOf(candidate("a", 60)),
            )
        assertEquals(1, plan.victims.size)
        assertEquals(90L, plan.remainingBytes)
    }

    @Test
    @DisplayName("候选不足时尽力而为，不抛异常")
    fun candidatesExhausted() {
        val plan =
            LruEvictionPlan.plan(
                totalBytes = 1_000,
                maxBytes = 100,
                targetBytes = 90,
                candidates = listOf(candidate("only", 10)),
            )
        assertEquals(1, plan.victims.size)
        assertEquals(990L, plan.remainingBytes)
    }

    @Test
    @DisplayName("负的 sizeBytes 按 0 处理，不会把总量算多")
    fun negativeSizeTreatedAsZero() {
        val plan =
            LruEvictionPlan.plan(
                totalBytes = 200,
                maxBytes = 100,
                targetBytes = 50,
                candidates = listOf(candidate("broken", -50), candidate("real", 120)),
            )
        assertEquals(0L, plan.victims[0].sizeBytes.coerceAtLeast(0L))
        assertEquals(80L, plan.remainingBytes)
    }

    @Test
    @DisplayName("磁盘上限常量为 100MB")
    fun maxBytesIs100Mb() {
        assertEquals(100L * 1024L * 1024L, CacheRepository.MAX_BYTES)
        assertEquals(7L * 24L * 60L * 60L * 1000L, CacheRepository.TTL_MS)
    }

    @Test
    @DisplayName("缓存键三段均不得为空")
    fun cacheKeyRequiresAllParts() {
        assertThrows<IllegalArgumentException> { CacheKey("", "1.0.0", "m") }
        assertThrows<IllegalArgumentException> { CacheKey("s", " ", "m") }
        assertThrows<IllegalArgumentException> { CacheKey("s", "1.0.0", "") }
        val key = CacheKey("s", "1.0.0", "qwen-max")
        assertEquals("s", key.sha256)
    }
}
