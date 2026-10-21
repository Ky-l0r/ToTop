package com.totop.app.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.totop.app.data.local.entity.ReportEntity
import kotlinx.coroutines.flow.Flow

/** `report` 表访问对象（开发方案 5.1，周报 / 月报）。 */
@Dao
interface ReportDao {
    @Query("SELECT * FROM report WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): ReportEntity?

    /** 同一周期只保留一份报告（type + periodKey 唯一索引）。 */
    @Query("SELECT * FROM report WHERE type = :type AND periodKey = :periodKey LIMIT 1")
    suspend fun findByPeriod(
        type: String,
        periodKey: String,
    ): ReportEntity?

    @Query("SELECT * FROM report WHERE type = :type AND periodKey = :periodKey LIMIT 1")
    fun observeByPeriod(
        type: String,
        periodKey: String,
    ): Flow<ReportEntity?>

    @Query("SELECT * FROM report WHERE type = :type ORDER BY periodKey DESC LIMIT :limit")
    fun observeLatestOfType(
        type: String,
        limit: Int,
    ): Flow<List<ReportEntity>>

    @Query("SELECT * FROM report ORDER BY createdAt DESC, id ASC LIMIT :limit OFFSET :offset")
    fun observePage(
        limit: Int,
        offset: Int,
    ): Flow<List<ReportEntity>>

    @Query("SELECT COUNT(*) FROM report")
    fun observeTotal(): Flow<Int>

    @Upsert
    suspend fun upsert(entity: ReportEntity)

    @Upsert
    suspend fun upsertAll(entities: List<ReportEntity>)

    @Query("DELETE FROM report WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("DELETE FROM report")
    suspend fun deleteAll()
}
