package com.totop.app.ui.mistakes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.totop.app.data.prefs.SecretStore
import com.totop.app.data.remote.AiGateway
import com.totop.app.data.remote.LlmCallRegistry
import com.totop.app.data.remote.LlmException
import com.totop.app.data.remote.SolveEvent
import com.totop.app.data.remote.SolveRequest
import com.totop.app.domain.model.AiStage
import com.totop.app.domain.model.RecallSelfResult
import com.totop.app.domain.usecase.port.DomainClock
import com.totop.app.ml.OcrSnapshot
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 复习页的 AI 校验状态（默认关闭，开启才消耗 token）。 */
sealed interface ReviewCheckState {
    /** 未请求（默认状态）：页面不显示任何 AI 内容，也不消耗 token。 */
    data object Off : ReviewCheckState

    /** 正在请求。 */
    data object Running : ReviewCheckState

    /** 请求成功：展示模型给出的独立解答摘要，供学生对照。 */
    data class Done(val answer: String, val method: String, val cached: Boolean) : ReviewCheckState

    /** 请求失败：只展示可读原因，不影响自评主流程。 */
    data class Failed(val message: String) : ReviewCheckState
}

/** 复习页整体状态。 */
data class ReviewUiState(
    val session: ReviewSession = ReviewSession(),
    val loading: Boolean = true,
    val errorMessage: String? = null,
    val checkState: ReviewCheckState = ReviewCheckState.Off,
    /** AI 校验开关；默认关闭（FR-4.3：自评是零 token 主路径）。 */
    val aiCheckEnabled: Boolean = false,
    /** 每次自评后的即时反馈，用于「下次复习：明天」提示。 */
    val lastFeedback: ReviewFeedback? = null,
    /** 无待复习题时的说明。 */
    val hintMessage: String = "",
) {
    /** 顶部进度文案。 */
    val progressText: String
        get() =
            if (session.candidates.isEmpty()) {
                "本轮没有到期的错题"
            } else {
                "第 " +
                    (session.index + 1).coerceAtMost(
                        session.candidates.size,
                    ) + " / " + session.candidates.size + " 道"
            }
}

/**
 * 复习页 ViewModel（FR-4.2 / FR-4.3 / FR-4.4）。
 *
 * 三条硬约束：
 * 1. **自评零 token**：勾选「做对了 / 还是不会」只写一条 review_log，不调用任何模型；
 * 2. **AI 校验默认关闭**：只有用户主动打开开关才发起一次独立求解，且失败不影响自评；
 * 3. **不直接调用 Retrofit**：所有模型调用都经 [AiGateway]（与解题页同一个门面）。
 */
