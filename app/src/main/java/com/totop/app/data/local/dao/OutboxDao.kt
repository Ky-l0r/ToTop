package com.totop.app.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.totop.app.data.local.entity.OutboxEntity
import kotlinx.coroutines.flow.Flow

/**
 * `outbox` 表访问对象（开发方案 5.1 / FR-8.5）。
 *
 * 补传顺序为入队顺序（id 升序）；指数退避 1s/2s/4s，最多 3 次（FR-8.2）。
 */
@Dao
interface OutboxDao {
    @Query("SELECT * FROM outbox WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): OutboxEntity?

    /** 到期待补传队列，按入队顺序。 */
    @Query("SELECT * FROM outbox WHERE nextRetryAt <= :nowUtc ORDER BY id ASC LIMIT :limit")
    suspend fun due(
        nowUtc: Long,
        limit: Int,
    ): List<OutboxEntity>

    @Query("SELECT * FROM outbox WHERE nextRetryAt <= :nowUtc ORDER BY id ASC LIMIT :limit")
    fun observeDue(
        nowUtc: Long,
        limit: Int,
    ): Flow<List<OutboxEntity>>

    @Query("SELECT * FROM outbox ORDER BY id ASC LIMIT :limit OFFSET :offset")
    fun observePage(
        limit: Int,
        offset: Int,
    ): Flow<List<OutboxEntity>>

    @Query("SELECT COUNT(*) FROM outbox")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM outbox")
    fun observeCount(): Flow<Int>

    @Upsert
    suspend fun upsert(entity: OutboxEntity)

    @Upsert
    suspend fun upsertAll(entities: List<OutboxEntity>)

    /** 补传成功或彻底放弃后出队。 */
    @Query("DELETE FROM outbox WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("UPDATE outbox SET attempts = :attempts, nextRetryAt = :nextRetryAt WHERE id = :id")
    suspend fun updateAttempt(
        id: String,
        attempts: Int,
        nextRetryAt: Long,
    )

    @Query("DELETE FROM outbox WHERE attempts >= :maxAttempts")
    suspend fun deleteExhausted(maxAttempts: Int): Int

    @Query("DELETE FROM outbox")
    suspend fun deleteAll()
}
