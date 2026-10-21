package com.totop.app.data.repo

import androidx.room.withTransaction
import com.totop.app.data.local.AppDatabase
import com.totop.app.data.local.dao.CacheEntryDao
import com.totop.app.data.local.entity.CacheEntryEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 缓存键（开发方案 6.3：缓存键 = sha256(图片或题干) + promptVersion + model）。
 *
 * 三者任一变化都必须是不同条目，因此同为 cache_entry 的联合主键。
 */
data class CacheKey(
    val sha256: String,
    val promptVer: String,
    val model: String,
) {
    init {
        require(sha256.isNotBlank()) { "cacheEntry sha256 不得为空" }
        require(promptVer.isNotBlank()) { "cacheEntry promptVer 不得为空" }
        require(model.isNotBlank()) { "cacheEntry model 不得为空" }
    }
}

/**
 * 缓存仓库：AI 结果缓存的读写、TTL 与 LRU 淘汰（FR-8.4、方案 5.2）。
 *
 * 磁盘上限 [MAX_BYTES]（100MB）。写入路径的步骤：
 * 1. 计算新条目字节数；
 * 2. 若「当前总量 - 已有同键条目 + 新条目」超过上限，按 lastAccessAt 升序淘汰，
 *    直到回落到上限的 [EVICT_TARGET_RATIO]（留出余量，避免每次写入都淘汰）；
 * 3. 淘汰与写入在同一事务内完成。
 *
 * 淘汰计划计算是纯函数 [LruEvictionPlan.plan]，可在 JVM 单测覆盖，不依赖数据库。
 */
