package com.totop.app.data.repo

import androidx.room.withTransaction
import com.totop.app.data.local.AppDatabase
import com.totop.app.data.local.dao.ExamDao
import com.totop.app.data.local.dao.ExamWithScores
import com.totop.app.data.local.entity.ExamScoreEntity
import com.totop.app.data.local.entity.ExamSessionEntity
import com.totop.app.domain.model.ExamKind
import com.totop.app.domain.model.ExamRecord
import com.totop.app.domain.model.ExamScore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 考试成绩仓库（docs/学情与成绩趋势.md）。
 *
 * 只做搬运与事务边界：字段口径、平均方式、完整场次判定全部在 domain 层，
 * 这里不重复任何业务规则。
 */
@Singleton
class ExamRepository
    @Inject
    constructor(
        private val database: AppDatabase,
        private val dao: ExamDao,
    ) {
        private var dispatcher: CoroutineDispatcher = Dispatchers.IO

        internal fun withDispatcher(dispatcher: CoroutineDispatcher): ExamRepository {
            this.dispatcher = dispatcher
            return this
        }

        /** 全部场次（升序）。数据量小，页面按学期自行过滤，避免每次切学期都重新查库。 */
        fun observeAll(): Flow<List<ExamRecord>> =
            dao.observeAll().map { rows -> rows.map { it.toDomain() } }.flowOn(dispatcher)

        suspend fun all(): List<ExamRecord> = withContext(dispatcher) { dao.allSessions().map { it.toDomain() } }

        suspend fun byId(id: String): ExamRecord? = withContext(dispatcher) { dao.sessionById(id)?.toDomain() }

        /**
         * 保存整场考试（新增或覆盖）。
         *
         * 先删该场全部科目再写入：编辑时学生可能删掉某一科，只做 upsert 会留下幽灵成绩，
         * 而幽灵成绩会直接把「完整场次」判成真。
         */
        suspend fun save(record: ExamRecord): ExamRecord =
            withContext(dispatcher) {
                database.withTransaction {
                    dao.upsertSession(record.toSessionEntity())
                    dao.deleteScoresOf(record.id)
                    dao.upsertScores(record.scores.map { it.toEntity(record.id) })
                }
                record
            }

        /** 删除整场考试（连带科目）。 */
        suspend fun delete(id: String): Boolean =
            withContext(dispatcher) {
                database.withTransaction {
                    dao.deleteScoresOf(id)
                    dao.deleteSession(id)
                }
                true
            }

        /** 归档用：全部清空（备份恢复前调用）。 */
        suspend fun deleteAll() =
            withContext(dispatcher) {
                database.withTransaction {
                    dao.deleteAllScores()
                    dao.deleteAllSessions()
                }
            }

        private fun ExamWithScores.toDomain(): ExamRecord =
            ExamRecord(
                id = session.id,
                occurredOn = session.occurredOn,
                kind = ExamKind.fromWire(session.kind),
                name = session.name,
                gradeSize = session.gradeSize,
                classSize = session.classSize,
                gradeRank = session.gradeRank,
                classRank = session.classRank,
                scores = scores.map { it.toDomain() },
                createdAtUtc = session.createdAt,
                updatedAtUtc = session.updatedAt,
            )

        private fun ExamScoreEntity.toDomain(): ExamScore =
            ExamScore(
                subject = subject,
                score = score,
                fullScore = fullScore,
                gradeRank = gradeRank,
                classRank = classRank,
            )

        private fun ExamRecord.toSessionEntity(): ExamSessionEntity =
            ExamSessionEntity(
                id = id,
                occurredOn = occurredOn,
                kind = kind.wire,
                name = name?.trim()?.takeIf { it.isNotEmpty() },
                gradeSize = gradeSize,
                classSize = classSize,
                gradeRank = gradeRank,
                classRank = classRank,
                createdAt = createdAtUtc,
                updatedAt = updatedAtUtc,
            )

        private fun ExamScore.toEntity(sessionId: String): ExamScoreEntity =
            ExamScoreEntity(
                id = sessionId + "#" + subject,
                sessionId = sessionId,
                subject = subject,
                score = score,
                fullScore = fullScore,
                gradeRank = gradeRank,
                classRank = classRank,
            )
    }
