package com.totop.app.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.totop.app.data.local.entity.LearnedMaterialEntity
import kotlinx.coroutines.flow.Flow

/** `learned_material` 表访问对象。 */
@Dao
interface LearnedMaterialDao {
    @Upsert
    suspend fun upsert(entity: LearnedMaterialEntity)

    @Query("SELECT * FROM learned_material ORDER BY createdAtUtc DESC")
    fun observeAll(): Flow<List<LearnedMaterialEntity>>

    @Query("SELECT * FROM learned_material ORDER BY createdAtUtc DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<LearnedMaterialEntity>

    @Query("SELECT * FROM learned_material WHERE subject = :subject ORDER BY createdAtUtc DESC LIMIT :limit")
    suspend fun bySubject(
        subject: String,
        limit: Int,
    ): List<LearnedMaterialEntity>

    @Query("SELECT COUNT(*) FROM learned_material")
    fun observeCount(): Flow<Int>

    @Query("DELETE FROM learned_material WHERE id = :id")
    suspend fun deleteById(id: String): Int
}
