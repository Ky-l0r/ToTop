package com.totop.app.ui.companion

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.totop.app.data.repo.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 陪伴作业里一道题的状态。 */
enum class CompanionPhase {
    /** 还没开始这一题。 */
    IDLE,

    /** 计时中，学生自己写。 */
    TIMING,

    /** 暂停。 */
    PAUSED,

    /** 时间到，可以求助或继续自己想。 */
    OVERTIME,
}

/**
 * 陪伴作业的默认参数。
 *
 * 单独抽一个对象而不是放在 [CompanionViewModel] 的 companion 里：
 * 数据类 [CompanionUiState] 的默认参数需要引用它，而逻辑上它并不属于 ViewModel。
 */
object CompanionDefaults {
    /** 默认每题 5 分钟。 */
    const val PER_QUESTION_SECONDS: Int = 300

    /** 可选的几档时长（秒）。 */
    val PRESETS: List<Int> = listOf(180, 300, 480, 600)

    const val MIN_SECONDS: Int = 60

    const val MAX_SECONDS: Int = 1800
}

/**
 * 陪伴作业的一轮统计。
 *
 * @param finishedCount 本轮已经自己写完的题数
 * @param hintCount 本轮求助过的题数
 * @param totalSeconds 本轮累计思考时长（秒）
 */
data class CompanionUiState(
    val phase: CompanionPhase = CompanionPhase.IDLE,
    /** 每题思考时长（秒），默认 5 分钟（见 [CompanionDefaults.PER_QUESTION_SECONDS]）。 */
    val perQuestionSeconds: Int = CompanionDefaults.PER_QUESTION_SECONDS,
    /** 剩余秒数。 */
    val remainingSeconds: Int = CompanionDefaults.PER_QUESTION_SECONDS,
    val finishedCount: Int = 0,
    val hintCount: Int = 0,
    val totalSeconds: Int = 0,
    /** 是否已经结束本轮（结束卡片可见）。 */
    val roundFinished: Boolean = false,
) {
    /** 分钟:秒 展示。 */
    val clockText: String
        get() {
            val safe = remainingSeconds.coerceAtLeast(0)
            val minutes = safe / 60
            val seconds = safe % 60
            return minutes.toString().padStart(2, '0') + ":" + seconds.toString().padStart(2, '0')
        }

    /** 进度 0..1（已用时间占比）。 */
    val progress: Float
        get() =
            if (perQuestionSeconds <= 0) {
                0f
            } else {
                ((perQuestionSeconds - remainingSeconds).toFloat() / perQuestionSeconds).coerceIn(0f, 1f)
            }

    /** 一句话状态。 */
    val statusText: String
        get() =
            when (phase) {
                CompanionPhase.IDLE -> "准备好就开始，先自己想五分钟"
                CompanionPhase.TIMING -> "先自己动手，卡住也不要急着问"
                CompanionPhase.PAUSED -> "已暂停"
                CompanionPhase.OVERTIME -> "时间到。写下你能想到的第一步，实在没头绪再问"
            }
}

/**
 * 陪伴作业模式。
 *
 * 设计取舍（这一版只做「节奏」，不替学生写作业）：
 * 1. **计时是核心**：每道题先给固定时长（默认 5 分钟，可改 3/5/8/10），
 *    目的是拦住「一遇到卡顿就看答案」的习惯；
 * 2. **求助有门槛但不禁止**：时间到之前不给求助按钮，
 *    时间到之后才解锁「问一下 AI」——它跳转到拍题页，用的是同一套解题能力，
 *    不在陪伴模式里重造一套对话；
 * 3. **一轮结束给复盘**：做了几题、求助了几次、平均每题花多久。
 *
 * 刻意不做的事：不统计正确率、不给排名、不发通知。陪伴模式的意义是
 * 「有人在旁边看着时间」，不是又一个排行榜。
 */
