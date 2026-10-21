package com.totop.app.data.repo

import com.totop.app.domain.model.Mastery
import com.totop.app.domain.model.MasteryCalculator
import com.totop.app.domain.model.MasteryInput
import com.totop.app.domain.model.RecallEvent
import com.totop.app.domain.model.RecallSelfResult
import com.totop.app.domain.usecase.port.DAY_MILLIS
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 掌握度即时重算器。
 *
 * **为什么需要它**（这是真机反馈「错题本和薄弱知识点没有同步出现内容」的直接成因）：
 * 掌握度原来只由 [com.totop.app.work.DailyStatsWorker] 每天跑一次，
 * 而「还需要多练的知识点」页面、首页的薄弱项、知识点热力图全部只读 mastery 表。
 * 结果是：学生解完一道题、错题本里已经有了，但薄弱知识点要等到第二天才出现——
 * 中间这段时间里，用户看到的是「数据不同步」。
 *
 * 设计取舍：
 * - **不重新实现公式**：直接调用 domain 的 [MasteryCalculator]，与每日 Worker 口径完全一致；
 *   一旦有两套公式，界面上的分数与趋势图就会自相矛盾。
 * - **只算相关知识点**：解一道题通常命中 1-3 个知识点，没必要全量重扫。
 * - **失败不抛出**：掌握度是派生数据，写失败不该让一次成功的解题变成失败。
 * - **不做历史快照合并以外的裁剪**：历史保留交给 [KnowledgeRepository.saveMastery]（90 天）。
 */
@Singleton
class MasteryRecorder
    @Inject
    constructor(
        private val knowledgeRepository: KnowledgeRepository,
        private val mistakeRepository: MistakeRepository,
    ) {
        private var dispatcher: CoroutineDispatcher = Dispatchers.IO

        internal fun withDispatcher(dispatcher: CoroutineDispatcher): MasteryRecorder {
            this.dispatcher = dispatcher
            return this
        }

        /** 计算基准时间来源，便于单测注入。 */
        private var clock: () -> Long = { System.currentTimeMillis() }

        internal fun withClock(clock: () -> Long): MasteryRecorder {
            this.clock = clock
            return this
        }

        /**
         * 重算指定知识点的掌握度并写库。
         *
         * @param kpointIds 本次解题命中的知识点；为空时什么都不做（没有归属就没有可算的对象）
         * @return 实际写入的知识点数；失败返回 0
         */
        suspend fun recompute(kpointIds: List<String>): Int =
            withContext(dispatcher) {
                val targets = kpointIds.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                if (targets.isEmpty()) return@withContext 0
                val nowUtc = clock()
                var written = 0
                targets.forEach { kpointId ->
                    val ok =
                        runCatching {
                            val input = inputsOf(kpointId, nowUtc)
                            val score = MasteryCalculator.compute(input)
                            val previous = runCatching { knowledgeRepository.masteryOf(kpointId) }.getOrNull()
                            val mastery =
                                Mastery(
                                    kpointId = kpointId,
                                    score = score,
                                    history = mergedHistory(previous, score, nowUtc),
                                    updatedAtUtc = nowUtc,
                                )
                            knowledgeRepository.saveMastery(mastery)
                        }.isSuccess
                    if (ok) written++
                }
                written
            }

        /** 全量重算（设置页「立即重算掌握度」与每日 Worker 的补跑入口）。 */
        suspend fun recomputeAll(): Int =
            withContext(dispatcher) {
                val points =
                    runCatching { knowledgeRepository.catalogEnsuringSeeded().allKnowledgePoints() }
                        .getOrDefault(emptyList())
                if (points.isEmpty()) return@withContext 0
                recompute(points.map { it.id })
            }

        /**
         * 组装算法入参。
         *
         * 与 [com.totop.app.domain.usecase.BuildMasteryUseCase] 保持同一口径：
         * 错题频次取近 30 天、复习自评取近 30 天、覆盖度按练习次数 / 5 归一。
         */
        private suspend fun inputsOf(
            kpointId: String,
            nowUtc: Long,
        ): MasteryInput {
            val fromUtc = nowUtc - MasteryCalculator.MISTAKE_WINDOW_DAYS * DAY_MILLIS
            val mistakeTimes =
                runCatching {
                    mistakeRepository
                        .pageByKpoint(kpointId, PageRequest(page = 0, size = MAX_SAMPLES))
                        .map { it.createdAtUtc }
                        .filter { it in fromUtc..nowUtc }
                }.getOrDefault(emptyList())

            val reviews =
                runCatching {
                    mistakeRepository
                        .pageByKpoint(kpointId, PageRequest(page = 0, size = MAX_SAMPLES))
                        .flatMap { record -> mistakeRepository.reviewsOf(record.id) }
                        .filter { it.reviewedAtUtc in fromUtc..nowUtc }
                        .map { log ->
                            RecallEvent(
                                reviewedAtUtc = log.reviewedAtUtc,
                                selfResult = log.selfResult,
                            )
                        }
                }.getOrDefault(emptyList())

            val hintCount =
                runCatching {
                    mistakeRepository
                        .pageByKpoint(kpointId, PageRequest(page = 0, size = MAX_SAMPLES))
                        .sumOf { record -> mistakeRepository.hintsOf(record.id).size }
                }.getOrDefault(0)

            val practice = mistakeTimes.size + reviews.size
            return MasteryInput(
                recallEvents = reviews,
                mistakeAtUtc = mistakeTimes,
                nowUtc = nowUtc,
                coverage = (practice.toDouble() / COVERAGE_FULL_AT).coerceIn(0.0, 1.0),
                hintOnly = hintCount > 0 && reviews.any { it.selfResult == RecallSelfResult.GOT_IT },
            )
        }

        /** 合并当天快照：同一天只保留一条，其余按日期升序保留。 */
        private fun mergedHistory(
            previous: Mastery?,
            newScore: Double,
            nowUtc: Long,
        ): List<com.totop.app.domain.model.MasterySnapshot> {
            val today = nowUtc / DAY_MILLIS
            val kept = previous?.history.orEmpty().filter { it.dateEpochDayUtc != today }
            return (kept + com.totop.app.domain.model.MasterySnapshot(dateEpochDayUtc = today, score = newScore))
                .sortedBy { it.dateEpochDayUtc }
                .takeLast(KnowledgeRepository.HISTORY_KEEP_DAYS)
        }

        companion object {
            /** 覆盖度达到 1.0 所需的最少练习次数，与 BuildMasteryUseCase 一致。 */
            const val COVERAGE_FULL_AT: Int = 5

            /** 单个知识点最多回看多少条错题（避免极端数据量下拖慢主线程）。 */
            const val MAX_SAMPLES: Int = 200
        }
    }
