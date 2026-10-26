package com.totop.app.ui.knowledge

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.totop.app.data.repo.KnowledgeRepository
import com.totop.app.data.repo.MistakeRepository
import com.totop.app.data.repo.UtcDay
import com.totop.app.domain.model.KnowledgePoint
import com.totop.app.domain.model.Mastery
import com.totop.app.domain.model.Subjects
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 科目分组（知识点页的顶层分类）。 */
data class SubjectHeatGroup(
    /** 科目代码，例如 math。 */
    val subject: String,
    /** 科目中文名。 */
    val subjectLabel: String,
    /** 该科目下的章节分组。 */
    val chapters: List<ChapterHeatGroup>,
    /** 该科目有数据的知识点数量。 */
    val scoredCount: Int,
    /** 该科目平均掌握度（只统计已有数据的知识点）。 */
    val averageScore: Double,
    /** 该科目错题合计。 */
    val mistakeTotal: Int,
) {
    /** 是否有任何数据；没有数据的科目默认折叠，避免首屏被十科空列表淹没。 */
    val hasData: Boolean get() = scoredCount > 0 || mistakeTotal > 0
}

/** 章节树热力分组（一个章节一行折叠区）。 */
data class ChapterHeatGroup(
    /** 所属科目代码；空表示未知。 */
    val subject: String = "",
    val chapter: String,
    val rows: List<HeatRow>,
    /** 该章节平均掌握度（只统计已有数据的行）。 */
    val averageScore: Double,
    /** 该章节错题合计。 */
    val mistakeTotal: Int,
) {
    /** 章节色阶标签。 */
    val bandLabel: String
        get() =
            when (KnowledgeCharts.bandOfScore(averageScore)) {
                com.totop.app.domain.model.MasteryBand.RED -> "红"
                com.totop.app.domain.model.MasteryBand.YELLOW -> "黄"
                com.totop.app.domain.model.MasteryBand.GREEN -> "绿"
            }
}

/** 知识点页状态（FR-3）。 */
data class KnowledgeUiState(
    val loading: Boolean = true,
    val errorMessage: String? = null,
    val radar: RadarData = RadarData(emptyList(), 0.0),
    /** 按科目分组的章节热力（顺序与 Subjects.ORDER 一致）。 */
    val subjectGroups: List<SubjectHeatGroup> = emptyList(),
    /** 当前选中的科目；null 表示「全部科目」。 */
    val selectedSubject: String? = null,
    val trend: TrendData = TrendData(emptyList(), 0.0, 0.0, 0),
    val pie: PieData = PieData(emptyList(), 0),
    /** 已统计的知识点数量（有掌握度记录）。 */
    val masteredKpointCount: Int = 0,
    /** 薄弱知识点数量（<60）。 */
    val weakCount: Int = 0,
    /** 平均掌握度。 */
    val averageScore: Double = 0.0,
    /** 累计错题数。 */
    val mistakeTotal: Int = 0,
    /** 最近一次重算时间（UTC 毫秒）；为 null 表示还没有掌握度数据。 */
    val lastComputedAtUtc: Long? = null,
) {
    /** 是否完全没有掌握度数据（需要引导先去解题）。 */
    val hasNoMastery: Boolean get() = !loading && masteredKpointCount == 0 && mistakeTotal == 0

    /** 当前筛选后要展示的科目分组。 */
    val visibleSubjectGroups: List<SubjectHeatGroup>
        get() = selectedSubject?.let { code -> subjectGroups.filter { it.subject == code } } ?: subjectGroups

    /** 顶部的科目筛选项：(科目代码或 null, 展示名, 知识点数)。 */
    val subjectTabs: List<Pair<String?, String>>
        get() =
            listOf<Pair<String?, String>>(null to "全部") +
                subjectGroups.map { it.subject to it.subjectLabel }

    /** 顶部摘要文案。 */
    val summaryText: String
        get() =
            when {
                loading -> "正在计算掌握度…"
                hasNoMastery -> "还没有掌握度数据：先去解一道题，系统会按自评与错题自动算。"
                else -> "平均掌握度 " + Math.round(averageScore) + " · 薄弱知识点 " + weakCount + " 个"
            }
}

