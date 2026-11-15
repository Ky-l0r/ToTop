package com.totop.app.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.totop.app.data.local.entity.KpointMethodEntity
import kotlinx.coroutines.flow.Flow

/** `kpoint_method` 表访问对象。 */
@Dao
interface KpointMethodDao {
    @Upsert
    suspend fun upsert(entity: KpointMethodEntity)

    @Query("SELECT * FROM kpoint_method ORDER BY sampleCount DESC, updatedAtUtc DESC")
    fun observeAll(): Flow<List<KpointMethodEntity>>

    /** 合并前查重：同一知识点 + 同一归一化方法名。 */
    @Query("SELECT * FROM kpoint_method WHERE kpointId = :kpointId AND nameKey = :nameKey LIMIT 1")
    suspend fun find(
        kpointId: String,
        nameKey: String,
    ): KpointMethodEntity?

    /**
     * 按候选知识点召回。
     *
     * 排序用「被验证过几次」而不是时间：一张只出现过一次的方法卡参考价值很低，
     * 而同一知识点下最常用的那种解法，正是这一类题的通用路径。
     */
    @Query(
        "SELECT * FROM kpoint_method WHERE kpointId IN (:kpointIds) " +
            "ORDER BY sampleCount DESC, updatedAtUtc DESC LIMIT :limit",
    )
    suspend fun byKpoints(
        kpointIds: List<String>,
        limit: Int,
    ): List<KpointMethodEntity>

    @Query("SELECT COUNT(*) FROM kpoint_method")
    fun observeCount(): Flow<Int>

    @Query("DELETE FROM kpoint_method WHERE id = :id")
    suspend fun deleteById(id: String): Int
}
