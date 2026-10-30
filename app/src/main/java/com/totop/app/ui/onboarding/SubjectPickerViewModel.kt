package com.totop.app.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.totop.app.data.prefs.SubjectPrefs
import com.totop.app.domain.model.SubjectScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 科目选择页的状态。 */
data class SubjectPickerState(
    val selected: Set<String> = SubjectScope.ALL,
    val loading: Boolean = true,
    val saving: Boolean = false,
) {
    /** 至少要选一科：一科都不选，错题本与知识点就是空的，用户会以为坏了。 */
    val canConfirm: Boolean get() = selected.isNotEmpty() && !saving

    /** 是否全选（用于「全选/全不选」按钮的文案与状态）。 */
    val allSelected: Boolean get() = selected.containsAll(SubjectScope.PICKABLE)
}

/**
 * 科目选择页的 ViewModel。
 *
 * 首次启动与设置页共用同一份状态与同一个页面：
 * 首次启动时没有返回键，设置页里有——差别只在界面，读写逻辑完全一样。
 */
@HiltViewModel
class SubjectPickerViewModel
    @Inject
    constructor(
        private val subjectPrefs: SubjectPrefs,
    ) : ViewModel() {
        private val _state = MutableStateFlow(SubjectPickerState())
        val state: StateFlow<SubjectPickerState> = _state.asStateFlow()

        init {
            viewModelScope.launch {
                val allowed = runCatching { subjectPrefs.allowed() }.getOrDefault(SubjectScope.ALL)
                _state.value = SubjectPickerState(selected = allowed, loading = false)
            }
        }

        /** 勾选/取消一科。 */
        fun toggle(code: String) {
            val current = _state.value.selected
            val next = if (current.contains(code)) current - code else current + code
            _state.value = _state.value.copy(selected = next)
        }

        /** 全选 / 全不选。 */
        fun toggleAll() {
            val next = if (_state.value.allSelected) emptySet() else SubjectScope.ALL
            _state.value = _state.value.copy(selected = next)
        }

        /** 保存并回调（回调在保存成功之后触发，避免写库失败还跳走）。 */
        fun save(onSaved: () -> Unit) {
            if (!_state.value.canConfirm) return
            _state.value = _state.value.copy(saving = true)
            viewModelScope.launch {
                runCatching { subjectPrefs.save(_state.value.selected) }
                _state.value = _state.value.copy(saving = false)
                onSaved()
            }
        }
    }
