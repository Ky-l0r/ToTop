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

    /**
     * 按科目删除知识点（种子数据「按科目替换」用）。
     *
     * 为什么必须按科目删：种子是逐科加载的，若每科都整表清空，
     * 加载第二科时就会把第一科刚写进去的知识点全部删掉，
     * 最终库里只剩最后一科——这正是「只有数学有知识点」的成因。
     */
    @Query("DELETE FROM knowledge_point WHERE subject = :subject")
    suspend fun deleteKnowledgePointsOfSubject(subject: String)

    /**
     * 按科目删除边。
     *
     * 边表本身没有 subject 列（不改表结构、不引入迁移），
     * 因此用「端点属于该科目」的子查询来界定：只要 fromId 或 toId 落在该科目内就删。
     */
    @Query(
        "DELETE FROM kpoint_edge WHERE fromId IN (SELECT id FROM knowledge_point WHERE subject = :subject) " +
            "OR toId IN (SELECT id FROM knowledge_point WHERE subject = :subject)",
    )
    suspend fun deleteEdgesOfSubject(subject: String)

    @Query("DELETE FROM kpoint_edge")
    suspend fun deleteAllEdges()
}
