package com.totop.app.data.remote

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 缓存键（开发方案 3.4：双层缓存，键 = sha256(图片或题干) + promptVersion + model）。
 *
 * 说明：文档正文与 assets/prompts/prompt_version.json 的规则都要求
 * 「任一阶段提示词文本变更都必须同步升级 promptVersion，否则旧缓存会被错误复用」，
 * 因此键里带上 promptVersion 即可，无需再对提示词正文做二次哈希。
 */
data class CacheKey(
    val sha256: String,
    val promptVersion: String,
    val model: String,
) {
    init {
        require(sha256.isNotBlank()) { "缓存键的 sha256 不得为空" }
        require(promptVersion.isNotBlank()) { "缓存键的 promptVersion 不得为空" }
        require(model.isNotBlank()) { "缓存键的 model 不得为空" }
    }

    /** 稳定的字符串形式，用于 Room cache_entry 主键与 LRU 表键。 */
    val storageKey: String get() = sha256 + "|" + promptVersion + "|" + model

    override fun toString(): String = storageKey
}

/** 一条缓存记录。 */
data class CacheEntry(
    val key: CacheKey,
    /** 原始 JSON（阶段输出或完整解析结果）。 */
    val resultJson: String,
    /** 字节数，用于 60MB 上限淘汰。 */
    val sizeBytes: Long,
    /** 最后访问时间（UTC 毫秒），LRU 依据。 */
    val lastAccessAtUtc: Long,
) {
    /** 是否处于 TTL 有效期内。 */
    fun isFresh(
        nowUtc: Long,
        ttlMillis: Long,
    ): Boolean = nowUtc - lastAccessAtUtc <= ttlMillis
}

/**
 * 持久层接口（第二层缓存），由 data 层用 Room cache_entry 表实现。
 *
 * 全部为挂起函数：Room 的 DAO 本身就是挂起 API，
 * 这样适配器无需 runBlocking，也就不会在 IO 线程上阻塞或引发 ANR。
 */
interface CacheStore {
    suspend fun get(storageKey: String): CacheEntry?

    suspend fun put(entry: CacheEntry)

    suspend fun entriesByLastAccess(): List<CacheEntry>

    suspend fun delete(storageKey: String)

    suspend fun totalSizeBytes(): Long

    suspend fun clear()
}

/** 内存实现：单测与「数据库尚未就绪」的降级路径。 */
class InMemoryCacheStore : CacheStore {
    private val map = LinkedHashMap<String, CacheEntry>()

    override suspend fun get(storageKey: String): CacheEntry? = map[storageKey]

    override suspend fun put(entry: CacheEntry) {
        map[entry.key.storageKey] = entry
    }

    override suspend fun entriesByLastAccess(): List<CacheEntry> = map.values.sortedBy { it.lastAccessAtUtc }

    override suspend fun delete(storageKey: String) {
        map.remove(storageKey)
    }

    override suspend fun totalSizeBytes(): Long = map.values.sumOf { it.sizeBytes }

    override suspend fun clear() {
        map.clear()
    }

    val size: Int get() = map.size
}

/**
 * 双层缓存（开发方案 3.2 第 [3] 步、3.4 成本控制）。
 *
 * 第一层：进程内 LRU，容量上限 60MB（[DEFAULT_MEMORY_LIMIT_BYTES]）；
 * 第二层：[CacheStore]（Room cache_entry 表），带同样的容量上限与 LRU 淘汰，
 * 以及 TTL（默认 30 天），避免长期占用手机空间。
 *
 * 命中行为：命中后刷新 lastAccessAtUtc 并回填内存层，保证 LRU 顺序正确。
 * 所有公开的读写方法都是挂起函数，可在 IO 调度器上安全调用。
 */