@HiltViewModel
class CompanionViewModel
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(CompanionUiState())
        val uiState: StateFlow<CompanionUiState> = _uiState.asStateFlow()

        private var ticker: Job? = null

        /** 开始计时（或从暂停恢复）。 */
        fun start() {
            val state = _uiState.value
            if (state.phase == CompanionPhase.TIMING) return
            // 从暂停恢复时保留剩余时间，重新开始时回到完整时长
            val remaining =
                if (state.phase == CompanionPhase.PAUSED) {
                    state.remainingSeconds
                } else {
                    state.perQuestionSeconds
                }
            _uiState.value =
                state.copy(
                    phase = CompanionPhase.TIMING,
                    remainingSeconds = remaining,
                    roundFinished = false,
                )
            launchTicker()
        }

        /** 暂停。 */
        fun pause() {
            ticker?.cancel()
            ticker = null
            _uiState.update { it.copy(phase = CompanionPhase.PAUSED) }
        }

        /** 这一题自己想完了：计入统计并进入下一题。 */
        fun finishQuestion() {
            ticker?.cancel()
            ticker = null
            _uiState.update {
                it.copy(
                    phase = CompanionPhase.IDLE,
                    remainingSeconds = it.perQuestionSeconds,
                    finishedCount = it.finishedCount + 1,
                    totalSeconds = it.totalSeconds + (it.perQuestionSeconds - it.remainingSeconds).coerceAtLeast(0),
                )
            }
        }

        /** 时间到之后求助：计入求助次数，剩余时间重置，让学生回来继续。 */
        fun recordHint() {
            _uiState.update {
                it.copy(
                    hintCount = it.hintCount + 1,
                    totalSeconds = it.totalSeconds + it.perQuestionSeconds,
                    phase = CompanionPhase.IDLE,
                    remainingSeconds = it.perQuestionSeconds,
                )
            }
        }

        /** 切换每题的思考时长；只能在空闲状态下改。 */
        fun setPerQuestionSeconds(seconds: Int) {
            if (_uiState.value.phase == CompanionPhase.TIMING) return
            val bounded = seconds.coerceIn(CompanionDefaults.MIN_SECONDS, CompanionDefaults.MAX_SECONDS)
            _uiState.update {
                it.copy(perQuestionSeconds = bounded, remainingSeconds = bounded)
            }
            viewModelScope.launch {
                runCatching { settingsRepository.putInt(KEY_PER_QUESTION, bounded) }
            }
        }

        /** 结束本轮并给出复盘；同时把本轮统计写进设置表，下次进来能看到上一轮。 */
        fun finishRound() {
            ticker?.cancel()
            ticker = null
            _uiState.update { it.copy(phase = CompanionPhase.IDLE, roundFinished = true) }
        }

        /** 重新开始一轮。 */
        fun startNewRound() {
            ticker?.cancel()
            ticker = null
            _uiState.update {
                CompanionUiState(perQuestionSeconds = it.perQuestionSeconds, remainingSeconds = it.perQuestionSeconds)
            }
        }

        /** 读上一次选的时长。 */
        fun bootstrap() {
            viewModelScope.launch {
                val stored =
                    runCatching {
                        settingsRepository.getInt(KEY_PER_QUESTION, CompanionDefaults.PER_QUESTION_SECONDS)
                    }.getOrDefault(CompanionDefaults.PER_QUESTION_SECONDS)
                val bounded = stored.coerceIn(CompanionDefaults.MIN_SECONDS, CompanionDefaults.MAX_SECONDS)
                _uiState.update { it.copy(perQuestionSeconds = bounded, remainingSeconds = bounded) }
            }
        }

        private fun launchTicker() {
            ticker?.cancel()
            ticker =
                viewModelScope.launch {
                    while (true) {
                        delay(1000L)
                        val state = _uiState.value
                        if (state.phase != CompanionPhase.TIMING) return@launch
                        if (state.remainingSeconds <= 1) {
                            _uiState.update {
                                it.copy(
                                    remainingSeconds = 0,
                                    phase = CompanionPhase.OVERTIME,
                                    totalSeconds = it.totalSeconds + it.perQuestionSeconds,
                                )
                            }
                            return@launch
                        }
                        _uiState.update { it.copy(remainingSeconds = it.remainingSeconds - 1) }
                    }
                }
        }

        override fun onCleared() {
            ticker?.cancel()
            ticker = null
            super.onCleared()
        }

        companion object {
            /** 设置表里的键。 */
            private const val KEY_PER_QUESTION: String = "companionPerQuestionSeconds"
        }
    }