/**
 * 知识点掌握度页 ViewModel（FR-3）。
 *
 * 数据来源全部本机：
 * - mastery 表（掌握度 + 90 天历史快照）；
 * - mistake_kpoint 的错题分布（决定雷达轴与热力排序）；
 * - 知识点树（名称与章节）。
 *
 * 计算规则：掌握度由领域层 [com.totop.app.domain.model.MasteryCalculator] 产出入库，
 * 本页**只读取与展示**，不在 UI 层重新计算分数（避免两套口径）。
 */
@HiltViewModel
class KnowledgeViewModel
    @Inject
    constructor(
        private val knowledgeRepository: KnowledgeRepository,
        private val mistakeRepository: MistakeRepository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(KnowledgeUiState())
        val uiState: StateFlow<KnowledgeUiState> = _uiState.asStateFlow()

        /** 首次进入加载。 */
        fun bootstrap() {
            if (!_uiState.value.loading) return
            refresh()
        }

        /** 重新读取（下拉刷新 / 复习后返回）。 */
        fun refresh() {
            _uiState.value = _uiState.value.copy(loading = true, errorMessage = null)
            viewModelScope.launch {
                try {
                    val points =
                        runCatching { knowledgeRepository.catalogEnsuringSeeded().allKnowledgePoints() }
                            .getOrDefault(emptyList())
                    val masteries = runCatching { knowledgeRepository.allMastery() }.getOrDefault(emptyList())
                    val masteryById: Map<String, Mastery> = masteries.associateBy { it.kpointId }
                    val nameById: Map<String, String> = points.associate { it.id to it.name }
                    val mistakeCounts = loadMistakeCounts()

                    val radar = KnowledgeCharts.radar(masteryById, nameById, mistakeCounts)
                    val heatRows = KnowledgeCharts.heatRows(points, masteryById, mistakeCounts)
                    // 先按科目分组，再在科目内按章节分组。
                    //
                    // 旧实现把十科七百多个知识点混在一张扁平列表里，
                    // 学生看到的是一眼望不到头的「章节热力」，根本不知道自己在看哪一科。
                    // 科目是第一层的筛选维度，章节只能是科目内部的第二层。
                    val subjectOfRow: Map<String, String> = points.associate { it.id to it.subject }
                    val subjectGroups = groupBySubject(heatRows, subjectOfRow)
                    // 无数据科目一律折叠（见 SubjectHeatGroup.hasData），
                    // 这里不再单独维护一份扁平列表。
                    val merged = KnowledgeCharts.mergeHistory(masteries)
                    val trend = KnowledgeCharts.trend(merged)
                    val pie = KnowledgeCharts.pie(runCatching { errorDistribution() }.getOrDefault(emptyMap()))
                    val average = if (masteries.isEmpty()) 0.0 else masteries.sumOf { it.score } / masteries.size
                    _uiState.value =
                        KnowledgeUiState(
                            loading = false,
                            errorMessage = null,
                            radar = radar,
                            subjectGroups = subjectGroups,
                            selectedSubject = _uiState.value.selectedSubject,
                            trend = trend,
                            pie = pie,
                            masteredKpointCount = masteries.size,
                            weakCount = masteries.count { it.score < WEAK_THRESHOLD },
                            averageScore = average,
                            mistakeTotal = mistakeCounts.values.sum(),
                            lastComputedAtUtc = masteries.maxOfOrNull { it.updatedAtUtc },
                        )
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (throwable: Throwable) {
                    _uiState.value =
                        _uiState.value.copy(
                            loading = false,
                            errorMessage = "读取掌握度失败：" + describe(throwable),
                        )
                }
            }
        }

        private suspend fun loadMistakeCounts(): Map<String, Int> {
            val to = System.currentTimeMillis() + 1L
            return runCatching { mistakeRepository.kpointDistribution(0L, to) }.getOrDefault(emptyMap())
        }

        private suspend fun errorDistribution(): Map<String, Int> {
            val to = System.currentTimeMillis() + 1L
            return runCatching { mistakeRepository.errorTypeDistribution(0L, to) }.getOrDefault(emptyMap())
        }

        /** 按科目分组：科目顺序固定（Subjects.ORDER），科目内部再按章节分组。 */
        private fun groupBySubject(
            rows: List<HeatRow>,
            subjectOfRow: Map<String, String>,
        ): List<SubjectHeatGroup> =
            rows
                .groupBy { subjectOfRow[it.kpointId].orEmpty() }
                .map { (subject, list) ->
                    val scored = list.filter { !it.untouched }
                    SubjectHeatGroup(
                        subject = subject,
                        subjectLabel = Subjects.label(subject),
                        chapters = groupByChapter(subject, list),
                        scoredCount = scored.size,
                        averageScore = if (scored.isEmpty()) 0.0 else scored.sumOf { it.score } / scored.size,
                        mistakeTotal = list.sumOf { it.mistakeCount },
                    )
                }
                .sortedBy { group ->
                    // 未知科目排到最后，其余严格按界面展示顺序
                    Subjects.ORDER.indexOf(group.subject).let { if (it < 0) Subjects.ORDER.size else it }
                }

        /** 科目内部按章节分组，章节内按掌握度升序（最需要补的排前面）。 */
        private fun groupByChapter(
            subject: String,
            rows: List<HeatRow>,
        ): List<ChapterHeatGroup> =
            rows
                .groupBy { it.chapter.ifBlank { "未分章" } }
                .map { (chapter, list) ->
                    val scored = list.filter { !it.untouched }
                    ChapterHeatGroup(
                        subject = subject,
                        chapter = chapter,
                        rows = list.sortedWith(compareBy({ it.untouched }, { it.score })),
                        averageScore = if (scored.isEmpty()) 0.0 else scored.sumOf { it.score } / scored.size,
                        mistakeTotal = list.sumOf { it.mistakeCount },
                    )
                }
                .sortedWith(compareByDescending<ChapterHeatGroup> { it.mistakeTotal }.thenBy { it.averageScore })

        /** 切换科目筛选；传 null 表示看全部科目。 */
        fun selectSubject(subject: String?) {
            _uiState.value = _uiState.value.copy(selectedSubject = subject)
        }

        /** 知识点名称（图表点击后用）。 */
        fun nameOf(kpointId: String): String? =
            _uiState.value.subjectGroups
                .asSequence()
                .flatMap { it.chapters.asSequence() }
                .flatMap { it.rows.asSequence() }
                .firstOrNull { it.kpointId == kpointId }
                ?.name

        private fun describe(throwable: Throwable): String =
            throwable.message?.takeIf { it.isNotBlank() } ?: throwable.javaClass.simpleName

        companion object {
            /** 薄弱阈值，与 Mastery.WEAK_BELOW 一致。 */
            const val WEAK_THRESHOLD: Double = 60.0

            /** 趋势图展示最近多少天。 */
            const val TREND_DAYS: Int = 30

            /** 趋势区间文案：把 UTC 日序号换算成日期，便于学生对照日历。 */
            fun trendRangeText(trend: TrendData): String {
                val first = trend.points.firstOrNull() ?: return "暂无趋势数据"
                val last = trend.points.last()
                return UtcDay.startOfDayMillis(first.epochDayUtc).let { from ->
                    com.totop.app.ui.mistakes.MistakeFormat.formatDate(from) + " 至 " +
                        com.totop.app.ui.mistakes.MistakeFormat.formatDate(UtcDay.startOfDayMillis(last.epochDayUtc))
                }
            }
        }
    }

/** 知识点详情页所需的数据快照。 */
data class KpointDetailUiState(
    val loading: Boolean = true,
    val errorMessage: String? = null,
    val point: KnowledgePoint? = null,
    val parentName: String? = null,
    val masteries: Mastery? = null,
    val prerequisites: List<KnowledgePoint> = emptyList(),
    val mistakes: List<MistakeCardUiLite> = emptyList(),
    val verifiedExerciseCount: Int = 0,
) {
    /** 掌握度分数（无数据时为 null）。 */
    val score: Double? get() = masteries?.score

    /** 掌握度文案。 */
    val scoreText: String get() = score?.let { Math.round(it).toString() } ?: "待练习"

    /** 是否可发起巩固练习（有知识点即可，题目由 AI 生成或复用已校验题库）。 */
    val canPractice: Boolean get() = point != null
}

/** 知识点详情页的错题行（精简投影）。 */
data class MistakeCardUiLite(
    val mistakeId: String,
    val stemSummary: String,
    val errorTypeLabel: String,
    val recurrenceIndex: Int,
    val createdAtUtc: Long,
) {
    /** 复发徽标（首次为 null）。 */
    val recurrenceBadge: String?
        get() = if (recurrenceIndex <= 1) null else "第 " + recurrenceIndex + " 次错"
}
