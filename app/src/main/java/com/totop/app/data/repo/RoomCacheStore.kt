package com.totop.app.data.repo

import com.totop.app.data.remote.CacheEntry
import com.totop.app.data.remote.CacheStore
import javax.inject.Inject
import javax.inject.Singleton
import com.totop.app.data.remote.CacheKey as RemoteCacheKey

/**
 * [CacheStore] 的 Room 实现：把 ai 模块的第二层缓存抽象接到 `cache_entry` 表（开发方案 5.1 / FR-8.4）。
 *
 * 映射关系（与 ai 模块的契约严格对齐）：
 * - `RemoteCacheKey.storageKey = sha256|promptVersion|model`，等价于本表的联合主键 (sha256, promptVer, model)；
 * - `CacheEntry.lastAccessAtUtc` 对应 `cache_entry.lastAccessAt`，是 LRU 淘汰依据；
 * - `sizeBytes` 按 UTF-8 字节数计算，用于 100MB 磁盘上限。
 *
 * 写入统一走 [CacheRepository.put]：超限时按 LRU 淘汰到上限的 90%，
 * 因此本适配器不再实现第二套淘汰逻辑，两处口径保持一致。
 *
 * 命名说明：本包的 [CacheKey] 与 ai 模块的 `CacheKey` 语义相同但分属两层，
 * 这里用别名 [RemoteCacheKey] 明确区分，避免遮蔽。
 */
@Singleton
class RoomCacheStore
    @Inject
    constructor(
        private val repository: CacheRepository,
    ) : CacheStore {
        override suspend fun get(storageKey: String): CacheEntry? {
            val key = splitKey(storageKey) ?: return null
            val snapshot = repository.peek(key) ?: return null
            return snapshot.toEntry()
        }

        override suspend fun put(entry: CacheEntry) {
            val remoteKey = entry.key
            repository.put(
                key = CacheKey(sha256 = remoteKey.sha256, promptVer = remoteKey.promptVersion, model = remoteKey.model),
                resultJson = entry.resultJson,
                nowUtc = entry.lastAccessAtUtc,
            )
        }

        override suspend fun entriesByLastAccess(): List<CacheEntry> =
            repository.entriesByLastAccess().map { it.toEntry() }

        override suspend fun delete(storageKey: String) {
            val key = splitKey(storageKey) ?: return
            repository.remove(key)
        }

        override suspend fun totalSizeBytes(): Long = repository.totalBytes()

        override suspend fun clear() {
            repository.clear()
        }

        private fun splitKey(storageKey: String): CacheKey? {
            val parts = storageKey.split(SEPARATOR)
            if (parts.size != KEY_PARTS) return null
            return runCatching {
                CacheKey(sha256 = parts[0], promptVer = parts[1], model = parts[2])
            }.getOrNull()
        }

        private fun CacheRepository.CacheEntrySnapshot.toEntry(): CacheEntry =
            CacheEntry(
                key = RemoteCacheKey(sha256 = sha256, promptVersion = promptVer, model = model),
                resultJson = resultJson,
                sizeBytes = sizeBytes,
                lastAccessAtUtc = lastAccessAtUtc,
            )

        private companion object {
            /** 与 ai 模块 CacheKey.storageKey 的分隔符保持一致。 */
            const val SEPARATOR = "|"
            const val KEY_PARTS = 3
        }
    }
