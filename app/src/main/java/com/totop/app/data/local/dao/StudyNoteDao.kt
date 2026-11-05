package com.totop.app.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.totop.app.data.local.entity.StudyNoteEntity
import kotlinx.coroutines.flow.Flow

/** `study_note` 表访问对象（0.7.0）。 */
@Dao
interface StudyNoteDao {
    @Upsert
    suspend fun upsert(entity: StudyNoteEntity)

    @Query("SELECT * FROM study_note WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): StudyNoteEntity?

    @Query("SELECT * FROM study_note WHERE kpointId = :kpointId ORDER BY createdAtUtc DESC")
    fun observeByKpoint(kpointId: String): Flow<List<StudyNoteEntity>>

    @Query("SELECT * FROM study_note WHERE kpointId = :kpointId ORDER BY createdAtUtc DESC")
    suspend fun byKpoint(kpointId: String): List<StudyNoteEntity>

    /**
     * 按科目取笔记，供复习资料「我的笔记」一节使用。
     *
     * 走 knowledge_point 的 subject 列而不是在 study_note 里冗余一列：
     * 科目只属于知识点，笔记跟着知识点走，冗余一份会在科目改名或节点迁移后对不上。
     * 没有挂节点的笔记不参与（[kpointId] 为 null 时 JOIN 天然过滤掉）。
     */
    @Query(
        "SELECT study_note.* FROM study_note " +
            "INNER JOIN knowledge_point ON knowledge_point.id = study_note.kpointId " +
            "WHERE knowledge_point.subject = :subject " +
            "ORDER BY study_note.createdAtUtc DESC LIMIT :limit",
    )
    suspend fun bySubject(
        subject: String,
        limit: Int,
    ): List<StudyNoteEntity>

    /** 某科目有多少条已挂节点的笔记，用来解释「为什么这节是空的」。 */
    @Query(
        "SELECT COUNT(*) FROM study_note " +
            "INNER JOIN knowledge_point ON knowledge_point.id = study_note.kpointId " +
            "WHERE knowledge_point.subject = :subject",
    )
    suspend fun countBySubject(subject: String): Int

    @Query("SELECT COUNT(*) FROM study_note")
    fun observeTotal(): Flow<Int>

    @Query("DELETE FROM study_note WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("DELETE FROM study_note")
    suspend fun deleteAll()
}
