package com.totop.app.ui.reviewmaterial

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.totop.app.data.prefs.SecretStore
import com.totop.app.data.repo.MistakeRepository
import com.totop.app.domain.model.Subjects
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class ReviewMaterialUiState(
    val subjects: List<String> = listOf("全部", "语文", "数学", "英语", "物理", "化学", "生物", "政治", "历史", "地理"),
    val selectedSubject: String = "数学",
    val materialState: ReviewMaterialState = ReviewMaterialState.Idle,
    /** 操作反馈（导出成功等），一次性提示。 */
    val message: String? = null,
    /** 最近一次导出的 PDF 绝对路径，供打印/分享使用。 */
    val lastPdfPath: String? = null,
)

/**
 * 复习资料 ViewModel。
 *
 * 生成流程与解题流程同构：
 * 取本地错题 → 组装提示词 → 调用模型 → 严格校验 JSON → 失败可重试。
 * 复用同一套错误分类与重试语义，「模型没通」和「生成失败」在界面上表达一致。
 */
@HiltViewModel
class ReviewMaterialViewModel
    @Inject
    constructor(
        @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
        private val gateway: ReviewMaterialGateway,
        private val secretStore: SecretStore,
        private val mistakeRepository: MistakeRepository,
        private val questionRepository: com.totop.app.data.repo.QuestionRepository,
    ) : ViewModel() {
        private val pdfWriter = ReviewMaterialPdfWriter()

        private val _uiState = MutableStateFlow(ReviewMaterialUiState())
        val uiState: StateFlow<ReviewMaterialUiState> = _uiState.asStateFlow()

        fun selectSubject(label: String) {
            _uiState.value =
                _uiState.value.copy(
                    selectedSubject = label,
                    materialState = ReviewMaterialState.Idle,
                    lastPdfPath = null,
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
                    if (subjectLabel == "全部") {
                        mistakeRepository.page(com.totop.app.data.repo.PageRequest.FIRST)
                    } else {
                        mistakeRepository.pageBySubject(
                            subject = subjectCode,
                            limit = ReviewMaterialGateway.MAX_MISTAKES,
                            offset = 0,
                        )
                    }
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
         * 失败时把具体原因写进 message（例如磁盘不可写），
         * 而不是笼统提示「导出失败」，便于用户判断是权限问题还是空间不足。
         */
        fun exportPdf() {
            val material =
                (_uiState.value.materialState as? ReviewMaterialState.Success)?.material
                    ?: return
            viewModelScope.launch {
                when (val result = pdfWriter.write(context, material)) {
                    is PdfWriteResult.Success ->
                        _uiState.value =
                            _uiState.value.copy(
                                message = "已生成 PDF：" + result.file.name,
                                lastPdfPath = result.file.absolutePath,
                            )
                    is PdfWriteResult.Failure ->
                        _uiState.value = _uiState.value.copy(message = "导出失败：" + result.reason)
                }
            }
        }

        /** 科目中文名转代码，供后续按科目取数据。 */
        fun subjectCode(): String = Subjects.codeOf(_uiState.value.selectedSubject)
    }
