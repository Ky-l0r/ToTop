package com.totop.app.domain.usecase

import com.totop.app.domain.model.DailyStats
import com.totop.app.domain.model.ErrorType
import com.totop.app.domain.model.ErrorTypeMapper
import com.totop.app.domain.usecase.port.DAY_MILLIS
import com.totop.app.domain.usecase.port.DailyStatsStore
import com.totop.app.domain.usecase.port.DomainClock
import com.totop.app.domain.usecase.port.DomainSettings
import com.totop.app.domain.usecase.port.IdGenerator
import com.totop.app.domain.usecase.port.KpointDirectory
import com.totop.app.domain.usecase.port.MistakeStore
import com.totop.app.domain.usecase.port.ReportNarrator
import com.totop.app.domain.usecase.port.ReportStore
import com.totop.app.domain.usecase.port.epochDayOf
import com.totop.app.domain.usecase.port.startOfUtcDay
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 报告周期（周报 / 月报，FR-5）。 */
enum class ReportPeriod(val type: String, val label: String, val days: Int) {
    WEEKLY("weekly", "周报", 7),
    MONTHLY("monthly", "月报", 30),
}

/** 一个错因的计数。 */
@Serializable
data class ErrorTypeCount(
    val code: String,
    val label: String,
    val count: Int,
)

/** 一个知识点的复发计数。 */
@Serializable
data class KpointCount(
    val kpointId: String,
    val name: String,
    val count: Int,
)

/**
 * 报告的纯统计数据（FR-5.1 限定的六项指标）。
 *
 * 全部数字由本地计算得出，AI 只能写解读文字，**不得改动任何数字**（FR-5.3）。
 */
@Serializable
data class ReportStats(
    val type: String,
    val periodKey: String,
    val fromUtc: Long,
    val toUtc: Long,
    /** 新增错题数。 */
    val newMistakeCount: Int,
    /** 复发 Top 知识点（同知识点重复出错次数降序）。 */
    val recurringKpoints: List<KpointCount>,
    /** 错因类型分布（固定七类，未出现的类别 count 为 0）。 */
    val errorTypeDistribution: List<ErrorTypeCount>,
    /** 复习完成率 0..1。 */
    val reviewCompletionRate: Double,
    /** 已完成复习次数。 */
    val reviewDoneCount: Int,
    /** 学习时长（秒）。 */
    val studySeconds: Long,
    /** 活跃天数（有任意记录的天数）。 */
    val activeDays: Int,
    /** 统计覆盖的天数。 */
    val periodDays: Int,
) {
    /** 学习时长的人类可读形式，例如「3 小时 20 分」。 */
    val studyDurationText: String
        get() {
            val totalMinutes = studySeconds / 60
            val hours = totalMinutes / 60
            val minutes = totalMinutes % 60
            return when {
                hours > 0 && minutes > 0 -> hours.toString() + " 小时 " + minutes + " 分"
                hours > 0 -> hours.toString() + " 小时"
                minutes > 0 -> minutes.toString() + " 分钟"
                else -> "不足 1 分钟"
            }
        }

    /** 复习完成率的百分比文本，例如「82%」。 */
    val reviewRateText: String get() = Math.round(reviewCompletionRate * 100).toString() + "%"

    /** 错因分布按次数降序，用于「问题性质」展示（不是对错统计）。 */
    fun topErrorTypes(limit: Int = 3): List<ErrorTypeCount> =
        errorTypeDistribution.filter { it.count > 0 }.sortedByDescending { it.count }.take(limit)
}

/** 报告生成结果。 */
sealed interface ReportOutcome {
    /**
     * 生成成功（已落库）。
     *
     * @param stats 纯统计数据（所有数字的唯一来源）
     * @param markdown 模板渲染后的完整报告正文（AI 关闭时即为最终版）
     * @param aiComment AI 解读；未启用或调用失败时为 null
     * @param aiDegraded AI 解读被请求但失败，已降级为纯统计版（FR-5.4）
     */
    data class Generated(
        val stats: ReportStats,
        val markdown: String,
        val aiComment: String?,
        val aiDegraded: Boolean,
        val reportId: String,
    ) : ReportOutcome {
        /** 是否为纯统计版（没有任何 AI 文字）。 */
        val pureStatistics: Boolean get() = aiComment == null
    }

    /** 统计所需数据完全缺失（该周期没有任何记录）。 */
    data class NoData(val periodKey: String, val fromUtc: Long, val toUtc: Long) : ReportOutcome {
        /** 面向用户的提示（不是错误，只是这一周期还没开始）。 */
        val userMessage: String get() = "这一周期还没有学习记录，先去完成一道题吧。"
    }
}

