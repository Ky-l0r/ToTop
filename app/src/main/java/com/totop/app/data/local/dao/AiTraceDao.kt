package com.totop.app.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.totop.app.data.local.entity.AiTraceEntity
import kotlinx.coroutines.flow.Flow

/**
 * `ai_trace` 表访问对象（开发方案 5.1 / 6.2）。
 *
 * 设置页近 30 天用量趋势与成功率统计均读本表；requestId 唯一，重复写入会被 IGNORE。
 */
@Dao
interface AiTraceDao {
    @Query("SELECT * FROM ai_trace WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): AiTraceEntity?

    @Query("SELECT * FROM ai_trace WHERE requestId = :requestId LIMIT 1")
    suspend fun findByRequestId(requestId: String): AiTraceEntity?

    @Query("SELECT * FROM ai_trace ORDER BY createdAt DESC, id ASC LIMIT :limit OFFSET :offset")
    suspend fun page(
        limit: Int,
        offset: Int,
    ): List<AiTraceEntity>

    @Query("SELECT * FROM ai_trace ORDER BY createdAt DESC, id ASC LIMIT :limit OFFSET :offset")
    fun observePage(
        limit: Int,
        offset: Int,
    ): Flow<List<AiTraceEntity>>

    @Query(
        "SELECT * FROM ai_trace WHERE createdAt >= :fromUtc AND createdAt < :toUtc " +
            "ORDER BY createdAt DESC, id ASC",
    )
    suspend fun between(
        fromUtc: Long,
        toUtc: Long,
    ): List<AiTraceEntity>

    @Query(
        "SELECT * FROM ai_trace WHERE createdAt >= :fromUtc AND createdAt < :toUtc " +
            "ORDER BY createdAt DESC, id ASC",
    )
    fun observeBetween(
        fromUtc: Long,
        toUtc: Long,
    ): Flow<List<AiTraceEntity>>

    /** 近 N 天用量汇总（总 token、总耗时、成功率分子分母）。 */
    @Query(
        "SELECT COALESCE(SUM(inputTokens), 0) FROM ai_trace " +
            "WHERE createdAt >= :fromUtc AND createdAt < :toUtc",
    )
    suspend fun sumInputTokens(
        fromUtc: Long,
        toUtc: Long,
    ): Int

    @Query(
        "SELECT COALESCE(SUM(outputTokens), 0) FROM ai_trace " +
            "WHERE createdAt >= :fromUtc AND createdAt < :toUtc",
    )
    suspend fun sumOutputTokens(
        fromUtc: Long,
        toUtc: Long,
    ): Int

    @Query("SELECT COUNT(*) FROM ai_trace WHERE createdAt >= :fromUtc AND createdAt < :toUtc AND ok = 1")
    suspend fun countOk(
        fromUtc: Long,
        toUtc: Long,
    ): Int

    @Query("SELECT COUNT(*) FROM ai_trace WHERE createdAt >= :fromUtc AND createdAt < :toUtc")
    suspend fun countBetween(
        fromUtc: Long,
        toUtc: Long,
    ): Int

    @Upsert
    suspend fun upsert(entity: AiTraceEntity)

    @Upsert
    suspend fun upsertAll(entities: List<AiTraceEntity>)

    @Query("DELETE FROM ai_trace WHERE id = :id")
    suspend fun deleteById(id: String): Int

    /** 保留策略：只保留最近 N 天 trace，控制数据库体积。 */
    @Query("DELETE FROM ai_trace WHERE createdAt < :keepFromUtc")
    suspend fun deleteOlderThan(keepFromUtc: Long): Int

    @Query("DELETE FROM ai_trace")
    suspend fun deleteAll()
}
