package com.totop.app.ui.offline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.totop.app.data.repo.OfflineNote
import com.totop.app.data.repo.OfflineNoteRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class OfflineNoteUiState(
    val items: List<OfflineNote> = emptyList(),
    val loading: Boolean = true,
    /** 操作失败原因（删除失败等），非致命。 */
    val message: String? = null,
)

/**
 * 离线记题 ViewModel。
 *
 * 本页**不持有任何模型客户端**：离线记题的语义就是「完全不联网」，
 * 因此这里连 LlmClient 都不注入，从依赖层面杜绝误调用。
 * 数据来自 [OfflineNoteRepository] 的本地 JSON，不涉及任何网络请求。
 */
@HiltViewModel
class OfflineNoteViewModel
    @Inject
    constructor(
        private val repository: OfflineNoteRepository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(OfflineNoteUiState())
        val uiState: StateFlow<OfflineNoteUiState> = _uiState.asStateFlow()

        init {
            refresh()
        }

        /** 重新加载列表。 */
        fun refresh() {
            viewModelScope.launch {
                _uiState.value = _uiState.value.copy(loading = true)
                val notes =
                    runCatching { repository.all() }.getOrElse { failure ->
                        _uiState.value =
                            _uiState.value.copy(
                                loading = false,
                                message = "读取离线记录失败：" + (failure.message ?: "未知原因"),
                            )
                        emptyList()
                    }
                _uiState.value = _uiState.value.copy(items = notes, loading = false)
            }
        }

        /** 删除一条。 */
        fun delete(id: String) {
            viewModelScope.launch {
                runCatching { repository.delete(id) }.onFailure { failure ->
                    _uiState.value =
                        _uiState.value.copy(message = "删除失败：" + (failure.message ?: "未知原因"))
                }
                refresh()
            }
        }

        /** 清除一次性提示。 */
        fun dismissMessage() {
            _uiState.value = _uiState.value.copy(message = null)
        }
    }
