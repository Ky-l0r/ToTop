package com.totop.app.work

/**
 * 磁盘缓存清理计划（开发方案 5.2 / FR-8.4：磁盘上限 100MB）。
 *
 * 与 data 层的 [com.totop.app.data.repo.LruEvictionPlan] 分工不同：
 * - LruEvictionPlan 处理 **cache_entry 表** 的字节预算（纯 DB）；
 * - 本类处理 **文件系统** 上的图片缓存（filesDir/images/thumbs 与 cacheDir），
 *   按「缩略图优先、最久未访问优先」的顺序给出待删文件清单。
 *
 * 全部是纯函数：输入是文件元数据快照，输出是待删路径，不触碰文件系统，
 * 因此可以在 JVM 单测里穷举边界。
 */
object CacheCleanupPlan {
    /** 磁盘上限默认 100MB（FR-8.4）。 */
    const val DEFAULT_MAX_BYTES: Long = 100L * 1024L * 1024L

    /** 清理后回落到上限的多少比例（留出余量，避免每次启动都触发清理）。 */
    const val TARGET_RATIO: Double = 0.85

    /** 单次清理最多删除多少文件（避免长时间占用 IO）。 */
    const val MAX_VICTIMS_PER_RUN: Int = 500

    /** 一个候选文件的元数据快照。 */
    data class FileSnapshot(
        /** 绝对路径。 */
        val path: String,
        val sizeBytes: Long,
        /** 最后访问/修改时间（UTC 毫秒），用于 LRU 排序。 */
        val lastAccessUtc: Long,
        /** 是否为缩略图（缩略图可再生，优先删除）。 */
        val isThumbnail: Boolean = false,
    ) {
        init {
            require(path.isNotBlank()) { "path 不得为空" }
        }
    }

    /** 清理计划。 */
    data class Plan(
        val victims: List<FileSnapshot>,
        val freedBytes: Long,
        val remainingBytes: Long,
        val totalBytesBefore: Long,
    ) {
        val deletedCount: Int get() = victims.size

        /** 是否需要清理（未超上限时为 false）。 */
        val needed: Boolean get() = victims.isNotEmpty()

        /** 释放比例 0..1。 */
        val freedRatio: Double
            get() = if (totalBytesBefore <= 0L) 0.0 else freedBytes.toDouble() / totalBytesBefore.toDouble()
    }

    /**
     * 计算清理计划。
     *
     * 淘汰顺序（先命中先删除，顺序即优先级，禁止调换）：
     * 1. 缩略图先于原图（缩略图可从原图再生，原图是用户数据的唯一来源）；
     * 2. 同类别内按 [FileSnapshot.lastAccessUtc] 升序（最久未访问先删）；
     * 3. 最多删除 [MAX_VICTIMS_PER_RUN] 个；
     * 4. 一旦回落到 [targetBytes] 立即停止。
     *
     * @param files 全部候选文件
     * @param maxBytes 硬上限；总量不超过时不删任何文件
     * @param targetBytes 期望回落到的目标值，默认取上限的 [TARGET_RATIO]
     */
    fun plan(
        files: List<FileSnapshot>,
        maxBytes: Long = DEFAULT_MAX_BYTES,
        targetBytes: Long = (DEFAULT_MAX_BYTES * TARGET_RATIO).toLong(),
    ): Plan {
        val sanitized = files.map { it.copy(sizeBytes = it.sizeBytes.coerceAtLeast(0L)) }
        val total = sanitized.sumOf { it.sizeBytes }
        if (total <= maxBytes) return Plan(emptyList(), 0L, total, total)

        val target = targetBytes.coerceIn(0L, maxBytes)
        val ordered =
            sanitized.sortedWith(
                compareByDescending<FileSnapshot> { it.isThumbnail }
                    .thenBy { it.lastAccessUtc },
            )

        var remaining = total
        var freed = 0L
        val victims = ArrayList<FileSnapshot>()
        for (candidate in ordered) {
            if (remaining <= target) break
            if (victims.size >= MAX_VICTIMS_PER_RUN) break
            victims += candidate
            freed += candidate.sizeBytes
            remaining -= candidate.sizeBytes
        }
        return Plan(victims, freed, remaining, total)
    }

    /**
     * 过期条目清理：删除 [nowUtc] 之前 [ttlMillis] 生成、且仍未被访问的文件。
     *
     * 用于 cacheDir 下的相机临时图（拍照后立即压缩落盘，临时文件只活到预处理完成）。
     */
    fun expired(
        files: List<FileSnapshot>,
        nowUtc: Long,
        ttlMillis: Long,
    ): List<FileSnapshot> {
        if (ttlMillis <= 0L) return emptyList()
        val threshold = nowUtc - ttlMillis
        return files.filter { it.lastAccessUtc < threshold }.sortedBy { it.lastAccessUtc }
    }

    /** 容量展示文本（设置页「缓存占用 12.3 MB」）。 */
    fun formatBytes(bytes: Long): String {
        if (bytes < 0L) return "0 B"
        if (bytes < 1024L) return bytes.toString() + " B"
        val units = listOf("KB", "MB", "GB")
        var value = bytes.toDouble() / 1024.0
        var unitIndex = 0
        while (value >= 1024.0 && unitIndex < units.lastIndex) {
            value /= 1024.0
            unitIndex++
        }
        val rounded = Math.round(value * 10.0) / 10.0
        return rounded.toString() + " " + units[unitIndex]
    }
}
