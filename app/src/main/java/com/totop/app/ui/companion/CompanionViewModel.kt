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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/**
 * 问答流里的一条。
 *
 * [answer] 边生成边长；[streaming] 为 true 表示还在等，此时界面显示占位而不是空白气泡。
 */
data class CompanionQa(
    val question: String,
    val answer: String = "",
    val streaming: Boolean = false,
    val error: String? = null,
)

/**
 * 陪伴页的一轮状态。
 *
 * 与上一版的根本差别：**没有「这一题」这一层**。
 * 上一版把作业切成一道道题，每道题要按「开始这一题」「写完了」两次，一轮三十题就是六十次点按；
 * 而写作业是连续的，题目之间没有段落。现在只有「这一场自习」一个单位：
 * 进来即计时，只有问和答，离开即结账。
 *
 * @param elapsedSeconds 本次进入后累计的秒数
 * @param items 问答流，问过的都留着，不再「写完一题就清空」
 */
data class CompanionUiState(
    val elapsedSeconds: Int = 0,
    val question: String = "",
    val items: List<CompanionQa> = emptyList(),
    val asking: Boolean = false,
) {
    /** 顶栏那行小字。不足一分钟时不显示「0 分钟」这种读起来像没开始的内容。 */
    val clockLabel: String
        get() = if (elapsedSeconds < 60) "刚开始" else "已写 " + (elapsedSeconds / 60) + " 分钟"

    /** 有内容且不在等回答时才能发第二条，避免同一句话连发两次。 */
    val canAsk: Boolean get() = question.isNotBlank() && !asking
}

/**
 * 陪伴作业。
 *
 * 三条规则：
 * 1. **进来就计时**：没有开始/暂停/结束三个键，只有一条累计时长，离开页面时结账；
 * 2. **随时可问**：提问不打断计时——写作业时问一句本身就是学习的一部分，
 *    把它从专注时长里扣掉反而失真，也免掉「问完要记得点继续」这层状态；
 * 3. **问答都留着**：翻回去能看见刚才问过什么，不再写下一题就清空。
 *
 * 刻意不做的事：不排名、不打卡、不发通知、不做 5 分钟提醒。
 * 提醒是「以计时为主」时才需要的兜底（怕学生盯着倒计时不动手），
 * 既然这页以问为主，学生自己会开口。
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
        private var askJob: Job? = null

        /** 已经写进当天统计的秒数。用游标而不是「一次结账」，避免切页时重复累加。 */
        private var recordedSeconds: Int = 0

        fun updateQuestion(text: String) {
            _uiState.update { it.copy(question = text) }
        }

        /**
         * 走一秒。
         *
         * 由界面在**页面可见时**调用（`repeatOnLifecycle(RESUMED)` 里每秒一次），
         * 不放在 ViewModel 自己起的循环里：那样切到后台、或切到别的一页也照常计，
         * 学生把手机放下半小时就等于凭空多记半小时。
         */
        fun tick() {
            _uiState.update { it.copy(elapsedSeconds = it.elapsedSeconds + 1) }
            if (_uiState.value.elapsedSeconds - recordedSeconds >= CHECKPOINT_SECONDS) checkpoint()
        }

        /**
         * 提问：先把气泡塞进流里（立即有反馈），再边收边填。
         *
         * 提问**不暂停计时**，见类注释第 2 条。
         */
        fun ask() {
            val state = _uiState.value
            val text = state.question.trim()
            if (text.isEmpty() || state.asking) return
            _uiState.update {
                it.copy(
                    question = "",
                    asking = true,
                    items = it.items + CompanionQa(question = text, streaming = true),
                )
            }
            askJob?.cancel()
            askJob =
                viewModelScope.launch {
                    runCatching { runAsk(text) }
                    _uiState.update { it.copy(asking = false) }
                }
        }

        /**
         * 把自上次以来的专注时长写进当天统计。
         *
         * 由界面在离开页面时调用，[tick] 每分钟也会调一次兜底：
         * 不能放在 [onCleared] 里——那时 viewModelScope 已经跟着取消，
         * 启动的协程还没跑到写库就没了。
         */
        fun checkpoint() {
            val elapsed = _uiState.value.elapsedSeconds
            val delta = elapsed - recordedSeconds
            if (delta <= 0) return
            recordedSeconds = elapsed
            viewModelScope.launch {
                runCatching { insightRepository.addFocusSeconds(delta.toLong(), System.currentTimeMillis()) }
            }
        }

        private suspend fun runAsk(text: String) {
            val credentials = runCatching { secretStore.read() }.getOrNull()
            if (credentials == null || !credentials.canCall) {
                markLast("还没配置模型。请到设置页填写 Base URL、API Key 与模型名后再问。")
                return
            }
            var failure: String? = null
            runCatching {
                askGateway
                    .ask(
                        credentials = credentials,
                        question = text,
                        registry = registry,
                        requestId = UUID.randomUUID().toString(),
                    ).collect { event ->
                        when (event) {
                            is CompanionAskEvent.Delta -> updateLast { it.copy(answer = it.answer + event.text) }
                            is CompanionAskEvent.Completed -> {
                                updateLast { it.copy(answer = event.text, streaming = false) }
                                recordForgotten(text)
                            }
                            is CompanionAskEvent.Failed -> failure = event.error.userMessage
                        }
                    }
            }.onFailure { throwable ->
                failure = "提问失败：" + (throwable.message ?: "未知原因")
            }
            // 流没有给出结论就结束了（网络断在半路、被取消）：收尾，
            // 否则气泡会一直停在「正在想」。
            if (_uiState.value.items.lastOrNull()?.streaming == true) {
                markLast(failure ?: "模型没有返回内容，可以再问一次。")
            }
        }

        private fun updateLast(block: (CompanionQa) -> CompanionQa) {
            _uiState.update { state ->
                val last = state.items.lastOrNull() ?: return@update state
                state.copy(items = state.items.dropLast(1) + block(last))
            }
        }

        private fun markLast(error: String) {
            updateLast { it.copy(streaming = false, error = error) }
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

        private companion object {
            /** 低于 3 个字的知识点名太泛（如「函数」「方程」），只用作噪声。 */
            const val MIN_KP_NAME_LENGTH: Int = 3

            /** 一句话最多记 3 个知识点：再多基本是长句里顺带提到的，不代表真的想不起来。 */
            const val MAX_KP_HITS: Int = 3

            /** 每隔这么久把专注时长写一次库：进程被杀最多丢一分钟，又不至于每秒写一次。 */
            const val CHECKPOINT_SECONDS: Int = 60
        }

        override fun onCleared() {
            askJob?.cancel()
            registry.cancelAllAndSeal()
            super.onCleared()
        }
    }
