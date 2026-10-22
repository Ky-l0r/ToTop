package com.totop.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.totop.app.data.local.entity.MistakeKpointEntity
import com.totop.app.data.local.entity.MistakeRecordEntity
import kotlinx.coroutines.flow.Flow

/** 按科目的错题计数投影。 */
data class SubjectMistakeCount(
    val subject: String,
    val total: Int,
)

/**
 * `mistake_record` 与 `mistake_kpoint` 表访问对象。
 *
 * 多对多关系（mistake_kpoint）与错题本体放在同一 DAO：归属写入必须与错题写入同步，
 * 拆开会让「先写错题再写归属」的事务边界变得难以保证。
 * 事务由 Repository 通过 `AppDatabase.withTransaction` 组合这些方法完成。
 */
@Dao
interface MistakeDao {
    @Query("SELECT * FROM mistake_record WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): MistakeRecordEntity?

    @Query("SELECT * FROM mistake_record WHERE id = :id LIMIT 1")
    fun observeById(id: String): Flow<MistakeRecordEntity?>

    @Query("SELECT * FROM mistake_record WHERE questionId = :questionId ORDER BY createdAt DESC, id ASC")
    suspend fun byQuestionId(questionId: String): List<MistakeRecordEntity>

    @Query("SELECT * FROM mistake_record WHERE questionId = :questionId ORDER BY createdAt DESC, id ASC")
    fun observeByQuestionId(questionId: String): Flow<List<MistakeRecordEntity>>

    @Query("SELECT COUNT(*) FROM mistake_record")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM mistake_record")
    fun observeCount(): Flow<Int>

    @Query("SELECT * FROM mistake_record ORDER BY createdAt DESC, id ASC LIMIT :limit OFFSET :offset")
    suspend fun page(
        limit: Int,
        offset: Int,
    ): List<MistakeRecordEntity>

    @Query("SELECT * FROM mistake_record ORDER BY createdAt DESC, id ASC LIMIT :limit OFFSET :offset")
    fun observePage(
        limit: Int,
        offset: Int,
    ): Flow<List<MistakeRecordEntity>>

    @Query(
        "SELECT * FROM mistake_record WHERE errorType = :errorType " +
            "ORDER BY createdAt DESC, id ASC LIMIT :limit OFFSET :offset",
    )
    fun observePageByErrorType(
        errorType: String,
        limit: Int,
        offset: Int,
    ): Flow<List<MistakeRecordEntity>>

    /** 与某知识点相关的错题分页。 */
    @Query(
        "SELECT mr.* FROM mistake_record AS mr " +
            "INNER JOIN mistake_kpoint AS mk ON mk.mistakeId = mr.id " +
            "WHERE mk.kpointId = :kpointId " +
            "ORDER BY mr.createdAt DESC, mr.id ASC LIMIT :limit OFFSET :offset",
    )
    suspend fun pageByKpointId(
        kpointId: String,
        limit: Int,
        offset: Int,
    ): List<MistakeRecordEntity>

    @Query(
        "SELECT mr.* FROM mistake_record AS mr " +
            "INNER JOIN mistake_kpoint AS mk ON mk.mistakeId = mr.id " +
            "WHERE mk.kpointId = :kpointId " +
            "ORDER BY mr.createdAt DESC, mr.id ASC LIMIT :limit OFFSET :offset",
    )
    fun observePageByKpointId(
        kpointId: String,
        limit: Int,
        offset: Int,
    ): Flow<List<MistakeRecordEntity>>

    @Upsert
    suspend fun upsert(entity: MistakeRecordEntity)

    @Upsert
    suspend fun upsertAll(entities: List<MistakeRecordEntity>)

    @Query("DELETE FROM mistake_record WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("DELETE FROM mistake_record")
    suspend fun deleteAll()

    // ---------- mistake_kpoint 多对多 ----------

    @Query("SELECT * FROM mistake_kpoint WHERE mistakeId = :mistakeId")
    suspend fun kpointsOfMistake(mistakeId: String): List<MistakeKpointEntity>

    @Query("SELECT * FROM mistake_kpoint WHERE mistakeId = :mistakeId")
    fun observeKpointsOfMistake(mistakeId: String): Flow<List<MistakeKpointEntity>>

    @Query("SELECT * FROM mistake_kpoint WHERE kpointId = :kpointId")
    suspend fun mistakesOfKpoint(kpointId: String): List<MistakeKpointEntity>

    @Query("SELECT * FROM mistake_kpoint WHERE kpointId = :kpointId")
    fun observeMistakesOfKpoint(kpointId: String): Flow<List<MistakeKpointEntity>>

    @Query("SELECT DISTINCT kpointId FROM mistake_kpoint WHERE mistakeId = :mistakeId")
    fun observeKpointIdsOfMistake(mistakeId: String): Flow<List<String>>

    @Query("SELECT DISTINCT mistakeId FROM mistake_kpoint WHERE kpointId = :kpointId")
    fun observeMistakeIdsOfKpoint(kpointId: String): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertKpointLinks(links: List<MistakeKpointEntity>): List<Long>

    @Upsert
    suspend fun upsertKpointLink(link: MistakeKpointEntity)

    @Query("DELETE FROM mistake_kpoint WHERE mistakeId = :mistakeId")
    suspend fun deleteKpointLinksOfMistake(mistakeId: String)

    @Query("DELETE FROM mistake_kpoint WHERE mistakeId = :mistakeId AND kpointId = :kpointId")
    suspend fun deleteKpointLink(
        mistakeId: String,
        kpointId: String,
    )

    @Query("SELECT COUNT(*) FROM mistake_kpoint WHERE kpointId = :kpointId")
    suspend fun countMistakesOfKpoint(kpointId: String): Int

    @Query("SELECT DISTINCT kpointId FROM mistake_kpoint WHERE mistakeId IN (:mistakeIds)")
    suspend fun kpointIdsOfMistakes(mistakeIds: List<String>): List<String>

    // ---------- 日汇总与统计用聚合 ----------

    /** 聚合区间内新增错题数（左闭右开，UTC 毫秒）。 */
    @Query("SELECT COUNT(*) FROM mistake_record WHERE createdAt >= :fromUtc AND createdAt < :toUtc")
    suspend fun countCreatedBetween(
        fromUtc: Long,
        toUtc: Long,
    ): Int

    @Query("SELECT COUNT(*) FROM mistake_record")
    fun observeTotal(): Flow<Int>

    /** 按错因统计，用于周报「错因分布」。 */
    @Query(
        "SELECT errorType AS errorType, COUNT(*) AS total FROM mistake_record " +
            "WHERE createdAt >= :fromUtc AND createdAt < :toUtc GROUP BY errorType ORDER BY total DESC",
    )
    suspend fun countByErrorTypeBetween(
        fromUtc: Long,
        toUtc: Long,
    ): List<ErrorTypeCount>

    /** 按知识点统计错题数，掌握度与知识点详情页使用。 */
    @Query(
        "SELECT mk.kpointId AS kpointId, COUNT(*) AS total FROM mistake_kpoint AS mk " +
            "INNER JOIN mistake_record AS mr ON mr.id = mk.mistakeId " +
            "WHERE mr.createdAt >= :fromUtc AND mr.createdAt < :toUtc " +
            "GROUP BY mk.kpointId ORDER BY total DESC",
    )
    suspend fun countByKpointBetween(
        fromUtc: Long,
        toUtc: Long,
    ): List<KpointMistakeCount>

    /** 同知识点已有的出错次数，用于计算 recurrenceIndex（首次为 1）。 */
    @Query(
        "SELECT COUNT(DISTINCT mk.mistakeId) FROM mistake_kpoint AS mk " +
            "INNER JOIN mistake_record AS mr ON mr.id = mk.mistakeId " +
            "WHERE mk.kpointId = :kpointId AND mr.id <> :excludeMistakeId",
    )
    suspend fun countOtherMistakesOfKpoint(
        kpointId: String,
        excludeMistakeId: String,
    ): Int

    // ---------- 按科目聚合（JOIN question，零迁移） ----------

    /**
     * 按科目统计错题数。
     *
     * mistake_record 本身没有科目标识，科目在 question.subject 上。
     * 用 JOIN 取是本项目的零迁移方案：不改表、不写 Room 迁移，
     * 因此不会触碰任何既有用户数据，也不可能因迁移失败丢错题。
     */
    @Query(
        "SELECT q.subject AS subject, COUNT(*) AS total " +
            "FROM mistake_record AS mr " +
            "INNER JOIN question AS q ON q.id = mr.questionId " +
            "GROUP BY q.subject ORDER BY total DESC",
    )
    suspend fun countGroupBySubject(): List<SubjectMistakeCount>

    /** 指定科目的错题数。 */
    @Query(
        "SELECT COUNT(*) FROM mistake_record AS mr " +
            "INNER JOIN question AS q ON q.id = mr.questionId " +
            "WHERE q.subject = :subject",
    )
    suspend fun countBySubject(subject: String): Int

    /** 指定科目的错题分页。 */
    @Query(
        "SELECT mr.* FROM mistake_record AS mr " +
            "INNER JOIN question AS q ON q.id = mr.questionId " +
            "WHERE q.subject = :subject " +
            "ORDER BY mr.createdAt DESC, mr.id ASC LIMIT :limit OFFSET :offset",
    )
    suspend fun pageBySubject(
        subject: String,
        limit: Int,
        offset: Int,
    ): List<MistakeRecordEntity>

    /** 指定科目下各知识点的错题数（用于「还需要多练的知识点」）。 */
    @Query(
        "SELECT mk.kpointId AS kpointId, COUNT(*) AS total FROM mistake_kpoint AS mk " +
            "INNER JOIN mistake_record AS mr ON mr.id = mk.mistakeId " +
            "INNER JOIN question AS q ON q.id = mr.questionId " +
            "WHERE q.subject = :subject " +
            "GROUP BY mk.kpointId ORDER BY total DESC LIMIT :limit",
    )
    suspend fun kpointCountBySubject(
        subject: String,
        limit: Int,
    ): List<KpointMistakeCount>
}

/** 错因计数投影。 */
data class ErrorTypeCount(val errorType: String, val total: Int)

/** 知识点错题计数投影。 */
data class KpointMistakeCount(val kpointId: String, val total: Int)
