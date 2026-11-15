package com.totop.app.ui.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.totop.app.data.repo.StudyNoteRepository
import com.totop.app.domain.model.StudyNoteDigest
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 卡点笔记页状态。 */
sealed interface NotesUiState {
    /** 一条笔记都没有。 */
    data object Empty : NotesUiState

    /** 按分类分好节的笔记；分节顺序由 [StudyNoteDigest.sections] 固定为「重点 → 卡点」。 */
    data class Ready(val sections: List<StudyNoteDigest.Section>) : NotesUiState
}

/**
 * 卡点笔记 ViewModel。
 *
 * 只做三件事：列出（按类分节）、删除、计数。笔记的**产生**在陪伴页，
 * 这里不重复一遍录入流程——同一条笔记两个入口只会让「来源」和「分类」都变得不可信。
 *
 * 为什么不在这里做筛选/搜索：这个阶段笔记量是几十条，加筛选只是把「找不到」换成「不会用」；
 * 分节已经解决了「重点和卡点混在一起」这个真问题。
 */
@HiltViewModel
class NotesViewModel
    @Inject
    constructor(
        private val notes: StudyNoteRepository,
    ) : ViewModel() {
        val state: StateFlow<NotesUiState> =
            notes
                .observeAll()
                .map { list ->
                    if (list.isEmpty()) {
                        NotesUiState.Empty
                    } else {
                        NotesUiState.Ready(StudyNoteDigest.sections(list))
                    }
                }
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                    initialValue = NotesUiState.Empty,
                )

        /** 删除一条：学生自己记的，记错了要能删掉。 */
        fun delete(id: String) {
            viewModelScope.launch { runCatching { notes.delete(id) } }
        }

        private companion object {
            /** 页面不可见 5 秒后停止收集，避免后台一直握着查询。 */
            const val STOP_TIMEOUT_MILLIS: Long = 5_000L
        }
    }
