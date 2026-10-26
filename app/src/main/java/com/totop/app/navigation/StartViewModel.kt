package com.totop.app.navigation

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
 * 启动分流的持有者：第一次打开先进引导，之后直接进首页。
 *
 * 为什么不在 MainActivity 里判：判定要读 Room 的 settings 表，是挂起调用；
 * Activity 里做挂起判定会让首帧出现「先闪一下首页再跳到引导」。
 * 放到 ViewModel 里，界面在判定完成前只显示背景色，判定完成后一次性决定起始路由。
 */
@HiltViewModel
class StartViewModel
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
    ) : ViewModel() {
        /** 是否已完成启动判定（false 时界面只画背景，避免闪屏）。 */
        private val _ready = MutableStateFlow(false)
        val ready: StateFlow<Boolean> = _ready.asStateFlow()

        /** 起始路由：看过引导为首页，否则为引导页。 */
        private val _startRoute = MutableStateFlow(Routes.HOME)
        val startRoute: StateFlow<String> = _startRoute.asStateFlow()

        init {
            viewModelScope.launch {
                val done =
                    runCatching {
                        settingsRepository.getBoolean(SettingsRepository.Keys.FIRST_LAUNCH_DONE, false)
                    }.getOrDefault(false)
                _startRoute.value = if (done) Routes.HOME else Routes.ONBOARDING
                _ready.value = true
            }
        }
    }
