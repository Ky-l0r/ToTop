package com.totop.app.ui.mistakes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 错题回顾页的状态。 */
data class MistakeDetailState(
    val loading: Boolean = true,
    val detail: MistakeDetailUi? = null,
    val errorMessage: String? = null,
    val deleted: Boolean = false,
) {
    /** 是否真的找不到这道题（区别于「加载中」与「读取失败」）。 */
    val notFound: Boolean get() = !loading && detail == null && errorMessage == null
}

/**
 * 错题回顾页的 ViewModel（回答「我当时错在哪里」）。
 *
 * 与复习流程（[ReviewViewModel]）的区别：
 * - 回顾是**只读**的：把当时拍的题、当时写的思路、AI 当时的判断原样摆出来；
 * - 复习是**可交互**的：按 SM-2 排期重做、记录掌握度。
 *
 * 旧实现点错题直接跳复习页，学生想「看看我错在哪」却被迫进入做题流程，
 * 两件事被混成一件——这是真机反馈里明确指出的问题。
 */
@HiltViewModel
class MistakeDetailViewModel
    @Inject
    constructor(
        private val detailGateway: MistakeDetailGateway,
        private val mistakeRepository: com.totop.app.data.repo.MistakeRepository,
        private val masteryRecorder: com.totop.app.data.repo.MasteryRecorder,
    ) : ViewModel() {
        private val _state = MutableStateFlow(MistakeDetailState())
        val state: StateFlow<MistakeDetailState> = _state.asStateFlow()

        private var loadedId: String? = null

        /** 载入详情。重复调用同一 id 时直接返回，避免每次重组都查库。 */
        fun load(mistakeId: String) {
            if (mistakeId.isBlank()) {
                _state.value = MistakeDetailState(loading = false)
                return
            }
            if (loadedId == mistakeId && _state.value.detail != null) return
            loadedId = mistakeId
            _state.value = MistakeDetailState(loading = true)
            viewModelScope.launch {
                try {
                    val detail = detailGateway.detail(mistakeId)
                    _state.value = MistakeDetailState(loading = false, detail = detail)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (throwable: Throwable) {
                    _state.value =
                        MistakeDetailState(
                            loading = false,
                            errorMessage = "读取这道错题失败：" + (throwable.message ?: "未知原因"),
                        )
                }
            }
        }

        /**
         * 删除这道错题。
         *
         * 删除后重算相关知识点掌握度：错题减少会改变正确率，
         * 若不重算，薄弱知识点会一直挂着已经删掉的题。
         */
        fun delete(onDone: () -> Unit) {
            val detail = _state.value.detail ?: return
            viewModelScope.launch {
                try {
                    mistakeRepository.delete(detail.mistakeId)
                    runCatching { masteryRecorder.recompute(kpointIds = emptyList()) }
                    _state.value = _state.value.copy(deleted = true)
                    onDone()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (throwable: Throwable) {
                    _state.value =
                        _state.value.copy(errorMessage = "删除失败：" + (throwable.message ?: "未知原因"))
                }
            }
        }
    }
