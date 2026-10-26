package com.totop.app.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.totop.app.data.repo.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 首次引导的 ViewModel。
 *
 * 只负责一件事：把「已看过引导」这个标记写进 settings 表。
 * 标记键复用 [SettingsRepository.Keys.FIRST_LAUNCH_DONE]，
 * 与设置页「重新查看引导」入口共用同一份状态，不另开一套。
 */
@HiltViewModel
class OnboardingViewModel
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
    ) : ViewModel() {
        private val _finished = MutableStateFlow(false)
        val finished: StateFlow<Boolean> = _finished.asStateFlow()

        /**
         * 完成引导：落标记 -> 通知界面收起。
         *
         * 回调**不在这里直接调用**：界面用 LaunchedEffect 监听 [finished] 收尾，
         * 这样无论标记是先写的还是先回的，收尾动作都只发生一次。
         * 两处都调用会让 navController 连续导航两次。
         */
        fun complete() {
            viewModelScope.launch {
                runCatching { settingsRepository.putBoolean(SettingsRepository.Keys.FIRST_LAUNCH_DONE, true) }
                _finished.value = true
            }
        }
    }