class LlmCache(
    private val store: CacheStore,
    private val memoryLimitBytes: Long = DEFAULT_MEMORY_LIMIT_BYTES,
    private val diskLimitBytes: Long = DEFAULT_DISK_LIMIT_BYTES,
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    /**
     * 串行化访问的互斥锁。
     *
     * 不能用 @Synchronized：Kotlin 禁止在挂起函数上加该注解（挂起期间不能持有监视器锁）。
     * Mutex 会正确挂起而不是阻塞线程，符合 IO 调度器的协作式调度。
     */
    private val mutex = Mutex()

    /** 内存 LRU：accessOrder = true，淘汰在 [evictMemoryIfNeeded] 中按字节上限手动执行。 */
    private val memory = LinkedHashMap<String, CacheEntry>(16, 0.75f, true)

    /** 统计：命中/未命中，便于设置页展示「缓存为你省下 N 次调用」。 */
    private val hits = java.util.concurrent.atomic.AtomicLong(0)
    private val misses = java.util.concurrent.atomic.AtomicLong(0)
    private val memoryBytes = java.util.concurrent.atomic.AtomicLong(0)

    /** 读取缓存；过期视为未命中并顺手清理。 */
    suspend fun get(key: CacheKey): String? =
        mutex.withLock {
            val storageKey = key.storageKey
            val nowUtc = now()
            val cached = memory[storageKey]
            if (cached != null && cached.isFresh(nowUtc, ttlMillis)) {
                hits.incrementAndGet()
                return@withLock cached.resultJson
            }
            if (cached != null) {
                memory.remove(storageKey)
                memoryBytes.addAndGet(-cached.sizeBytes)
            }
            val persisted = runCatching { store.get(storageKey) }.getOrNull()
            if (persisted == null || !persisted.isFresh(nowUtc, ttlMillis)) {
                if (persisted != null) runCatching { store.delete(storageKey) }
                misses.incrementAndGet()
                return@withLock null
            }
            val refreshed = persisted.copy(lastAccessAtUtc = nowUtc)
            runCatching { store.put(refreshed) }
            putMemory(refreshed)
            hits.incrementAndGet()
            refreshed.resultJson
        }

    /** 写入缓存（同时落两层，并触发超额淘汰）。 */
    suspend fun put(
        key: CacheKey,
        resultJson: String,
    ) = mutex.withLock {
        val nowUtc = now()
        val entry =
            CacheEntry(
                key = key,
                resultJson = resultJson,
                sizeBytes = resultJson.toByteArray(Charsets.UTF_8).size.toLong(),
                lastAccessAtUtc = nowUtc,
            )
        putMemory(entry)
        runCatching {
            store.put(entry)
            evictDiskIfNeededLocked()
        }
    }

    /** 删除单条（提示词版本升级时按 promptVersion 清理）。 */
    suspend fun invalidate(key: CacheKey) =
        mutex.withLock {
            val storageKey = key.storageKey
            memory.remove(storageKey)?.let { memoryBytes.addAndGet(-it.sizeBytes) }
            runCatching { store.delete(storageKey) }
            Unit
        }

    /** 清空两层缓存。 */
    suspend fun clear() =
        mutex.withLock {
            memory.clear()
            memoryBytes.set(0)
            runCatching { store.clear() }
            Unit
        }

    /** 命中数。 */
    fun hitCount(): Long = hits.get()

    /** 未命中数。 */
    fun missCount(): Long = misses.get()

    /** 当前内存层占用字节数。 */
    fun memorySizeBytes(): Long = memoryBytes.get()

    /** 当前磁盘层占用字节数。 */
    suspend fun diskSizeBytes(): Long = runCatching { store.totalSizeBytes() }.getOrDefault(0L)

    private fun putMemory(entry: CacheEntry) {
        val previous = memory.put(entry.key.storageKey, entry)
        if (previous != null) memoryBytes.addAndGet(-previous.sizeBytes)
        memoryBytes.addAndGet(entry.sizeBytes)
        evictMemoryIfNeeded()
    }

    private fun evictMemoryIfNeeded() {
        while (memoryBytes.get() > memoryLimitBytes && memory.isNotEmpty()) {
            val eldest = memory.entries.iterator().next()
            memory.remove(eldest.key)
            memoryBytes.addAndGet(-eldest.value.sizeBytes)
        }
    }

    private suspend fun evictDiskIfNeededLocked() {
        var total = store.totalSizeBytes()
        if (total <= diskLimitBytes) return
        val ordered = store.entriesByLastAccess()
        for (entry in ordered) {
            if (total <= diskLimitBytes) break
            store.delete(entry.key.storageKey)
            total -= entry.sizeBytes
        }
    }

    companion object {
        /** 内存 LRU 上限：60MB（开发方案 3.4）。 */
        const val DEFAULT_MEMORY_LIMIT_BYTES: Long = 60L * 1024L * 1024L

        /** 磁盘缓存上限：128MB，避免长期使用后占满手机空间。 */
        const val DEFAULT_DISK_LIMIT_BYTES: Long = 128L * 1024L * 1024L

        /** 默认 TTL：30 天。 */
        const val DEFAULT_TTL_MILLIS: Long = 30L * 24L * 60L * 60L * 1000L
    }
}
