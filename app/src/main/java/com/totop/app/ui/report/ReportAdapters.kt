package com.totop.app.ui.report

import com.totop.app.data.local.AppDatabase
import com.totop.app.data.local.entity.ReportEntity
import com.totop.app.data.prefs.SecretStore
import com.totop.app.data.repo.DailyStatsRepository
import com.totop.app.data.repo.KnowledgeRepository
import com.totop.app.data.repo.MistakeRepository
import com.totop.app.data.repo.PageRequest
import com.totop.app.data.repo.ReportRepository
import com.totop.app.data.repo.SettingsRepository
import com.totop.app.domain.model.DailyStats
import com.totop.app.domain.model.KnowledgePoint
import com.totop.app.domain.model.KpointAssignment
import com.totop.app.domain.model.KpointEdge
import com.totop.app.domain.model.MistakeRecord
import com.totop.app.domain.model.ReviewLog
import com.totop.app.domain.usecase.port.DailyStatsStore
import com.totop.app.domain.usecase.port.DomainSettings
import com.totop.app.domain.usecase.port.KpointDirectory
import com.totop.app.domain.usecase.port.MistakeStore
import com.totop.app.domain.usecase.port.ReportStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/*
 * 领域端口在本机的实现（周报链路）。
 *
 * 背景：domain 的 [com.totop.app.domain.usecase.BuildWeeklyReportUseCase] 只依赖端口，
 * 端口的具体实现由 data 层提供；本任务的改动范围限定在 ui/ 四个包，
 * 因此在 ui/report 内做一组**薄适配器**，把既有 Repository 适配成端口，
 * 让「六项指标 + 模板渲染」的唯一实现仍然留在 domain 层（不在 UI 里复制一套统计口径）。
 *
 * 适配器只做转发与必要的分页聚合，不含任何业务规则。
 */

/** 并发安全的分页扫描工具：数据规模是单个学生的错题本，页大小 100 足够。 */
internal object Paging {
    const val PAGE_SIZE: Int = 100

    /**
     * 反复取页直到取完或达到上限。
     *
     * @param limit 最多取多少条（防止误传入无限循环）
     */
    suspend fun <T> collect(
        limit: Int = MAX_ITEMS,
        fetch: suspend (page: Int, size: Int) -> List<T>,
    ): List<T> {
        val bounded = limit.coerceIn(1, MAX_ITEMS)
        val result = ArrayList<T>(bounded)
        var page = 0
        while (result.size < bounded) {
            val batch = fetch(page, PAGE_SIZE)
            if (batch.isEmpty()) break
            result += batch.take(bounded - result.size)
            if (batch.size < PAGE_SIZE) break
            page++
        }
        return result
    }

    /** 单次聚合的硬上限。 */
    const val MAX_ITEMS: Int = 5000
}

