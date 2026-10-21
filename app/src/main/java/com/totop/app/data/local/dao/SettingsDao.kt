package com.totop.app.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.totop.app.data.local.entity.SettingsEntity
import kotlinx.coroutines.flow.Flow

/**
 * `settings` 表访问对象（开发方案 5.1）。
 *
 * 只存非敏感配置：API Key 一律存 EncryptedSharedPreferences（方案 6.1），禁止写入本表。
 */
@Dao
interface SettingsDao {
    @Query("SELECT value FROM settings WHERE key = :key LIMIT 1")
    suspend fun value(key: String): String?

    @Query("SELECT value FROM settings WHERE key = :key LIMIT 1")
    fun observeValue(key: String): Flow<String?>

    @Query("SELECT * FROM settings WHERE key = :key LIMIT 1")
    suspend fun find(key: String): SettingsEntity?

    @Query("SELECT * FROM settings ORDER BY key ASC")
    suspend fun all(): List<SettingsEntity>

    @Query("SELECT * FROM settings ORDER BY key ASC")
    fun observeAll(): Flow<List<SettingsEntity>>

    @Query("SELECT COUNT(*) FROM settings WHERE key = :key")
    suspend fun contains(key: String): Int

    @Upsert
    suspend fun upsert(entity: SettingsEntity)

    @Upsert
    suspend fun upsertAll(entities: List<SettingsEntity>)

    @Query("DELETE FROM settings WHERE key = :key")
    suspend fun delete(key: String): Int
}
