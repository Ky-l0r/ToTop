package com.totop.app.ui.reviewmaterial

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.totop.app.data.prefs.SecretStore
import com.totop.app.data.repo.MistakeRepository
import com.totop.app.domain.model.Subjects
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class ReviewMaterialUiState(
    /** 可选科目：十科全部列出（按课程顺序），不再写死「全部 + 数学优先」。 */
    val subjects: List<String> = Subjects.ALL_LABELS,
    val selectedSubject: String = Subjects.label("math"),
    val materialState: ReviewMaterialState = ReviewMaterialState.Idle,
    /** 操作反馈（导出成功等），一次性提示。 */
    val message: String? = null,
    /** 最近一次导出的文件信息；非空时界面显示「打开 / 分享」入口。 */
    val exportedFile: ExportArtifactInfo? = null,
    /** 导出进行中。 */
    val exporting: Boolean = false,
) {
    /** 当前选中的科目代码。 */
    val selectedCode: String get() = Subjects.codeOf(selectedSubject)
}

/**
 * 复习资料 ViewModel。
 *
 * 生成流程与解题流程同构：
 * 取本地错题 → 组装提示词 → 调用模型 → 严格校验 JSON → 失败可重试。
 * 复用同一套错误分类与重试语义，「模型没通」和「生成失败」在界面上表达一致。
 *
 * 导出的关键修复：
 * 旧实现只把 PDF 写进 cacheDir 并在界面打一行文件名，用户在文件管理器里根本找不到。
 * 现在导出后交给 [ExportSink] 发布到系统「下载」目录，并保留 content:// Uri，
 * 界面据此提供「打开」「分享/打印」两个真实可用的出口。
 */
