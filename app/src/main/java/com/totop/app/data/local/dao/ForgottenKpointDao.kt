package com.totop.app.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.totop.app.data.local.entity.ForgottenKpointEntity
import kotlinx.coroutines.flow.Flow

/**
 * `forgotten_kpoint` 表访问对象。
 *
 * 排序统一为「问得多的在前，其次最近问的在前」：
 * 首页只展示三条，需要把真正反复卡住的点顶上来，
 * 而不是最近随口问过一次的那个。
 */
@Dao
interface ForgottenKpointDao {
    @Query("SELECT * FROM forgotten_kpoint ORDER BY askCount DESC, lastAskedAt DESC LIMIT :limit")
    suspend fun top(limit: Int): List<ForgottenKpointEntity>

    @Query("SELECT * FROM forgotten_kpoint ORDER BY askCount DESC, lastAskedAt DESC LIMIT :limit")
    fun observeTop(limit: Int): Flow<List<ForgottenKpointEntity>>

    @Query("SELECT * FROM forgotten_kpoint WHERE kpointId = :kpointId LIMIT 1")
    suspend fun find(kpointId: String): ForgottenKpointEntity?

    @Query("SELECT COUNT(*) FROM forgotten_kpoint")
    suspend fun count(): Int

    @Upsert
    suspend fun upsert(entity: ForgottenKpointEntity)

    @Query("DELETE FROM forgotten_kpoint")
    suspend fun deleteAll()
}
