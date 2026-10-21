package com.totop.app.work

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** 磁盘缓存清理的纯函数测试（开发方案 5.2 / FR-8.4：磁盘上限 100MB）。 */
class CacheCleanupPlanTest {
    private fun file(
        path: String,
        size: Long,
        lastAccess: Long,
        thumb: Boolean = false,
    ) = CacheCleanupPlan.FileSnapshot(path, size, lastAccess, thumb)

    @Test
    @DisplayName("未超上限时不删任何文件")
    fun underLimit() {
        val files = listOf(file("a.jpg", 10, 1), file("b.jpg", 20, 2))
        val plan = CacheCleanupPlan.plan(files, maxBytes = 100, targetBytes = 85)
        assertFalse(plan.needed)
        assertEquals(0, plan.deletedCount)
        assertEquals(30L, plan.totalBytesBefore)
        assertEquals(30L, plan.remainingBytes)
    }

    @Test
    @DisplayName("超上限时按 LRU 删除直到回落到目标")
    fun evictsUntilTarget() {
        val files = (1..10).map { file("f" + it + ".jpg", 10, it.toLong()) }
        val plan = CacheCleanupPlan.plan(files, maxBytes = 50, targetBytes = 20)
        assertTrue(plan.needed)
        assertEquals(100L, plan.totalBytesBefore)
        assertTrue(plan.remainingBytes <= 20L)
        assertEquals(80L, plan.freedBytes)
        // 最久未访问的在最前
        assertEquals("f1.jpg", plan.victims.first().path)
    }

    @Test
    @DisplayName("缩略图优先于原图被回收（原图是唯一数据来源）")
    fun thumbnailsFirst() {
        val files =
            listOf(
                file("origin1.jpg", 50, 100), // 更久未访问，但它是原图
                file("thumb1.jpg", 50, 200, thumb = true), // 更近访问，但它是缩略图
                file("origin2.jpg", 50, 50),
            )
        val plan = CacheCleanupPlan.plan(files, maxBytes = 100, targetBytes = 50)
        assertEquals("thumb1.jpg", plan.victims.first().path)
    }

    @Test
    @DisplayName("同类内按最后访问时间升序删除")
    fun lruOrderWithinKind() {
        val files =
            listOf(
                file("new.jpg", 40, 900),
                file("old.jpg", 40, 100),
                file("mid.jpg", 40, 500),
            )
        val plan = CacheCleanupPlan.plan(files, maxBytes = 80, targetBytes = 40)
        assertEquals(listOf("old.jpg", "mid.jpg"), plan.victims.map { it.path })
    }

    @Test
    @DisplayName("单次删除数量有上限，不会一次删空")
    fun maxVictimsPerRun() {
        val files = (1..CacheCleanupPlan.MAX_VICTIMS_PER_RUN + 200).map { file("f" + it, 1, it.toLong()) }
        val plan = CacheCleanupPlan.plan(files, maxBytes = 10, targetBytes = 0)
        assertEquals(CacheCleanupPlan.MAX_VICTIMS_PER_RUN, plan.deletedCount)
    }

    @Test
    @DisplayName("默认上限为 100MB，目标为 85%")
    fun defaults() {
        assertEquals(100L * 1024L * 1024L, CacheCleanupPlan.DEFAULT_MAX_BYTES)
        assertEquals(0.85, CacheCleanupPlan.TARGET_RATIO, 0.0001)
        assertEquals(CacheCleanupPlan.DEFAULT_MAX_BYTES, CacheCleanWorker.MAX_BYTES)
        assertTrue(CacheCleanWorker.TARGET_BYTES < CacheCleanWorker.MAX_BYTES)
    }

    @Test
    @DisplayName("过期清理按 TTL 过滤，TTL 非正时返回空")
    fun expired() {
        val now = 1_000_000L
        val files = listOf(file("old", 1, now - 10_000), file("fresh", 1, now - 100))
        val expired = CacheCleanupPlan.expired(files, nowUtc = now, ttlMillis = 5_000)
        assertEquals(listOf("old"), expired.map { it.path })
        assertTrue(CacheCleanupPlan.expired(files, now, ttlMillis = 0).isEmpty())
    }

    @Test
    @DisplayName("字节格式化用于设置页展示")
    fun formatBytes() {
        assertEquals("0 B", CacheCleanupPlan.formatBytes(0))
        assertEquals("512 B", CacheCleanupPlan.formatBytes(512))
        assertEquals("1.0 KB", CacheCleanupPlan.formatBytes(1024))
        assertEquals("1.5 MB", CacheCleanupPlan.formatBytes(1024L * 1024L * 3 / 2))
        assertTrue(CacheCleanupPlan.formatBytes(100L * 1024 * 1024).endsWith("MB"))
        assertEquals("0 B", CacheCleanupPlan.formatBytes(-1))
    }

    @Test
    @DisplayName("负的字节数被安全地当作 0 处理")
    fun negativeSizeSanitized() {
        val plan = CacheCleanupPlan.plan(listOf(file("x", -100, 1)), maxBytes = 10, targetBytes = 0)
        assertEquals(0L, plan.totalBytesBefore)
        assertFalse(plan.needed)
    }
}
