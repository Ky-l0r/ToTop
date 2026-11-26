package com.totop.app.data.repo

import com.totop.app.data.local.dao.ForgottenKpointDao
import com.totop.app.data.local.entity.ForgottenKpointEntity
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
 * 陪伴模式产生的数据：问过的知识点。
 *
 * 0.9 起只剩这一类。以前还写「专注时长」（累加到 `daily_stats.studySeconds`，保留学科键 `_focus`），
 * 陪伴页去掉计时后没有秒数可写，这条写入与方法一并删除（见 docs/偏离记录.md 第 53 章）。
 * `daily_stats.studySeconds` 这一列本身没删：周报用例还在读它，删列要迁移，而它只是不再增长。
 *
 * 这里的时间参数一律由调用方传入 UTC 毫秒，便于单测固定时钟。
 */
@Singleton
class CompanionInsightRepository
    @Inject
    constructor(
        private val dao: ForgottenKpointDao,
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

        private fun ForgottenKpointEntity.toModel(): ForgottenKpoint =
            ForgottenKpoint(
                kpointId = kpointId,
                name = name,
                subject = subject,
                askCount = askCount,
                lastAskedAtUtc = lastAskedAt,
            )
    }
