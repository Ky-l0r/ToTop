package com.totop.app.ui.chat

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.totop.app.data.prefs.SecretStore
import com.totop.app.data.remote.AiGateway
import com.totop.app.data.remote.FollowUpEvent
import com.totop.app.data.remote.FollowUpGateway
import com.totop.app.data.remote.FollowUpTurn
import com.totop.app.data.remote.LlmCallRegistry
import com.totop.app.data.remote.LlmException
import com.totop.app.data.remote.SolveContextBuilder
import com.totop.app.data.remote.SolveEvent
import com.totop.app.data.remote.SolveRequest
import com.totop.app.data.repo.ChatTurn
import com.totop.app.data.repo.MistakeRepository
import com.totop.app.data.repo.QuestionRepository
import com.totop.app.data.repo.SettingsRepository
import com.totop.app.data.repo.SolveSession
import com.totop.app.data.repo.SolveSessionRepository
import com.totop.app.domain.model.AiSolvedContent
import com.totop.app.domain.model.AiStage
import com.totop.app.domain.model.ErrorType
import com.totop.app.domain.model.HintLog
import com.totop.app.domain.model.KpointAssignment
import com.totop.app.domain.model.MistakeRecord
import com.totop.app.domain.model.PageRole
import com.totop.app.domain.model.Question
import com.totop.app.domain.model.QuestionDraft
import com.totop.app.domain.model.QuestionPage
import com.totop.app.domain.model.QuestionSourceType
import com.totop.app.domain.model.StudentStepStatus
import com.totop.app.domain.model.Subjects
import com.totop.app.domain.model.TeachingMode
import com.totop.app.ml.ImagePreprocessor
import com.totop.app.ml.OcrOutcome
import com.totop.app.ml.OcrRecognizer
import com.totop.app.ml.PreprocessResult
import com.totop.app.util.EncodedImage
import com.totop.app.util.ImageCropResult
import com.totop.app.util.ImageCropper
import com.totop.app.util.ImageMath
import com.totop.app.util.NormalizedRect
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject

/**
 * 解题页 ViewModel（FR-1 / FR-2）。
 *
 * 本版的核心变化（对应真机反馈）：
 *
 * 1. **会话化**：一次「拍题 → 解答 → 追问」是**一条会话**，落盘保存。
 *    切到别的页面再回来，历史还在；还可以从会话列表点回来回顾。
 *    旧版把状态全放在内存里，页面销毁即清空，用户看到的是「一片空白的新界面」。
 * 2. **追问不再重跑整套流程**：输入框里的文字若是在已有解答之后发出的，
 *    走 [FollowUpGateway] 带上下文回答，而不是再跑一遍四阶段（那会得到一次全新解答）。
 * 3. **发送后清空输入框**：文字已经作为消息进入对话流，留在输入框里既重复又误导。
 * 4. **拍照后先框选**：拍照完成立即打开框选页，用户确认后再回到对话页写思路；
 *    不再出现「拍完直接发送」的错序流程。
 * 5. **落库与掌握度联动**：没有定位到错因时也写入错题本（作为「待复习」记录），
 *    并在写入后立刻重算相关知识点的掌握度——否则错题本与薄弱知识点永远是空的。
 */
