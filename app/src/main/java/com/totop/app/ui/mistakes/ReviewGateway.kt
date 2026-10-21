package com.totop.app.ui.mistakes

import com.totop.app.data.local.AppDatabase
import com.totop.app.data.repo.MistakeRepository
import com.totop.app.domain.model.RecallSelfResult
import com.totop.app.domain.model.ReviewLog
import com.totop.app.domain.usecase.port.DomainClock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 复习数据端口（FR-4.2 / FR-4.4）。
 *
 * 与错题本同理，本任务的改动范围不允许碰 data 层，因此在 ui 层放一个窄适配层：
 * - 查询「今天该复习哪些题」需要 join 错题、题目、知识点与标准步骤；
 * - 写入一条自评只需要 review_log（间隔重复由 domain 的 Sm2Scheduler 决定）。
 *
 * 上层只看到领域模型 [ReviewCandidate]，看不到 SQL。
 */
interface ReviewGateway {
    /**
     * 取待复习队列。
     *
     * @param nowUtc 当前时刻（UTC 毫秒）
     * @param limit 单轮上限（默认 5，与 ReviewSchedule.DEFAULT_DAILY_LIMIT 一致）
     */
    suspend fun dueCandidates(
        nowUtc: Long,
        limit: Int = DEFAULT_LIMIT,
    ): List<ReviewCandidate>

    /** 写入一条自评记录（**零 token**，不触发任何模型调用）。 */
    suspend fun recordSelfResult(
        mistakeId: String,
        selfResult: RecallSelfResult,
        nowUtc: Long,
    )

    companion object {
        /** 单轮复习上限。 */
        const val DEFAULT_LIMIT: Int = 5
    }
}

/** Room 实现。 */
@Singleton
class RoomReviewGateway
    @Inject
    constructor(
        private val database: AppDatabase,
        private val mistakeRepository: MistakeRepository,
        @Suppress("unused") // 构造参数：调用方按名注入，当前实现未直接使用
        private val clock: DomainClock = DomainClock.SYSTEM,
    ) : ReviewGateway {
        private var dispatcher: CoroutineDispatcher = Dispatchers.IO

        internal fun withDispatcher(dispatcher: CoroutineDispatcher): RoomReviewGateway {
            this.dispatcher = dispatcher
            return this
        }

        override suspend fun dueCandidates(
            nowUtc: Long,
            limit: Int,
        ): List<ReviewCandidate> =
            withContext(dispatcher) {
                val bounded = limit.coerceIn(1, MAX_LIMIT)
                val ids =
                    database.query(
                        "SELECT mr.id AS id, mr.createdAt AS createdAt FROM mistake_record AS mr " +
                            "ORDER BY mr.createdAt ASC, mr.id ASC",
                        null,
                    ).use { row ->
                        val list = ArrayList<String>(row.count)
                        while (row.moveToNext()) {
                            list += row.getStringOrNull(0).orEmpty()
                        }
                        list
                    }
                if (ids.isEmpty()) return@withContext emptyList()

                val due = ArrayList<ReviewCandidate>(bounded)
                for (id in ids) {
                    if (due.size >= bounded) break
                    val candidate = candidateOf(id) ?: continue
                    if (isDue(candidate, nowUtc)) due += candidate
                }
                due.sortedBy { if (it.reviewHistory.isEmpty()) it.createdAtUtc else it.schedule.dueAtUtc }
            }

        /**
         * 写入一条自评记录。
         *
         * 只写 review_log 一行：间隔重复的下次到期时间由 domain 的 Sm2Scheduler 按历史重放得出，
         * 因此不需要额外表；这也是「自评零 token」的落点——整个过程没有任何模型调用。
         */
        override suspend fun recordSelfResult(
            mistakeId: String,
            selfResult: RecallSelfResult,
            nowUtc: Long,
        ) = withContext(dispatcher) {
            mistakeRepository.addReview(
                ReviewLog(
                    id = UUID.randomUUID().toString(),
                    mistakeId = mistakeId,
                    reviewedAtUtc = nowUtc,
                    selfResult = selfResult,
                ),
            )
        }

        /** 读取一道错题在复习界面需要的全部字段。 */
        private suspend fun candidateOf(mistakeId: String): ReviewCandidate? {
            val record = mistakeRepository.byId(mistakeId) ?: return null
            val history =
                mistakeRepository.reviewsOf(mistakeId)
                    .sortedBy { it.reviewedAtUtc }
                    .map {
                        ReviewPoint(
                            reviewedAtUtc = it.reviewedAtUtc,
                            gotIt = it.selfResult == RecallSelfResult.GOT_IT,
                        )
                    }
            val questionRow =
                database.query(
                    "SELECT q.stemText AS stemText, q.subject AS subject FROM question AS q " +
                        "INNER JOIN mistake_record AS mr ON mr.questionId = q.id WHERE mr.id = ? LIMIT 1",
                    arrayOf(mistakeId),
                ).use { row ->
                    if (row.moveToFirst()) {
                        row.getStringOrNull(0).orEmpty() to row.getStringOrNull(1).orEmpty()
                    } else {
                        "" to ""
                    }
                }
            val kpointNames =
                database.query(
                    "SELECT kp.name AS name FROM mistake_kpoint AS mk " +
                        "INNER JOIN knowledge_point AS kp ON kp.id = mk.kpointId WHERE mk.mistakeId = ? " +
                        "ORDER BY kp.chapter ASC, kp.orderIndex ASC",
                    arrayOf(mistakeId),
                ).use { row ->
                    val list = ArrayList<String>(row.count)
                    while (row.moveToNext()) {
                        row.getStringOrNull(0)?.let { list += it }
                    }
                    list
                }
            val hintsUsed = mistakeRepository.maxHintLevel(mistakeId)
            return ReviewCandidate(
                mistakeId = record.mistake.id,
                questionId = record.mistake.questionId,
                stemText = questionRow.first,
                subject = questionRow.second,
                errorTypeLabel = record.mistake.errorType.label,
                kpointNames = kpointNames,
                finalAnswer = record.mistake.finalAnswer,
                solutionSteps = record.mistake.correctSolution.steps.map { it.content },
                studentSteps = record.mistake.studentSteps.map { it.content },
                recurrenceIndex = record.mistake.recurrenceIndex,
                reviewHistory = history,
                hintsUsed = hintsUsed,
                createdAtUtc = record.mistake.createdAtUtc,
            )
        }

        companion object {
            /** 硬上限：单轮最多 20 道，避免会话过长。 */
            const val MAX_LIMIT: Int = 20
        }
    }