/** [MistakeStore] 的 Room 实现。 */
@Singleton
class RoomMistakeStore
    @Inject
    constructor(
        private val mistakeRepository: MistakeRepository,
        private val database: AppDatabase,
    ) : MistakeStore {
        private var dispatcher: CoroutineDispatcher = Dispatchers.IO

        internal fun withDispatcher(dispatcher: CoroutineDispatcher): RoomMistakeStore {
            this.dispatcher = dispatcher
            return this
        }

        override suspend fun byId(id: String): MistakeStore.MistakeWithKpoints? =
            mistakeRepository.byId(id)?.let { row ->
                MistakeStore.MistakeWithKpoints(mistake = row.mistake, kpointIds = row.kpointIds)
            }

        override suspend fun byQuestionId(questionId: String): List<MistakeRecord> =
            mistakeRepository.byQuestionId(questionId)

        override suspend fun save(
            record: MistakeRecord,
            assignments: List<KpointAssignment>,
            recomputeRecurrence: Boolean,
        ): MistakeRecord = mistakeRepository.save(record, assignments, recomputeRecurrence)

        override suspend fun recent(limit: Int): List<MistakeRecord> =
            Paging.collect(limit) { page, size -> mistakeRepository.page(PageRequest(page, size)) }

        override suspend fun rangeOf(
            fromUtc: Long,
            toUtc: Long,
            limit: Int,
        ): List<MistakeRecord> =
            Paging.collect(limit) { page, size -> mistakeRepository.page(PageRequest(page, size)) }
                .filter { it.createdAtUtc in fromUtc until toUtc }

        override suspend fun kpointsOfMistake(mistakeId: String): List<String> =
            mistakeRepository.kpointsOfMistake(mistakeId)

        override suspend fun countCreatedBetween(
            fromUtc: Long,
            toUtc: Long,
        ): Int = mistakeRepository.countCreatedBetween(fromUtc, toUtc)

        override suspend fun mistakeTimesOfKpoint(
            kpointId: String,
            fromUtc: Long,
            toUtc: Long,
        ): List<Long> =
            Paging.collect { page, size -> mistakeRepository.pageByKpoint(kpointId, PageRequest(page, size)) }
                .map { it.createdAtUtc }
                .filter { it in fromUtc until toUtc }
                .sorted()

        override suspend fun errorTypeDistribution(
            fromUtc: Long,
            toUtc: Long,
        ): Map<String, Int> = mistakeRepository.errorTypeDistribution(fromUtc, toUtc)

        override suspend fun kpointDistribution(
            fromUtc: Long,
            toUtc: Long,
        ): Map<String, Int> = mistakeRepository.kpointDistribution(fromUtc, toUtc)

        override suspend fun mistakeIdsOfKpoint(kpointId: String): List<String> =
            Paging.collect { page, size -> mistakeRepository.pageByKpoint(kpointId, PageRequest(page, size)) }
                .map { it.id }

        override suspend fun addReview(log: ReviewLog) = mistakeRepository.addReview(log)

        override suspend fun reviewsOf(mistakeId: String): List<ReviewLog> = mistakeRepository.reviewsOf(mistakeId)

        override suspend fun reviewsOfKpoint(
            kpointId: String,
            fromUtc: Long,
            toUtc: Long,
        ): List<ReviewLog> =
            withContext(dispatcher) {
                val sql =
                    "SELECT rl.id AS id, rl.mistakeId AS mistakeId, rl.reviewedAt AS reviewedAt, " +
                        "rl.selfResult AS selfResult FROM review_log AS rl " +
                        "INNER JOIN mistake_kpoint AS mk ON mk.mistakeId = rl.mistakeId " +
                        "WHERE mk.kpointId = ? AND rl.reviewedAt >= ? AND rl.reviewedAt < ? " +
                        "ORDER BY rl.reviewedAt ASC"
                database.query(sql, arrayOf(kpointId, fromUtc, toUtc)).use { row ->
                    val list = ArrayList<ReviewLog>(row.count)
                    while (row.moveToNext()) {
                        val selfResult =
                            com.totop.app.domain.model.RecallSelfResult.fromWire(
                                if (row.isNull(3)) "" else row.getString(3),
                            )
                        if (selfResult != null) {
                            list +=
                                ReviewLog(
                                    id = row.getString(0),
                                    mistakeId = row.getString(1),
                                    reviewedAtUtc = row.getLong(2),
                                    selfResult = selfResult,
                                )
                        }
                    }
                    list
                }
            }

        /**
         * 区间计数。
         *
         * 特殊值 [CountKeys.PENDING]（周报「待复习」口径）：区间内新增、但还没有任何复习记录的错题数。
         * 用「区间新增错题 - 区间内已复习的不同错题」计算，不引入任何比率式评分指标。
         */
        override suspend fun reviewCountBetween(
            fromUtc: Long,
            toUtc: Long,
            selfResultWire: String,
        ): Int =
            if (selfResultWire == CountKeys.PENDING) {
                withContext(dispatcher) {
                    val sql =
                        "SELECT COUNT(*) FROM mistake_record AS mr WHERE mr.createdAt >= ? AND mr.createdAt < ? " +
                            "AND NOT EXISTS (SELECT 1 FROM review_log AS rl WHERE rl.mistakeId = mr.id)"
                    database.query(sql, arrayOf(fromUtc, toUtc)).use { row ->
                        if (row.moveToFirst()) row.getInt(0) else 0
                    }
                }
            } else {
                mistakeRepository.reviewCountBetween(fromUtc, toUtc, selfResultWire)
            }

        /** 计数键常量（与 domain 的 PENDING_REVIEW_WIRE 对应）。 */
        object CountKeys {
            const val PENDING: String = "pending"
        }
    }

