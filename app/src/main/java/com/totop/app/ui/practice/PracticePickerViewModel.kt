package com.totop.app.ui.practice

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.totop.app.data.repo.KnowledgeRepository
import com.totop.app.domain.model.Subjects
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 巩固练习的选择页（0.9）：先选科目，再选知识点与题型。
 *
 * 为什么要有这一步：以前只能从知识点详情进去练，等于「先找到这道题才会练这道题」。
 * 学生真正想的是「今天练数学，练函数那块」，所以入口必须能自己挑范围。
 */
@HiltViewModel
class PracticePickerViewModel
    @Inject
    constructor(
        private val knowledgeRepository: KnowledgeRepository,
    ) : ViewModel() {
        /** 一行可选知识点。 */
        data class Item(
            val id: String,
            val name: String,
            val chapter: String,
        )

        data class UiState(
            val loading: Boolean = true,
            /** 有知识点的科目（按十科顺序）。 */
            val subjects: List<String> = emptyList(),
            val subject: String = "",
            val items: List<Item> = emptyList(),
            val selectedId: String = "",
            val selectedName: String = "",
            /** 空串表示不限题型。 */
            val questionType: String = "",
            /** true = 按该知识点最近一道错题出题。 */
            val useHistory: Boolean = false,
            val message: String = "",
        ) {
            val canStart: Boolean get() = !loading && selectedId.isNotBlank()
        }

        private val _uiState = MutableStateFlow(UiState())
        val uiState: StateFlow<UiState> = _uiState.asStateFlow()

        private var all: List<Item> = emptyList()

        fun bootstrap() {
            if (!_uiState.value.loading) return
            viewModelScope.launch {
                val points =
                    runCatching { knowledgeRepository.catalogEnsuringSeeded().allKnowledgePoints() }
                        .getOrDefault(emptyList())
                if (points.isEmpty()) {
                    _uiState.value =
                        _uiState.value.copy(loading = false, message = "知识点树还没载入，先去「知识点」页刷新一次。")
                    return@launch
                }
                all =
                    points.map { point ->
                        Item(id = point.id, name = point.name, chapter = point.chapter.ifBlank { "未分章" })
                    }
                // 科目顺序跟十科固定顺序走，不按字母排：学生对「语数外物化…」有肌肉记忆
                val subjects = Subjects.ORDER.filter { code -> points.any { it.subject == code } }
                val first = subjects.firstOrNull().orEmpty()
                _uiState.value =
                    _uiState.value.copy(
                        loading = false,
                        subjects = subjects,
                        subject = first,
                        items = itemsOf(points.filter { it.subject == first }),
                    )
            }
        }

        fun selectSubject(code: String) {
            viewModelScope.launch {
                val points =
                    runCatching { knowledgeRepository.catalogEnsuringSeeded().allKnowledgePoints() }
                        .getOrDefault(emptyList())
                val filtered = points.filter { it.subject == code }
                _uiState.value =
                    _uiState.value.copy(
                        subject = code,
                        items = itemsOf(filtered),
                        // 换科目要清掉已选知识点，否则会拿语文的 ID 去练数学
                        selectedId = "",
                        selectedName = "",
                    )
            }
        }

        fun selectItem(
            id: String,
            name: String,
        ) {
            _uiState.value = _uiState.value.copy(selectedId = id, selectedName = name)
        }

        fun setQuestionType(label: String) {
            _uiState.value = _uiState.value.copy(questionType = label)
        }

        fun setUseHistory(value: Boolean) {
            _uiState.value = _uiState.value.copy(useHistory = value)
        }

        private fun itemsOf(points: List<com.totop.app.domain.model.KnowledgePoint>): List<Item> =
            points
                .sortedWith(compareBy({ it.chapter }, { it.orderIndex }, { it.name }))
                .map { point -> Item(id = point.id, name = point.name, chapter = point.chapter.ifBlank { "未分章" }) }
    }
