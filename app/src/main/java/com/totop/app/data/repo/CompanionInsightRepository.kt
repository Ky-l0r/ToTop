package com.totop.app.data.repo

import com.totop.app.data.local.dao.ForgottenKpointDao
import com.totop.app.data.local.entity.ForgottenKpointEntity
import com.totop.app.domain.model.DailyStats
import com.totop.app.domain.model.KnowledgePoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** 一个「问过的知识点」。 */
data class ForgottenKpoint(
    val kpointId: String,
    val name: String,
    val subject: String,
    val askCount: Int,
    val lastAskedAtUtc: Long,
)

/**
 * 陪伴模式产生的两类数据：问过的知识点、专注时长。
 *
 * 专注时长直接累加到 `daily_stats`（`studySeconds`），用保留学科键 [FOCUS_SUBJECT]：
 * - 学情报告的「学习时长」与「活跃天数」都从这张表聚合，写进去就自动生效，
 *   不必再改周报用例与它的测试；
 * - 保留键以下划线开头，不会被当成真实学科展示；
 * - 独立建一张"专注表"会让报告同时存在两个"时长"口径，反而需要解释哪个才是学习时间。
 *
 * 这里的时间参数一律由调用方传入 UTC 毫秒，便于单测固定时钟。
 */
@Singleton
class CompanionInsightRepository
    @Inject
    constructor(
        private val dao: ForgottenKpointDao,
        private val dailyStats: DailyStatsRepository,
    ) {
        /**
         * 记录本次提问涉及的知识点。
         *
         * 只累加次数与时间，不新建知识点：ID 来自内置目录，目录里没有的点不会被传进来。
         */
        suspend fun recordAsked(
            points: List<KnowledgePoint>,
            nowUtc: Long,
        ) {
            points.distinctBy { it.id }.forEach { point ->
                val existing = dao.find(point.id)
                dao.upsert(
                    ForgottenKpointEntity(
                        kpointId = point.id,
                        name = point.name,
                        subject = point.subject,
                        askCount = (existing?.askCount ?: 0) + 1,
                        lastAskedAt = nowUtc,
                    ),
                )
            }
        }

        /** 问得最多的前几条，首页与复习资料用。 */
        suspend fun topForgotten(limit: Int): List<ForgottenKpoint> = dao.top(limit).map { it.toModel() }

        fun observeTopForgotten(limit: Int): Flow<List<ForgottenKpoint>> =
            dao.observeTop(limit).map { rows -> rows.map { it.toModel() } }

        /** 全部易忘点（复习资料按学科筛选时用）。 */
        suspend fun forgottenOfSubject(subject: String): List<ForgottenKpoint> =
            dao.top(Int.MAX_VALUE).map { it.toModel() }.filter { it.subject == subject }

        /** 累加专注时长到当天的 `daily_stats`。 */
        suspend fun addFocusSeconds(
            seconds: Long,
            nowUtc: Long,
        ) {
            if (seconds <= 0L) return
            dailyStats.accumulate(
                DailyStats(
                    // 与 daily_stats 既有口径一致：UTC 日序号（自 1970-01-01 起的天数）
                    dateEpochDayUtc = Math.floorDiv(nowUtc, MILLIS_PER_DAY),
                    subject = FOCUS_SUBJECT,
                    studySeconds = seconds,
                ),
            )
        }

        /**
         * 当天已经记过的专注秒数。
         *
         * 陪伴页进入时读一次当作起点，顶栏据此显示「今天已写 N 分钟」——
         * 只算本次进入后的秒数会让人以为数据丢了：写完一段去看别的页面再回来，
         * 数字从零重新开始，比不显示更糟。
         */
        suspend fun todayFocusSeconds(nowUtc: Long = System.currentTimeMillis()): Long =
            dailyStats.get(UtcDay.of(nowUtc), FOCUS_SUBJECT)?.studySeconds ?: 0L

        private fun ForgottenKpointEntity.toModel(): ForgottenKpoint =
            ForgottenKpoint(
                kpointId = kpointId,
                name = name,
                subject = subject,
                askCount = askCount,
                lastAskedAtUtc = lastAskedAt,
            )

        private companion object {
            /** 专注时长在 daily_stats 里使用的保留学科键。 */
            const val FOCUS_SUBJECT: String = "_focus"

            const val MILLIS_PER_DAY: Long = 86_400_000L
        }
    }
