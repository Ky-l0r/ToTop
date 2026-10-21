package com.totop.app.ui.chat

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.totop.app.data.prefs.SecretStore
import com.totop.app.data.remote.AiGateway
import com.totop.app.data.remote.LlmCallRegistry
import com.totop.app.data.remote.LlmException
import com.totop.app.data.remote.SolveEvent
import com.totop.app.data.remote.SolveRequest
import com.totop.app.data.repo.MistakeRepository
import com.totop.app.data.repo.QuestionRepository
import com.totop.app.data.repo.SettingsRepository
import com.totop.app.domain.model.AiSolvedContent
import com.totop.app.domain.model.AiStage
import com.totop.app.domain.model.ErrorType
import com.totop.app.domain.model.HintLog
import com.totop.app.domain.model.MistakeRecord
import com.totop.app.domain.model.Question
import com.totop.app.domain.model.QuestionSourceType
import com.totop.app.domain.model.StudentStepStatus
import com.totop.app.domain.model.TeachingMode
import com.totop.app.ml.ImagePreprocessor
import com.totop.app.ml.OcrOutcome
import com.totop.app.ml.OcrRecognizer
import com.totop.app.ml.OcrSnapshot
import com.totop.app.ml.PreprocessResult
import com.totop.app.ml.RouteDecision
import com.totop.app.domain.model.PageRole
import com.totop.app.domain.model.QuestionDraft
import com.totop.app.domain.model.QuestionPage
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
 * 架构约束（本任务书强制）：
 * - **不直接调用 Retrofit / OkHttp / WebView**：模型调用只经 [AiGateway]，
 *   数据读写只经 Repository，OCR 与图片预处理只经 ml 层接口；
 * - 「停止」= [LlmCallRegistry.cancelAll] 中断 socket，[onCleared] 再兜底一次，
 *   保证页面离开后不继续消耗 token；
 * - 讲解模式可在会话内临时覆盖设置里的默认值（不写 settings 表）。
 */