/** [DailyStatsStore] 的 Room 实现。 */
@Singleton
class RoomDailyStatsStore
    @Inject
    constructor(
        private val repository: DailyStatsRepository,
    ) : DailyStatsStore {
        override suspend fun between(
            fromDateEpochDay: Long,
            toDateEpochDay: Long,
        ): List<DailyStats> = repository.between(fromDateEpochDay, toDateEpochDay)

        override suspend fun sumMistakeCount(
            fromDateEpochDay: Long,
            toDateEpochDay: Long,
        ): Int = repository.sumMistakeCount(fromDateEpochDay, toDateEpochDay)

        override suspend fun sumReviewDoneCount(
            fromDateEpochDay: Long,
            toDateEpochDay: Long,
        ): Int = repository.sumReviewDoneCount(fromDateEpochDay, toDateEpochDay)

        override suspend fun sumStudySeconds(
            fromDateEpochDay: Long,
            toDateEpochDay: Long,
        ): Long = repository.sumStudySeconds(fromDateEpochDay, toDateEpochDay)
    }

/** [KpointDirectory] 的 Room 实现。 */
@Singleton
class RoomKpointDirectory
    @Inject
    constructor(
        private val repository: KnowledgeRepository,
    ) : KpointDirectory {
        override suspend fun allKnowledgePoints(): List<KnowledgePoint> =
            repository.catalogEnsuringSeeded().allKnowledgePoints()

        override suspend fun knowledgePoint(id: String): KnowledgePoint? = repository.catalog().knowledgePoint(id)

        override suspend fun edges(): List<KpointEdge> = repository.edges()

        override suspend fun idByName(name: String): String? = repository.idByName(name)

        override suspend fun candidatesFor(
            stemText: String,
            subject: String,
            limit: Int,
        ): List<KnowledgePoint> {
            val points = allKnowledgePoints().filter { subject.isBlank() || it.subject == subject }
            val haystack = stemText.take(CANDIDATE_TEXT_LIMIT)
            return points
                .filter { it.parentId != null && it.name.isNotBlank() && haystack.contains(it.name) }
                .sortedByDescending { it.name.length }
                .take(limit.coerceIn(1, CANDIDATE_LIMIT))
        }

        override suspend fun prerequisiteIdsOf(kpointId: String): List<String> =
            repository.catalog().prerequisiteIdsOf(kpointId)

        companion object {
            /** 参与匹配的题干长度上限。 */
            const val CANDIDATE_TEXT_LIMIT: Int = 4000

            /** 候选上限。 */
            const val CANDIDATE_LIMIT: Int = 40
        }
    }

/** [ReportStore] 的 Room 实现。 */
@Singleton
class RoomReportStore
    @Inject
    constructor(
        private val repository: ReportRepository,
    ) : ReportStore {
        override suspend fun save(
            id: String,
            type: String,
            periodKey: String,
            statsJson: String,
            aiComment: String?,
            createdAtUtc: Long,
        ) = repository.save(
            ReportEntity(
                id = id,
                type = type,
                periodKey = periodKey,
                statsJson = statsJson,
                aiComment = aiComment,
                createdAt = createdAtUtc,
            ),
        )

        override suspend fun byPeriod(
            type: String,
            periodKey: String,
        ): ReportStore.ReportRecord? =
            repository.byPeriod(type, periodKey)?.let { row ->
                ReportStore.ReportRecord(
                    id = row.id,
                    type = row.type,
                    periodKey = row.periodKey,
                    statsJson = row.statsJson,
                    aiComment = row.aiComment,
                    createdAtUtc = row.createdAt,
                )
            }
    }

/** [DomainSettings] 的实现（只读少量开关）。 */
@Singleton
class RoomDomainSettings
    @Inject
    constructor(
        private val repository: SettingsRepository,
        private val secretStore: SecretStore,
    ) : DomainSettings {
        /** 周报 AI 解读开关：默认关闭（关闭时输出纯统计版，0 token）。 */
        override suspend fun reportAiEnabled(): Boolean =
            repository.getBoolean(SettingsRepository.Keys.REPORT_AI_ENABLED, false)

        /** 是否允许 AI 出题：默认开启；关闭后知识点详情页不再自动生成变式题。 */
        override suspend fun exerciseAiEnabled(): Boolean =
            repository.getBoolean(SettingsRepository.Keys.GENERATE_EXERCISE, true)

        /** 「简洁 / 详细」两档：当前实现固定简洁档（详细档留给大模型档位）。 */
        override suspend fun detailedOutput(): Boolean = false

        /**
         * 是否已配置可用 Key。
         *
         * 只问 [SecretStore]（加密存储）而不是 settings 表：
         * settings 表按安全约定**不存任何凭据**，用它判断配置状态必然失真。
         */
        override suspend fun llmConfigured(): Boolean = runCatching { secretStore.read().canCall }.getOrDefault(false)
    }
