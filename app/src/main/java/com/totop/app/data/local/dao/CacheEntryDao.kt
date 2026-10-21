package com.totop.app.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.totop.app.data.local.entity.CacheEntryEntity
import kotlinx.coroutines.flow.Flow

/**
 * `cache_entry` 表访问对象（开发方案 5.1 / FR-8.4）。
 *
 * 主键 (sha256, promptVer, model)：三者任一变化即为不同条目。
 * LRU 淘汰：按 lastAccessAt 升序删除最久未访问的条目，直到总字节数回落到上限以内（100MB）。
 */
@Dao
interface CacheEntryDao {
    @Query(
        "SELECT * FROM cache_entry WHERE sha256 = :sha256 AND promptVer = :promptVer AND model = :model LIMIT 1",
    )
    suspend fun find(
        sha256: String,
        promptVer: String,
        model: String,
    ): CacheEntryEntity?

    @Query(
        "SELECT * FROM cache_entry WHERE sha256 = :sha256 AND promptVer = :promptVer AND model = :model LIMIT 1",
    )
    fun observe(
        sha256: String,
        promptVer: String,
        model: String,
    ): Flow<CacheEntryEntity?>

    /** 命中后刷新 LRU 时间戳。 */
    @Query(
        "UPDATE cache_entry SET lastAccessAt = :nowUtc " +
            "WHERE sha256 = :sha256 AND promptVer = :promptVer AND model = :model",
    )
    suspend fun touch(
        sha256: String,
        promptVer: String,
        model: String,
        nowUtc: Long,
    ): Int

    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM cache_entry")
    suspend fun totalBytes(): Long

    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM cache_entry")
    fun observeTotalBytes(): Flow<Long>

    @Query("SELECT COUNT(*) FROM cache_entry")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM cache_entry")
    fun observeCount(): Flow<Int>

    /** 最久未访问的条目（LRU 淘汰候选）。 */
    @Query("SELECT * FROM cache_entry ORDER BY lastAccessAt ASC LIMIT :limit")
    suspend fun leastRecentlyUsed(limit: Int): List<CacheEntryEntity>

    /** 全部条目，按 lastAccessAt 升序（LlmCache 的 LRU 全量视图使用）。 */
    @Query("SELECT * FROM cache_entry ORDER BY lastAccessAt ASC")
    suspend fun allByLastAccess(): List<CacheEntryEntity>

    /** 超过 TTL 的条目（方案 FR-8.4「带 TTL」）。 */
    @Query("SELECT * FROM cache_entry WHERE lastAccessAt < :expiredBeforeUtc ORDER BY lastAccessAt ASC")
    suspend fun expiredBefore(expiredBeforeUtc: Long): List<CacheEntryEntity>

    @Query("DELETE FROM cache_entry WHERE lastAccessAt < :expiredBeforeUtc")
    suspend fun deleteExpiredBefore(expiredBeforeUtc: Long): Int

    @Query(
        "DELETE FROM cache_entry WHERE sha256 = :sha256 AND promptVer = :promptVer AND model = :model",
    )
    suspend fun delete(
        sha256: String,
        promptVer: String,
        model: String,
    ): Int

    /** 提示词版本升级后旧缓存必须失效（assets/seed 中 promptVersion 的规则）。 */
    @Query("DELETE FROM cache_entry WHERE promptVer <> :currentPromptVer")
    suspend fun deleteOtherPromptVersions(currentPromptVer: String): Int

    @Upsert
    suspend fun upsert(entity: CacheEntryEntity)

    @Upsert
    suspend fun upsertAll(entities: List<CacheEntryEntity>)

    @Query("DELETE FROM cache_entry")
    suspend fun deleteAll()
}
