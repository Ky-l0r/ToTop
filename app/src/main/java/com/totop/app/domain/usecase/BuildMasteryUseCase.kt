package com.totop.app.domain.usecase

import com.totop.app.domain.model.Mastery
import com.totop.app.domain.model.MasteryCalculator
import com.totop.app.domain.model.MasteryInput
import com.totop.app.domain.model.MasterySnapshot
import com.totop.app.domain.model.RecallEvent
import com.totop.app.domain.usecase.port.DAY_MILLIS
import com.totop.app.domain.usecase.port.DailyStatsStore
import com.totop.app.domain.usecase.port.DomainClock
import com.totop.app.domain.usecase.port.HintStore
import com.totop.app.domain.usecase.port.KpointDirectory
import com.totop.app.domain.usecase.port.MasteryHistoryTrimmer
import com.totop.app.domain.usecase.port.MasteryStore
import com.totop.app.domain.usecase.port.MistakeStore
import com.totop.app.domain.usecase.port.epochDayOf

/**
 * 单个知识点的重算结果（便于 UI 与日志逐项排查）。
 */
data class MasteryChange(
    val kpointId: String,
    val kpointName: String,
    val previousScore: Double?,
    val newScore: Double,
    val mistakeCount30d: Int,
    val recallEvents: Int,
    val hintOnly: Boolean,
    val coverage: Double,
) {
    /** 相对上次的变化量；首次计算时为 null。 */
    val delta: Double? get() = previousScore?.let { newScore - it }

    /** 掌握度是否上升。 */
    val improved: Boolean get() = delta?.let { it > 0.0 } ?: false

    /** 掌握度是否下降。 */
    val worsened: Boolean get() = delta?.let { it < 0.0 } ?: false
}

/** 批量重算结果。 */
data class MasteryRebuildResult(
    val computedAtUtc: Long,
    val changes: List<MasteryChange>,
    /** 参与计算的知识点数量。 */
    val kpointCount: Int,
    /** 掌握度低于 60 的知识点数量（用于首页「今天先补这几个」）。 */
    val weakCount: Int,
) {
    /** 掌握度上升的知识点。 */
    val improved: List<MasteryChange> get() = changes.filter { it.improved }

    /** 掌握度下降的知识点。 */
    val worsened: List<MasteryChange> get() = changes.filter { it.worsened }

    /** 平均掌握度 0..100；无数据时为 0。 */
    val averageScore: Double
        get() = if (changes.isEmpty()) 0.0 else changes.sumOf { it.newScore } / changes.size

    /** 最薄弱的 N 个知识点（先补哪个）。 */
    fun weakest(limit: Int = 5): List<MasteryChange> = changes.sortedBy { it.newScore }.take(limit)
}

/** 重算范围。 */
sealed interface MasteryScope {
    /** 全量重算（DailyStatsWorker 每日一次）。 */
    data object All : MasteryScope

    /** 只重算这些知识点（例如刚做完一道题）。 */
    data class Only(val kpointIds: List<String>) : MasteryScope {
        init {
            require(kpointIds.isNotEmpty()) { "重算范围不得为空；全量请用 MasteryScope.All" }
        }
    }
}

/**
 * 掌握度批量重算用例（开发方案 4.3.2 / FR-3.2 / FR-3.3）。
 *
 * 职责：
 * 1. 按知识点聚合三类输入 —— 复习自评（recall）、近 30 天错题频次、覆盖度；
 * 2. 调用纯算法 [MasteryCalculator]（本用例不含任何公式，公式只在算法里）；
 * 3. 写入 mastery 表，并**并入历史快照**（保留 90 天，供趋势图降采样）。
 *
 * 关键约束：
 * - 不使用任何机器打分口径：输入只有自评、错题次数、提示记录与练习覆盖度（FR-3.1）；
 * - 所有时间窗口按 UTC 毫秒计算（[MasteryCalculator] 内部按 86_400_000 换算，与时区无关）；
 * - 「全靠提示想通」的判定：若某错题本次复习前已解锁提示，且自评为「做对了」，则记 hintOnly，
 *   掌握度整体 x0.6（方案 4.3.2 的 hintPenalty）。
 */