@HiltViewModel
class ReviewMaterialViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: android.content.Context,
        private val gateway: ReviewMaterialGateway,
        private val secretStore: SecretStore,
        private val mistakeRepository: MistakeRepository,
        private val questionRepository: com.totop.app.data.repo.QuestionRepository,
    ) : ViewModel() {
        private val pdfWriter = ReviewMaterialPdfWriter()

        private val sink = ExportSink(context)

        private val _uiState = MutableStateFlow(ReviewMaterialUiState())
        val uiState: StateFlow<ReviewMaterialUiState> = _uiState.asStateFlow()

        /** 待分享的 Intent；界面消费后调用 [consumePendingIntent]。 */
        private val _pendingIntent = MutableStateFlow<Intent?>(null)
        val pendingIntent: StateFlow<Intent?> = _pendingIntent.asStateFlow()

        fun selectSubject(label: String) {
            _uiState.value =
                _uiState.value.copy(
                    selectedSubject = label,
                    materialState = ReviewMaterialState.Idle,
                    exportedFile = null,
                )
        }

        /** 生成复习资料。失败时给出可读原因，绝不静默。 */
        fun generate() {
            if (_uiState.value.materialState is ReviewMaterialState.Generating) return
            _uiState.value = _uiState.value.copy(materialState = ReviewMaterialState.Generating, message = null)
            viewModelScope.launch {
                val credentials = secretStore.read()
                if (!credentials.canCall) {
                    _uiState.value =
                        _uiState.value.copy(
                            materialState =
                                ReviewMaterialState.Failed(
                                    message = "还没配置模型。请到设置页填写 Base URL、API Key 与模型名，再来生成。",
                                    retryable = false,
                                ),
                        )
                    return@launch
                }
                // 真实错题作为原料：资料里讲的必须是用户自己错过的题，
                // 而不是模型凭空编的通用题。取不到就退化为通用要点，但会说明。
                val subjectLabel = _uiState.value.selectedSubject
                val mistakes = loadMistakes(subjectLabel)
                // 把「流结束了但一个事件都没收到」也归一成一个明确的失败，
                // 这样后续 when 分支是穷尽的，不需要再判空。
                val result: ReviewMaterialResult =
                    runCatching {
                        var last: ReviewMaterialResult? = null
                        gateway
                            .generate(
                                credentials = credentials,
                                subjectLabel = subjectLabel,
                                mistakes = mistakes,
                                requestId = UUID.randomUUID().toString(),
                            ).collect { outcome -> last = outcome }
                        last ?: ReviewMaterialResult.Failure(
                            com.totop.app.data.remote.LlmError.Unknown(
                                detail = "模型没有返回任何内容，请重试一次",
                            ),
                        )
                    }.getOrElse { failure ->
                        ReviewMaterialResult.Failure(
                            com.totop.app.data.remote.LlmErrorMapper.fromThrowable(failure),
                        )
                    }
                when (val outcome = result) {
                    is ReviewMaterialResult.Success ->
                        _uiState.value =
                            _uiState.value.copy(
                                materialState = ReviewMaterialState.Success(outcome.material),
                                message =
                                    if (mistakes.isEmpty()) {
                                        "该科目暂时没有错题记录，这份资料是按通用要点生成的。"
                                    } else {
                                        "已根据你错过的 " + mistakes.size + " 道题生成。"
                                    },
                                exportedFile = null,
                            )
                    is ReviewMaterialResult.Failure ->
                        _uiState.value =
                            _uiState.value.copy(
                                materialState =
                                    ReviewMaterialState.Failed(
                                        message = outcome.error.userMessage,
                                        retryable = outcome.error.isRetryable,
                                    ),
                            )
                }
            }
        }

        /**
         * 从本地错题里取该科目的典型错题摘要。
         *
         * 复用既有的按科目分页查询（走 mistake_record JOIN question），
         * 不新增表、不改表结构，因此不存在迁移风险。
         * 题干从 question 表按 id 读；读不到时用错因兜底，不中断整次生成。
         */
        private suspend fun loadMistakes(subjectLabel: String): List<ReviewMistake> =
            runCatching {
                val subjectCode = Subjects.codeOf(subjectLabel)
                val records =
                    mistakeRepository.pageBySubject(
                        subject = subjectCode,
                        limit = ReviewMaterialGateway.MAX_MISTAKES,
                        offset = 0,
                    )
                records.take(ReviewMaterialGateway.MAX_MISTAKES).map { record ->
                    ReviewMistake(
                        stem = questionStemOf(record.questionId),
                        errorSummary = record.errorExplain.ifBlank { "（未记录具体错因）" },
                        correctIdea = record.correctSolution.steps.firstOrNull()?.content.orEmpty(),
                    )
                }
            }.getOrDefault(emptyList())

        /** 题干按 questionId 读取；失败返回空串，让模型按错因推断即可。 */
        private suspend fun questionStemOf(questionId: String): String =
            runCatching { questionRepository.byId(questionId)?.stemText.orEmpty() }.getOrDefault("")

        /**
         * 导出 A4 PDF。
         *
         * 三步：渲染到临时文件 → 发布到下载目录 → 记住可分享 Uri。
         * 任何一步失败都把具体原因写进 message（例如磁盘不可写、空间不足），
         * 而不是笼统提示「导出失败」。
         */
        fun exportPdf() {
            val material =
                (_uiState.value.materialState as? ReviewMaterialState.Success)?.material
                    ?: return
            if (_uiState.value.exporting) return
            _uiState.value = _uiState.value.copy(exporting = true, message = "正在生成 PDF…")
            viewModelScope.launch {
                when (val result = pdfWriter.write(context, material)) {
                    is PdfWriteResult.Success -> {
                        val displayName =
                            ReviewMaterialPdfWriter.FILE_STEM + System.currentTimeMillis() + ".pdf"
                        when (val published = sink.publish(result.file, PDF_MIME, displayName)) {
                            is PublishResult.Success ->
                                _uiState.value =
                                    _uiState.value.copy(
                                        exporting = false,
                                        exportedFile = published.info,
                                        message =
                                            if (published.info.visibleInDownloads) {
                                                "已保存到「下载/ToTop/" + published.info.displayName + "」，可直接打开或分享。"
                                            } else {
                                                "已生成 " + published.info.displayName + "，可点下方按钮打开或分享。"
                                            },
                                    )
                            is PublishResult.Failure ->
                                _uiState.value =
                                    _uiState.value.copy(
                                        exporting = false,
                                        message = "PDF 已生成但保存失败：" + published.reason + "（可改用分享按钮）",
                                    )
                        }
                    }
                    is PdfWriteResult.Failure ->
                        _uiState.value =
                            _uiState.value.copy(exporting = false, message = "导出失败：" + result.reason)
                }
            }
        }

        /** 打开刚导出的 PDF。 */
        fun openExported() {
            val info = _uiState.value.exportedFile ?: return
            val intent = sink.openIntent(info, PDF_MIME)
            if (intent == null) {
                _uiState.value = _uiState.value.copy(message = "本机没有能打开 PDF 的应用，可以用「分享/打印」发到别处。")
            } else {
                _pendingIntent.value = intent
            }
        }

        /** 分享或打印刚导出的 PDF。 */
        fun shareExported() {
            val info = _uiState.value.exportedFile ?: return
            val intent = sink.shareIntent(info, PDF_MIME)
            if (intent == null) {
                _uiState.value = _uiState.value.copy(message = "没有可用的分享入口，请检查文件是否还在。")
            } else {
                _pendingIntent.value = intent
            }
        }

        /** 界面已消费 Intent。 */
        fun consumePendingIntent() {
            _pendingIntent.value = null
        }

        /** 科目中文名转代码，供后续按科目取数据。 */
        fun subjectCode(): String = Subjects.codeOf(_uiState.value.selectedSubject)

        private companion object {
            /** PDF 的 MIME 类型。 */
            const val PDF_MIME: String = "application/pdf"
        }
    }
