package com.totop.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.totop.app.data.local.entity.KnowledgePointEntity
import com.totop.app.data.local.entity.KpointEdgeEntity
import kotlinx.coroutines.flow.Flow

/**
 * `knowledge_point` 与 `kpoint_edge` 表访问对象（内置种子数据）。
 *
 * 写入入口只有 SeedRepository：先做环检测（FR-7.3），再在同一事务里整树替换。
 * 因此这里只提供批量写与整表删除，不提供单点增删，避免绕过环检测。
 */
@Dao
interface KnowledgeDao {
    @Query("SELECT * FROM knowledge_point ORDER BY subject ASC, chapter ASC, orderIndex ASC, id ASC")
    suspend fun allKnowledgePoints(): List<KnowledgePointEntity>

    @Query("SELECT * FROM knowledge_point ORDER BY subject ASC, chapter ASC, orderIndex ASC, id ASC")
    fun observeKnowledgePoints(): Flow<List<KnowledgePointEntity>>

    @Query("SELECT * FROM knowledge_point WHERE id = :id LIMIT 1")
    suspend fun knowledgePoint(id: String): KnowledgePointEntity?

    @Query("SELECT * FROM knowledge_point WHERE id = :id LIMIT 1")
    fun observeKnowledgePoint(id: String): Flow<KnowledgePointEntity?>

    /** 根节点（parentId 为 null）。 */
    @Query(
        "SELECT * FROM knowledge_point WHERE parentId IS NULL " +
            "ORDER BY subject ASC, chapter ASC, orderIndex ASC, id ASC",
    )
    suspend fun rootKnowledgePoints(): List<KnowledgePointEntity>

    @Query(
        "SELECT * FROM knowledge_point WHERE parentId = :parentId " +
            "ORDER BY orderIndex ASC, id ASC",
    )
    suspend fun childrenOf(parentId: String): List<KnowledgePointEntity>

    @Query(
        "SELECT * FROM knowledge_point WHERE subject = :subject " +
            "ORDER BY chapter ASC, orderIndex ASC, id ASC",
    )
    suspend fun knowledgePointsOfSubject(subject: String): List<KnowledgePointEntity>

    @Query("SELECT COUNT(*) FROM knowledge_point")
    suspend fun countKnowledgePoints(): Int

    @Query("SELECT COUNT(*) FROM knowledge_point")
    fun observeKnowledgePointCount(): Flow<Int>

    @Query("SELECT * FROM kpoint_edge ORDER BY fromId ASC, toId ASC, type ASC")
    suspend fun allEdges(): List<KpointEdgeEntity>

    @Query("SELECT * FROM kpoint_edge ORDER BY fromId ASC, toId ASC, type ASC")
    fun observeEdges(): Flow<List<KpointEdgeEntity>>

    @Query("SELECT * FROM kpoint_edge WHERE type = :type ORDER BY fromId ASC, toId ASC")
    suspend fun edgesOfType(type: String): List<KpointEdgeEntity>

    @Query("SELECT * FROM kpoint_edge WHERE toId = :kpointId")
    suspend fun incomingEdges(kpointId: String): List<KpointEdgeEntity>

    @Query("SELECT * FROM kpoint_edge WHERE fromId = :kpointId")
    suspend fun outgoingEdges(kpointId: String): List<KpointEdgeEntity>

    @Query("SELECT COUNT(*) FROM kpoint_edge")
    suspend fun countEdges(): Int

    @Upsert
    suspend fun upsertKnowledgePoints(entities: List<KnowledgePointEntity>)

    @Upsert
    suspend fun upsertEdges(entities: List<KpointEdgeEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replaceEdges(entities: List<KpointEdgeEntity>): List<Long>

    @Query("DELETE FROM knowledge_point")
    suspend fun deleteAllKnowledgePoints()

    @Query("DELETE FROM kpoint_edge")
    suspend fun deleteAllEdges()
}
