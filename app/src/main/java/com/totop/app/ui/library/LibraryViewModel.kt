package com.totop.app.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.totop.app.data.repo.LearnedMaterialRepository
import com.totop.app.domain.model.LearnedMaterial
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 资料库页状态。 */
sealed interface LibraryUiState {
    /** 还没有任何资料。 */
    data object Empty : LibraryUiState

    data class Ready(val items: List<LearnedMaterial>) : LibraryUiState
}

/**
 * 资料库 ViewModel。
 *
 * 只做三件事：列出、删除、统计条数。资料的**产生**发生在拍题页（看完解答后存进去），
 * 这里不重复一遍录入流程——同一条资料两个入口只会让「来源」标注变得更不可信。
 */
@HiltViewModel
class LibraryViewModel
    @Inject
    constructor(
        private val materials: LearnedMaterialRepository,
    ) : ViewModel() {
        val state: StateFlow<LibraryUiState> =
            materials
                .observeAll()
                .map { list -> if (list.isEmpty()) LibraryUiState.Empty else LibraryUiState.Ready(list) }
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                    initialValue = LibraryUiState.Empty,
                )

        /** 删除一条资料。 */
        fun delete(id: String) {
            viewModelScope.launch { runCatching { materials.delete(id) } }
        }

        private companion object {
            /** 页面不可见 5 秒后停止收集，避免后台一直握着查询。 */
            const val STOP_TIMEOUT_MILLIS: Long = 5_000L
        }
    }
