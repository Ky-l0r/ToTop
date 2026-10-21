package com.totop.app.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.totop.app.data.local.entity.MasteryEntity
import kotlinx.coroutines.flow.Flow

/**
 * `mastery` 表访问对象（开发方案 5.1 / 5.2）。
 *
 * historyJson 的 90 天裁剪在写入前完成（见 MasteryRepository.upsert 与 [MasteryHistory]），
 * 数据库层只负责存取，不做隐式截断，方便导出与恢复时保留完整快照。
 */
@Dao
interface MasteryDao {
    @Query("SELECT * FROM mastery WHERE kpointId = :kpointId LIMIT 1")
    suspend fun findById(kpointId: String): MasteryEntity?

    @Query("SELECT * FROM mastery WHERE kpointId = :kpointId LIMIT 1")
    fun observeById(kpointId: String): Flow<MasteryEntity?>

    @Query("SELECT * FROM mastery ORDER BY score ASC, kpointId ASC")
    suspend fun all(): List<MasteryEntity>

    @Query("SELECT * FROM mastery ORDER BY score ASC, kpointId ASC")
    fun observeAll(): Flow<List<MasteryEntity>>

    /** 薄弱知识点（掌握度低于阈值）。 */
    @Query("SELECT * FROM mastery WHERE score < :threshold ORDER BY score ASC, kpointId ASC")
    suspend fun below(threshold: Double): List<MasteryEntity>

    @Query("SELECT * FROM mastery WHERE score < :threshold ORDER BY score ASC, kpointId ASC")
    fun observeBelow(threshold: Double): Flow<List<MasteryEntity>>

    @Query("SELECT AVG(score) FROM mastery")
    suspend fun averageScore(): Double?

    @Query("SELECT AVG(score) FROM mastery")
    fun observeAverageScore(): Flow<Double?>

    @Query("SELECT COUNT(*) FROM mastery")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM mastery WHERE score < :threshold")
    suspend fun countBelow(threshold: Double): Int

    @Upsert
    suspend fun upsert(entity: MasteryEntity)

    @Upsert
    suspend fun upsertAll(entities: List<MasteryEntity>)

    @Query("DELETE FROM mastery WHERE kpointId = :kpointId")
    suspend fun deleteById(kpointId: String): Int

    @Query("DELETE FROM mastery")
    suspend fun deleteAll()
}
