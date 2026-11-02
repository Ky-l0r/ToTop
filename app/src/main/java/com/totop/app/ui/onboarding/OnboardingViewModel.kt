package com.totop.app.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.totop.app.data.repo.SettingsRepository
import com.totop.app.domain.model.TeachingMode
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
         * 讲解模式：引导（先想一步）还是直接（看完整讲解）。
         *
         * 与设置页共用同一个键 [SettingsRepository.Keys.MODE]，不另存一份：
         * 引导页选「直接」之后，拍题页第一次进来就是直接讲；之后在设置里改了，
         * 这里再打开时显示的也是改过的值——两处读的是同一个事实。
         *
         * 初值取默认，读库是异步的；读到之后覆盖。用户在这一瞬间就点了选择也不会丢：
         * 点选本身就是一次写库，写的是他刚选的那个值。
         */
        private val _mode = MutableStateFlow(TeachingMode.DEFAULT)
        val mode: StateFlow<TeachingMode> = _mode.asStateFlow()

        init {
            viewModelScope.launch {
                val stored = runCatching { settingsRepository.get(SettingsRepository.Keys.MODE) }.getOrNull()
                _mode.value = TeachingMode.fromWire(stored)
            }
        }

        /** 选择讲解方式：立刻写库，不等「开始用」。 */
        fun setMode(mode: TeachingMode) {
            if (_mode.value == mode) return
            _mode.value = mode
            viewModelScope.launch {
                runCatching { settingsRepository.put(SettingsRepository.Keys.MODE, mode.wire) }
            }
        }

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
