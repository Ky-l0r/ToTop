package com.totop.app.ui.companion

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.totop.app.data.prefs.SecretStore
import com.totop.app.data.remote.CompanionAskEvent
import com.totop.app.data.remote.CompanionAskGateway
import com.totop.app.data.remote.LlmCallRegistry
import com.totop.app.data.repo.CompanionInsightRepository
import com.totop.app.data.repo.KnowledgeRepository
import com.totop.app.data.repo.StudyNoteRepository
import com.totop.app.domain.model.KnowledgePoint
import com.totop.app.domain.model.StudyNoteKind
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
    /** 这一条回答是否已经存成笔记，以及存成了哪一类。存过之后按钮变成「已存为…」，避免重复存。 */
    val savedKind: StudyNoteKind? = null,
    /** 存笔记失败时的原因（只给一句能读的，不带堆栈）。 */
    val noteError: String? = null,
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
 * @param todayBaseSeconds 进入本页之前当天已经记过的秒数（顶栏显示「今天」而不是「这次」）
 * @param items 问答流，问过的都留着，不再「写完一题就清空」
 */
data class CompanionUiState(
    val elapsedSeconds: Int = 0,
    val todayBaseSeconds: Int = 0,
    val question: String = "",
    val items: List<CompanionQa> = emptyList(),
    val asking: Boolean = false,
) {
    /** 当天累计：进入之前的存量 + 本次进入后的增量。 */
    val todaySeconds: Int get() = todayBaseSeconds + elapsedSeconds

    /** 顶栏那行小字。不足一分钟时不显示「0 分钟」这种读起来像没开始的内容。 */
    val clockLabel: String
        get() = if (todaySeconds < 60) "刚开始" else "今天已写 " + (todaySeconds / 60) + " 分钟"

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
        private val noteRepository: StudyNoteRepository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(CompanionUiState())
        val uiState: StateFlow<CompanionUiState> = _uiState.asStateFlow()

        private val registry = LlmCallRegistry()
        private var askJob: Job? = null

        /** 已经写进当天统计的秒数。用游标而不是「一次结账」，避免切页时重复累加。 */
        private var recordedSeconds: Int = 0

        init {
            // 起点只读一次：之后本页的每一秒都由 tick 自己累加，
            // 若改成持续观察当天那一行，checkpoint 写库与界面读数之间会有一次回跳。
            viewModelScope.launch {
                val base = runCatching { insightRepository.todayFocusSeconds() }.getOrDefault(0L)
                _uiState.update { it.copy(todayBaseSeconds = base.toInt()) }
            }
        }

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
            // 新气泡就是最后一条，下标由追加前的长度决定：
            // 之后所有增量都按这个下标回填，不再依赖「最后一条」。
            val index = _uiState.value.items.lastIndex
            askJob =
                viewModelScope.launch {
                    runCatching { runAsk(text, index) }
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

        /**
         * 跑一次提问，增量按 [index] 回填。
         *
         * 为什么不再「往最后一条填」：中间某条失败后学生也想重试，
         * 而按「最后一条」回填会把重试的结果写到别处去。
         * 下标在请求期间是稳定的——[asking] 为 true 时不允许再提问，
         * 期间 items 只会被同一条的增量修改，不会插入或删除。
         */
        private suspend fun runAsk(
            text: String,
            index: Int,
        ) {
            val credentials = runCatching { secretStore.read() }.getOrNull()
            if (credentials == null || !credentials.canCall) {
                markAt(index, "还没配置模型。请到设置页填写 Base URL、API Key 与模型名后再问。")
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
                            is CompanionAskEvent.Delta -> updateAt(index) { it.copy(answer = it.answer + event.text) }
                            is CompanionAskEvent.Completed -> {
                                updateAt(index) { it.copy(answer = event.text, streaming = false) }
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
            if (_uiState.value.items.getOrNull(index)?.streaming == true) {
                markAt(index, failure ?: "模型没有返回内容，可以再问一次。")
            }
        }

        /**
         * 重发某一条提问。
         *
         * 写作业时网络断一下很常见，失败后让学生把整句话重打一遍是最没必要的成本。
         * 只重跑请求、不新增气泡；同时清掉上一次的错误文案——否则重试成功了红字还挂着。
         *
         * 任意一条都能重试（不只是最后一条）：中途某条断了，学生往下问的时候
         * 未必想回头处理，等想起来时那条早就不是最后一条了。
         */
        fun retryAt(index: Int) {
            val state = _uiState.value
            val item = state.items.getOrNull(index) ?: return
            if (state.asking || item.streaming) return
            val text = item.question.trim()
            if (text.isEmpty()) return
            _uiState.update { current ->
                current.copy(
                    asking = true,
                    items =
                        current.items.toMutableList().also { list ->
                            list[index] = item.copy(answer = "", error = null, streaming = true)
                        },
                )
            }
            askJob?.cancel()
            askJob =
                viewModelScope.launch {
                    runCatching { runAsk(text, index) }
                    _uiState.update { it.copy(asking = false) }
                }
        }

        private fun updateAt(
            index: Int,
            block: (CompanionQa) -> CompanionQa,
        ) {
            _uiState.update { state ->
                val target = state.items.getOrNull(index) ?: return@update state
                state.copy(
                    items = state.items.toMutableList().also { it[index] = block(target) },
                )
            }
        }

        private fun markAt(
            index: Int,
            error: String,
        ) {
            updateAt(index) { it.copy(streaming = false, error = error) }
        }

        /**
         * 把某一条回答存成笔记。
         *
         * 命中受控知识点就挂上去（只挂已有节点，命中不到就存成没有归属的笔记），
         * 不做「自动总结」：笔记的价值在于它就是当时那句原话，改写后学生复习时认不出来。
         *
         * @param kind 由学生点「记重点 / 记卡点」决定，不给默认值——
         *   一旦有默认值，所有人都会存成同一类，分类也就失去意义。
         */
        fun saveNote(
            index: Int,
            kind: StudyNoteKind,
        ) {
            val item = _uiState.value.items.getOrNull(index) ?: return
            if (item.streaming || item.answer.isBlank() || item.savedKind != null) return
            viewModelScope.launch {
                val kpointId = runCatching { kpointHits(item.question).firstOrNull()?.id }.getOrNull()
                val saved =
                    runCatching {
                        noteRepository.saveCompanion(
                            question = item.question,
                            answer = item.answer,
                            kpointId = kpointId,
                            kind = kind,
                        )
                    }
                _uiState.update { state ->
                    val current = state.items.getOrNull(index) ?: return@update state
                    val updated =
                        current.copy(
                            savedKind = if (saved.isSuccess) kind else null,
                            noteError = if (saved.isSuccess) null else "没存成，再点一次",
                        )
                    state.copy(items = state.items.toMutableList().also { it[index] = updated })
                }
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
                val hits = kpointHits(question)
                if (hits.isEmpty()) return@launch
                runCatching { insightRepository.recordAsked(hits, System.currentTimeMillis()) }
            }
        }

        /** 按名字子串命中知识点，命中不到返回空表。 */
        private suspend fun kpointHits(text: String): List<KnowledgePoint> {
            val catalog = runCatching { knowledgeRepository.catalogEnsuringSeeded() }.getOrNull() ?: return emptyList()
            return catalog
                .allKnowledgePoints()
                .filter { point -> point.name.length >= MIN_KP_NAME_LENGTH && text.contains(point.name) }
                .take(MAX_KP_HITS)
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
