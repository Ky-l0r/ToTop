package com.totop.app.ui.companion

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.totop.app.data.prefs.SecretStore
import com.totop.app.data.remote.CompanionAskEvent
import com.totop.app.data.remote.CompanionAskGateway
import com.totop.app.data.remote.LlmCallRegistry
import com.totop.app.data.repo.CompanionInsightRepository
import com.totop.app.data.repo.KnowledgeRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/** 陪伴作业里这一题的状态。 */
enum class CompanionPhase {
    /** 还没开始这一题。 */
    IDLE,

    /** 正计时中。 */
    TIMING,

    /** 暂停（含提问期间）。 */
    PAUSED,
}

/** 陪伴模式的固定阈值。 */
object CompanionDefaults {
    /** 本题专注超过这个秒数，提醒一次「可以问了」。 */
    const val HINT_AFTER_SECONDS: Int = 300
}

/**
 * 陪伴作业的一轮状态。
 *
 * 与上一版的区别：这里是**正计时**（番茄钟），不是倒计时。
 * 倒计时隐含「到点就该求助」，而新口径是「一直在做，卡住随时问」，
 * 计时只负责记录专注时长，并在 5 分钟时给一句提醒。
 *
 * @param elapsedSeconds 本题已专注秒数
 * @param finishedCount 本轮写完的题数
 * @param askCount 本轮提问次数
 * @param totalFocusSeconds 本轮已写完题目的专注秒数
 */
data class CompanionUiState(
    val phase: CompanionPhase = CompanionPhase.IDLE,
    val elapsedSeconds: Int = 0,
    val finishedCount: Int = 0,
    val askCount: Int = 0,
    val totalFocusSeconds: Int = 0,
    /** 本题是否已经提醒过「做了一会儿了」。 */
    val hinted: Boolean = false,
    val question: String = "",
    val answer: String? = null,
    val answerError: String? = null,
    val asking: Boolean = false,
    val roundFinished: Boolean = false,
) {
    /** 分钟:秒。写作业很少超过一小时，因此不做时:分:秒。 */
    val clockText: String
        get() {
            val safe = elapsedSeconds.coerceAtLeast(0)
            return (safe / 60).toString().padStart(2, '0') + ":" + (safe % 60).toString().padStart(2, '0')
        }

    /** 本轮累计（含正在做的这一题）。 */
    val totalWithCurrent: Int get() = totalFocusSeconds + elapsedSeconds

    /** 是否该显示「可以问了」的提醒。 */
    val showHint: Boolean get() = hinted && !roundFinished

    /** 一句话状态。 */
    val statusText: String
        get() =
            when {
                roundFinished -> "本轮结束"
                asking -> "正在回答，计时已暂停"
                phase == CompanionPhase.TIMING -> "自己在写，卡住随时问"
                phase == CompanionPhase.PAUSED -> "已暂停"
                else -> "准备好就开始，计时只记录你真正在写的时间"
            }
}

/**
 * 陪伴作业。
 *
 * 三条规则：
 * 1. **正计时**：只记录专注时长，不设截止时间；本轮结束给「几题 / 多久 / 问了几次」；
 * 2. **随时可问**：提问时计时自动暂停，回答完自动恢复——问问题的时间不该算进专注时长；
 * 3. **5 分钟提醒**：本题做到 5 分钟时给一句「卡住可以问我」，每题只提醒一次。
 *
 * 刻意不做的事：不排名、不打卡、不发通知。
 */