/**
 * 学情周报 / 月报用例（开发方案 FR-5）。
 *
 * 硬性约束（逐条落实）：
 * 1. **禁止全表扫描**：只从 daily_stats 按 [fromDate, toDate] 区间读取日汇总（FR-5.2）；
 *    错因与知识点分布也用带时间窗口的聚合查询，不拉全表。
 * 2. **数字全部由模板渲染**（[renderMarkdown]），AI 只写约 200 字的解读，
 *    且正文必须声明「数字由本机统计生成」与「AI 生成」标识（FR-5.3 / FR-10.6）。
 * 3. **AI 解读可关闭**：关闭或调用失败时输出纯统计版，页面仍可用（FR-5.4）。
 * 4. 指标严格限定为 FR-5.1 的六项，不引入任何「答对答错比率」类指标。
 */
class BuildWeeklyReportUseCase(
    private val dailyStats: DailyStatsStore,
    private val mistakes: MistakeStore,
    private val kpoints: KpointDirectory,
    private val reports: ReportStore,
    private val narrator: ReportNarrator,
    private val settings: DomainSettings = DomainSettings.DEFAULTS,
    private val clock: DomainClock = DomainClock.SYSTEM,
    private val ids: IdGenerator = IdGenerator.UUID_GENERATOR,
) {
    /**
     * 生成报告。
     *
     * @param period 周报或月报
     * @param nowUtc 基准时刻；报告覆盖 [now - periodDays, now)
     * @param forceAiComment null 表示按设置；true/false 强制开/关（设置页可临时覆盖）
     */
    suspend fun build(
        period: ReportPeriod = ReportPeriod.WEEKLY,
        nowUtc: Long = clock.nowUtcMillis(),
        forceAiComment: Boolean? = null,
    ): ReportOutcome {
        val window = windowOf(period, nowUtc)
        val stats = computeStats(period, window, nowUtc)

        val hasAnyData =
            stats.newMistakeCount > 0 ||
                stats.reviewDoneCount > 0 ||
                stats.studySeconds > 0 ||
                stats.activeDays > 0
        if (!hasAnyData) return ReportOutcome.NoData(stats.periodKey, stats.fromUtc, stats.toUtc)

        val statsJson = statsJson(stats)
        val aiEnabled = forceAiComment ?: runCatching { settings.reportAiEnabled() }.getOrDefault(false)
        var aiComment: String? = null
        var degraded = false
        if (aiEnabled) {
            when (val narrative = narrator.narrate(statsJson, ids.newId())) {
                is ReportNarrator.NarrativeOutcome.Success -> {
                    aiComment = narrative.text.trim().takeIf { it.isNotEmpty() }
                    if (aiComment == null) degraded = true
                }

                is ReportNarrator.NarrativeOutcome.Failure -> degraded = true
            }
        }

        val markdown = renderMarkdown(stats, aiComment)
        val reportId = period.type + "-" + stats.periodKey
        runCatching {
            reports.save(
                id = reportId,
                type = period.type,
                periodKey = stats.periodKey,
                statsJson = statsJson,
                aiComment = aiComment,
                createdAtUtc = nowUtc,
            )
        }
        return ReportOutcome.Generated(
            stats = stats,
            markdown = markdown,
            aiComment = aiComment,
            aiDegraded = degraded,
            reportId = reportId,
        )
    }

    /** 只算不写（预览 / 单测）。 */
    suspend fun preview(
        period: ReportPeriod = ReportPeriod.WEEKLY,
        nowUtc: Long = clock.nowUtcMillis(),
    ): ReportStats = computeStats(period, windowOf(period, nowUtc), nowUtc)

    /**
     * 模板渲染（所有数字都在这里出现，AI 文本只作为附加段落追加在末尾）。
     *
     * @param aiComment AI 解读；为 null 时不输出该段（纯统计版）
     */
    fun renderMarkdown(
        stats: ReportStats,
        aiComment: String?,
    ): String {
        val builder = StringBuilder()
        val title = if (stats.type == ReportPeriod.WEEKLY.type) "周报" else "月报"
        builder.append("# 学情").append(title).append("（").append(stats.periodKey).append("）").append(NL).append(NL)
        builder.append("> 本报告所有数字均由本机统计生成，不上传任何数据。").append(NL).append(NL)

        builder.append("## 一、本期概览").append(NL).append(NL)
        builder.append("- 新增错题：").append(stats.newMistakeCount).append(" 道").append(NL)
        builder.append("- 完成复习：").append(stats.reviewDoneCount).append(" 次").append(NL)
        builder.append("- 复习完成率：").append(stats.reviewRateText).append(NL)
        builder.append("- 学习时长：").append(stats.studyDurationText).append(NL)
        builder.append("- 活跃天数：").append(stats.activeDays).append(" / ").append(stats.periodDays).append(" 天")
            .append(NL).append(NL)

        builder.append("## 二、需要注意的知识点（按重复出错次数）").append(NL).append(NL)
        if (stats.recurringKpoints.isEmpty()) {
            builder.append("- 本期没有重复出错的知识点，保持住。").append(NL).append(NL)
        } else {
            stats.recurringKpoints.forEachIndexed { index, item ->
                builder.append(index + 1).append(". ").append(item.name)
                    .append("：本期错 ").append(item.count).append(" 次").append(NL)
            }
            builder.append(NL)
        }

        builder.append("## 三、错因分布").append(NL).append(NL)
        val nonZero = stats.errorTypeDistribution.filter { it.count > 0 }
        if (nonZero.isEmpty()) {
            builder.append("- 本期没有记录到错因。").append(NL).append(NL)
        } else {
            nonZero.sortedByDescending { it.count }.forEach { item ->
                builder.append("- ").append(item.label).append("：").append(item.count).append(" 次").append(NL)
            }
            builder.append(NL)
        }

        builder.append("## 四、建议").append(NL).append(NL)
        val weakest = stats.recurringKpoints.firstOrNull()
        if (weakest != null) {
            builder.append("- 优先补「").append(weakest.name).append("」，本期它重复出错 ")
                .append(weakest.count).append(" 次。").append(NL)
        }
        if (stats.reviewDoneCount == 0 && stats.newMistakeCount > 0) {
            builder.append("- 本期还没有复习记录，建议今晚先把到期错题过一遍。").append(NL)
        }
        builder.append("- 继续按「先定位错因、再补前置知识点」的顺序推进，比盲目刷题更省时间。").append(NL).append(NL)

        if (aiComment != null) {
            builder.append("## 五、AI 解读与建议（AI 生成）").append(NL).append(NL)
            builder.append(aiComment).append(NL).append(NL)
            builder.append("> 以上解读由 AI 生成，仅供参考；其中的数字来自本机统计。").append(NL)
        }
        return builder.toString().trimEnd() + NL
    }

    /** 周期键：周报为 2025-W03，月报为 2025-01（按 UTC 计算，与时区无关）。 */
    fun periodKeyOf(
        period: ReportPeriod,
        nowUtc: Long,
    ): String {
        val epochDay = epochDayOf(nowUtc)
        return if (period == ReportPeriod.WEEKLY) {
            val weekday = Math.floorMod(epochDay + 3L, 7L)
            val monday = epochDay - weekday
            utcYearOf(monday).toString() + "-W" + weekOfYear(monday).toString().padStart(2, '0')
        } else {
            utcYearMonthOf(epochDay)
        }
    }

    private suspend fun computeStats(
        period: ReportPeriod,
        window: Window,
        nowUtc: Long,
    ): ReportStats {
        val fromDate = epochDayOf(window.fromUtc)
        val toDate = epochDayOf(window.toUtc - 1L)
        val daily = runCatching { dailyStats.between(fromDate, toDate) }.getOrDefault(emptyList())
        val aggregated = aggregate(daily)

        val mistakeCount =
            runCatching { mistakes.countCreatedBetween(window.fromUtc, window.toUtc) }
                .getOrDefault(aggregated.mistakeCount)
        val distribution =
            runCatching { mistakes.errorTypeDistribution(window.fromUtc, window.toUtc) }
                .getOrDefault(emptyMap())
        val kpointCounts =
            runCatching { mistakes.kpointDistribution(window.fromUtc, window.toUtc) }
                .getOrDefault(emptyMap())

        val nameById =
            runCatching { kpoints.allKnowledgePoints() }
                .getOrDefault(emptyList())
                .associate { it.id to it.name }
        val recurring =
            kpointCounts.entries
                .filter { it.value > 1 }
                .sortedByDescending { it.value }
                .take(RECURRING_LIMIT)
                .map { KpointCount(kpointId = it.key, name = nameById[it.key] ?: it.key, count = it.value) }

        val reviewDone =
            runCatching { dailyStats.sumReviewDoneCount(fromDate, toDate) }
                .getOrDefault(aggregated.reviewDoneCount)
        val reviewPending =
            runCatching {
                mistakes.reviewCountBetween(window.fromUtc, window.toUtc, PENDING_REVIEW_WIRE)
            }.getOrDefault(0)
        val reviewRate =
            when {
                reviewDone + reviewPending <= 0 -> 1.0
                else -> (reviewDone.toDouble() / (reviewDone + reviewPending)).coerceIn(0.0, 1.0)
            }

        val studySeconds =
            runCatching { dailyStats.sumStudySeconds(fromDate, toDate) }
                .getOrDefault(aggregated.studySeconds)
        val activeDays =
            daily.count {
                it.mistakeCount > 0 || it.reviewDoneCount > 0 || it.studySeconds > 0 || it.hintCount > 0
            }

        return ReportStats(
            type = period.type,
            periodKey = periodKeyOf(period, nowUtc),
            fromUtc = window.fromUtc,
            toUtc = window.toUtc,
            newMistakeCount = mistakeCount,
            recurringKpoints = recurring,
            errorTypeDistribution = fixedDistribution(distribution),
            reviewCompletionRate = reviewRate,
            reviewDoneCount = reviewDone,
            studySeconds = studySeconds,
            activeDays = activeDays,
            periodDays = period.days,
        )
    }

    /** 固定七类全部出现（未出现的为 0），保证报告图形状稳定。 */
    private fun fixedDistribution(raw: Map<String, Int>): List<ErrorTypeCount> {
        val byType: Map<ErrorType, Int> =
            raw.entries.mapNotNull { (code, count) ->
                ErrorTypeMapper.mapToTypeOrNull(code)?.let { it to count }
            }.groupBy({ it.first }, { it.second }).mapValues { (_, values) -> values.sum() }
        return ErrorType.FIXED.map { type ->
            ErrorTypeCount(code = type.code, label = type.label, count = byType[type] ?: 0)
        }
    }

    private fun aggregate(daily: List<DailyStats>): DailyStats {
        if (daily.isEmpty()) return DailyStats(dateEpochDayUtc = 0L, subject = "")
        return DailyStats(
            dateEpochDayUtc = 0L,
            subject = "",
            mistakeCount = daily.sumOf { it.mistakeCount },
            kpointCount = daily.sumOf { it.kpointCount },
            hintCount = daily.sumOf { it.hintCount },
            reviewDoneCount = daily.sumOf { it.reviewDoneCount },
            studySeconds = daily.sumOf { it.studySeconds },
        )
    }

    private fun statsJson(stats: ReportStats): String = REPORT_JSON.encodeToString(stats)

    private data class Window(val fromUtc: Long, val toUtc: Long)

    private fun windowOf(
        period: ReportPeriod,
        nowUtc: Long,
    ): Window {
        val endExclusive = startOfUtcDay(nowUtc) + DAY_MILLIS
        val from = endExclusive - period.days.toLong() * DAY_MILLIS
        return Window(fromUtc = from, toUtc = endExclusive)
    }

    private fun daysBeforeYear(year: Int): Long {
        var days = 0L
        for (y in 1970 until year) days += if (isLeap(y)) 366 else 365
        return days
    }

    private fun utcYearOf(epochDay: Long): Int {
        var year = 1970
        var remaining = epochDay
        while (year < MAX_YEAR) {
            val daysInYear = if (isLeap(year)) 366 else 365
            if (remaining < daysInYear) return year
            remaining -= daysInYear
            year++
        }
        return MAX_YEAR
    }

    private fun weekOfYear(mondayEpochDay: Long): Int {
        val year = utcYearOf(mondayEpochDay)
        val dayOfYear = mondayEpochDay - daysBeforeYear(year)
        return (dayOfYear / 7L).toInt() + 1
    }

    private fun utcYearMonthOf(epochDay: Long): String {
        val year = utcYearOf(epochDay)
        var remaining = epochDay - daysBeforeYear(year)
        val monthLengths = if (isLeap(year)) LEAP_MONTH else COMMON_MONTH
        var month = 1
        for (length in monthLengths) {
            if (remaining < length) break
            remaining -= length
            month++
        }
        return year.toString() + "-" + month.toString().padStart(2, '0')
    }

    private fun isLeap(year: Int): Boolean = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0

    companion object {
        /** 复发 Top 知识点最多展示条数。 */
        const val RECURRING_LIMIT: Int = 5

        /** 「待复习」在统计口径中的占位标记（由 data 层按实际语义映射）。 */
        const val PENDING_REVIEW_WIRE: String = "pending"

        /** 换行符（统一 LF，导出与展示都稳定）。 */
        private const val NL: String = "\n"

        /** 支持的年份上限，防止脏数据导致死循环。 */
        private const val MAX_YEAR: Int = 9999

        private val COMMON_MONTH = intArrayOf(31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
        private val LEAP_MONTH = intArrayOf(31, 29, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)

        internal val REPORT_JSON: Json =
            Json {
                prettyPrint = false
                ignoreUnknownKeys = true
                encodeDefaults = true
            }
    }
}
