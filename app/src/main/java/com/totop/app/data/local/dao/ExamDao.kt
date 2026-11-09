package com.totop.app.data.local.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Upsert
import com.totop.app.data.local.entity.ExamScoreEntity
import com.totop.app.data.local.entity.ExamSessionEntity
import kotlinx.coroutines.flow.Flow

/** 一场考试及其各科成绩。 */
data class ExamWithScores(
    @Embedded val session: ExamSessionEntity,
    @Relation(parentColumn = "id", entityColumn = "sessionId")
    val scores: List<ExamScoreEntity>,
)

/**
 * `exam_session` / `exam_score` 访问对象（docs/学情与成绩趋势.md）。
 *
 * 查询一律走 `@Transaction`：场次与科目分两次查，不加事务可能读到「场次在、科目还没写完」的中间态。
 * 删除先子后父，不依赖 SQLite 的 foreign_keys 开关。
 */
@Dao
interface ExamDao {
    /** 某学期内的场次（按日期升序）。 */
    @Transaction
    @Query("SELECT * FROM exam_session WHERE occurredOn >= :fromDay AND occurredOn < :toDay ORDER BY occurredOn ASC")
    suspend fun sessionsBetween(
        fromDay: Long,
        toDay: Long,
    ): List<ExamWithScores>

    /** 全部场次，供页面按学期自行过滤（数据量在「每学期几十条」级别）。 */
    @Transaction
    @Query("SELECT * FROM exam_session ORDER BY occurredOn ASC")
    fun observeAll(): Flow<List<ExamWithScores>>

    /** 全部场次（挂起版，供一次性计算）。 */
    @Transaction
    @Query("SELECT * FROM exam_session ORDER BY occurredOn ASC")
    suspend fun allSessions(): List<ExamWithScores>

    @Transaction
    @Query("SELECT * FROM exam_session WHERE id = :id LIMIT 1")
    suspend fun sessionById(id: String): ExamWithScores?

    @Upsert
    suspend fun upsertSession(session: ExamSessionEntity)

    @Upsert
    suspend fun upsertScores(scores: List<ExamScoreEntity>)

    /**
     * 覆盖某场次的科目成绩：先清后写。
     *
     * 编辑时如果学生删掉了某一科，只做 upsert 会留下幽灵记录，
     * 所以整场重写而不是逐条合并。
     */
    @Query("DELETE FROM exam_score WHERE sessionId = :sessionId")
    suspend fun deleteScoresOf(sessionId: String)

    @Query("DELETE FROM exam_session WHERE id = :id")
    suspend fun deleteSession(id: String)

    @Query("SELECT COUNT(*) FROM exam_session")
    fun observeTotal(): Flow<Int>

    @Query("DELETE FROM exam_session")
    suspend fun deleteAllSessions()

    @Query("DELETE FROM exam_score")
    suspend fun deleteAllScores()
}
