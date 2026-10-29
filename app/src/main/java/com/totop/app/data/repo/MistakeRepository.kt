package com.totop.app.data.repo

import androidx.room.withTransaction
import com.totop.app.data.local.AppDatabase
import com.totop.app.data.local.dao.HintDao
import com.totop.app.data.local.dao.MistakeDao
import com.totop.app.data.local.dao.ReviewDao
import com.totop.app.data.local.entity.MistakeKpointEntity
import com.totop.app.data.local.mapper.EntityMappers.toDomain
import com.totop.app.data.local.mapper.EntityMappers.toEntity
import com.totop.app.domain.model.HintLog
import com.totop.app.domain.model.KpointAssignment
import com.totop.app.domain.model.MistakeRecord
import com.totop.app.domain.model.ReviewLog
import com.totop.app.domain.model.StandardSolution
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 错题仓库：错题本体 + 知识点归属 + 复习/提示记录。
 *
 * 单一本地数据源（无远端）；写操作在事务内完成「错题 + 归属」的原子提交，
 * 避免出现「错题已入库但知识点归属丢失」导致掌握度统计缺口的中间态。
 */
@Singleton
class MistakeRepository
    @Inject
    constructor(
        private val database: AppDatabase,
        private val mistakeDao: MistakeDao,
        private val reviewDao: ReviewDao,
        private val hintDao: HintDao,
    ) {
        private var dispatcher: CoroutineDispatcher = Dispatchers.IO

        internal fun withDispatcher(dispatcher: CoroutineDispatcher): MistakeRepository {
            this.dispatcher = dispatcher
            return this
        }

        /** 错题 + 其知识点归属。 */
        data class MistakeWithKpoints(
            val mistake: MistakeRecord,
            val kpointIds: List<String>,
        )

        /** mistake_kpoint 的一行投影。 */
        data class MistakeKpointLink(
            val mistakeId: String,
            val kpointId: String,
            val confidence: Double,
        )

        suspend fun byId(id: String): MistakeWithKpoints? =
            withContext(dispatcher) {
                val row = mistakeDao.findById(id) ?: return@withContext null
                MistakeWithKpoints(
                    mistake = row.toDomain(),
                    kpointIds = mistakeDao.kpointsOfMistake(id).map { it.kpointId },
                )
            }

        fun observeById(id: String): Flow<MistakeWithKpoints?> =
            combine(
                mistakeDao.observeById(id),
                mistakeDao.observeKpointIdsOfMistake(id),
            ) { row, kpointIds ->
                row?.let { MistakeWithKpoints(mistake = it.toDomain(), kpointIds = kpointIds) }
            }.flowOn(dispatcher)

        suspend fun byQuestionId(questionId: String): List<MistakeRecord> =
            withContext(dispatcher) { mistakeDao.byQuestionId(questionId).map { it.toDomain() } }

        fun observeByQuestionId(questionId: String): Flow<List<MistakeRecord>> =
            mistakeDao.observeByQuestionId(questionId).map { list -> list.map { it.toDomain() } }.flowOn(dispatcher)

        suspend fun page(request: PageRequest): List<MistakeRecord> =
            withContext(dispatcher) {
                mistakeDao.page(request.size, request.offset).map { it.toDomain() }
            }

        fun observePage(request: PageRequest): Flow<List<MistakeRecord>> =
            mistakeDao.observePage(request.size, request.offset)
                .map { list -> list.map { it.toDomain() } }
                .flowOn(dispatcher)

        fun observePageByErrorType(
            errorType: String,
            request: PageRequest,
        ): Flow<List<MistakeRecord>> =
            mistakeDao.observePageByErrorType(errorType, request.size, request.offset)
                .map { list -> list.map { it.toDomain() } }
                .flowOn(dispatcher)

        suspend fun pageByKpoint(
            kpointId: String,
            request: PageRequest,
        ): List<MistakeRecord> =
            withContext(dispatcher) {
                mistakeDao.pageByKpointId(kpointId, request.size, request.offset).map { it.toDomain() }
            }

        fun observePageByKpoint(
            kpointId: String,
            request: PageRequest,
        ): Flow<List<MistakeRecord>> =
            mistakeDao.observePageByKpointId(kpointId, request.size, request.offset)
                .map { list -> list.map { it.toDomain() } }
                .flowOn(dispatcher)

        /**
         * 按科目分页取错题（走 mistake_record JOIN question）。
         *
         * 之所以用 JOIN 而不是给 mistake_record 加 subject 列：
         * 那样需要升 DB version、写 Migration 并补迁移测试，
         * 而科目本来就存放在 question 表上，JOIN 是零迁移的正确做法。
         */
        suspend fun pageBySubject(
            subject: String,
            limit: Int,
            offset: Int,
        ): List<MistakeRecord> =
            withContext(dispatcher) {
                mistakeDao.pageBySubject(subject, limit, offset).map { it.toDomain() }
            }

        suspend fun count(): Int = withContext(dispatcher) { mistakeDao.count() }

        fun observeCount(): Flow<Int> = mistakeDao.observeCount().flowOn(dispatcher)

        /**
         * 保存错题与其知识点归属（FR-1.7 受控 ID）。
         *
         * 归属列表按 [KpointAssignment] 传入；重复的 kpointId 以最高置信度为准。
         * [recomputeRecurrence] 为 true 时按同知识点历史出错次数重算 recurrenceIndex（FR-4.5）。
         */
        suspend fun save(
            record: MistakeRecord,
            assignments: List<KpointAssignment> = emptyList(),
            recomputeRecurrence: Boolean = false,
        ): MistakeRecord =
            withContext(dispatcher) {
                database.withTransaction {
                    val resolved =
                        if (recomputeRecurrence) {
                            val prior =
                                assignments
                                    .map { mistakeDao.countOtherMistakesOfKpoint(it.kpointId, record.id) }
                                    .maxOrNull()
                                    ?: 0
                            record.copy(recurrenceIndex = prior + 1)
                        } else {
                            record
                        }
                    mistakeDao.upsert(resolved.toEntity())
                    mistakeDao.deleteKpointLinksOfMistake(resolved.id)
                    if (assignments.isNotEmpty()) {
                        val deduped =
                            assignments
                                .groupBy { it.kpointId }
                                .map { (kpointId, list) ->
                                    MistakeKpointEntity(
                                        mistakeId = resolved.id,
                                        kpointId = kpointId,
                                        confidence = list.maxOf { it.confidence },
                                    )
                                }
                        mistakeDao.insertKpointLinks(deduped)
                    }
                    resolved
                }
            }

        suspend fun updateKpoints(
            mistakeId: String,
            assignments: List<KpointAssignment>,
        ) = withContext(dispatcher) {
            database.withTransaction {
                mistakeDao.deleteKpointLinksOfMistake(mistakeId)
                mistakeDao.insertKpointLinks(
                    assignments
                        .groupBy { it.kpointId }
                        .map { (kpointId, list) ->
                            MistakeKpointEntity(
                                mistakeId = mistakeId,
                                kpointId = kpointId,
                                confidence = list.maxOf { it.confidence },
                            )
                        },
                )
            }
        }

        /**
         * 用追问得到的订正结果更新错题记录。
         *
         * 只改「结论」这一层：最终答案与标准解答里的答案字段。
         * 学生步骤、错因、知识点归属、复发计数一律不动——
         * 这次订正是**同一道题**的结论修正，不是重新做错一次，
         * 动别的字段会让掌握度与复习排期凭空多出一轮。
         *
         * 直接 upsert 整行（保留主键与其它列）而不是走 [save]：
         * [save] 会按 assignments 重写知识点归属，传空列表就把归属清空了。
         *
         * @return 错题是否存在并已更新
         */
        suspend fun applyCorrection(
            mistakeId: String,
            finalAnswer: String,
            solution: StandardSolution,
        ): Boolean =
            withContext(dispatcher) {
                val row = mistakeDao.findById(mistakeId) ?: return@withContext false
                val updated =
                    row.toDomain()
                        .copy(
                            finalAnswer = finalAnswer,
                            correctSolution = solution.copy(finalAnswer = finalAnswer),
                        ).toEntity()
                mistakeDao.upsert(updated)
                true
            }

        /** 某知识点下的错题归属（含置信度）。 */
        suspend fun linksOfKpoint(kpointId: String): List<MistakeKpointLink> =
            withContext(dispatcher) {
                mistakeDao.mistakesOfKpoint(kpointId).map {
                    MistakeKpointLink(mistakeId = it.mistakeId, kpointId = it.kpointId, confidence = it.confidence)
                }
            }

        suspend fun kpointsOfMistake(mistakeId: String): List<String> =
            withContext(dispatcher) {
                mistakeDao.kpointsOfMistake(mistakeId).map { it.kpointId }
            }

        fun observeKpointIdsOfMistake(mistakeId: String): Flow<List<String>> =
            mistakeDao.observeKpointIdsOfMistake(mistakeId).flowOn(dispatcher)

        suspend fun kpointIdsOfMistakes(mistakeIds: List<String>): List<String> =
            withContext(dispatcher) {
                if (mistakeIds.isEmpty()) emptyList() else mistakeDao.kpointIdsOfMistakes(mistakeIds)
            }

        suspend fun delete(id: String): Boolean = withContext(dispatcher) { mistakeDao.deleteById(id) > 0 }

        suspend fun deleteAll() = withContext(dispatcher) { mistakeDao.deleteAll() }

        // ---------- 复习记录 ----------

        suspend fun addReview(log: ReviewLog) = withContext(dispatcher) { reviewDao.upsert(log.toEntity()) }

        suspend fun reviewsOf(mistakeId: String): List<ReviewLog> =
            withContext(dispatcher) { reviewDao.byMistakeId(mistakeId).map { it.toDomain() } }

        fun observeReviewsOf(mistakeId: String): Flow<List<ReviewLog>> =
            reviewDao.observeByMistakeId(mistakeId).map { list -> list.map { it.toDomain() } }.flowOn(dispatcher)

        suspend fun reviewCountBetween(
            fromUtc: Long,
            toUtc: Long,
            selfResultWire: String,
        ): Int = withContext(dispatcher) { reviewDao.countBetween(selfResultWire, fromUtc, toUtc) }

        // ---------- 提示记录 ----------

        suspend fun addHint(log: HintLog) = withContext(dispatcher) { hintDao.upsert(log.toEntity()) }

        suspend fun hintsOf(mistakeId: String): List<HintLog> =
            withContext(dispatcher) { hintDao.byMistakeId(mistakeId).map { it.toDomain() } }

        fun observeHintsOf(mistakeId: String): Flow<List<HintLog>> =
            hintDao.observeByMistakeId(mistakeId).map { list -> list.map { it.toDomain() } }.flowOn(dispatcher)

        /** 已解锁的最高提示级别；无记录返回 0（对应「尚未提示」）。 */
        suspend fun maxHintLevel(mistakeId: String): Int =
            withContext(dispatcher) { hintDao.maxLevelOfMistake(mistakeId) ?: 0 }

        fun observeMaxHintLevel(mistakeId: String): Flow<Int> =
            hintDao.observeMaxLevelOfMistake(mistakeId).map { it ?: 0 }.flowOn(dispatcher)

        suspend fun hintCountBetween(
            fromUtc: Long,
            toUtc: Long,
        ): Int = withContext(dispatcher) { hintDao.countBetween(fromUtc, toUtc) }

        // ---------- 统计聚合（供 DailyStats / WeeklyReport 用例使用） ----------

        suspend fun countCreatedBetween(
            fromUtc: Long,
            toUtc: Long,
        ): Int = withContext(dispatcher) { mistakeDao.countCreatedBetween(fromUtc, toUtc) }

        suspend fun errorTypeDistribution(
            fromUtc: Long,
            toUtc: Long,
        ): Map<String, Int> =
            withContext(
                dispatcher,
            ) { mistakeDao.countByErrorTypeBetween(fromUtc, toUtc).associate { it.errorType to it.total } }

        suspend fun kpointDistribution(
            fromUtc: Long,
            toUtc: Long,
        ): Map<String, Int> =
            withContext(
                dispatcher,
            ) { mistakeDao.countByKpointBetween(fromUtc, toUtc).associate { it.kpointId to it.total } }
    }
