package com.totop.app.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.totop.app.data.local.entity.ReviewLogEntity
import kotlinx.coroutines.flow.Flow

/** `review_log` 表访问对象（开发方案 5.1 / FR-4）。所有时间为 UTC 毫秒。 */
@Dao
interface ReviewDao {
    @Query("SELECT * FROM review_log WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): ReviewLogEntity?

    @Query("SELECT * FROM review_log WHERE mistakeId = :mistakeId ORDER BY reviewedAt DESC, id ASC")
    suspend fun byMistakeId(mistakeId: String): List<ReviewLogEntity>

    @Query("SELECT * FROM review_log WHERE mistakeId = :mistakeId ORDER BY reviewedAt DESC, id ASC")
    fun observeByMistakeId(mistakeId: String): Flow<List<ReviewLogEntity>>

    @Query(
        "SELECT * FROM review_log WHERE reviewedAt >= :fromUtc AND reviewedAt < :toUtc " +
            "ORDER BY reviewedAt DESC, id ASC LIMIT :limit OFFSET :offset",
    )
    suspend fun pageBetween(
        fromUtc: Long,
        toUtc: Long,
        limit: Int,
        offset: Int,
    ): List<ReviewLogEntity>

    @Query(
        "SELECT * FROM review_log WHERE reviewedAt >= :fromUtc AND reviewedAt < :toUtc " +
            "ORDER BY reviewedAt DESC, id ASC LIMIT :limit OFFSET :offset",
    )
    fun observePageBetween(
        fromUtc: Long,
        toUtc: Long,
        limit: Int,
        offset: Int,
    ): Flow<List<ReviewLogEntity>>

    /** 区间内自评「做对了」的次数，用于日汇总与周报。 */
    @Query(
        "SELECT COUNT(*) FROM review_log WHERE selfResult = :selfResult " +
            "AND reviewedAt >= :fromUtc AND reviewedAt < :toUtc",
    )
    suspend fun countBetween(
        selfResult: String,
        fromUtc: Long,
        toUtc: Long,
    ): Int

    @Query("SELECT COUNT(*) FROM review_log")
    fun observeTotal(): Flow<Int>

    @Query("SELECT COUNT(*) FROM review_log WHERE mistakeId = :mistakeId")
    suspend fun countOfMistake(mistakeId: String): Int

    @Upsert
    suspend fun upsert(entity: ReviewLogEntity)

    @Upsert
    suspend fun upsertAll(entities: List<ReviewLogEntity>)

    @Query("DELETE FROM review_log WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("DELETE FROM review_log")
    suspend fun deleteAll()
}