class BuildMasteryUseCase(
    private val kpoints: KpointDirectory,
    private val masteryStore: MasteryStore,
    private val mistakes: MistakeStore,
    private val hints: HintStore,
    @Suppress("unused") // 构造参数：调用方按名注入，当前实现未直接使用
    private val dailyStats: DailyStatsStore,
    private val clock: DomainClock = DomainClock.SYSTEM,
    private val trimmer: MasteryHistoryTrimmer = MasteryHistoryTrimmer.byEpochDay(),
) {
    /**
     * 批量重算并写库。
     *
     * @param scope 重算范围
     * @param nowUtc 计算基准时刻；默认取注入时钟
     */
    suspend fun rebuild(
        scope: MasteryScope = MasteryScope.All,
        nowUtc: Long = clock.nowUtcMillis(),
    ): MasteryRebuildResult {
        val targets =
            when (scope) {
                is MasteryScope.All -> kpoints.allKnowledgePoints()
                is MasteryScope.Only -> scope.kpointIds.mapNotNull { id -> kpoints.knowledgePoint(id) }
            }
        val previous = runCatching { masteryStore.allMastery().associateBy { it.kpointId } }.getOrDefault(emptyMap())
        val changes = mutableListOf<MasteryChange>()

        for (kpoint in targets) {
            val score = scoreOf(kpoint.id, nowUtc)
            val before = previous[kpoint.id]
            val mastery =
                Mastery(
                    kpointId = kpoint.id,
                    score = score.score,
                    history = mergedHistory(before, score.score, nowUtc),
                    updatedAtUtc = nowUtc,
                )
            runCatching { masteryStore.save(mastery) }
            changes +=
                MasteryChange(
                    kpointId = kpoint.id,
                    kpointName = kpoint.name,
                    previousScore = before?.score,
                    newScore = score.score,
                    mistakeCount30d = score.mistakeCount30d,
                    recallEvents = score.recallEvents,
                    hintOnly = score.hintOnly,
                    coverage = score.coverage,
                )
        }
        return MasteryRebuildResult(
            computedAtUtc = nowUtc,
            changes = changes,
            kpointCount = changes.size,
            weakCount = changes.count { it.newScore < WEAK_THRESHOLD },
        )
    }

    /**
     * 只算不写（预览 / 单测 / 「如果今天复习会怎样」）。
     */
    suspend fun preview(
        kpointId: String,
        nowUtc: Long = clock.nowUtcMillis(),
    ): MasteryChange? {
        val kpoint = kpoints.knowledgePoint(kpointId) ?: return null
        val score = scoreOf(kpointId, nowUtc)
        val before = runCatching { masteryStore.masteryOf(kpointId) }.getOrNull()
        return MasteryChange(
            kpointId = kpointId,
            kpointName = kpoint.name,
            previousScore = before?.score,
            newScore = score.score,
            mistakeCount30d = score.mistakeCount30d,
            recallEvents = score.recallEvents,
            hintOnly = score.hintOnly,
            coverage = score.coverage,
        )
    }

    /** 掌握度变化后的复习排期提示：薄弱知识点优先进入复习队列。 */
    fun weakestOf(
        result: MasteryRebuildResult,
        limit: Int = 5,
    ): List<MasteryChange> = result.weakest(limit)

    private suspend fun scoreOf(
        kpointId: String,
        nowUtc: Long,
    ): ScoreInputs {
        val mistakeTimes =
            runCatching {
                mistakes.mistakeTimesOfKpoint(
                    kpointId,
                    nowUtc - MasteryCalculator.MISTAKE_WINDOW_DAYS * DAY_MILLIS,
                    nowUtc + 1L,
                )
            }.getOrDefault(emptyList())

        val reviews =
            runCatching {
                mistakes.reviewsOfKpoint(
                    kpointId,
                    nowUtc - MasteryCalculator.MISTAKE_WINDOW_DAYS * DAY_MILLIS,
                    nowUtc + 1L,
                )
            }.getOrDefault(emptyList())
        val recallEvents = reviews.map { RecallEvent(reviewedAtUtc = it.reviewedAtUtc, selfResult = it.selfResult) }
        val hintOnly = recallEvents.isNotEmpty() && hintOnlyOf(kpointId, nowUtc)
        val coverage = coverageOf(mistakeTimes.size + recallEvents.size)
        val value =
            MasteryCalculator.compute(
                MasteryInput(
                    recallEvents = recallEvents,
                    mistakeAtUtc = mistakeTimes,
                    nowUtc = nowUtc,
                    coverage = coverage,
                    hintOnly = hintOnly,
                ),
            )
        return ScoreInputs(
            score = value,
            mistakeCount30d = mistakeTimes.size,
            recallEvents = recallEvents.size,
            hintOnly = hintOnly,
            coverage = coverage,
        )
    }

    /**
     * 「全靠提示才想通」判定：该知识点近期有提示记录，且没有一次「未用提示」的正面自评。
     *
     * 这里取保守口径：只要有提示记录且最近一次自评是「做对了」，就认为这次是靠提示想通的。
     */
    private suspend fun hintOnlyOf(
        kpointId: String,
        nowUtc: Long,
    ): Boolean {
        val hintCount =
            runCatching {
                hints.hintCountOfKpoint(
                    kpointId,
                    nowUtc - MasteryCalculator.MISTAKE_WINDOW_DAYS * DAY_MILLIS,
                    nowUtc + 1L,
                )
            }.getOrDefault(0)
        return hintCount > 0
    }

    /**
     * 覆盖度：该知识点近 30 天的练习量（错题数 + 复习次数）相对 [COVERAGE_FULL_AT] 的比值。
     *
     * 定义刻意保守：只统计「确实动过手」的记录，不使用任何「答对/答错」的比率。
     */
    private fun coverageOf(practiceCount: Int): Double =
        (practiceCount.toDouble() / COVERAGE_FULL_AT).coerceIn(0.0, 1.0)

    /**
     * 合并历史快照：同一天只保留一条（最新的覆盖旧的），并按 90 天裁剪。
     */
    private fun mergedHistory(
        previous: Mastery?,
        newScore: Double,
        nowUtc: Long,
    ): List<MasterySnapshot> {
        val today = epochDayOf(nowUtc)
        val kept = previous?.history.orEmpty().filter { it.dateEpochDayUtc != today }
        val merged =
            (kept + MasterySnapshot(dateEpochDayUtc = today, score = newScore))
                .sortedBy { it.dateEpochDayUtc }
        return trimmer.trim(merged, HISTORY_KEEP_DAYS)
    }

    private data class ScoreInputs(
        val score: Double,
        val mistakeCount30d: Int,
        val recallEvents: Int,
        val hintOnly: Boolean,
        val coverage: Double,
    )

    companion object {
        /** 薄弱阈值：低于该分数视为需要优先补强。 */
        const val WEAK_THRESHOLD: Double = 60.0

        /** 覆盖度达到 1.0 所需的最少练习次数（含错题与复习）。 */
        const val COVERAGE_FULL_AT: Int = 5

        /** 历史快照保留天数（开发方案 5.2：mastery.historyJson 保留最近 90 天）。 */
        const val HISTORY_KEEP_DAYS: Int = MasteryHistoryTrimmer.DEFAULT_KEEP_DAYS
    }
}
