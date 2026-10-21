package com.totop.app.data.repo

/**
 * LRU 淘汰计划（纯函数，便于 JVM 单测）。
 *
 * 输入按 lastAccessAt 升序排列的候选，输出需要删除的条目，直到总量回落到目标字节数以内。
 * 用于 cache_entry 的 100MB 磁盘上限控制（开发方案 5.2 / FR-8.4）。
 */
object LruEvictionPlan {
    /** 一条候选缓存条目。 */
    data class Candidate(
        val sha256: String,
        val promptVer: String,
        val model: String,
        val sizeBytes: Long,
    )

    /** 淘汰计划：待删除条目、释放字节数、淘汰后剩余字节数。 */
    data class Plan(val victims: List<Candidate>, val freedBytes: Long, val remainingBytes: Long) {
        val evicted: Int get() = victims.size
    }

    /**
     * 计算淘汰计划。
     *
     * @param totalBytes 写入新条目后的预估总量
     * @param maxBytes 硬上限；不超过则无淘汰
     * @param targetBytes 期望回落到的目标值（一般取上限的 90%）
     * @param candidates 按 lastAccessAt 升序的候选（最久未访问在前）
     */
    fun plan(
        totalBytes: Long,
        maxBytes: Long,
        targetBytes: Long,
        candidates: List<Candidate>,
    ): Plan {
        if (totalBytes <= maxBytes) return Plan(emptyList(), 0L, totalBytes)
        val target = targetBytes.coerceAtMost(maxBytes).coerceAtLeast(0L)
        var remaining = totalBytes
        val victims = ArrayList<Candidate>()
        var freed = 0L
        for (candidate in candidates) {
            if (remaining <= target) break
            val size = candidate.sizeBytes.coerceAtLeast(0L)
            victims += candidate
            freed += size
            remaining -= size
        }
        return Plan(victims, freed, remaining)
    }
}
