package com.totop.app.data.repo

import com.totop.app.data.local.dao.DailyStatsDao
import com.totop.app.data.local.mapper.EntityMappers
import com.totop.app.data.local.mapper.EntityMappers.toEntity
import com.totop.app.domain.model.DailyStats
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 每日汇总仓库（开发方案 5.1 `daily_stats`，主键 (date, subject)，Worker 使用 UPSERT）。
 *
 * date 为 UTC 日序号（自 1970-01-01 起的天数）。日序号的换算放在 util 层，
 * 本仓库只接受已换算好的值，避免在数据层引入时区逻辑。
 */
@Singleton
class DailyStatsRepository
    @Inject
    constructor(
        private val dao: DailyStatsDao,
    ) {
        private var dispatcher: CoroutineDispatcher = Dispatchers.IO

        internal fun withDispatcher(dispatcher: CoroutineDispatcher): DailyStatsRepository {
            this.dispatcher = dispatcher
            return this
        }

        suspend fun upsert(stats: DailyStats): DailyStats =
            withContext(dispatcher) {
                dao.upsert(with(EntityMappers) { stats.toEntity() })
                stats
            }

        suspend fun upsertAll(items: List<DailyStats>): List<DailyStats> =
            withContext(dispatcher) {
                dao.upsertAll(items.map { with(EntityMappers) { it.toEntity() } })
                items
            }

        /**
         * 累加写入：同一天同一学科累加各计数（Worker 增量汇总使用）。
         *
         * 与 [upsert] 的区别：upsert 是覆盖，累加是读取现有行后相加再写回，
         * 因此必须在事务内调用；本方法由 DailyStatsWorker 在单线程串行执行。
         */
        suspend fun accumulate(delta: DailyStats): DailyStats =
            withContext(dispatcher) {
                val existing = dao.find(delta.dateEpochDayUtc, delta.subject)
                val merged =
                    if (existing == null) {
                        delta
                    } else {
                        DailyStats(
                            dateEpochDayUtc = delta.dateEpochDayUtc,
                            subject = delta.subject,
                            mistakeCount = existing.mistakeCount + delta.mistakeCount,
                            kpointCount = maxOf(existing.kpointCount, delta.kpointCount),
                            hintCount = existing.hintCount + delta.hintCount,
                            reviewDoneCount = existing.reviewDoneCount + delta.reviewDoneCount,
                            studySeconds = existing.studySeconds + delta.studySeconds,
                        )
                    }
                dao.upsert(with(EntityMappers) { merged.toEntity() })
                merged
            }

        suspend fun get(
            dateEpochDayUtc: Long,
            subject: String,
        ): DailyStats? =
            withContext(dispatcher) {
                dao.find(dateEpochDayUtc, subject)?.let { with(EntityMappers) { it.toDomain() } }
            }

        fun observe(
            dateEpochDayUtc: Long,
            subject: String,
        ): Flow<DailyStats?> =
            dao.observe(dateEpochDayUtc, subject)
                .map { row -> row?.let { with(EntityMappers) { it.toDomain() } } }
                .flowOn(dispatcher)

        suspend fun between(
            fromDate: Long,
            toDate: Long,
        ): List<DailyStats> =
            withContext(dispatcher) {
                dao.between(fromDate, toDate).map { with(EntityMappers) { it.toDomain() } }
            }

        fun observeBetween(
            fromDate: Long,
            toDate: Long,
        ): Flow<List<DailyStats>> =
            dao.observeBetween(fromDate, toDate)
                .map { rows -> rows.map { with(EntityMappers) { it.toDomain() } } }
                .flowOn(dispatcher)

        fun observeLatestOfSubject(
            subject: String,
            days: Int = TREND_DAYS,
        ): Flow<List<DailyStats>> =
            dao.observeLatestOfSubject(subject, days)
                .map { rows -> rows.map { with(EntityMappers) { it.toDomain() } }.sortedBy { it.dateEpochDayUtc } }
                .flowOn(dispatcher)

        suspend fun sumMistakeCount(
            fromDate: Long,
            toDate: Long,
        ): Int = withContext(dispatcher) { dao.sumMistakeCount(fromDate, toDate) }

        suspend fun sumReviewDoneCount(
            fromDate: Long,
            toDate: Long,
        ): Int = withContext(dispatcher) { dao.sumReviewDoneCount(fromDate, toDate) }

        suspend fun sumStudySeconds(
            fromDate: Long,
            toDate: Long,
        ): Long = withContext(dispatcher) { dao.sumStudySeconds(fromDate, toDate) }

        /** 保留策略：清理早于指定日期的汇总。 */
        suspend fun purgeOlderThan(keepFromDate: Long): Int =
            withContext(dispatcher) { dao.deleteOlderThan(keepFromDate) }

        suspend fun clear() = withContext(dispatcher) { dao.deleteAll() }

        companion object {
            /** 趋势图默认窗口（天）。 */
            const val TREND_DAYS: Int = 14
        }
    }
