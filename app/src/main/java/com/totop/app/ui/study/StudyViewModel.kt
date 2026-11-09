package com.totop.app.ui.study

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.totop.app.data.prefs.SubjectPrefs
import com.totop.app.data.repo.DailyStatsRepository
import com.totop.app.data.repo.ExamRepository
import com.totop.app.data.repo.SettingsRepository
import com.totop.app.domain.model.ExamDefaults
import com.totop.app.domain.model.ExamKind
import com.totop.app.domain.model.ExamRecord
import com.totop.app.domain.model.ScoreTrendAnalyzer
import com.totop.app.domain.model.Subjects
import com.totop.app.domain.model.Term
import com.totop.app.domain.model.TermCalendar
import com.totop.app.domain.model.TrendFilter
import com.totop.app.domain.usecase.port.TrendNarrator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject

/**
 * 学情页 ViewModel。
 *
 * 关键约束：
 * 1. **数字全部在 domain 算**：页面只排版，不重新计算得分率或排名；
 * 2. **AI 手动触发**：进页面不发请求，关闭时页面照常可用；
 * 3. **学期是唯一时间维度**：科目与类型只是筛选，不改变存储口径；
 * 4. 不直接访问 Room：全部经 Repository。
 */
@HiltViewModel
class StudyViewModel
    @Inject
    constructor(
        private val exams: ExamRepository,
        private val dailyStats: DailyStatsRepository,
        private val settings: SettingsRepository,
        private val subjectPrefs: SubjectPrefs,
        private val narrator: TrendNarrator,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(StudyUiState())
        val uiState: StateFlow<StudyUiState> = _uiState.asStateFlow()

        private var allRecords: List<ExamRecord> = emptyList()

        init {
            viewModelScope.launch {
                loadPreferences()
                exams.observeAll().collect { records ->
                    allRecords = records
                    refresh()
                }
            }
        }

        /** 切换科目（null = 全部科目）。 */
        fun selectSubject(subject: String?) {
            if (_uiState.value.selectedSubject == subject) return
            _uiState.update { it.copy(selectedSubject = subject, ai = StudyAiState.Idle) }
            viewModelScope.launch { refresh() }
        }

        /** 切换学期。 */
        fun selectTerm(index: Int) {
            if (index == _uiState.value.termIndex || index !in _uiState.value.terms.indices) return
            _uiState.update { it.copy(termIndex = index, ai = StudyAiState.Idle) }
            viewModelScope.launch { refresh() }
        }

        /** 切换类型筛选（null = 全部）。 */
        fun selectKind(kind: ExamKind?) {
            if (_uiState.value.kindFilter == kind) return
            _uiState.update { it.copy(kindFilter = kind, ai = StudyAiState.Idle) }
            viewModelScope.launch { refresh() }
        }

        /** 修改本学期开学日；传 null 表示恢复自动推断。 */
        fun setTermStart(day: Long?) {
            viewModelScope.launch {
                if (day == null) {
                    settings.remove(SettingsRepository.Keys.EXAM_TERM_START)
                } else {
                    settings.putLong(SettingsRepository.Keys.EXAM_TERM_START, day)
                }
                loadPreferences()
                refresh()
            }
        }

        /** 设置目标（单科或全局）。 */
        fun setTarget(
            subject: String?,
            target: Double?,
        ) {
            viewModelScope.launch {
                val key =
                    if (subject == null) {
                        SettingsRepository.Keys.EXAM_TARGET_GLOBAL
                    } else {
                        SettingsRepository.Keys.examTarget(
                            subject,
                        )
                    }
                if (target == null) settings.remove(key) else settings.put(key, target.toString())
                loadPreferences()
                refresh()
            }
        }

        /**
         * 保存一场考试。
         *
         * @param onSaved 保存成功后的回调（关弹窗）
         */
        fun save(
            record: ExamRecord,
            onSaved: () -> Unit,
        ) {
            viewModelScope.launch {
                val errors = ExamDefaults.validate(record)
                if (errors.isNotEmpty()) {
                    _uiState.update { it.copy(errorMessage = errors.joinToString("；")) }
                    return@launch
                }
                val now = System.currentTimeMillis()
                val merged =
                    record.copy(
                        createdAtUtc = if (record.createdAtUtc > 0L) record.createdAtUtc else now,
                        updatedAtUtc = now,
                    )
                runCatching { exams.save(merged) }
                    .onFailure { error ->
                        _uiState.update { it.copy(errorMessage = "保存失败：" + (error.message ?: "未知原因")) }
                        return@launch
                    }
                // 人数写回设置：下次录入自动带出，学生不用每场都填
                record.classSize?.let { settings.putInt(SettingsRepository.Keys.EXAM_CLASS_SIZE, it) }
                record.gradeSize?.let { settings.putInt(SettingsRepository.Keys.EXAM_GRADE_SIZE, it) }
                _uiState.update { it.copy(errorMessage = null) }
                onSaved()
            }
        }

        /** 删除一场考试（连带各科成绩）。 */
        fun delete(id: String) {
            viewModelScope.launch {
                runCatching { exams.delete(id) }
                    .onFailure { error ->
                        _uiState.update { it.copy(errorMessage = "删除失败：" + (error.message ?: "未知原因")) }
                    }
            }
        }

        /** 手动生成 AI 解读。 */
        fun generateNarration() {
            viewModelScope.launch {
                if (_uiState.value.trend.points.isEmpty()) return@launch
                _uiState.update { it.copy(ai = StudyAiState.Running) }
                val summary = buildSummaryJson(_uiState.value)
                try {
                    when (val outcome = narrator.narrate(summary, "study-" + System.currentTimeMillis())) {
                        is TrendNarrator.Outcome.Success ->
                            _uiState.update { it.copy(ai = StudyAiState.Done(outcome.text)) }

                        is TrendNarrator.Outcome.Failure ->
                            _uiState.update { it.copy(ai = StudyAiState.Failed(outcome.userMessage)) }
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Throwable) {
                    _uiState.update {
                        it.copy(ai = StudyAiState.Failed("生成失败：" + (error.message ?: "未知原因")))
                    }
                }
            }
        }

        /** 关闭错误提示。 */
        fun dismissError() {
            _uiState.update { it.copy(errorMessage = null) }
        }

        /** 读设置：科目、人数、目标、默认满分、开学日覆盖。 */
        private suspend fun loadPreferences() {
            val allowed = subjectPrefs.allowed()
            val ordered = Subjects.ORDER.filter { allowed.contains(it) }
            val overrideStart =
                settings.getLong(SettingsRepository.Keys.EXAM_TERM_START, -1L).takeIf { it > 0L }
            val today = todayLocalEpochDay()
            val terms = TermCalendar.recent(today, overrideStart, TERM_COUNT)
            val targets = LinkedHashMap<String, Double>()
            ordered.forEach { code ->
                val value = settings.getDouble(SettingsRepository.Keys.examTarget(code), 0.0)
                if (value > 0.0) targets[code] = value
            }
            val fullScores = LinkedHashMap<String, Double>()
            ordered.forEach { code ->
                val value = settings.getDouble(SettingsRepository.Keys.examFullScore(code), 0.0)
                fullScores[code] = if (value > 0.0) value else ExamDefaults.defaultFullScore(code)
            }
            val globalTarget =
                settings.getDouble(SettingsRepository.Keys.EXAM_TARGET_GLOBAL, 0.0).takeIf { it > 0.0 }
            val classSize = settings.getInt(SettingsRepository.Keys.EXAM_CLASS_SIZE, 0).takeIf { it > 0 }
            val gradeSize = settings.getInt(SettingsRepository.Keys.EXAM_GRADE_SIZE, 0).takeIf { it > 0 }

            _uiState.update { current ->
                current.copy(
                    mySubjects = ordered,
                    terms = terms,
                    termIndex = current.termIndex.coerceIn(0, (terms.size - 1).coerceAtLeast(0)),
                    globalTarget = globalTarget,
                    subjectTargets = targets,
                    defaultFullScores = fullScores,
                    classSize = classSize,
                    gradeSize = gradeSize,
                    termStartOverride = overrideStart,
                    selectedSubject = current.selectedSubject?.takeIf { ordered.contains(it) },
                )
            }
        }

        /** 按当前筛选重算趋势与本学期概览。 */
        private suspend fun refresh() {
            val state = _uiState.value
            val term = state.selectedTerm ?: return
            val visible = allRecords.filter { it.occurredOn >= term.fromDay && it.occurredOn < term.toDayExclusive }

            // 「完整场次」的判定口径：学生勾选的科目 ∩ 本学期出现过记录的科目。
            // 学生没挑过科目时默认勾满十科，若直接拿十科去判，任何一场考试都不完整。
            val termSubjects = visible.flatMap { it.countableScores }.map { it.subject }.toSet()
            val intersection =
                if (state.mySubjects.isEmpty()) {
                    termSubjects
                } else {
                    termSubjects.intersect(
                        state.mySubjects.toSet(),
                    )
                }
            val effective = if (intersection.isEmpty()) termSubjects else intersection

            val trend =
                ScoreTrendAnalyzer.analyze(
                    records = visible,
                    filter =
                        TrendFilter(
                            subject = state.selectedSubject,
                            requiredSubjects = effective,
                            kind = state.kindFilter,
                            target = state.currentTarget,
                        ),
                )
            val overview = loadOverview(term)
            _uiState.update {
                it.copy(
                    loading = false,
                    effectiveSubjects = effective,
                    recordSubjects = termSubjects,
                    trend = trend,
                    overview = overview,
                    exams = visible.sortedByDescending { record -> record.occurredOn },
                )
            }
        }

        /** 本学期概览：只读 daily_stats，与成绩无关。 */
        private suspend fun loadOverview(term: Term): ExamOverview {
            val rows = dailyStats.between(term.fromDay, term.toDayExclusive - 1)
            return ExamOverview(
                mistakeCount = rows.sumOf { it.mistakeCount },
                reviewDoneCount = rows.sumOf { it.reviewDoneCount },
                studySeconds = rows.sumOf { it.studySeconds },
                activeDays =
                    rows.filter { it.studySeconds > 0L || it.mistakeCount > 0 || it.reviewDoneCount > 0 }
                        .map { it.dateEpochDayUtc }
                        .distinct()
                        .size,
                kpointCount = rows.sumOf { it.kpointCount },
            )
        }

        /**
         * AI 解读的输入摘要。
         *
         * 只给最近 [MAX_SUMMARY_POINTS] 个点：给全量会把 token 花在陈年旧账上，
         * 而且解读的重点本来就是「最近」。
         */
        private fun buildSummaryJson(state: StudyUiState): String {
            val trend = state.trend
            val root = JSONObject()
            root.put("学期", state.selectedTerm?.label.orEmpty())
            root.put("科目", state.subjectLabel)
            root.put("目标", state.currentTarget ?: JSONObject.NULL)
            root.put("最近一次得分率", trend.latest?.rate?.let { round1(it) } ?: JSONObject.NULL)
            root.put("较上次变化", trend.delta?.let { round1(it) } ?: JSONObject.NULL)
            root.put("学期平均", trend.average?.let { round1(it) } ?: JSONObject.NULL)
            root.put("最好一次", trend.best?.let { round1(it) } ?: JSONObject.NULL)
            root.put("年级排名", trend.latest?.gradeRank ?: JSONObject.NULL)
            root.put("年级人数", trend.latest?.gradeSize ?: JSONObject.NULL)
            root.put("班级排名", trend.latest?.classRank ?: JSONObject.NULL)
            root.put("班级人数", trend.latest?.classSize ?: JSONObject.NULL)
            val array = JSONArray()
            trend.points.takeLast(MAX_SUMMARY_POINTS).forEach { point ->
                val (year, month, day) = TermCalendar.civilOf(point.occurredOn)
                val item = JSONObject()
                item.put("日期", month.toString() + "-" + day.toString())
                item.put("名称", point.name)
                item.put("类型", point.kind.label)
                item.put("得分率", round1(point.rate))
                point.gradeRank?.let { item.put("年级排名", it) }
                item.put("科目数", point.subjectCount)
                array.put(item)
            }
            root.put("最近几次", array)
            return root.toString()
        }

        private fun round1(value: Double): Double = Math.round(value * 10.0) / 10.0

        companion object {
            /** 下拉里可选的学期数：本学期 + 前三个。 */
            const val TERM_COUNT: Int = 4

            /** 摘要里最多带几次考试。 */
            const val MAX_SUMMARY_POINTS: Int = 8
        }
    }
