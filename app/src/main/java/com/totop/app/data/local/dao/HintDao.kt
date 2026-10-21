package com.totop.app.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.totop.app.data.local.entity.HintLogEntity
import kotlinx.coroutines.flow.Flow

/**
 * `hint_log` 表访问对象（开发方案 5.1，level 1..3）。
 *
 * 「全靠提示想通」判定（FR-3.2 / MasteryCalculator.hintOnly）以本表的记录为依据。
 */
@Dao
interface HintDao {
    @Query("SELECT * FROM hint_log WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): HintLogEntity?

    @Query("SELECT * FROM hint_log WHERE mistakeId = :mistakeId ORDER BY createdAt DESC, id ASC")
    suspend fun byMistakeId(mistakeId: String): List<HintLogEntity>

    @Query("SELECT * FROM hint_log WHERE mistakeId = :mistakeId ORDER BY createdAt DESC, id ASC")
    fun observeByMistakeId(mistakeId: String): Flow<List<HintLogEntity>>

    /** 某错题已解锁到的最高提示级别；无记录返回 null。 */
    @Query("SELECT MAX(level) FROM hint_log WHERE mistakeId = :mistakeId")
    suspend fun maxLevelOfMistake(mistakeId: String): Int?

    @Query("SELECT MAX(level) FROM hint_log WHERE mistakeId = :mistakeId")
    fun observeMaxLevelOfMistake(mistakeId: String): Flow<Int?>

    @Query("SELECT COUNT(*) FROM hint_log WHERE mistakeId = :mistakeId")
    suspend fun countOfMistake(mistakeId: String): Int

    @Query("SELECT COUNT(*) FROM hint_log WHERE createdAt >= :fromUtc AND createdAt < :toUtc")
    suspend fun countBetween(
        fromUtc: Long,
        toUtc: Long,
    ): Int

    @Query("SELECT COUNT(*) FROM hint_log")
    fun observeTotal(): Flow<Int>

    @Upsert
    suspend fun upsert(entity: HintLogEntity)

    @Upsert
    suspend fun upsertAll(entities: List<HintLogEntity>)

    @Query("DELETE FROM hint_log WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("DELETE FROM hint_log")
    suspend fun deleteAll()
}
