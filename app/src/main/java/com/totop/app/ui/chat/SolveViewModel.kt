package com.totop.app.ui.chat

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.totop.app.data.prefs.SecretStore
import com.totop.app.data.remote.AiGateway
import com.totop.app.data.remote.FollowUpCorrection
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
        private val lastSourceType = MutableStateFlow(QuestionSourceType.CAMERA)

        /** 本轮主图的路径与哈希，落库时写进 question。 */
        private val lastImagePath = MutableStateFlow<String?>(null)

        private val lastImageSha256 = MutableStateFlow<String?>(null)

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

        /**
         * 最近一次真正发出去的题目。
         *
         * 为什么需要留存：发送后输入区会立刻清空（图片与文字都进了消息流），
         * 但「重试」还需要那份图片与题干才能重发。若继续从草稿读，
         * 重试就会拿到一份已经被清空的输入，直接失败。
         */
        private var lastSentQuestion: PreparedQuestion? = null

        /**
         * 最近一次追问的输入（含补拍页）。
         *
         * 重试时要原样重发：补拍的照片此时已经从输入区清空，
         * 若只按消息正文重发，重试就退化成一次「没有图片的追问」，
         * 模型看不到学生刚补的那张，答复自然还是原来那份。
         */
        private var lastFollowUp: FollowUpRequest? = null

        private data class FollowUpRequest(
            val text: String,
            val pages: List<PreparedPage>,
        )

        private var solveJob: Job? = null

        private var requestId: String = ""

        /**
         * 初始化：读设置默认模式 + 刷新网络 + 载入会话列表 + 恢复最近会话。
         *
         * @param resumeLastSession 是否恢复最近一次会话。
         *   陪伴作业的「问一下 AI」进来时传 false——那种场景下用户的意图是问一道新题，
         *   恢复旧会话会让他看到上一道题的解答，误以为这次提问没生效。
         */
        fun bootstrap(resumeLastSession: Boolean = true) {
            refreshNetworkStatus()
            viewModelScope.launch {
                val stored = settingsRepository.get(SettingsRepository.Keys.MODE)
                if (!_modeOverridden.value) {
                    _mode.value = TeachingMode.fromWire(stored)
                }
                refreshSessions()
                if (!resumeLastSession) {
                    // 不恢复旧会话，同时保证输入区是干净的
                    startNewSession()
                    return@launch
                }
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
         * 历史里的解题气泡靠 [ChatTurn.solutionJson] 还原成结构化卡片，
         * 因此重开后仍有「这题考什么 / 错在哪 / 知识点」，也能直接接着追问；
         * 老版本写下的会话没有这个快照，此时退回纯文本渲染（内容仍可读）。
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
                lastSentQuestion = null
                lastFollowUp = null
            }
        }

        /**
         * 「已掌握」：标记这道题，然后开下一题。
         *
         * 只写题目级的 selfMasteredAt，不动 mastery 表——掌握度由复习自评、错题频次、
         * 覆盖度推导，学生自评直接改分数会让推荐路径失真（口径见 docs/需求定稿-0.5.0.md）。
         * 标记失败不拦下一步：题目可能已被删除，提示一句继续做题比卡住体验更好。
         */
        fun markMasteredAndNext() {
            val questionId = latestSolution()?.questionId
            if (questionId != null) {
                viewModelScope.launch {
                    runCatching {
                        questionRepository.markMastered(questionId, System.currentTimeMillis())
                    }.onFailure { failure ->
                        showToast("标记失败：" + (failure.message ?: "未知原因"))
                    }
                }
            }
            startNewSession()
            showToast(com.totop.app.util.CopyDeck.encouragement(appContext))
        }

        /**
         * 开一条新对话。
         *
         * 这是学生点「我已理解」或顶部「+」时调用的入口：
         * 明确地把当前对话归档、清空输入区，而不是靠「再拍一张就覆盖」的隐式行为。
         *
         * 状态清理**同步**完成，只把「归档落盘」放进协程：
         * 旧实现整段都在 coroutine 里，而归档是一次挂起调用；它挂起期间用户若已经
         * 发出了新问题，恢复后紧接着的 `registry.cancelAll()` + `solveJob?.cancel()`
         * 会把刚发出的请求一起取消——这正是「新建对话后提问，AI 没有任何输出」的另一种成因。
         */
        fun startNewSession() {
            val currentId = _sessionId.value
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
            lastSentQuestion = null
            lastFollowUp = null
            viewModelScope.launch {
                if (currentId != null) {
                    runCatching { sessionRepository.setArchived(currentId, true) }
                }
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

        /**
         * 把当前消息流整体落盘（失败只提示，不打断阅读）。
         *
         * 会话 ID 在这里**按需生成**。
         *
         * 这是「历史对话列表永远是空的」的根因修复：旧实现只读取 [_sessionId]，
         * 全类没有任何一处给它赋值，于是每次落盘都停在第一行 return，
         * 会话文件一个都没写出来，侧边栏自然什么都不显示；
         * 用户新建对话后同样不会多出任何一条记录。
         */
        private suspend fun persistSession(subjectCode: String = "") {
            if (_messages.value.isEmpty()) return
            val id =
                _sessionId.value
                    ?: runCatching { sessionRepository.create() }
                        .getOrNull()
                        ?.also { created -> _sessionId.value = created.id }
                        ?.id
                    ?: return
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
            lastSourceType.value = source
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
            val text = _draft.value.studentProcess.trim()
            // 有图时，输入框里的文字是「解题思路」；没有图时，它只能是题目本身。
            //
            // 这是取消 OCR 之后必须补上的一条口径：无图请求没有任何视觉输入，
            // 若仍把文字当解题思路，模型拿到的题目就是空的，必然报「阶段 A 未识别出题干」。
            val hasPages = draft.pages.isNotEmpty()
            return PreparedQuestion(
                stemText = if (hasPages) _draft.value.stem.trim() else text,
                studentRawText = if (hasPages) text else "",
                pages =
                    draft.pages.map { page ->
                        PreparedPage(
                            imagePath = page.imagePath,
                            sha256 = page.sha256,
                            cropRect = page.cropRect,
                            thumbnailPath = page.thumbnailPath,
                        )
                    },
                sourceLabel = if (hasPages) "拍照" else "手输",
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

        // ---------- 讲解模式 ----------

        /**
         * 切换讲解模式（会话内临时覆盖，不写回设置表）。
         *
         * 从 DIRECT 切回 GUIDED 时重置「已看全解」标记，防止答案残留泄漏。
         */
        fun setMode(newMode: TeachingMode) {
            if (_mode.value == newMode) return
            _mode.value = newMode
            _modeOverridden.value = true
        }

        // ---------- 发送 / 追问 / 停止 / 重试 ----------

        /**
         * 发送：判断走「这道题的首次解答」还是「围绕这道题的继续对话」。
         *
         * 判定规则只有一条，用户可以自己总结出来：
         * **当前会话里已经有这道题的解答 → 之后的一切输入都属于这道题。**
         *
         * 为什么不再用「这一轮有没有新图」当分界：
         * 一次对话只对应一道题。用户在 AI 没看清题目时补拍一张，是补充当前题，
         * 不是换题；把它判成新题会让模型拿到一份被当成新题的截图，
         * 前半段的解答与错因全部作废，学生看到的是「我又得从头讲一遍」。
         * 换题的唯一入口是「新建对话」（或「我已理解」）。
         */
        fun send() {
            if (solveJob?.isActive == true) return
            val text = _draft.value.studentProcess.trim()
            val draftPages = _draftPages.value.pages
            if (text.isEmpty() && draftPages.isEmpty()) {
                showToast("先拍一张题，或者写下你的问题")
                return
            }
            if (hasSolutionInConversation()) {
                // 已解答过：补拍的照片作为「补充材料」一起发给模型，
                // 由视觉模型重新看清题目细节，而不是当成一道新题。
                sendFollowUp(
                    text = text,
                    supplementPages =
                        draftPages.map { page ->
                            PreparedPage(
                                imagePath = page.imagePath,
                                sha256 = page.sha256,
                                cropRect = page.cropRect,
                                thumbnailPath = page.thumbnailPath,
                            )
                        },
                )
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
        private fun hasSolutionInConversation(): Boolean = _messages.value.any { it.solution != null }

        /** 取最近一次解答的上下文。 */
        private fun latestSolution(): SolutionPayload? = _messages.value.lastOrNull { it.solution != null }?.solution

        /**
         * 追问 / 补拍。
         *
         * 上下文 = 最近一次解答 + 这一轮之前的所有对话；若这一轮带了补拍照片，
         * 图片一并交给**视觉模型**，并明确告知「这是对当前题的补充，不是新题」。
         *
         * 只带最近一次解答而不是全部，是为了让模型始终围绕「当前这道题」回答。
         */
        private fun sendFollowUp(
            text: String,
            supplementPages: List<PreparedPage> = emptyList(),
        ) {
            val solution = latestSolution()
            val pages = supplementPages
            val hasSupplement = pages.isNotEmpty()
            lastFollowUp = FollowUpRequest(text = text, pages = pages)
            val studentMessage =
                ConversationMessage(
                    id = UUID.randomUUID().toString(),
                    fromStudent = true,
                    // 只补拍不写字时给一条可读的说明，消息流里不能出现空气泡
                    text = text.ifBlank { if (hasSupplement) "补拍了一张" else "" },
                    pages = pages.map { it.imagePath },
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
            // 立刻清空输入区：文字与图片都已经进入对话流，留着会让人以为没发出去
            _draft.update { it.copy(studentProcess = "") }
            if (hasSupplement) {
                _draftPages.value = QuestionDraft.EMPTY
                _activePage.value = 0
            }
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
                    val encoded = if (hasSupplement) encodePages(pages) else emptyList()
                    // 补拍的照片同样按框选裁剪后再展示：气泡里看到的必须是发出去的那张
                    if (encoded.isNotEmpty()) {
                        updateMessage(studentMessage.id) { it.copy(pages = encoded.map { page -> page.displayPath }) }
                    }
                    if (hasSupplement && encoded.isEmpty()) {
                        updateMessage(replyId) {
                            it.copy(
                                streaming = false,
                                errorText = "补拍的照片读取失败，这次没有上传。请重新拍一次，或直接打字说明。",
                                retryable = true,
                            )
                        }
                        _phase.value = ComposerPhase.EMPTY
                        return@launch
                    }
                    val context = solution?.let(::contextOfSolution).orEmpty()
                    // 追问的历史只带**最近几轮对话**，不带整份记录。
                    //
                    // 省钱的关键在这里：解题气泡的正文是一整份结构化解答
                    // （原题 + 已知 + 求 + 错因 + 标准解答 + 答案），动辄一两千字。
                    // 旧实现把它放进每一次追问的 messages 里，于是第三问、第四问的输入
                    // 会成倍增长，而模型真正需要的只是「刚才聊到哪」。
                    // 完整题目信息已经由 context 单独给出一次，不必在历史里重复。
                    val history =
                        _messages.value
                            .filter { it.id != replyId && !it.streaming }
                            .takeLast(FOLLOW_UP_HISTORY_TURNS)
                            .mapNotNull { message ->
                                // 解题气泡整条丢掉：它的内容就是 context，
                                // 再进历史等于把同一份解答付两次钱。
                                val body =
                                    when {
                                        message.solution != null -> ""
                                        else -> message.text.trim()
                                    }
                                if (body.isBlank()) {
                                    null
                                } else {
                                    FollowUpTurn(
                                        text = body.take(FOLLOW_UP_TURN_MAX_CHARS),
                                        fromStudent = message.fromStudent,
                                    )
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
                                images = encoded.map { it.encoded.base64 },
                                supplementNote =
                                    if (hasSupplement) {
                                        "学生这次补拍了照片，用来补充先前那道题（可能是原题没拍清楚）。" +
                                            "请把照片里的信息与上面的题目合并理解；如果照片显示原题信息有误，" +
                                            "以照片为准修正，但**不要把它当成一道新题重新讲一遍**。"
                                    } else {
                                        ""
                                    },
                            ).collect { event ->
                                when (event) {
                                    is FollowUpEvent.Delta -> {
                                        buffer.append(event.text)
                                        _streamText.value = buffer.toString()
                                        updateMessage(replyId) { it.copy(text = buffer.toString()) }
                                    }
                                    is FollowUpEvent.Completed -> {
                                        // 回答里可能带一行「[[订正]]最终答案：…」：
                                        // 那一行是给客户端写库用的，不能显示给学生，也不能进下一轮上下文。
                                        val parsed = FollowUpCorrection.parse(event.text)
                                        updateMessage(replyId) {
                                            it.copy(text = parsed.text, streaming = false)
                                        }
                                        // 改了口径就必须落到错题本：否则学生回头看「回顾」页，
                                        // 看到的还是那个被推翻的旧答案。
                                        parsed.correctedAnswer?.let { corrected ->
                                            syncCorrection(
                                                answer = corrected,
                                                replyId = replyId,
                                                verifiedOnly = parsed.verifiedOnly,
                                            )
                                        }
                                        if (hasSupplement && encoded.isNotEmpty()) {
                                            refreshQuestionFromSupplement(solution?.questionId, encoded)
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

        /**
         * 追问里 AI 订正了最终答案：写回错题本，并同步界面上方那张解答卡。
         *
         * 为什么必须回写：错题本里的「标准解答 / 答案」是学生在复习页核对的全部依据。
         * 追问把答案从「A、C」改成「C」而库里还是「A、C」，
         * 他下次复习看到的就还是错的结论——这比不纠错更糟。
         *
         * 写不进去时不静默：气泡上给一句可执行的回执，而不是让用户以为已经同步。
         */
        private suspend fun syncCorrection(
            answer: String,
            replyId: String,
            verifiedOnly: Boolean = false,
        ) {
            val payload = latestSolution() ?: return
            if (payload.finalAnswer.trim() == answer.trim()) {
                // 重算后结论没变也要给回执：学生质疑过，就该看到「确实核对过了」，
                // 否则他会以为 AI 只是把原答案又抄了一遍。
                if (verifiedOnly) {
                    updateMessage(replyId) {
                        it.copy(correctionNote = "已按你说的重算，结论没变：" + answer)
                    }
                }
                return
            }
            val mistakeId = payload.mistakeId
            if (mistakeId.isNullOrBlank()) {
                updateMessage(replyId) {
                    it.copy(correctionNote = "这次订正没能写进错题本：这道题当时没有入库")
                }
                return
            }
            val solution = SolutionMapper.toContent(payload).standardSolution
            val updated =
                runCatching {
                    mistakeRepository.applyCorrection(
                        mistakeId = mistakeId,
                        finalAnswer = answer,
                        solution = solution,
                    )
                }.getOrNull() ?: false
            if (!updated) {
                updateMessage(replyId) {
                    it.copy(correctionNote = "这次订正没能写进错题本，可以到错题详情页手动核对")
                }
                return
            }
            // 界面上的卡片同步改：同一个屏幕上不能同时出现新旧两个答案
            _messages.update { list ->
                list.map { message ->
                    val current = message.solution
                    if (current != null && current.mistakeId == mistakeId) {
                        message.copy(solution = current.copy(finalAnswer = answer))
                    } else {
                        message
                    }
                }
            }
            updateMessage(replyId) {
                it.copy(correctionNote = "已把订正写进错题本 · 新答案：" + answer)
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
                val previous = lastFollowUp ?: FollowUpRequest(text = studentBefore.text, pages = emptyList())
                sendFollowUp(text = previous.text, supplementPages = previous.pages)
                return
            }
            // 解题失败重试：优先用「上一次真正发出去的那份题目」。
            //
            // 为什么不能只读草稿：发送成功后输入区已按设计清空（图片与文字都进了消息流），
            // 此时从草稿组装必然得到一份空题目，重试会直接提示「题目内容为空」——
            // 用户看到的是「明明拍了题却说我什么都没拍」。
            val question = lastSentQuestion?.takeIf { it.canSolve } ?: buildQuestionFromDraft()
            if (!question.canSolve) {
                showToast("没有可以重试的题目，请重新拍一张或写下题干")
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
                // 第一次点直接给到第 2 级。
                //
                // 第 1 级只有方法名（「这道题的主线是配方法」），学生看完往往还得再点一次；
                // 第 2 级才是「下一步做什么」，才是真正能让人动笔的那一级。
                // 逐级点等于逼他多点一次废话，这也是真机反馈「提示总是分三次给」的来源。
                // 但第 3 级（具体式子与展开顺序）仍然要再点一次：那是接近答案的内容，
                // 提前塞给他会把「自己先想」这一步彻底跳过。
                val target =
                    if (payload.unlockedHintLevel == 0) {
                        // 只有 1 级提示时（步骤太少）也直接给到第 1 级，不做无意义停顿
                        minOf(2, payload.hints.size)
                    } else {
                        payload.unlockedHintLevel + 1
                    }
                if (target <= payload.unlockedHintLevel || target > payload.hints.size) {
                    payload
                } else {
                    recordHint(payload.mistakeId, target)
                    payload.copy(unlockedHintLevel = target)
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
            // 先裁剪、再建消息。
            //
            // 顺序很关键：气泡与学生看到的图必须是**实际上传的那张**（框选后的）。
            // 旧实现把原始整页路径塞进消息，用户框完题目、抬头一看还是整页照片，
            // 自然判断「框选没生效 / 发的还是原图」——这比坐标算错更直接地造成该反馈。
            val uploadPages = encodePages(question.pages)
            val displayPages =
                uploadPages.map { it.displayPath }.ifEmpty { question.pages.map { it.imagePath } }
            val studentMessage =
                ConversationMessage(
                    id = UUID.randomUUID().toString(),
                    fromStudent = true,
                    text = studentText,
                    pages = displayPages,
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
            // 发送后立刻清空输入区。
            //
            // 这是真机反馈「发送后图片还在输入框上面，看起来就像没有被发送」的直接修法：
            // 图片已经作为消息的一部分进入对话流（下面 persistAndLink 还会把它写进错题本），
            // 再在输入框里留一份，用户会误以为这次发送失败了、或者以为会重复上传。
            // 文字同理——旧版只清文字不清图片，观感上是「半发送」状态。
            lastSentQuestion = question
            _draft.update { it.copy(studentProcess = "") }
            _draftPages.value = QuestionDraft.EMPTY
            _activePage.value = 0
            _phase.value = ComposerPhase.SOLVING
            _stages.value = StageProgressMapper.initial()
            pageWarnings.clear()
            // 落库与历史都指向裁剪后的图：错题本里看到的应该就是学生框定的那道题
            lastImagePath.value = displayPages.firstOrNull()
            lastImageSha256.value = uploadPages.firstOrNull()?.encoded?.sha256 ?: question.imageSha256

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
            val encodedPages = uploadPages.map { it.encoded }
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
                    // ocr 字段保留为「题目文本」通道，但**不再来自 OCR**：
                    // 有图时为空（题目由视觉模型直接看图读出），无图时是用户手输的题干。
                    // 本地 OCR 已从主链路移除——它对纸质题目、公式与图表的识别损耗太大，
                    // 用它先转一遍文本反而会把「图里才有的条件」筛掉。
                    ocr =
                        com.totop.app.ml.OcrSnapshot(
                            text = question.stemText,
                            avgConfidence = if (question.stemText.isBlank()) 0.0 else 1.0,
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
        private suspend fun encodePages(pages: List<PreparedPage>): List<UploadPage> {
            if (pages.isEmpty()) return emptyList()
            val result = ArrayList<UploadPage>(pages.size)
            val failures = mutableListOf<Int>()
            pages.forEachIndexed { index, page ->
                val upload = buildUploadPage(page)
                if (upload == null) failures.add(index + 1) else result.add(upload)
            }
            if (failures.isNotEmpty()) {
                pageWarnings.add(
                    "第 " + failures.joinToString("、") + " 页读取失败，本次只上传了其余 " + result.size + " 页",
                )
            }
            return result
        }

        /**
         * 一页的「上传形态 + 展示路径」。
         *
         * @param encoded 真正发给模型的那张（已按框选裁剪）
         * @param displayPath 气泡与错题本要显示的图片路径。
         *        有框选时是裁剪图的落盘文件，未框选时就是原图路径。
         */
        private data class UploadPage(
            val encoded: EncodedImage,
            val displayPath: String,
        )

        /**
         * 补拍之后把错题本里的题干换成「看清新照片之后」的版本。
         *
         * 只重跑阶段 A（识题），解答与错因保持原样：补拍是同一道题的补充照片，不是换题，
         * 重跑 B 会得到一份新解答，与已有的错因、提示、复发计数全部对不上。
         *
         * 失败只提示、不改数据：错题本里留着旧题干，也比写入一份没校验过的识别结果安全。
         */
        private suspend fun refreshQuestionFromSupplement(
            questionId: String?,
            pages: List<UploadPage>,
        ) {
            if (questionId == null || pages.isEmpty()) return
            val credentials = runCatching { secretStore.read() }.getOrNull() ?: return
            if (!credentials.canCall) return
            val images = pages.map { it.encoded }
            runCatching {
                val outcome =
                    gateway.identifyOnly(
                        credentials = credentials,
                        request =
                            SolveRequest(
                                // 补拍走视觉通道，ocr 文本留空：这次要读的就是照片本身
                                ocr =
                                    com.totop.app.ml.OcrSnapshot(
                                        text = "",
                                        avgConfidence = 0.0,
                                        hasFigure = false,
                                        isHandwritten = false,
                                    ),
                                image = images.first(),
                                extraImages = images.drop(1),
                                requestId = UUID.randomUUID().toString(),
                            ),
                        registry = registry,
                    )
                val recognized = outcome.question
                questionRepository.updateRecognized(
                    questionId = questionId,
                    stemText = recognized.stem.orEmpty(),
                    known = recognized.known,
                    ask = recognized.ask.orEmpty(),
                    options = recognized.options,
                    hasFigure = recognized.hasFigure,
                    newImagePaths = pages.map { it.displayPath },
                )
            }.onSuccess { updated ->
                showToast(
                    if (updated) {
                        "已按新照片更新这道题，错题本同步了"
                    } else {
                        "照片已用于本次回答，但错题本里找不到这道题了"
                    },
                )
            }.onFailure { failure ->
                showToast("照片已用于本次回答，错题本未更新：" + (failure.message ?: "未知原因"))
            }
        }

        /** 按框选裁剪并编码一页；有框选时把裁剪结果落盘，供气泡与错题本展示。 */
        private suspend fun buildUploadPage(page: PreparedPage): UploadPage? =
            withContext(Dispatchers.IO) {
                val crop =
                    when (val outcome = imageCropper.cropToJpegBytes(page.imagePath, page.cropRect)) {
                        is ImageCropResult.Success -> outcome
                        is ImageCropResult.Failure -> {
                            // 裁剪失败不能静默吞掉：退回原图，但把原因记下来
                            pageWarnings.add(outcome.reason + "（已改用整页上传）")
                            val bytes =
                                runCatching { java.io.File(page.imagePath).readBytes() }.getOrNull()
                                    ?: return@withContext null
                            ImageCropResult.Success(bytes = bytes, cropped = false)
                        }
                    }
                val bytes = crop.bytes
                val sha = runCatching { ImageMath.sha256Hex(bytes) }.getOrNull() ?: return@withContext null
                val displayPath =
                    if (crop.cropped) {
                        // 裁剪图按内容哈希命名，与主图同一套规则：同一选区重复发送不会重复占空间
                        runCatching {
                            val dir = java.io.File(appContext.filesDir, com.totop.app.ml.PreprocessPaths.IMAGE_DIR)
                            val file = java.io.File(dir, com.totop.app.ml.PreprocessPaths.fileNameFor(sha))
                            if (!file.exists()) {
                                dir.mkdirs()
                                file.writeBytes(bytes)
                            }
                            file.absolutePath
                        }.getOrDefault(page.imagePath)
                    } else {
                        page.imagePath
                    }
                val encoded =
                    runCatching {
                        EncodedImage(
                            jpegBytes = bytes,
                            base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP),
                            width = 0,
                            height = 0,
                            sha256 = sha,
                            sourceWidth = 0,
                            sourceHeight = 0,
                            rotationDegrees = 0,
                        )
                    }.getOrNull() ?: return@withContext null
                UploadPage(encoded = encoded, displayPath = displayPath)
            }

        private fun handleEvent(
            event: SolveEvent,
            replyId: String,
        ) {
            when (event) {
                is SolveEvent.Routed -> Unit
                is SolveEvent.StageStarted ->
                    _stages.value =
                        StageProgressMapper.start(
                            _stages.value.takeIf { it.isNotEmpty() } ?: StageProgressMapper.initial(),
                            event.stage,
                            event.cached,
                        )
                is SolveEvent.FirstToken ->
                    _stages.value = StageProgressMapper.firstToken(_stages.value, event.stage, event.elapsedMs)
                is SolveEvent.Delta -> {
                    // 刻意不渲染。
                    //
                    // 阶段 A/B/C/D 的增量是**结构化 JSON**（识题字段、步骤数组、错因对象），
                    // 吐到气泡里就是一大段 `{"subject":"math","stem":...}`，
                    // 学生既读不懂也看不出进度，只会以为「AI 在乱码」。
                    // 现在解题期间只显示一个「AI 正在思考」占位，完成后一次性换成结构化解答。
                    _streamText.value = ""
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
            // 气泡正文（同时是会话持久化的内容）。
            //
            // 历史回顾时只拿得到这段纯文本，所以它必须自身可读：
            // 分段加小标题、步骤之间空行、每段前留出「已知 / 求 / 解答」的结构。
            // 旧实现把所有内容用单换行糊在一起，回顾时像一段没有段落的短信，
            // 这也是真机反馈「原题没有排版看着难受」的一部分。
            val summaryText =
                buildString {
                    if (payload.stem.isNotBlank()) {
                        append("【原题】\n").append(payload.stem).append('\n')
                    }
                    if (payload.known.isNotEmpty()) {
                        append("\n【已知】\n")
                        payload.known.forEach { append("· ").append(it).append('\n') }
                    }
                    if (payload.ask.isNotBlank()) {
                        append("\n【求】").append(payload.ask).append('\n')
                    }
                    payload.firstError?.let { error ->
                        append("\n【错在哪里】\n")
                        append("第 ").append(error.stepIdx).append(" 步 · ").append(error.typeLabel).append('\n')
                        append(error.explain).append('\n')
                        if (error.correctThinking.isNotBlank()) {
                            append("下次这样想：").append(error.correctThinking).append('\n')
                        }
                    }
                    if (payload.steps.isNotEmpty()) {
                        append("\n【标准解答】\n")
                        payload.steps.forEach { step ->
                            append(step.idx).append(". ").append(step.content).append('\n')
                            if (step.why.isNotBlank()) append("   为什么：").append(step.why).append('\n')
                        }
                    }
                    if (payload.finalAnswer.isNotBlank()) {
                        append("\n【答案】").append(payload.finalAnswer)
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
                            imagePath = lastImagePath.value,
                            // 必须写入 sha256，否则 saveDeduplicated 的按图去重永远失效，
                            // 同一张图重解一次就会在错题本里多出一条重复记录。
                            imageSha256 = lastImageSha256.value,
                            // 多页题目把全部裁剪图都存下来，错题详情才能翻页看；
                            // 第 1 页仍是 imagePath（缓存与去重锚点用它）。
                            imagePaths =
                                _draftPages.value.pages
                                    .map { it.imagePath }
                                    .filter { it.isNotBlank() },
                            sourceType = lastSourceType.value,
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
                        .map {
                            KpointAssignment(
                                kpointId = it.kpointId,
                                name = it.name,
                                confidence = it.confidence,
                                usage = it.usage,
                            )
                        }
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
            // 页面销毁：中断所有在途 Call 并封存注册表，避免继续计费。
            // 这里必须用 cancelAllAndSeal：普通 cancelAll 只取消在途请求、注册表仍可用，
            // 而 ViewModel 销毁后不应再接受任何新调用。
            registry.cancelAllAndSeal()
            solveJob?.cancel()
            solveJob = null
            super.onCleared()
        }

        private companion object {
            /** 未配置模型时的统一提示。 */
            const val NOT_CONFIGURED_MESSAGE: String =
                "尚未配置模型（Base URL / API Key / 模型名）。请到设置页填写后再试：" +
                    "本应用不内置任何 Key，全部由你自己填写，也不经过开发者服务器。"

            /**
             * 追问时最多带几轮历史。
             *
             * 6 轮足以覆盖「老师你刚才说到哪」这类指代，再多只是在为上下文付费。
             * 完整题目信息由 context 单独给出，历史里不重复。
             */
            const val FOLLOW_UP_HISTORY_TURNS: Int = 6

            /** 单条历史消息的字符上限，防止某个超长回答把预算吃光。 */
            const val FOLLOW_UP_TURN_MAX_CHARS: Int = 400
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
