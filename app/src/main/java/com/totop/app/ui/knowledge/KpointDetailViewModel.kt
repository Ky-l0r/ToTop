package com.totop.app.ui.knowledge

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.totop.app.data.repo.ExerciseRepository
import com.totop.app.data.repo.KnowledgeRepository
import com.totop.app.data.repo.MistakeRepository
import com.totop.app.data.repo.QuestionRepository
import com.totop.app.domain.model.EdgeType
import com.totop.app.domain.model.KnowledgePoint
import com.totop.app.domain.model.Mastery
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 知识点详情页 ViewModel（FR-3.3）。
 *
 * 内容来源全部是内置知识点树（assets/seed，受控 ID），不调用模型：
 * 定义与适用范围、模板化解题思路、高频易错点、前置依赖、本知识点的错题、已校验练习题数量。
 */
@HiltViewModel
class KpointDetailViewModel
    @Inject
    constructor(
        private val knowledgeRepository: KnowledgeRepository,
        private val mistakeRepository: MistakeRepository,
        private val exerciseRepository: ExerciseRepository,
        private val questionRepository: QuestionRepository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(KpointDetailUiState())
        val uiState: StateFlow<KpointDetailUiState> = _uiState.asStateFlow()

        private var loadedId: String = ""

        /** 载入详情（幂等：同一 ID 不重复加载）。 */
        fun bootstrap(kpointId: String) {
            if (loadedId == kpointId && !_uiState.value.loading) return
            loadedId = kpointId
            _uiState.value = _uiState.value.copy(loading = true, errorMessage = null)
            viewModelScope.launch {
                try {
                    val catalog = knowledgeRepository.catalogEnsuringSeeded()
                    val point = catalog.knowledgePoint(kpointId)
                    if (point == null) {
                        _uiState.value =
                            KpointDetailUiState(
                                loading = false,
                                errorMessage =
                                    "内置知识点树里没有 ID 为 " + kpointId + " 的知识点。" +
                                        "AI 只会引用受控 ID，若这里为空说明数据文件可能不完整。",
                            )
                        return@launch
                    }
                    val parentName = point.parentId?.let { catalog.knowledgePoint(it)?.name }
                    val mastery = runCatching { knowledgeRepository.masteryOf(kpointId) }.getOrNull()
                    val prerequisites =
                        catalog.prerequisiteIdsOf(kpointId)
                            .mapNotNull { id -> catalog.knowledgePoint(id) }
                    val mistakes = loadMistakes(kpointId)
                    val exerciseCount =
                        runCatching {
                            exerciseRepository.verifiedOfKpoint(
                                kpointId,
                            ).size
                        }.getOrDefault(0)
                    _uiState.value =
                        KpointDetailUiState(
                            loading = false,
                            errorMessage = null,
                            point = point,
                            parentName = parentName,
                            masteries = mastery,
                            prerequisites = prerequisites,
                            mistakes = mistakes,
                            verifiedExerciseCount = exerciseCount,
                        )
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (throwable: Throwable) {
                    _uiState.value =
                        _uiState.value.copy(
                            loading = false,
                            errorMessage = "读取知识点失败：" + describe(throwable),
                        )
                }
            }
        }

        private suspend fun loadMistakes(kpointId: String): List<MistakeCardUiLite> =
            runCatching {
                mistakeRepository.pageByKpoint(
                    kpointId,
                    com.totop.app.data.repo.PageRequest(page = 0, size = MISTAKE_LIMIT),
                )
                    .map { record ->
                        MistakeCardUiLite(
                            mistakeId = record.id,
                            stemSummary =
                                com.totop.app.ui.mistakes.summarize(
                                    questionStemOf(record.questionId),
                                    STEM_LIMIT,
                                ),
                            errorTypeLabel = record.errorType.label,
                            recurrenceIndex = record.recurrenceIndex,
                            createdAtUtc = record.createdAtUtc,
                        )
                    }
            }.getOrDefault(emptyList())

        /** 读取题目题干（错题行需要摘要文本）；题目缺失时返回空串而不是抛异常。 */
        private suspend fun questionStemOf(questionId: String): String =
            runCatching { questionRepository.byId(questionId)?.stemText.orEmpty() }.getOrDefault("")

        /** 前置依赖节点的名称（供页面直接渲染）。 */
        fun prerequisiteNames(state: KpointDetailUiState): List<Pair<String, String>> =
            state.prerequisites.map { it.id to it.name }

        /** 该知识点是否薄弱（<60）。 */
        fun isWeak(mastery: Mastery?): Boolean = (mastery?.score ?: 0.0) < WEAK_THRESHOLD

        /** 边类型中文标签（前置 / 相关 / 延伸）。 */
        fun edgeLabel(type: EdgeType): String = type.label

        private fun describe(throwable: Throwable): String =
            throwable.message?.takeIf { it.isNotBlank() } ?: throwable.javaClass.simpleName

        companion object {
            /** 详情页最多展示的错题数。 */
            const val MISTAKE_LIMIT: Int = 20

            /** 题干摘要长度。 */
            const val STEM_LIMIT: Int = 60

            /** 薄弱阈值。 */
            const val WEAK_THRESHOLD: Double = 60.0

            /** 知识点名称的完整展示（含章节）。 */
            fun titleOf(point: KnowledgePoint): String =
                if (point.chapter.isBlank()) point.name else point.name + "（" + point.chapter + "）"
        }
    }
