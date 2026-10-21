package com.totop.app.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.totop.app.data.local.entity.ExerciseEntity
import kotlinx.coroutines.flow.Flow

/** `exercise` 表访问对象（开发方案 5.1 / FR-9）。 */
@Dao
interface ExerciseDao {
    @Query("SELECT * FROM exercise WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): ExerciseEntity?

    @Query("SELECT * FROM exercise WHERE kpointId = :kpointId AND verified = 1 ORDER BY createdAt DESC, id ASC")
    suspend fun verifiedOfKpoint(kpointId: String): List<ExerciseEntity>

    @Query("SELECT * FROM exercise WHERE kpointId = :kpointId AND verified = 1 ORDER BY createdAt DESC, id ASC")
    fun observeVerifiedOfKpoint(kpointId: String): Flow<List<ExerciseEntity>>

    /** 巩固练习：按知识点与难度取已通过校验的题目，随机取 limit 条。 */
    @Query(
        "SELECT * FROM exercise WHERE kpointId = :kpointId AND difficulty = :difficulty AND verified = 1 " +
            "ORDER BY RANDOM() LIMIT :limit",
    )
    suspend fun randomVerified(
        kpointId: String,
        difficulty: String,
        limit: Int,
    ): List<ExerciseEntity>

    @Query("SELECT * FROM exercise WHERE verified = 1 ORDER BY createdAt DESC, id ASC LIMIT :limit OFFSET :offset")
    fun observeVerifiedPage(
        limit: Int,
        offset: Int,
    ): Flow<List<ExerciseEntity>>

    @Query("SELECT COUNT(*) FROM exercise WHERE verified = 1")
    suspend fun countVerified(): Int

    @Query("SELECT COUNT(*) FROM exercise")
    fun observeTotal(): Flow<Int>

    @Query("SELECT COUNT(*) FROM exercise WHERE verified = 0")
    suspend fun countRejected(): Int

    @Upsert
    suspend fun upsert(entity: ExerciseEntity)

    @Upsert
    suspend fun upsertAll(entities: List<ExerciseEntity>)

    @Query("DELETE FROM exercise WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("DELETE FROM exercise WHERE kpointId = :kpointId")
    suspend fun deleteOfKpoint(kpointId: String): Int

    @Query("DELETE FROM exercise")
    suspend fun deleteAll()
}
