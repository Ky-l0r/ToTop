package com.totop.app.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.totop.app.data.repo.KpointMethodRepository
import com.totop.app.data.repo.LearnedMaterialRepository
import com.totop.app.domain.model.KpointMethod
import com.totop.app.domain.model.LearnedMaterial
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 资料库页状态。 */
sealed interface LibraryUiState {
    /** 方法卡与解析都还没有。 */
    data object Empty : LibraryUiState

    /**
     * @param methods 按知识点归纳的通用解法（客户端自动攒，学生不用管）
     * @param items 学生自己存进来的解析原文
     */
    data class Ready(
        val methods: List<KpointMethod>,
        val items: List<LearnedMaterial>,
    ) : LibraryUiState
}

/**
 * 资料库 ViewModel。
 *
 * 只做三件事：列出、删除、统计条数。资料的**产生**发生在拍题页（看完解答后存进去），
 * 这里不重复一遍录入流程——同一条资料两个入口只会让「来源」标注变得更不可信。
 *
 * 7.0 起多了一栏「通用解法」：它由每次解答自动归纳而来，学生不必维护；
 * 但必须看得见——否则「资料库会越用越准」只是一句承诺。
 */
@HiltViewModel
class LibraryViewModel
    @Inject
    constructor(
        private val materials: LearnedMaterialRepository,
        private val methodCards: KpointMethodRepository,
    ) : ViewModel() {
        val state: StateFlow<LibraryUiState> =
            combine(
                materials.observeByOrigin(com.totop.app.domain.model.MaterialOrigin.IMPORTED),
                methodCards.observeAll(),
            ) { items, methods ->
                if (items.isEmpty() && methods.isEmpty()) {
                    LibraryUiState.Empty
                } else {
                    LibraryUiState.Ready(methods = methods, items = items)
                }
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = LibraryUiState.Empty,
            )

        /** 删除一条资料。 */
        fun delete(id: String) {
            viewModelScope.launch { runCatching { materials.delete(id) } }
        }

        /**
         * 删除一张方法卡。
         *
         * 需要能删：合并规则是「同知识点 + 同方法名」，一旦把两种其实不同的方法认成一种，
         * 卡片里就会混进不属于它的步骤；删掉之后，下次做同类题会重新长出来。
         */
        fun deleteMethod(id: String) {
            viewModelScope.launch { runCatching { methodCards.delete(id) } }
        }

        private companion object {
            /** 页面不可见 5 秒后停止收集，避免后台一直握着查询。 */
            const val STOP_TIMEOUT_MILLIS: Long = 5_000L
        }
    }