@Singleton
class CacheRepository
    @Inject
    constructor(
        private val database: AppDatabase,
        private val dao: CacheEntryDao,
    ) {
        private var dispatcher: CoroutineDispatcher = Dispatchers.IO

        internal fun withDispatcher(dispatcher: CoroutineDispatcher): CacheRepository {
            this.dispatcher = dispatcher
            return this
        }

        /** 读取缓存；命中时异步刷新 LRU 时间戳。 */
        suspend fun get(
            key: CacheKey,
            nowUtc: Long = System.currentTimeMillis(),
        ): String? =
            withContext(dispatcher) {
                val row = dao.find(key.sha256, key.promptVer, key.model) ?: return@withContext null
                if (isExpired(row.lastAccessAt, nowUtc)) {
                    dao.delete(key.sha256, key.promptVer, key.model)
                    return@withContext null
                }
                dao.touch(key.sha256, key.promptVer, key.model, nowUtc)
                row.resultJson
            }

        /** 只读命中，不刷新 lastAccessAt（供上层判断 TTL 后自行决定是否 touch）。 */
        suspend fun peek(key: CacheKey): CacheEntrySnapshot? =
            withContext(dispatcher) {
                dao.find(key.sha256, key.promptVer, key.model)?.toSnapshot()
            }

        /** 全部条目，按 lastAccessAt 升序（最久未访问在前）。 */
        suspend fun entriesByLastAccess(): List<CacheEntrySnapshot> =
            withContext(dispatcher) {
                dao.allByLastAccess().map { it.toSnapshot() }
            }

        /** 刷新 LRU 时间戳。 */
        suspend fun touch(
            key: CacheKey,
            nowUtc: Long = System.currentTimeMillis(),
        ): Boolean = withContext(dispatcher) { dao.touch(key.sha256, key.promptVer, key.model, nowUtc) > 0 }

        /** 缓存条目的只读快照（屏蔽实体）。 */
        data class CacheEntrySnapshot(
            val sha256: String,
            val promptVer: String,
            val model: String,
            val resultJson: String,
            val sizeBytes: Long,
            val lastAccessAtUtc: Long,
            val createdAtUtc: Long,
        )

        private fun CacheEntryEntity.toSnapshot(): CacheEntrySnapshot =
            CacheEntrySnapshot(
                sha256 = sha256,
                promptVer = promptVer,
                model = model,
                resultJson = resultJson,
                sizeBytes = sizeBytes,
                lastAccessAtUtc = lastAccessAt,
                createdAtUtc = createdAt,
            )

        fun observe(key: CacheKey): Flow<String?> =
            dao.observe(key.sha256, key.promptVer, key.model).map { it?.resultJson }.flowOn(dispatcher)

        /**
         * 写入缓存，并在超限时做 LRU 淘汰。
         *
         * @return 本次淘汰掉的条目数。
         */
        suspend fun put(
            key: CacheKey,
            resultJson: String,
            nowUtc: Long = System.currentTimeMillis(),
        ): Int =
            withContext(dispatcher) {
                val sizeBytes = resultJson.toByteArray(Charsets.UTF_8).size.toLong()
                database.withTransaction {
                    val existing = dao.find(key.sha256, key.promptVer, key.model)
                    val total = dao.totalBytes()
                    val effectiveTotal =
                        if (existing != null) {
                            total - existing.sizeBytes + sizeBytes
                        } else {
                            total + sizeBytes
                        }
                    var evicted = 0
                    if (effectiveTotal > MAX_BYTES) {
                        val candidates =
                            dao.leastRecentlyUsed(LRU_SCAN_LIMIT)
                                .filterNot { it.isSameKey(key) }
                        val plan =
                            LruEvictionPlan.plan(
                                totalBytes = effectiveTotal,
                                maxBytes = MAX_BYTES,
                                targetBytes = (MAX_BYTES * EVICT_TARGET_RATIO).toLong(),
                                candidates =
                                    candidates.map {
                                        LruEvictionPlan.Candidate(
                                            it.sha256,
                                            it.promptVer,
                                            it.model,
                                            it.sizeBytes,
                                        )
                                    },
                            )
                        for (victim in plan.victims) {
                            evicted += dao.delete(victim.sha256, victim.promptVer, victim.model)
                        }
                    }
                    dao.upsert(
                        CacheEntryEntity(
                            sha256 = key.sha256,
                            promptVer = key.promptVer,
                            model = key.model,
                            resultJson = resultJson,
                            sizeBytes = sizeBytes,
                            lastAccessAt = nowUtc,
                            createdAt = existing?.createdAt ?: nowUtc,
                        ),
                    )
                    evicted
                }
            }

        /** 清理超过 TTL 的条目（FR-8.4「带 TTL」）。 */
        suspend fun purgeExpired(nowUtc: Long = System.currentTimeMillis()): Int =
            withContext(dispatcher) {
                dao.deleteExpiredBefore(nowUtc - TTL_MS)
            }

        /** 提示词版本升级后清理旧版本缓存（assets/seed 中 promptVersion 规则）。 */
        suspend fun purgeOtherPromptVersions(currentPromptVer: String): Int =
            withContext(dispatcher) { dao.deleteOtherPromptVersions(currentPromptVer) }

        suspend fun remove(key: CacheKey): Boolean =
            withContext(dispatcher) {
                dao.delete(key.sha256, key.promptVer, key.model) > 0
            }

        suspend fun totalBytes(): Long = withContext(dispatcher) { dao.totalBytes() }

        fun observeTotalBytes(): Flow<Long> = dao.observeTotalBytes().flowOn(dispatcher)

        suspend fun count(): Int = withContext(dispatcher) { dao.count() }

        fun observeCount(): Flow<Int> = dao.observeCount().flowOn(dispatcher)

        suspend fun clear() = withContext(dispatcher) { dao.deleteAll() }

        private fun isExpired(
            lastAccessAt: Long,
            nowUtc: Long,
        ): Boolean = nowUtc - lastAccessAt > TTL_MS

        private fun CacheEntryEntity.isSameKey(key: CacheKey): Boolean =
            sha256 == key.sha256 && promptVer == key.promptVer && model == key.model

        companion object {
            /** 磁盘上限 100MB（方案 5.2 / 任务书）。 */
            const val MAX_BYTES: Long = 100L * 1024L * 1024L

            /** 淘汰目标比例：一次淘汰到上限的 90%，避免频繁触发。 */
            const val EVICT_TARGET_RATIO: Double = 0.9

            /** 单次最多扫描的淘汰候选数，防止一次加载过多行。 */
            const val LRU_SCAN_LIMIT: Int = 500

            /** 缓存 TTL：7 天。 */
            const val TTL_MS: Long = 7L * 24L * 60L * 60L * 1000L
        }
    }