@HiltViewModel
class CompanionViewModel
    @Inject
    constructor(
        private val secretStore: SecretStore,
        private val askGateway: CompanionAskGateway,
        private val knowledgeRepository: KnowledgeRepository,
        private val insightRepository: CompanionInsightRepository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(CompanionUiState())
        val uiState: StateFlow<CompanionUiState> = _uiState.asStateFlow()

        private val registry = LlmCallRegistry()
        private var ticker: Job? = null
        private var askJob: Job? = null

        /** 开始（或从暂停恢复）正计时。 */
        fun start() {
            if (_uiState.value.phase == CompanionPhase.TIMING || _uiState.value.roundFinished) return
            _uiState.update { it.copy(phase = CompanionPhase.TIMING) }
            launchTicker()
        }

        /** 暂停。 */
        fun pause() {
            ticker?.cancel()
            ticker = null
            _uiState.update { it.copy(phase = CompanionPhase.PAUSED) }
        }

        /** 这一题写完了：计入本轮统计，回到空闲并清掉提问内容。 */
        fun finishQuestion() {
            ticker?.cancel()
            ticker = null
            recordFocus(_uiState.value.elapsedSeconds)
            _uiState.update {
                it.copy(
                    phase = CompanionPhase.IDLE,
                    totalFocusSeconds = it.totalFocusSeconds + it.elapsedSeconds,
                    elapsedSeconds = 0,
                    finishedCount = it.finishedCount + 1,
                    hinted = false,
                    question = "",
                    answer = null,
                    answerError = null,
                )
            }
        }

        /** 结束本轮，给出复盘。 */
        fun finishRound() {
            ticker?.cancel()
            ticker = null
            recordFocus(_uiState.value.elapsedSeconds)
            askJob?.cancel()
            registry.cancelAll()
            _uiState.update { it.copy(phase = CompanionPhase.IDLE, roundFinished = true) }
        }

        /** 重新开始一轮。 */
        fun startNewRound() {
            ticker?.cancel()
            ticker = null
            _uiState.value = CompanionUiState()
        }

        fun updateQuestion(text: String) {
            _uiState.update { it.copy(question = text) }
        }

        /**
         * 提问：暂停计时 → 请求 → 回答完自动恢复计时。
         *
         * 「自动恢复」只在提问前确实在计时时才做；学生本来就暂停着时，
         * 回答完仍然保持暂停，不能替他决定继续。
         */
        fun ask() {
            val state = _uiState.value
            val text = state.question.trim()
            if (text.isEmpty() || state.asking) return
            val wasTiming = state.phase == CompanionPhase.TIMING
            pause()
            _uiState.update {
                it.copy(asking = true, answer = null, answerError = null, askCount = it.askCount + 1)
            }
            askJob?.cancel()
            askJob =
                viewModelScope.launch {
                    val credentials = runCatching { secretStore.read() }.getOrNull()
                    if (credentials == null || !credentials.canCall) {
                        _uiState.update {
                            it.copy(
                                asking = false,
                                answerError = "还没配置模型。请到设置页填写 Base URL、API Key 与模型名后再问。",
                            )
                        }
                        return@launch
                    }
                    var last: CompanionAskEvent? = null
                    runCatching {
                        askGateway
                            .ask(
                                credentials = credentials,
                                question = text,
                                registry = registry,
                                requestId = UUID.randomUUID().toString(),
                            ).collect { event -> last = event }
                    }.onFailure { failure ->
                        _uiState.update {
                            it.copy(asking = false, answerError = "提问失败：" + (failure.message ?: "未知原因"))
                        }
                        if (wasTiming) start()
                        return@launch
                    }
                    when (val outcome = last) {
                        is CompanionAskEvent.Completed -> {
                            _uiState.update { it.copy(asking = false, answer = outcome.text) }
                            recordForgotten(text)
                        }
                        is CompanionAskEvent.Failed ->
                            _uiState.update { it.copy(asking = false, answerError = outcome.error.userMessage) }
                        else ->
                            _uiState.update {
                                it.copy(asking = false, answerError = "模型没有返回内容，可以再问一次。")
                            }
                    }
                    if (wasTiming) start()
                }
        }

        /** 专注时长按天落库（daily_stats 的 studySeconds），学情报告据此统计学习时长。 */
        private fun recordFocus(seconds: Int) {
            if (seconds <= 0) return
            viewModelScope.launch {
                runCatching { insightRepository.addFocusSeconds(seconds.toLong(), System.currentTimeMillis()) }
            }
        }

        /**
         * 把这次问到的知识点记进「易忘点」。
         *
         * 匹配方式是**知识点名子串命中**：问题文本往往就是「链式法则是什么」这种短句，
         * 直接用内置目录的名字去比对即可，不需要再为抽取知识点多花一次模型调用。
         * 名字长度要求 3 个字以上，避免「函数」「方程」这类过短词把整页知识点都命中。
         */
        private fun recordForgotten(question: String) {
            viewModelScope.launch {
                val catalog = runCatching { knowledgeRepository.catalogEnsuringSeeded() }.getOrNull() ?: return@launch
                val hits =
                    catalog
                        .allKnowledgePoints()
                        .filter { point -> point.name.length >= MIN_KP_NAME_LENGTH && question.contains(point.name) }
                        .take(MAX_KP_HITS)
                if (hits.isEmpty()) return@launch
                runCatching { insightRepository.recordAsked(hits, System.currentTimeMillis()) }
            }
        }

        private fun launchTicker() {
            ticker?.cancel()
            ticker =
                viewModelScope.launch {
                    while (true) {
                        delay(1_000L)
                        val state = _uiState.value
                        if (state.phase != CompanionPhase.TIMING) return@launch
                        val next = state.elapsedSeconds + 1
                        _uiState.update {
                            it.copy(
                                elapsedSeconds = next,
                                // 每题只提醒一次；提醒不打断计时，学生没看屏幕时也只是一句提示
                                hinted = it.hinted || next >= CompanionDefaults.HINT_AFTER_SECONDS,
                            )
                        }
                    }
                }
        }

        private companion object {
            /** 低于 3 个字的知识点名太泛（如「函数」「方程」），只用作噪声。 */
            const val MIN_KP_NAME_LENGTH: Int = 3

            /** 一句话最多记 3 个知识点：再多基本是长句里顺带提到的，不代表真的想不起来。 */
            const val MAX_KP_HITS: Int = 3
        }

        override fun onCleared() {
            ticker?.cancel()
            askJob?.cancel()
            registry.cancelAllAndSeal()
            ticker = null
            super.onCleared()
        }
    }