@HiltViewModel
class ReviewViewModel
    @Inject
    constructor(
        private val gateway: ReviewGateway,
        private val secretStore: SecretStore,
        private val aiGateway: AiGateway,
        private val clock: DomainClock = DomainClock.SYSTEM,
    ) : ViewModel() {
        private val registry = LlmCallRegistry()

        private val _uiState = MutableStateFlow(ReviewUiState())
        val uiState: StateFlow<ReviewUiState> = _uiState.asStateFlow()

        private var checkJob: Job? = null

        /** 载入待复习队列。 */
        fun bootstrap() {
            if (_uiState.value.session.candidates.isNotEmpty()) return
            viewModelScope.launch { load() }
        }

        /** 重新载入队列（结算页的「再来一轮」）。 */
        fun reload() {
            viewModelScope.launch { load() }
        }

        private suspend fun load() {
            _uiState.value = _uiState.value.copy(loading = true, errorMessage = null)
            try {
                val now = clock.nowUtcMillis()
                val candidates = gateway.dueCandidates(now, ReviewGateway.DEFAULT_LIMIT)
                _uiState.value =
                    _uiState.value.copy(
                        session = ReviewSession(candidates = candidates),
                        loading = false,
                        errorMessage = null,
                        checkState = ReviewCheckState.Off,
                        lastFeedback = null,
                        hintMessage =
                            if (candidates.isEmpty()) {
                                "今天没有到期的错题。按间隔重复的节奏，现在硬刷不如明天再来。"
                            } else {
                                ""
                            },
                    )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                _uiState.value =
                    _uiState.value.copy(
                        loading = false,
                        errorMessage = "读取待复习错题失败：" + describe(throwable),
                    )
            }
        }

        /** 切换 AI 校验开关（默认关闭）。 */
        fun setAiCheckEnabled(enabled: Boolean) {
            _uiState.value = _uiState.value.copy(aiCheckEnabled = enabled)
            if (!enabled) {
                checkJob?.cancel()
                registry.cancelAll()
                _uiState.value = _uiState.value.copy(checkState = ReviewCheckState.Off)
            }
        }

        /**
         * 自评（零 token）。
         *
         * @param selfResult 做对了 / 还是不会
         */
        fun submitSelfResult(selfResult: RecallSelfResult) {
            val state = _uiState.value
            val candidate = state.session.current ?: return
            if (state.session.submitting) return
            val now = clock.nowUtcMillis()
            val feedback = feedbackOf(candidate, selfResult, now)
            _uiState.value =
                _uiState.value.copy(
                    session = state.session.copy(submitting = true),
                    lastFeedback = feedback,
                )
            viewModelScope.launch {
                try {
                    gateway.recordSelfResult(candidate.mistakeId, selfResult, now)
                    val nextState = nextStateOf(candidate, selfResult, now)
                    val item =
                        ReviewOutcomeItem(
                            mistakeId = candidate.mistakeId,
                            stemSummary = candidate.stemSummary,
                            selfResult = selfResult,
                            intervalDays = nextState.intervalDays,
                            dueAtUtc = nextState.dueAtUtc,
                        )
                    val session = _uiState.value.session
                    val results = session.results + item
                    val nextIndex = session.index + 1
                    val finished = nextIndex >= session.candidates.size
                    _uiState.value =
                        _uiState.value.copy(
                            session =
                                session.copy(
                                    index = nextIndex,
                                    results = results,
                                    submitting = false,
                                    finished = finished,
                                ),
                            checkState = ReviewCheckState.Off,
                        )
                    // 进入下一题时清空 AI 校验结果，避免上一题的解答留在这一题下面
                    if (!finished) resetCheck()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (throwable: Throwable) {
                    _uiState.value =
                        _uiState.value.copy(
                            session = _uiState.value.session.copy(submitting = false),
                            errorMessage = "自评没有保存成功：" + describe(throwable),
                        )
                }
            }
        }

        /** 跳过当前题（不写记录，进入下一题）。 */
        fun skipCurrent() {
            val session = _uiState.value.session
            if (session.current == null) return
            val nextIndex = session.index + 1
            _uiState.value =
                _uiState.value.copy(
                    session =
                        session.copy(
                            index = nextIndex,
                            finished = nextIndex >= session.candidates.size,
                        ),
                    lastFeedback = null,
                )
            resetCheck()
        }

        /**
         * 可选 AI 校验（**默认关闭**）：让模型独立重解一遍，供学生对照自己的答案。
         *
         * 任何失败只写 [ReviewCheckState.Failed]，不打断自评流程，也不影响已保存的记录。
         */
        fun runAiCheck() {
            val candidate = _uiState.value.session.current ?: return
            if (_uiState.value.checkState is ReviewCheckState.Running) return
            val credentials = secretStore.read()
            if (!credentials.canCall) {
                _uiState.value =
                    _uiState.value.copy(
                        checkState =
                            ReviewCheckState.Failed(
                                "尚未配置模型，无法做 AI 校验。可以在设置页填写自己的 Base URL 与 Key；" +
                                    "不配置也不影响自评，自评不消耗任何 token。",
                            ),
                    )
                return
            }
            _uiState.value = _uiState.value.copy(checkState = ReviewCheckState.Running)
            checkJob?.cancel()
            checkJob =
                viewModelScope.launch {
                    try {
                        val request =
                            SolveRequest(
                                ocr = OcrSnapshot(text = candidate.stemText, avgConfidence = 1.0, hasFigure = false),
                                studentRawText = "",
                                candidateKpoints = emptyList(),
                            )
                        var answer = ""
                        var method = ""
                        var cached = false
                        aiGateway.solve(credentials = credentials, request = request, registry = registry)
                            .collect { event ->
                                when (event) {
                                    is SolveEvent.StageStarted -> if (event.cached) cached = true
                                    is SolveEvent.Completed -> {
                                        answer = event.content.standardSolution.finalAnswer
                                        method = event.content.standardSolution.method
                                    }
                                    is SolveEvent.Failed ->
                                        _uiState.value =
                                            _uiState.value.copy(
                                                checkState =
                                                    ReviewCheckState.Failed(
                                                        event.error.userMessage + "（本次校验未消耗自评记录）",
                                                    ),
                                            )
                                    else -> Unit
                                }
                            }
                        val current = _uiState.value.checkState
                        if (current !is ReviewCheckState.Failed) {
                            _uiState.value =
                                _uiState.value.copy(
                                    checkState =
                                        if (answer.isBlank() && method.isBlank()) {
                                            ReviewCheckState.Failed("模型没有给出可对照的答案，请稍后再试。")
                                        } else {
                                            ReviewCheckState.Done(answer = answer, method = method, cached = cached)
                                        },
                                )
                        }
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (error: LlmException) {
                        _uiState.value =
                            _uiState.value.copy(
                                checkState = ReviewCheckState.Failed(error.error.userMessage),
                            )
                    } catch (throwable: Throwable) {
                        _uiState.value =
                            _uiState.value.copy(
                                checkState = ReviewCheckState.Failed("AI 校验失败：" + describe(throwable)),
                            )
                    }
                }
        }

        /** 清除错误提示。 */
        fun clearError() {
            _uiState.value = _uiState.value.copy(errorMessage = null)
        }

        private fun resetCheck() {
            checkJob?.cancel()
            checkJob = null
            registry.cancelAll()
            _uiState.value = _uiState.value.copy(checkState = ReviewCheckState.Off)
        }

        private fun describe(throwable: Throwable): String =
            throwable.message?.takeIf { it.isNotBlank() } ?: throwable.javaClass.simpleName

        override fun onCleared() {
            registry.cancelAll()
            checkJob?.cancel()
            checkJob = null
            super.onCleared()
        }

        /** 供界面读取的阶段标签（失败时提示是哪个阶段出的问题）。 */
        fun stageLabel(stage: AiStage): String = stage.label

        /** 幂等用的请求说明（界面底部展示「AI 校验会产生一次调用」）。 */
        val aiCheckNote: String = "AI 校验会发起一次模型调用（约 1-3 千 token）；自评本身不消耗 token。"
    }

/** 状态更新小工具（保持 update 语义集中）。 */
internal fun MutableStateFlow<ReviewUiState>.mutate(block: (ReviewUiState) -> ReviewUiState) {
    update(block)
}