@Suppress("TooManyFunctions")
@HiltViewModel
class SolveViewModel
    @Inject
    constructor(
        @ApplicationContext private val appContext: Context,
        private val secretStore: SecretStore,
        private val gateway: AiGateway,
        private val knowledgeCatalog: KnowledgeCatalogReader,
        private val questionRepository: QuestionRepository,
        private val mistakeRepository: MistakeRepository,
        private val settingsRepository: SettingsRepository,
        private val ocrRecognizer: OcrRecognizer,
        private val imagePreprocessor: ImagePreprocessor,
        private val imageCropper: ImageCropper,
        private val offlineRepository: com.totop.app.data.repo.OfflineNoteRepository,
    ) : ViewModel() {
        private val registry = LlmCallRegistry()

        private val _uiState = MutableStateFlow<SolveUiState>(SolveUiState.Idle)
        val uiState: StateFlow<SolveUiState> = _uiState.asStateFlow()

        private val _networkStatus = MutableStateFlow(NetworkStatus.UNKNOWN)
        val networkStatus: StateFlow<NetworkStatus> = _networkStatus.asStateFlow()

        private val _mode = MutableStateFlow(TeachingMode.DEFAULT)
        val mode: StateFlow<TeachingMode> = _mode.asStateFlow()

        /** 会话内是否临时覆盖了设置里的默认模式。 */
        private val _modeOverridden = MutableStateFlow(false)
        val modeOverridden: StateFlow<Boolean> = _modeOverridden.asStateFlow()

        /** 输入草稿。 */
        private val _draft = MutableStateFlow(SolveDraft())
        val draft: StateFlow<SolveDraft> = _draft.asStateFlow()

        /** 本次路由决策（顶部展示「为什么这次用视觉模型」）。 */
        private val _routeDecision = MutableStateFlow<RouteDecision?>(null)
        val routeDecision: StateFlow<RouteDecision?> = _routeDecision.asStateFlow()

        private var solveJob: Job? = null

        private var requestId: String = ""

        /** 初始化：读设置默认模式 + 刷新网络状态。 */
        fun bootstrap() {
            refreshNetworkStatus()
            viewModelScope.launch {
                val stored = settingsRepository.get(SettingsRepository.Keys.MODE)
                if (!_modeOverridden.value) {
                    _mode.value = TeachingMode.fromWire(stored)
                }
            }
        }

        /** 刷新网络状态。 */
        fun refreshNetworkStatus() {
            _networkStatus.value = NetworkProbe.status(appContext)
        }

        // ---------- 输入（多页 + 框选） ----------

        /** 当前拍题草稿（多页）。 */
        private val _draftPages = MutableStateFlow<QuestionDraft>(QuestionDraft.EMPTY)
        val draftPages: StateFlow<QuestionDraft> = _draftPages.asStateFlow()

        /** 当前正在预览/框选第几页。 */
        private val _activePage = MutableStateFlow(0)
        val activePage: StateFlow<Int> = _activePage.asStateFlow()

        /** 图片处理中的提示（拍照后压缩/识别期间）。 */
        private val _processing = MutableStateFlow(false)
        val processing: StateFlow<Boolean> = _processing.asStateFlow()

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
         * 追加一页：预处理 + OCR，但**不覆盖已有页**，也不直接进入 Ready。
         *
         * 与旧实现的差别：旧实现每次导入都会把整个题目状态替换掉，
         * 拍第二页时第一页就被丢了，所以「多页」实际上无法实现。
         */
        private suspend fun appendPage(
            source: QuestionSourceType,
            preprocess: suspend () -> PreprocessResult,
        ) {
            _processing.value = true
            try {
                when (val result = preprocess()) {
                    is PreprocessResult.Failure -> {
                        _uiState.value =
                            SolveUiState.Failed(
                                question = currentQuestionOrNull(),
                                stage = AiStage.A,
                                message = result.error.userMessage,
                                retryable = true,
                            )
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
                        // 只要还停在空态，就把状态推进到 Ready，
                        // 让用户知道「已经有一张图了，可以写思路并发送」
                        if (_uiState.value is SolveUiState.Idle) {
                            _uiState.value = SolveUiState.Ready(buildQuestionFromDraft())
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
            if (_uiState.value is SolveUiState.Ready) {
                _uiState.value = SolveUiState.Ready(buildQuestionFromDraft())
            }
        }

        /** 删除某页。 */
        fun removePage(index: Int) {
            val next = _draftPages.value.removeAt(index)
            _draftPages.value = next
            _activePage.value = _activePage.value.coerceIn(0, (next.pageCount - 1).coerceAtLeast(0))
            _uiState.value =
                if (next.isEmpty) {
                    SolveUiState.Idle
                } else {
                    SolveUiState.Ready(buildQuestionFromDraft())
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
                }.onFailure { failure ->
                    _pageWarnings.value =
                        (_pageWarnings.value + ("离线保存失败：" + (failure.message ?: "未知原因"))).toMutableList()
                }
            }
        }

        /** 清空所有页（相机页「重拍」时使用）。 */
        fun clearPages() {
            _draftPages.value = QuestionDraft.EMPTY
            _activePage.value = 0
            if (_uiState.value !is SolveUiState.Solving) {
                _uiState.value = SolveUiState.Idle
            }
        }

        private fun currentQuestionOrNull(): PreparedQuestion? =
            when (val current = _uiState.value) {
                is SolveUiState.Ready -> current.question
                is SolveUiState.Solving -> current.question
                is SolveUiState.Solved -> current.question
                is SolveUiState.Failed -> current.question
                is SolveUiState.Cancelled -> current.question
                is SolveUiState.Idle -> null
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

        /** 纯文本输入（没有任何图片时的入口）。 */
        fun submitText(
            stem: String,
            studentProcess: String,
        ) {
            val trimmedStem = stem.trim()
            if (trimmedStem.isEmpty()) {
                _uiState.value =
                    SolveUiState.Failed(
                        question = null,
                        stage = AiStage.A,
                        message = "请先输入题目内容，或先拍一张照片",
                        retryable = false,
                    )
                return
            }
            _draft.update { it.copy(stem = trimmedStem, studentProcess = studentProcess) }
            _uiState.value =
                SolveUiState.Ready(
                    buildQuestionFromDraft().copy(
                        sourceLabel = "手输",
                        stemText = trimmedStem,
                        studentRawText = studentProcess.trim(),
                    ),
                )
        }

        /** 编辑题干草稿。 */
        fun updateStem(text: String) {
            _draft.update { it.copy(stem = text) }
        }

        /**
         * 编辑学生过程草稿。
         *
         * 必须同步进状态：对话页里输入框就是学生过程，
         * 发送时用的是状态里的 studentRawText。若只更新草稿不同步状态，
         * 用户写了思路却不会被送去阶段 C，错因永远定位不到。
         */
        fun updateStudentProcess(text: String) {
            _draft.update { it.copy(studentProcess = text) }
            val current = _uiState.value
            if (current is SolveUiState.Ready) {
                _uiState.value = current.copy(question = current.question.copy(studentRawText = text.trim()))
            }
        }

        /** 已就绪状态下补充学生过程。 */
        fun updateReadyStudentProcess(text: String) {
            val current = _uiState.value
            if (current is SolveUiState.Ready) {
                _uiState.value = current.copy(question = current.question.copy(studentRawText = text.trim()))
            }
            _draft.update { it.copy(studentProcess = text) }
        }

        /**
         * 对当前草稿做一次本地 OCR，把文本填到「题干」里供路由判断。
         *
         * 为什么 OCR 不是必需品：
         * 识别质量差（手写、反光、艺术字）时纯文本档会答错，
         * 交给视觉模型看原图反而更准。因此 OCR 失败/低置信度只降级为「走视觉档」，
         * 不能让用户卡在这一步无法解题。
         *
         * 只在用户还没手写过题干时填充，避免覆盖用户已经输入或修改的内容。
         */
        fun runOcrIfNeeded() {
            val draft = _draftPages.value
            if (draft.isEmpty) return
            if (_draft.value.stem.isNotBlank()) return
            viewModelScope.launch {
                val first = draft.pages.firstOrNull() ?: return@launch
                when (val ocr = ocrRecognizer.recognizeFile(first.imagePath)) {
                    is OcrOutcome.Success -> {
                        val snapshot = ocr.result.toSnapshot()
                        _draft.update { it.copy(stem = ocr.result.text) }
                        _uiState.value = SolveUiState.Ready(buildQuestionFromDraft())
                    }
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
            val current = _uiState.value
            if (current is SolveUiState.Solved && newMode != TeachingMode.DIRECT) {
                _uiState.value =
                    current.copy(
                        unlockedHintLevel = if (newMode == TeachingMode.SOCRATIC) 0 else current.unlockedHintLevel,
                        fullSolutionRevealed = false,
                    )
            }
        }

        // ---------- 发送 / 停止 / 重试 / 重新提问 ----------

        /** 发送。 */
        /**
         * 发送。
         *
         * 关键点：不能只从 [SolveUiState] 里取题目。
         * 用户可能「拍完照一句话没说就直接点发送」，此时状态仍是 Idle，
         * 旧实现会在这里静默 return——按钮点了没有任何反应，看起来像卡死。
         * 因此改为：状态里没有题目时，直接用当前草稿组装。
         */
        fun send() {
            val fromState =
                when (val current = _uiState.value) {
                    is SolveUiState.Ready -> current.question
                    is SolveUiState.Cancelled -> current.question
                    is SolveUiState.Failed -> current.question
                    else -> null
                }
            val question = fromState ?: buildQuestionFromDraft().takeIf { it.canSolve }
            if (question == null || !question.canSolve) return
            if (solveJob?.isActive == true) return
            // 状态由 runSolve 统一设置，这里不重复写，避免两处状态初始化不一致
            solveJob = viewModelScope.launch { runSolve(question) }
        }

        /** 停止：中断 socket，保留已收到的增量。 */
        fun stop() {
            val current = _uiState.value as? SolveUiState.Solving ?: return
            registry.cancelAll()
            solveJob?.cancel()
            solveJob = null
            _uiState.value =
                SolveUiState.Cancelled(
                    question = current.question,
                    hasPartialResult = current.streamText.isNotBlank(),
                )
        }

        /** 重试：同一题目重新发起（新 requestId，避免幂等去重拦截）。 */
        fun retry() {
            val question =
                when (val current = _uiState.value) {
                    is SolveUiState.Failed -> current.question
                    is SolveUiState.Cancelled -> current.question
                    else -> null
                } ?: return
            registry.reset()
            _uiState.value = SolveUiState.Ready(question)
            send()
        }

        /** 重新提问：回到空态，草稿保留在输入框。 */
        fun askAgain() {
            registry.cancelAll()
            solveJob?.cancel()
            solveJob = null
            registry.reset()
            _routeDecision.value = null
            _uiState.value = SolveUiState.Idle
        }

        /** 清空全部（含输入框）。 */
        fun reset() {
            askAgain()
            _draft.value = SolveDraft()
        }

        /** 引导模式：解锁下一级提示，并通过 domain 的 HintLog + Repository 写 hint_log。 */
        fun unlockNextHint() {
            val current = _uiState.value as? SolveUiState.Solved ?: return
            val next = current.unlockedHintLevel + 1
            if (next > current.hints.size || next > HintBuilder.MAX_LEVEL) return
            _uiState.value = current.copy(unlockedHintLevel = next)
            recordHint(current.mistakeId, next)
        }

        /** 引导模式：「我已想通，看全解」。 */
        fun revealFullSolution() {
            val current = _uiState.value as? SolveUiState.Solved ?: return
            _uiState.value = current.copy(fullSolutionRevealed = true)
        }

        // ---------- 主链路 ----------

        private suspend fun runSolve(question: PreparedQuestion) {
            requestId = UUID.randomUUID().toString()
            _uiState.value =
                SolveUiState.Solving(
                    question = question,
                    stages = StageProgressMapper.initial(),
                    streamText = "",
                    startedAtUtc = System.currentTimeMillis(),
                )
            val credentials = secretStore.read()
            if (!credentials.canCall) {
                _uiState.value =
                    SolveUiState.Failed(
                        question = question,
                        stage = AiStage.A,
                        message =
                            "尚未配置模型（Base URL / API Key / 模型名）。请到设置页填写后再试：" +
                                "本应用不内置任何 Key，全部由你自己填写，也不经过开发者服务器。",
                        retryable = false,
                    )
                return
            }
            val candidates =
                KpointCandidates.pick(
                    points = knowledgeCatalog.allPoints(),
                    haystack = question.stemText,
                )
            // 逐页裁剪并编码：框选区域在**上传前**裁掉，
            // 而不是让模型自己看整页（协议没有标准坐标字段，且裁剪更省 token、更准）。
            val encodedPages = encodePages(question)
            if (question.pages.isNotEmpty() && encodedPages.isEmpty()) {
                _uiState.value =
                    SolveUiState.Failed(
                        question = question,
                        stage = AiStage.A,
                        message = "图片读取失败，题目没有上传。请重新拍一次，或改用「相册」导入。",
                        retryable = true,
                    )
                return
            }
            val request =
                SolveRequest(
                    ocr =
                        OcrSnapshot(
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
                    .collect { event -> handleEvent(event) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: LlmException) {
                _uiState.value =
                    SolveUiState.Failed(
                        question = question,
                        stage = AiStage.A,
                        message = error.error.userMessage,
                        retryable = error.error.isRetryable,
                        technicalDetail = error.error.detail,
                    )
            } catch (throwable: Throwable) {
                _uiState.value =
                    SolveUiState.Failed(
                        question = question,
                        stage = AiStage.A,
                        message = "解题过程中出现未预期的问题：" + (throwable.message ?: throwable.javaClass.simpleName),
                        retryable = true,
                    )
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
                _pageWarnings.value =
                    mutableListOf(
                        "第 " + failures.joinToString("、") + " 页读取失败，本次只上传了其余 " +
                            result.size + " 页",
                    )
            } else {
                _pageWarnings.value = mutableListOf()
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
                            _pageWarnings.value =
                                (_pageWarnings.value + (crop.reason + "（已改用整页上传）")).toMutableList()
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

        /** 本轮的页面级告警（框选失败、某页丢失等），合并进最终 warnings。 */
        private val _pageWarnings = MutableStateFlow<MutableList<String>>(mutableListOf())

        private fun handleEvent(event: SolveEvent) {
            when (event) {
                is SolveEvent.Routed -> _routeDecision.value = event.decision
                is SolveEvent.StageStarted ->
                    mutateSolving { state ->
                        state.copy(stages = StageProgressMapper.start(state.stages, event.stage, event.cached))
                    }
                is SolveEvent.FirstToken ->
                    mutateSolving { state ->
                        state.copy(stages = StageProgressMapper.firstToken(state.stages, event.stage, event.elapsedMs))
                    }
                is SolveEvent.Delta ->
                    mutateSolving { state ->
                        state.copy(streamText = state.streamText + event.text)
                    }
                is SolveEvent.StageCompleted ->
                    mutateSolving { state ->
                        state.copy(stages = StageProgressMapper.complete(state.stages, event.stage, event.summary))
                    }
                is SolveEvent.Completed -> onCompleted(event)
                is SolveEvent.Failed -> {
                    val question = (_uiState.value as? SolveUiState.Solving)?.question
                    _uiState.value =
                        SolveUiState.Failed(
                            question = question,
                            stage = event.stage,
                            message = event.error.userMessage,
                            retryable = event.error.isRetryable,
                            technicalDetail = event.error.detail,
                            warnings = event.partialWarnings,
                        )
                }
            }
        }

        private inline fun mutateSolving(block: (SolveUiState.Solving) -> SolveUiState.Solving) {
            val current = _uiState.value
            if (current is SolveUiState.Solving) {
                _uiState.value = block(current)
            }
        }

        private fun onCompleted(event: SolveEvent.Completed) {
            val question = (_uiState.value as? SolveUiState.Solving)?.question ?: return
            val currentMode = _mode.value
            val hints =
                HintBuilder.build(
                    method = event.content.standardSolution.method,
                    stepContents = event.content.standardSolution.steps.map { it.content },
                )
            _uiState.value =
                SolveUiState.Solved(
                    question = question,
                    content = event.content,
                    warnings = event.warnings,
                    usage = event.usage,
                    unlockedHintLevel = 0,
                    fullSolutionRevealed = currentMode == TeachingMode.DIRECT,
                    hints = hints,
                )
            viewModelScope.launch { persist(question, event.content, currentMode) }
        }

        // ---------- 落库 ----------

        /**
         * 写入错题本。
         *
         * 只有定位到错误步骤时才建错题记录（FR-1.7）；题目本身总是保存。
         * 写库失败不打断阅读，只在页面给出提示。
         */
        @Suppress("TooGenericExceptionCaught")
        private suspend fun persist(
            question: PreparedQuestion,
            content: AiSolvedContent,
            currentMode: TeachingMode,
        ) {
            val firstError = content.firstError ?: return
            try {
                val saved =
                    questionRepository.saveDeduplicated(
                        Question(
                            id = content.question.id,
                            subject = content.question.subject,
                            grade = content.question.grade,
                            stemText = content.question.stemText,
                            known = content.question.known,
                            ask = content.question.ask,
                            options = content.question.options,
                            hasFigure = content.question.hasFigure,
                            // 多页题目只把第 1 页作为「主图」写入 question 表，
                            // 其余页随本次结果一起丢弃：错题本展示的是题干与错因，
                            // 多页原图不属于错题的必需属性，避免为此新增表与迁移。
                            imagePath = question.imagePath,
                            imageSha256 = question.imageSha256,
                            sourceType = QuestionSourceType.fromWire(question.sourceLabel),
                            createdAtUtc = content.question.createdAtUtc,
                        ),
                    )
                val record =
                    MistakeRecord(
                        id = UUID.randomUUID().toString(),
                        questionId = saved.question.id,
                        studentSteps = content.studentSteps,
                        firstErrorStepIdx = firstError.stepIdx,
                        errorType = firstError.type,
                        errorExplain = firstError.explain,
                        correctSolution = content.standardSolution,
                        finalAnswer = content.standardSolution.finalAnswer,
                        mode = currentMode,
                        createdAtUtc = System.currentTimeMillis(),
                        recurrenceIndex = 1,
                    )
                val assignments = content.kpointAssignments.filter { it.hasControlledId }
                val stored =
                    mistakeRepository.save(
                        record = record,
                        assignments = assignments,
                        recomputeRecurrence = true,
                    )
                updateSolved { it.copy(mistakeId = stored.id) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                updateSolved {
                    it.copy(persistMessage = "本次结果未能写入错题本：" + (throwable.message ?: "未知原因"))
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

        private inline fun updateSolved(block: (SolveUiState.Solved) -> SolveUiState.Solved) {
            val current = _uiState.value
            if (current is SolveUiState.Solved) {
                _uiState.value = block(current)
            }
        }

        // ---------- 派生数据（供 UI 直接读取） ----------

        /** 三态对齐后的学生步骤。 */
        fun alignedStudentSteps(state: SolveUiState.Solved): List<AlignedStudentStep> {
            val errorIdx = state.content.firstError?.stepIdx
            val errorType = state.content.firstError?.type
            return state.content.studentSteps.map { step ->
                AlignedStudentStep(
                    idx = step.idx,
                    content = step.content,
                    status = step.status,
                    isFirstError = errorIdx != null && step.idx == errorIdx,
                    errorLabel = if (errorIdx != null && step.idx == errorIdx) errorType?.label else null,
                )
            }
        }

        /** 七类错因的选中状态。 */
        fun errorTypeOptions(state: SolveUiState.Solved): List<ErrorTypeOption> {
            val selected = state.content.firstError?.type ?: ErrorType.FALLBACK
            return ErrorType.FIXED.map { type -> ErrorTypeOption(type = type, selected = type == selected) }
        }

        /** 按展示策略返回可见的最终答案；不可见时为 null。 */
        fun visibleFinalAnswer(
            state: SolveUiState.Solved,
            currentMode: TeachingMode,
        ): String? {
            if (!GuidePolicy.showsFinalAnswer(currentMode, state.fullSolutionRevealed)) return null
            return state.content.standardSolution.finalAnswer.ifBlank { null }
        }

        // ---------- 生命周期 ----------

        override fun onCleared() {
            // 页面销毁：中断所有在途 Call，避免继续计费
            registry.cancelAll()
            solveJob?.cancel()
            solveJob = null
            super.onCleared()
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