@Suppress("TooManyFunctions", "LargeClass")
@HiltViewModel
class SolveViewModel
    @Inject
    constructor(
        @ApplicationContext private val appContext: Context,
        private val secretStore: SecretStore,
        private val gateway: AiGateway,
        private val followUpGateway: FollowUpGateway,
        private val knowledgeCatalog: KnowledgeCatalogReader,
        private val questionRepository: QuestionRepository,
        private val mistakeRepository: MistakeRepository,
        private val settingsRepository: SettingsRepository,
        private val ocrRecognizer: OcrRecognizer,
        private val imagePreprocessor: ImagePreprocessor,
        private val imageCropper: ImageCropper,
        private val sessionRepository: SolveSessionRepository,
        private val offlineRepository: com.totop.app.data.repo.OfflineNoteRepository,
        private val masteryRecorder: com.totop.app.data.repo.MasteryRecorder,
    ) : ViewModel() {
        private val registry = LlmCallRegistry()

        // ---------- 输入侧状态 ----------

        /** 输入区阶段（只描述输入，不承载会话内容）。 */
        private val _phase = MutableStateFlow(ComposerPhase.EMPTY)
        val phase: StateFlow<ComposerPhase> = _phase.asStateFlow()

        /** 输入框草稿（学生正在敲的字）。 */
        private val _draft = MutableStateFlow(SolveDraft())
        val draft: StateFlow<SolveDraft> = _draft.asStateFlow()

        /** 拍照草稿（多页 + 框选）。 */
        private val _draftPages = MutableStateFlow<QuestionDraft>(QuestionDraft.EMPTY)
        val draftPages: StateFlow<QuestionDraft> = _draftPages.asStateFlow()

        /** 最近的图片来源，落库时写进 question.sourceType。 */
        private val _lastSourceType = MutableStateFlow(QuestionSourceType.CAMERA)

        /** 本轮主图的路径与哈希，落库时写进 question。 */
        private val _lastImagePath = MutableStateFlow<String?>(null)

        private val _lastImageSha256 = MutableStateFlow<String?>(null)

        /** 当前正在预览/框选第几页。 */
        private val _activePage = MutableStateFlow(0)
        val activePage: StateFlow<Int> = _activePage.asStateFlow()

        /** 图片处理中的提示（拍照后压缩/识别期间）。 */
        private val _processing = MutableStateFlow(false)
        val processing: StateFlow<Boolean> = _processing.asStateFlow()

        private val _networkStatus = MutableStateFlow(NetworkStatus.UNKNOWN)
        val networkStatus: StateFlow<NetworkStatus> = _networkStatus.asStateFlow()

        private val _mode = MutableStateFlow(TeachingMode.DEFAULT)
        val mode: StateFlow<TeachingMode> = _mode.asStateFlow()

        private val _modeOverridden = MutableStateFlow(false)
        val modeOverridden: StateFlow<Boolean> = _modeOverridden.asStateFlow()

        // ---------- 会话侧状态 ----------

        /** 当前会话的消息流（UI 直接渲染）。 */
        private val _messages = MutableStateFlow<List<ConversationMessage>>(emptyList())
        val messages: StateFlow<List<ConversationMessage>> = _messages.asStateFlow()

        /** 当前会话 ID；尚未建立会话时为 null。 */
        private val _sessionId = MutableStateFlow<String?>(null)
        val sessionId: StateFlow<String?> = _sessionId.asStateFlow()

        /** 会话列表（多对话管理面板）。 */
        private val _sessions = MutableStateFlow<List<SolveSession>>(emptyList())
        val sessions: StateFlow<List<SolveSession>> = _sessions.asStateFlow()

        /** 生成中的阶段进度（仅 Solving 时有意义）。 */
        private val _stages = MutableStateFlow<List<StageProgress>>(emptyList())
        val stages: StateFlow<List<StageProgress>> = _stages.asStateFlow()

        /** 流式增量文本（追问时用于实时渲染）。 */
        private val _streamText = MutableStateFlow("")
        val streamText: StateFlow<String> = _streamText.asStateFlow()

        /** 一次性提示（保存失败、离线存档等）。 */
        private val _toast = MutableStateFlow<String?>(null)
        val toast: StateFlow<String?> = _toast.asStateFlow()

        /** 本轮的页面级告警（框选失败、某页丢失等），合并进最终提示。 */
        private val pageWarnings = mutableListOf<String>()

        private var solveJob: Job? = null

        private var requestId: String = ""

        /** 初始化：读设置默认模式 + 刷新网络 + 载入会话列表 + 恢复最近会话。 */
        fun bootstrap() {
            refreshNetworkStatus()
            viewModelScope.launch {
                val stored = settingsRepository.get(SettingsRepository.Keys.MODE)
                if (!_modeOverridden.value) {
                    _mode.value = TeachingMode.fromWire(stored)
                }
                refreshSessions()
                // 冷启动恢复最近一次会话：用户从别的页面回来时，看到的应该是
                // 上次那一道题，而不是一片空白。这也是「切换窗口回来内容丢失」的正面修法。
                if (_sessionId.value == null) {
                    val latest = _sessions.value.firstOrNull { !it.archived }
                    if (latest != null) openSession(latest.id)
                }
            }
        }

        /** 刷新网络状态。 */
        fun refreshNetworkStatus() {
            _networkStatus.value = NetworkProbe.status(appContext)
        }

        /** 显示一次性提示。 */
        fun showToast(text: String) {
            _toast.value = text
        }

        /** 消费提示。 */
        fun dismissToast() {
            _toast.value = null
        }

        // ---------- 会话管理 ----------

        /** 重新读取会话列表。 */
        fun refreshSessions() {
            viewModelScope.launch {
                _sessions.value = runCatching { sessionRepository.all() }.getOrDefault(emptyList())
            }
        }

        /**
         * 打开一条历史会话回顾。
         *
         * 历史里的解题气泡按纯文本渲染（[SessionTurns.toMessages]）：
         * 会话文件只存 [ChatTurn]，不存完整的 [SolutionPayload] 结构，
         * 因此重开后看到的是完整文字记录，而不是可交互的分块卡片。
         * 这是刻意取舍——把结构化结果也存下来会让会话文件体积翻倍，
         * 而回顾场景需要的是「我当时问了什么、AI 当时怎么说的」。
         */
        fun openSession(id: String) {
            viewModelScope.launch {
                val session = runCatching { sessionRepository.byId(id) }.getOrNull() ?: return@launch
                registry.cancelAll()
                solveJob?.cancel()
                solveJob = null
                _sessionId.value = session.id
                _messages.value = SessionTurns.toMessages(session)
                _draft.value = SolveDraft()
                _draftPages.value = QuestionDraft.EMPTY
                _activePage.value = 0
                _phase.value = ComposerPhase.EMPTY
                _stages.value = emptyList()
                _streamText.value = ""
            }
        }

        /**
         * 开一条新对话。
         *
         * 这是学生点「已看懂，开始新题」时调用的入口：
         * 明确地把当前对话归档、清空输入区，而不是靠「再拍一张就覆盖」的隐式行为。
         */
        fun startNewSession() {
            viewModelScope.launch {
                val currentId = _sessionId.value
                if (currentId != null) {
                    runCatching { sessionRepository.setArchived(currentId, true) }
                }
                registry.cancelAll()
                solveJob?.cancel()
                solveJob = null
                _sessionId.value = null
                _messages.value = emptyList()
                _draft.value = SolveDraft()
                _draftPages.value = QuestionDraft.EMPTY
                _activePage.value = 0
                _phase.value = ComposerPhase.EMPTY
                _stages.value = emptyList()
                _streamText.value = ""
                refreshSessions()
            }
        }

        /** 删除一条会话。 */
        fun deleteSession(id: String) {
            viewModelScope.launch {
                runCatching { sessionRepository.delete(id) }
                if (_sessionId.value == id) {
                    _sessionId.value = null
                    _messages.value = emptyList()
                }
                refreshSessions()
            }
        }

        /** 归档一条会话。 */
        fun archiveSession(
            id: String,
            archived: Boolean,
        ) {
            viewModelScope.launch {
                runCatching { sessionRepository.setArchived(id, archived) }
                refreshSessions()
            }
        }

        /** 确保存在当前会话；返回会话 ID。 */
        private suspend fun ensureSession(title: String): String {
            val existing = _sessionId.value
            if (existing != null) return existing
            val created = sessionRepository.create(title = title)
            _sessionId.value = created.id
            return created.id
        }

        /** 把当前消息流整体落盘（失败只提示，不打断阅读）。 */
        private suspend fun persistSession(subjectCode: String = "") {
            val id = _sessionId.value ?: return
            val turns = _messages.value.map { SessionTurns.toTurn(it) }
            val title = SessionTurns.titleOf(_messages.value)
            val rounds = _messages.value.count { it.fromStudent }
            val session =
                SolveSession(
                    id = id,
                    title = title,
                    subtitle = if (subjectCode.isBlank()) "" else SessionTurns.subtitleOf(subjectCode, rounds),
                    turns = turns,
                    createdAtUtc = _messages.value.firstOrNull()?.createdAtUtc ?: System.currentTimeMillis(),
                    updatedAtUtc = System.currentTimeMillis(),
                )
            runCatching { sessionRepository.save(session) }
                .onFailure { showToast("对话没能保存：" + (it.message ?: "未知原因")) }
            refreshSessions()
        }

        // ---------- 输入（多页 + 框选） ----------

        /** 相册导入：作为新的一页追加。 */
        fun importFromGallery(uri: Uri) {
            viewModelScope.launch {
                appendPage(source = QuestionSourceType.GALLERY) {
                    imagePreprocessor.preprocessFromUri(uri)
                }
            }
        }

        /**
         * 拍照产物导入（CameraX 已落到 cacheDir 的临时文件）。
         *
         * 临时文件必须等预处理读完之后才删，否则会出现「预处理还在读、文件已被删」的竞态，
         * 因此清理放在 finally 里，与读取严格同序。
         */
        fun importFromCameraFile(path: String) {
            viewModelScope.launch {
                try {
                    appendPage(source = QuestionSourceType.CAMERA) {
                        imagePreprocessor.preprocessFromFile(path)
                    }
                } finally {
                    withContext(Dispatchers.IO) { runCatching { java.io.File(path).delete() } }
                }
            }
        }

        /**
         * 追加一页：预处理 + OCR，但**不覆盖已有页**，也不直接发送。
         *
         * 与旧实现的差别：
         * 1. 旧实现每次导入都会把整个题目状态替换掉，拍第二页时第一页就丢了；
         * 2. 旧实现在这里直接进 Ready 甚至发送，跳过了「写思路」这一步。
         */
        private suspend fun appendPage(
            source: QuestionSourceType,
            preprocess: suspend () -> PreprocessResult,
        ) {
            // 记录本页来源，落库时写进 question.sourceType（相册/拍照/手输可区分）
            _lastSourceType.value = source
            _processing.value = true
            try {
                when (val result = preprocess()) {
                    is PreprocessResult.Failure -> {
                        showToast(result.error.userMessage)
                    }
                    is PreprocessResult.Success -> {
                        val page =
                            QuestionPage(
                                imagePath = result.image.imagePath,
                                sha256 = result.image.sha256,
                                thumbnailPath = result.image.thumbnailPath,
                                role = if (_draftPages.value.pageCount == 0) PageRole.MAIN else PageRole.CONTINUATION,
                            )
                        _draftPages.value = _draftPages.value.add(page)
                        _activePage.value = _draftPages.value.pageCount - 1
                        if (_phase.value == ComposerPhase.EMPTY) {
                            _phase.value = ComposerPhase.READY
                        }
                    }
                }
            } finally {
                _processing.value = false
            }
        }

        /** 设置某页的框选区域；传 null 表示改用整图。 */
        fun setCrop(
            index: Int,
            rect: NormalizedRect?,
        ) {
            val draft = _draftPages.value
            val page = draft.pages.getOrNull(index) ?: return
            _draftPages.value = draft.replaceAt(index, page.copy(cropRect = rect))
        }

        /** 删除某页。 */
        fun removePage(index: Int) {
            val next = _draftPages.value.removeAt(index)
            _draftPages.value = next
            _activePage.value = _activePage.value.coerceIn(0, (next.pageCount - 1).coerceAtLeast(0))
            if (next.isEmpty && _phase.value == ComposerPhase.READY) {
                _phase.value = ComposerPhase.EMPTY
            }
        }

        /** 移动页序。 */
        fun movePage(
            from: Int,
            to: Int,
        ) {
            _draftPages.value = _draftPages.value.move(from, to)
            _activePage.value = to.coerceIn(0, (_draftPages.value.pageCount - 1).coerceAtLeast(0))
        }

        /** 选中某页进行预览。 */
        fun selectPage(index: Int) {
            _activePage.value = index.coerceIn(0, (_draftPages.value.pageCount - 1).coerceAtLeast(0))
        }

        /** 清空所有页。 */
        fun clearPages() {
            _draftPages.value = QuestionDraft.EMPTY
            _activePage.value = 0
            if (_phase.value == ComposerPhase.READY) _phase.value = ComposerPhase.EMPTY
        }

        /**
         * 存为离线记题：只落本机，不联网、不调用模型。
         *
         * 语义边界：离线记题的目的是「先拍下来别丢」，因此只存图片路径与用户手写的思路；
         * 真正的识别与解题等联网后再触发。这里刻意不写 mistake_record，
         * 因为错题记录必须带错因，而错因只能来自模型分析。
         */
        fun saveOffline() {
            val draft = _draftPages.value
            if (draft.isEmpty && _draft.value.stem.isBlank()) return
            viewModelScope.launch {
                runCatching {
                    offlineRepository.save(
                        stemText = _draft.value.stem.trim(),
                        studentProcess = _draft.value.studentProcess.trim(),
                        imagePaths = draft.pages.map { it.imagePath },
                    )
                }.onSuccess {
                    showToast("已存到「离线记题」，联网后再解")
                }.onFailure { failure ->
                    showToast("离线保存失败：" + (failure.message ?: "未知原因"))
                }
            }
        }

        /** 由多页草稿 + 输入框文字组装 [PreparedQuestion]。 */
        private fun buildQuestionFromDraft(): PreparedQuestion {
            val draft = _draftPages.value
            return PreparedQuestion(
                stemText = _draft.value.stem.trim(),
                studentRawText = _draft.value.studentProcess.trim(),
                pages =
                    draft.pages.map { page ->
                        PreparedPage(
                            imagePath = page.imagePath,
                            sha256 = page.sha256,
                            cropRect = page.cropRect,
                            thumbnailPath = page.thumbnailPath,
                        )
                    },
                sourceLabel = if (draft.isEmpty) "手输" else "拍照",
            )
        }

        /** 编辑题干草稿。 */
        fun updateStem(text: String) {
            _draft.update { it.copy(stem = text) }
        }

        /**
         * 编辑输入框文字。
         *
         * 注意：这里**只**更新草稿，不再同步进任何「题目状态」。
         * 旧实现把输入框文字双向绑定到 SolveUiState.Ready.question.studentRawText，
         * 导致发送后一清空输入框就把已发送的内容也一起抹掉了。
         */
        fun updateStudentProcess(text: String) {
            _draft.update { it.copy(studentProcess = text) }
        }

        /**
         * 对当前草稿做一次本地 OCR，把文本填到「题干」里供路由判断。
         *
         * 为什么 OCR 不是必需品：
         * 识别质量差（手写、反光、艺术字）时纯文本档会答错，
         * 交给视觉模型看原图反而更准。因此 OCR 失败/低置信度只降级为「走视觉档」，
         * 不能让用户卡在这一步无法解题。
         */
        fun runOcrIfNeeded() {
            val draft = _draftPages.value
            if (draft.isEmpty) return
            if (_draft.value.stem.isNotBlank()) return
            viewModelScope.launch {
                val first = draft.pages.firstOrNull() ?: return@launch
                when (val ocr = ocrRecognizer.recognizeFile(first.imagePath)) {
                    is OcrOutcome.Success -> _draft.update { it.copy(stem = ocr.result.text) }
                    is OcrOutcome.Failure -> Unit
                }
            }
        }

        // ---------- 讲解模式 ----------

        /**
         * 切换讲解模式（会话内临时覆盖，不写回设置表）。
         *
         * 从 DIRECT 切回 GUIDED / SOCRATIC 时重置「已看全解」标记，防止答案残留泄漏。
         */
        fun setMode(newMode: TeachingMode) {
            if (_mode.value == newMode) return
            _mode.value = newMode
            _modeOverridden.value = true
        }

        // ---------- 发送 / 追问 / 停止 / 重试 ----------

        /**
         * 发送：判断走「新题解题」还是「追问」。
         *
         * 判定规则（简单、可预测，用户能自己总结出来）：
         * - 当前会话里**已经有解答**（存在解题气泡）→ 走追问，带上上下文；
         * - 否则 → 走四阶段解题。
         *
         * 这条规则直接解决了旧版的「发送后直接重新输出一套全新解答」问题。
         */
        fun send() {
            if (solveJob?.isActive == true) return
            val text = _draft.value.studentProcess.trim()
            val hasPages = _draftPages.value.pageCount > 0
            if (text.isEmpty() && !hasPages) {
                showToast("先拍一张题，或者写下你的问题")
                return
            }
            // 已经解过题：走追问
            if (hasSolutionInConversation() && hasPages.not() && text.isNotEmpty()) {
                sendFollowUp(text)
                return
            }
            val question = buildQuestionFromDraft()
            if (!question.canSolve) {
                showToast("题目内容为空，先拍一张照片或写下题干")
                return
            }
            solveJob = viewModelScope.launch { runSolve(question) }
        }

        /** 当前会话里是否已经有解答。 */
        private fun hasSolutionInConversation(): Boolean =
            _messages.value.any { it.solution != null }

        /** 取最近一次解答的上下文。 */
        private fun latestSolution(): SolutionPayload? =
            _messages.value.lastOrNull { it.solution != null }?.solution

        /**
         * 追问。
         *
         * 上下文 = 最近一次解答 + 这一轮之前的所有对话。
         * 只带最近一次解答而不是全部，是为了让模型始终围绕「当前这道题」回答。
         */
        private fun sendFollowUp(text: String) {
            val solution = latestSolution()
            val studentMessage =
                ConversationMessage(
                    id = UUID.randomUUID().toString(),
                    fromStudent = true,
                    text = text,
                    createdAtUtc = System.currentTimeMillis(),
                )
            val replyId = UUID.randomUUID().toString()
            val replyMessage =
                ConversationMessage(
                    id = replyId,
                    fromStudent = false,
                    text = "",
                    streaming = true,
                    createdAtUtc = System.currentTimeMillis(),
                )
            _messages.update { it + studentMessage + replyMessage }
            // 立刻清空输入框：文字已经作为消息进入对话流，留着会让人以为没发出去
            _draft.update { it.copy(studentProcess = "") }
            _phase.value = ComposerPhase.SOLVING

            requestId = UUID.randomUUID().toString()
            solveJob =
                viewModelScope.launch {
                    val credentials = secretStore.read()
                    if (!credentials.canCall) {
                        updateMessage(replyId) {
                            it.copy(
                                streaming = false,
                                errorText = NOT_CONFIGURED_MESSAGE,
                                retryable = false,
                            )
                        }
                        _phase.value = if (_draftPages.value.pageCount > 0) ComposerPhase.READY else ComposerPhase.EMPTY
                        return@launch
                    }
                    val context = solution?.let(::contextOfSolution).orEmpty()
                    val history =
                        _messages.value
                            .filter { it.id != replyId && it.streaming.not() }
                            .mapNotNull { message ->
                                val body = message.text.ifBlank { message.solution?.stem.orEmpty() }
                                if (body.isBlank()) {
                                    null
                                } else {
                                    FollowUpTurn(text = body, fromStudent = message.fromStudent)
                                }
                            }
                    val buffer = StringBuilder()
                    try {
                        followUpGateway
                            .ask(
                                credentials = credentials,
                                context = context,
                                history = history,
                                registry = registry,
                                requestId = requestId,
                            ).collect { event ->
                                when (event) {
                                    is FollowUpEvent.Delta -> {
                                        buffer.append(event.text)
                                        _streamText.value = buffer.toString()
                                        updateMessage(replyId) { it.copy(text = buffer.toString()) }
                                    }
                                    is FollowUpEvent.Completed -> {
                                        updateMessage(replyId) {
                                            it.copy(text = event.text, streaming = false)
                                        }
                                    }
                                    is FollowUpEvent.Failed -> {
                                        updateMessage(replyId) {
                                            it.copy(
                                                streaming = false,
                                                errorText = event.error.userMessage,
                                                retryable = event.error.isRetryable,
                                            )
                                        }
                                    }
                                }
                            }
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (error: LlmException) {
                        updateMessage(replyId) {
                            it.copy(streaming = false, errorText = error.error.userMessage, retryable = error.error.isRetryable)
                        }
                    } catch (throwable: Throwable) {
                        updateMessage(replyId) {
                            it.copy(
                                streaming = false,
                                errorText = "追问时出现未预期的问题：" + (throwable.message ?: throwable.javaClass.simpleName),
                                retryable = true,
                            )
                        }
                    } finally {
                        _streamText.value = ""
                        _phase.value = if (_draftPages.value.pageCount > 0) ComposerPhase.READY else ComposerPhase.EMPTY
                        persistSession(solution?.subjectCode.orEmpty())
                    }
                }
        }

        /** 把解答压成追问上下文。 */
        private fun contextOfSolution(solution: SolutionPayload): String =
            SolveContextBuilder.build(
                stem = solution.stem,
                known = solution.known,
                ask = solution.ask,
                steps = solution.steps.map { it.content },
                finalAnswer = solution.finalAnswer,
                method = solution.method,
                errorTypeLabel = solution.firstError?.typeLabel.orEmpty(),
                errorExplain = solution.firstError?.explain.orEmpty(),
                correctThinking = solution.firstError?.correctThinking.orEmpty(),
            )

        /** 停止：中断 socket，保留已收到的增量。 */
        fun stop() {
            registry.cancelAll()
            solveJob?.cancel()
            solveJob = null
            _messages.update { list ->
                list.map { message ->
                    if (message.streaming) {
                        message.copy(streaming = false, errorText = "已停止本次生成")
                    } else {
                        message
                    }
                }
            }
            _streamText.value = ""
            _phase.value = if (_draftPages.value.pageCount > 0) ComposerPhase.READY else ComposerPhase.EMPTY
        }

        /** 重试：对失败的那条 AI 回复重新发起（新 requestId，避免幂等去重拦截）。 */
        fun retry() {
            val lastFailed = _messages.value.lastOrNull { it.errorText != null } ?: return
            // 追问失败：重发最后一句学生消息
            val studentBefore =
                _messages.value
                    .takeWhile { it.id != lastFailed.id }
                    .lastOrNull { it.fromStudent }
            registry.reset()
            if (hasSolutionInConversation() && studentBefore != null) {
                // 删掉失败的回复，让 sendFollowUp 重新生成
                _messages.update { list -> list.filterNot { it.id == lastFailed.id } }
                _messages.update { list -> list.filterNot { it.id == studentBefore.id } }
                sendFollowUp(studentBefore.text)
                return
            }
            val question = buildQuestionFromDraft()
            if (!question.canSolve) {
                showToast("题目内容为空，先拍一张照片或写下题干")
                return
            }
            _messages.update { list -> list.filterNot { it.id == lastFailed.id } }
            solveJob = viewModelScope.launch { runSolve(question) }
        }

        /** 重新提问：清空输入区，但保留对话历史（历史是用户的资产，不能随手丢）。 */
        fun askAgain() {
            registry.cancelAll()
            solveJob?.cancel()
            solveJob = null
            registry.reset()
            _draft.value = SolveDraft()
            _draftPages.value = QuestionDraft.EMPTY
            _activePage.value = 0
            _phase.value = ComposerPhase.EMPTY
            _streamText.value = ""
        }

        /** 清空全部（含输入框与会话）。 */
        fun reset() {
            askAgain()
            viewModelScope.launch { startNewSession() }
        }

        /** 引导模式：解锁下一级提示，并通过 domain 的 HintLog + Repository 写 hint_log。 */
        fun unlockNextHint(messageId: String) {
            updateSolution(messageId) { payload ->
                val next = payload.unlockedHintLevel + 1
                if (next > payload.hints.size || next > HintBuilder.MAX_LEVEL) {
                    payload
                } else {
                    recordHint(payload.mistakeId, next)
                    payload.copy(unlockedHintLevel = next)
                }
            }
        }

        /** 引导模式：「我已想通，看全解」。 */
        fun revealFullSolution(messageId: String) {
            updateSolution(messageId) { it.copy(fullSolutionRevealed = true) }
        }

        // ---------- 主链路 ----------

        private suspend fun runSolve(question: PreparedQuestion) {
            val studentText = question.studentRawText.trim()
            val studentMessage =
                ConversationMessage(
                    id = UUID.randomUUID().toString(),
                    fromStudent = true,
                    text = studentText,
                    pages = question.pages.map { it.imagePath },
                    createdAtUtc = System.currentTimeMillis(),
                )
            val replyId = UUID.randomUUID().toString()
            val replyMessage =
                ConversationMessage(
                    id = replyId,
                    fromStudent = false,
                    text = "",
                    streaming = true,
                    createdAtUtc = System.currentTimeMillis(),
                )
            _messages.update { it + studentMessage + replyMessage }
            _draft.update { it.copy(studentProcess = "") }
            _phase.value = ComposerPhase.SOLVING
            _stages.value = StageProgressMapper.initial()
            pageWarnings.clear()
            _lastImagePath.value = question.imagePath
            _lastImageSha256.value = question.imageSha256

            requestId = UUID.randomUUID().toString()
            val credentials = secretStore.read()
            if (!credentials.canCall) {
                updateMessage(replyId) {
                    it.copy(streaming = false, errorText = NOT_CONFIGURED_MESSAGE, retryable = false)
                }
                _phase.value = ComposerPhase.READY
                return
            }
            // 候选清单在这里只按题干文本粗筛（此时还不知道科目）；
            // 真正的「按科目收敛」发生在 [AiGateway] 阶段 A 识别出 subject 之后。
            val candidates =
                KpointCandidates.pick(
                    points = knowledgeCatalog.allPoints(),
                    haystack = question.stemText,
                )
            // 逐页裁剪并编码：框选区域在**上传前**裁掉，
            // 而不是让模型自己看整页（协议没有标准坐标字段，且裁剪更省 token、更准）。
            val encodedPages = encodePages(question)
            if (question.pages.isNotEmpty() && encodedPages.isEmpty()) {
                updateMessage(replyId) {
                    it.copy(
                        streaming = false,
                        errorText = "图片读取失败，题目没有上传。请重新拍一次，或改用「相册」导入。",
                        retryable = true,
                    )
                }
                _phase.value = ComposerPhase.READY
                return
            }
            val request =
                SolveRequest(
                    ocr =
                        com.totop.app.ml.OcrSnapshot(
                            text = question.stemText,
                            avgConfidence = question.avgConfidence,
                            hasFigure = question.hasFigure,
                            isHandwritten = question.isHandwritten,
                        ),
                    image = encodedPages.firstOrNull(),
                    extraImages = encodedPages.drop(1),
                    studentRawText = question.studentRawText,
                    candidateKpoints = candidates,
                    requestId = requestId,
                )
            try {
                gateway.solve(credentials = credentials, request = request, registry = registry)
                    .collect { event -> handleEvent(event, replyId) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: LlmException) {
                updateMessage(replyId) {
                    it.copy(
                        streaming = false,
                        errorText = error.error.userMessage,
                        retryable = error.error.isRetryable,
                    )
                }
            } catch (throwable: Throwable) {
                updateMessage(replyId) {
                    it.copy(
                        streaming = false,
                        errorText = "解题过程中出现未预期的问题：" + (throwable.message ?: throwable.javaClass.simpleName),
                        retryable = true,
                    )
                }
            } finally {
                _phase.value = if (_draftPages.value.pageCount > 0) ComposerPhase.READY else ComposerPhase.EMPTY
                _stages.value = emptyList()
            }
        }

        /**
         * 把各页按框选裁剪后编码为上传形态。
         *
         * 单页失败不影响其余页：题目跨页时，某一页读失败还能用剩下的页作答，
         * 只是会少看一部分内容——这种情况必须通过 warnings 告知用户，不能静默。
         */
        private suspend fun encodePages(question: PreparedQuestion): List<EncodedImage> {
            if (question.pages.isEmpty()) return emptyList()
            val result = ArrayList<EncodedImage>(question.pages.size)
            val failures = mutableListOf<Int>()
            question.pages.forEachIndexed { index, page ->
                val encoded = buildEncodedImage(page)
                if (encoded == null) failures.add(index + 1) else result.add(encoded)
            }
            if (failures.isNotEmpty()) {
                pageWarnings.add(
                    "第 " + failures.joinToString("、") + " 页读取失败，本次只上传了其余 " + result.size + " 页",
                )
            }
            return result
        }

        /** 按框选裁剪并编码一页。 */
        private suspend fun buildEncodedImage(page: PreparedPage): EncodedImage? =
            withContext(Dispatchers.IO) {
                val cropped =
                    when (val crop = imageCropper.cropToJpegBytes(page.imagePath, page.cropRect)) {
                        is ImageCropResult.Success -> crop.bytes
                        is ImageCropResult.Failure -> {
                            // 裁剪失败不能静默吞掉：退回原图，但把原因记下来
                            pageWarnings.add(crop.reason + "（已改用整页上传）")
                            runCatching { java.io.File(page.imagePath).readBytes() }.getOrNull()
                        }
                    } ?: return@withContext null
                runCatching {
                    EncodedImage(
                        jpegBytes = cropped,
                        base64 = android.util.Base64.encodeToString(cropped, android.util.Base64.NO_WRAP),
                        width = 0,
                        height = 0,
                        sha256 = ImageMath.sha256Hex(cropped),
                        sourceWidth = 0,
                        sourceHeight = 0,
                        rotationDegrees = 0,
                    )
                }.getOrNull()
            }

        private fun handleEvent(
            event: SolveEvent,
            replyId: String,
        ) {
            when (event) {
                is SolveEvent.Routed -> Unit
                is SolveEvent.StageStarted ->
                    _stages.value =
                        StageProgressMapper.start(_stages.value.takeIf { it.isNotEmpty() } ?: StageProgressMapper.initial(), event.stage, event.cached)
                is SolveEvent.FirstToken ->
                    _stages.value = StageProgressMapper.firstToken(_stages.value, event.stage, event.elapsedMs)
                is SolveEvent.Delta -> {
                    _streamText.value = _streamText.value + event.text
                    updateMessage(replyId) { it.copy(text = _streamText.value) }
                }
                is SolveEvent.StageCompleted ->
                    _stages.value = StageProgressMapper.complete(_stages.value, event.stage, event.summary)
                is SolveEvent.Completed -> onCompleted(event, replyId)
                is SolveEvent.Failed -> {
                    updateMessage(replyId) {
                        it.copy(
                            streaming = false,
                            errorText = event.error.userMessage,
                            failedStage = event.stage,
                            retryable = event.error.isRetryable,
                        )
                    }
                }
            }
        }

        private fun onCompleted(
            event: SolveEvent.Completed,
            replyId: String,
        ) {
            val content = event.content
            val payload = SolutionMapper.fromContent(content, _mode.value)
            val warnings = ArrayList<String>(event.warnings)
            warnings.addAll(pageWarnings)
            pageWarnings.clear()
            // 把「步骤 B 完成」等信息落成气泡正文，方便会话持久化与历史回顾
            val summaryText =
                buildString {
                    if (payload.stem.isNotBlank()) append(payload.stem).append('\n')
                    payload.steps.forEach { step ->
                        append(step.idx).append(". ").append(step.content).append('\n')
                        if (step.why.isNotBlank()) append("   为什么：").append(step.why).append('\n')
                    }
                    if (payload.finalAnswer.isNotBlank()) append("答案：").append(payload.finalAnswer)
                    payload.firstError?.let { error ->
                        append("\n\n第 ").append(error.stepIdx).append(" 步 · ").append(error.typeLabel)
                        append("\n").append(error.explain)
                        if (error.correctThinking.isNotBlank()) {
                            append("\n下次这样想：").append(error.correctThinking)
                        }
                    }
                }.trim()
            updateMessage(replyId) {
                it.copy(
                    text = summaryText,
                    streaming = false,
                    solution = payload,
                    errorText = warnings.takeIf { list -> list.isNotEmpty() }?.joinToString("；"),
                )
            }
            _streamText.value = ""
            viewModelScope.launch {
                persistAndLink(content, replyId)
                persistSession(content.question.subject)
            }
        }

        /** 更新某条消息。 */
        private fun updateMessage(
            id: String,
            block: (ConversationMessage) -> ConversationMessage,
        ) {
            _messages.update { list -> list.map { if (it.id == id) block(it) else it } }
        }

        /** 更新某条消息里的解题快照。 */
        private fun updateSolution(
            id: String,
            block: (SolutionPayload) -> SolutionPayload,
        ) {
            updateMessage(id) { message ->
                val payload = message.solution ?: return@updateMessage message
                message.copy(solution = block(payload))
            }
        }

        // ---------- 落库 ----------

        /**
         * 写入题目与错题本，并在写完后立刻重算掌握度。
         *
         * 旧实现有两处致命问题：
         * 1. `firstError == null` 时整体 return——没定位到错因就什么都不存，
         *    于是「学生没写思路」的题永远进不了错题本；
         * 2. 从不重算掌握度——错题本有数据了，但「还需要多练的知识点」全靠
         *    mastery 表，而 mastery 表只在每日 Worker 里更新，用户当天看不到任何变化。
         *
         * 这里的修复：
         * - 题目**总是**保存；
         * - 有错因就写完整错题记录；没定位到错因也写一条「待复习」记录
         *   （错因归到固定七类里的兜底项，并在解释里注明原因），保证错题本可见；
         * - 写入后立刻重算相关知识点掌握度。
         */
        @Suppress("TooGenericExceptionCaught")
        private suspend fun persistAndLink(
            content: AiSolvedContent,
            replyId: String,
        ) {
            try {
                val saved =
                    questionRepository.saveDeduplicated(
                        Question(
                            id = content.question.id,
                            subject = Subjects.normalize(content.question.subject),
                            grade = content.question.grade,
                            stemText = content.question.stemText,
                            known = content.question.known,
                            ask = content.question.ask,
                            options = content.question.options,
                            hasFigure = content.question.hasFigure,
                            // 多页题目只把第 1 页作为「主图」写入 question 表，
                            // 其余页随本次结果一起丢弃：错题本展示的是题干与错因，
                            // 多页原图不属于错题的必需属性，避免为此新增表与迁移。
                            imagePath = _lastImagePath.value,
                            // 必须写入 sha256，否则 saveDeduplicated 的按图去重永远失效，
                            // 同一张图重解一次就会在错题本里多出一条重复记录。
                            imageSha256 = _lastImageSha256.value,
                            sourceType = _lastSourceType.value,
                            createdAtUtc = content.question.createdAtUtc,
                        ),
                    )
                val firstError = content.firstError
                val record =
                    MistakeRecord(
                        id = UUID.randomUUID().toString(),
                        questionId = saved.question.id,
                        studentSteps = content.studentSteps,
                        firstErrorStepIdx = firstError?.stepIdx,
                        // 没有错因时用兜底类，但仍然入库：这样才能出现在错题本里
                        errorType = firstError?.type ?: ErrorType.FALLBACK,
                        errorExplain =
                            firstError?.explain
                                ?: "本次没有你的解题过程，暂时无法定位具体错在哪一步。补上思路再解一次，就能定位到第一个出错的步骤。",
                        correctSolution = content.standardSolution,
                        finalAnswer = content.standardSolution.finalAnswer,
                        mode = _mode.value,
                        createdAtUtc = System.currentTimeMillis(),
                        recurrenceIndex = 1,
                    )
                val assignments =
                    content.kpointAssignments
                        .filter { it.hasControlledId }
                        .map { KpointAssignment(kpointId = it.kpointId, name = it.name, confidence = it.confidence, usage = it.usage) }
                val stored =
                    mistakeRepository.save(
                        record = record,
                        assignments = assignments,
                        recomputeRecurrence = true,
                    )
                // 落库成功后立刻重算掌握度：错题写进去了，薄弱知识点必须同步变化
                runCatching {
                    masteryRecorder.recompute(
                        kpointIds = content.kpointAssignments.map { it.kpointId },
                    )
                }
                updateMessage(replyId) { message ->
                    val current = message.solution ?: return@updateMessage message
                    message.copy(solution = current.copy(mistakeId = stored.id))
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                updateMessage(replyId) { message ->
                    val current = message.solution ?: return@updateMessage message
                    message.copy(
                        solution = current.copy(persistMessage = "本次结果未能写入错题本：" + (throwable.message ?: "未知原因")),
                    )
                }
            }
        }

        /** 提示记录（经 domain 的 HintLog 与 Repository 接口写 hint_log）。 */
        private fun recordHint(
            mistakeId: String?,
            level: Int,
        ) {
            if (mistakeId.isNullOrBlank()) return
            viewModelScope.launch {
                runCatching {
                    mistakeRepository.addHint(
                        HintLog(
                            id = UUID.randomUUID().toString(),
                            mistakeId = mistakeId,
                            level = level,
                            createdAtUtc = System.currentTimeMillis(),
                        ),
                    )
                }
            }
        }

        // ---------- 派生数据（供 UI 直接读取） ----------

        /**
         * 三态对齐后的学生步骤。
         *
         * 学生过程原文不完整保存在 [SolutionPayload] 里（只有错因定位结果），
         * 因此这里把定位到的那一步还原成「错误」行，其余步骤由错因说明承载。
         * 保留这个方法是为了让旧调用方与单测继续可用。
         */
        fun alignedStudentSteps(payload: SolutionPayload): List<AlignedStudentStep> =
            payload.firstError?.let { error ->
                listOf(
                    AlignedStudentStep(
                        idx = error.stepIdx,
                        content = if (error.explain.isNotBlank()) error.explain else "第 " + error.stepIdx + " 步",
                        status = StudentStepStatus.WRONG,
                        isFirstError = true,
                        errorLabel = error.typeLabel,
                    ),
                )
            }.orEmpty()

        /** 七类错因的选中状态。 */
        fun errorTypeOptions(payload: SolutionPayload): List<ErrorTypeOption> {
            val selected = ErrorType.ofLabel(payload.firstError?.typeLabel.orEmpty()) ?: ErrorType.FALLBACK
            return ErrorType.FIXED.map { type -> ErrorTypeOption(type = type, selected = type == selected) }
        }

        /** 按展示策略返回可见的最终答案；不可见时为 null。 */
        fun visibleFinalAnswer(
            payload: SolutionPayload,
            currentMode: TeachingMode,
        ): String? {
            if (!GuidePolicy.showsFinalAnswer(currentMode, payload.fullSolutionRevealed)) return null
            return payload.finalAnswer.ifBlank { null }
        }

        // ---------- 生命周期 ----------

        override fun onCleared() {
            // 页面销毁：中断所有在途 Call，避免继续计费
            registry.cancelAll()
            solveJob?.cancel()
            solveJob = null
            super.onCleared()
        }

        private companion object {
            /** 未配置模型时的统一提示。 */
            const val NOT_CONFIGURED_MESSAGE: String =
                "尚未配置模型（Base URL / API Key / 模型名）。请到设置页填写后再试：" +
                    "本应用不内置任何 Key，全部由你自己填写，也不经过开发者服务器。"
        }
    }

/** 输入草稿。 */
data class SolveDraft(
    val stem: String = "",
    val studentProcess: String = "",
)

/** 三态对齐视图的展示标签。 */
internal fun AlignedStudentStep.viewLabel(): String =
    when (status) {
        StudentStepStatus.CORRECT -> "正确"
        StudentStepStatus.WRONG -> "错误"
        StudentStepStatus.MISSING -> "缺失"
    }
