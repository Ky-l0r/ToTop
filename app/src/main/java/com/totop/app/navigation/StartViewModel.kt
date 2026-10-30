package com.totop.app.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.totop.app.data.prefs.SubjectPrefs
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
        private val subjectPrefs: SubjectPrefs,
    ) : ViewModel() {
        /** 是否已完成启动判定（false 时界面只画背景，避免闪屏）。 */
        private val _ready = MutableStateFlow(false)
        val ready: StateFlow<Boolean> = _ready.asStateFlow()

        /** 起始路由：没看过引导去引导页，没选过科目去选科目，否则首页。 */
        private val _startRoute = MutableStateFlow(Routes.HOME)
        val startRoute: StateFlow<String> = _startRoute.asStateFlow()

        init {
            viewModelScope.launch {
                val done =
                    runCatching {
                        settingsRepository.getBoolean(SettingsRepository.Keys.FIRST_LAUNCH_DONE, false)
                    }.getOrDefault(false)
                // 「看过引导」与「选过科目」是两次独立的判定：
                // 已装过老版本、看完了引导但没有科目键的用户，升级后要补一次选择。
                val picked = runCatching { subjectPrefs.hasPicked() }.getOrDefault(false)
                _startRoute.value =
                    when {
                        !done -> Routes.ONBOARDING
                        !picked -> Routes.SUBJECT_PICK
                        else -> Routes.HOME
                    }
                _ready.value = true
            }
        }
    }
