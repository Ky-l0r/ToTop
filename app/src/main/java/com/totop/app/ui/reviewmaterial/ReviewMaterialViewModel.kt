package com.totop.app.ui.reviewmaterial

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.totop.app.data.prefs.SecretStore
import com.totop.app.data.repo.MistakeRepository
import com.totop.app.domain.model.Subjects
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/** 列表页（选科目 + 生成 + 历史）的状态。 */
data class ReviewMaterialUiState(
    /** 可选科目：十科全部列出（按课程顺序），不再写死「全部 + 数学优先」。 */
    val subjects: List<String> = Subjects.ALL_LABELS,
    val selectedSubject: String = Subjects.label("math"),
    /** 生成状态；Success 只用来把按钮切成「重新生成」，正文渲染在详情页。 */
    val materialState: ReviewMaterialState = ReviewMaterialState.Idle,
    /** 以往的复习资料，最新在前；生成成功后会立刻刷新。 */
    val history: List<ReviewMaterialSummary> = emptyList(),
    /** 历史列表是否还在读取。 */
    val historyLoading: Boolean = false,
    /** 操作反馈，一次性提示。 */
    val message: String? = null,
) {
    /** 当前选中的科目代码。 */
    val selectedCode: String get() = Subjects.codeOf(selectedSubject)
}

/**
 * 复习资料列表页 ViewModel。
 *
 * 拆页后职责收窄为：选科目 → 生成 → 存档 → 让界面进详情。
 * 正文预览与 PDF 导出全部移到 [ReviewMaterialDetailViewModel]——
 * 列表页不再为「预读一份上万字的资料」付出 IO 与内存，
 * 点击历史项也不再需要先把整份资料 decode 出来。
 *
 * 生成流程与解题流程同构：取本地错题 → 组装提示词 → 调用模型 → 严格校验 JSON → 失败可重试。
 */
@HiltViewModel
class ReviewMaterialViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val gateway: ReviewMaterialGateway,
        private val secretStore: SecretStore,
        private val mistakeRepository: MistakeRepository,
        private val questionRepository: com.totop.app.data.repo.QuestionRepository,
        private val insights: com.totop.app.data.repo.CompanionInsightRepository,
        private val noteRepository: com.totop.app.data.repo.StudyNoteRepository,
        private val materialStore: ReviewMaterialStore,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(ReviewMaterialUiState())
        val uiState: StateFlow<ReviewMaterialUiState> = _uiState.asStateFlow()

        /**
         * 生成成功后的一次性跳转信号。
         *
         * 用 Channel 而不是 StateFlow：导航事件只能被消费一次，
         * 存进 StateFlow 会在旋转/重建时重放，造成反复进详情页。
         */
        private val _openDetail = Channel<Long>(Channel.BUFFERED)
        val openDetail: Flow<Long> get() = _openDetail.receiveAsFlow()

        init {
            // 一进页面就把历史读出来：学生退出后再进来，最想看到的是「上次那份还在不在」。
            refreshHistory()
        }

        /** 重新读取历史列表。 */
        fun refreshHistory() {
            viewModelScope.launch {
                _uiState.value = _uiState.value.copy(historyLoading = true)
                val history = materialStore.list()
                _uiState.value = _uiState.value.copy(history = history, historyLoading = false)
            }
        }

        /** 删除一份历史资料。 */
        fun deleteHistory(createdAtUtc: Long) {
            viewModelScope.launch {
                val removed = materialStore.delete(createdAtUtc)
                _uiState.value =
                    _uiState.value.copy(
                        message = if (removed) "已删除这份资料。" else "删除失败，文件可能被占用。",
                    )
                refreshHistory()
            }
        }

        fun selectSubject(label: String) {
            _uiState.value =
                _uiState.value.copy(
                    selectedSubject = label,
                    materialState = ReviewMaterialState.Idle,
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
                // 取材池大小：报告「本科目共 N 道，本次纳入 M 道」用。
                // 与 mistakes.size 分开取，因为一次最多只喂 8 道，只报 8 会让人以为错题本里就这么多。
                val available =
                    runCatching { mistakeRepository.countBySubject(Subjects.codeOf(subjectLabel)) }
                        .getOrDefault(mistakes.size)
                // 提示词外置在 assets：读不到就是资源问题，直接说清楚，
                // 不要用一个空 system 提示词让模型自由发挥。
                val systemPrompt =
                    runCatching { ReviewMaterialPrompt.load(context) }.getOrElse { failure ->
                        _uiState.value =
                            _uiState.value.copy(
                                materialState =
                                    ReviewMaterialState.Failed(
                                        message =
                                            "读取复习资料模板失败：" + (failure.message ?: ReviewMaterialPrompt.ASSET_PATH),
                                        retryable = false,
                                    ),
                            )
                        return@launch
                    }
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
                                systemPrompt = systemPrompt,
                                forgottenPoints = loadForgotten(subjectLabel),
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
                    is ReviewMaterialResult.Success -> {
                        // 补上「取材池有多大」：资料正文与卷首说明都按这个数写。
                        val material =
                            materialWithNotes(
                                outcome.material.copy(
                                    mistakesAvailable = maxOf(available, outcome.material.mistakesUsed),
                                ),
                                subjectLabel,
                            )
                        // 先存档，存档成功才发跳转：详情页按时间戳读文件，
                        // 存档失败还跳过去只会看到一个「读取不到」的空页面。
                        // 失败原因必须在这里说清楚，不能包装成「模型失败请重试」——
                        // 那会诱导学生再付一次生成费用。
                        val saved = materialStore.save(material)
                        val origin =
                            when {
                                material.mistakesUsed <= 0 ->
                                    "该科目暂时没有错题记录，这份资料是按通用要点生成的。"
                                material.mistakesAvailable > material.mistakesUsed ->
                                    "本科目共 " + material.mistakesAvailable + " 道错题，本次纳入最近 " +
                                        material.mistakesUsed + " 道。"
                                else -> "已根据你错过的 " + material.mistakesUsed + " 道题生成。"
                            }
                        _uiState.value =
                            _uiState.value.copy(
                                materialState = ReviewMaterialState.Success(material),
                                message =
                                    origin +
                                        if (saved) {
                                            "已存入「以往的复习资料」。"
                                        } else {
                                            "本地存档失败，这份资料暂时打不开；可以重新生成，不会重复扣模型的费用。"
                                        },
                            )
                        refreshHistory()
                        if (saved) _openDetail.send(material.createdAtUtc)
                    }
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
         * 把「我的笔记」作为一节插进资料里。
         *
         * 这一节刻意不交给模型写：笔记是学生自己的原话，模型一转述就变成「系统总结」，
         * 复习时反而认不出来；而且笔记根本不在提示词里，让模型写只能靠编。
         * 因此由客户端原样拼进去，位置按定稿放在「易忘点回顾」之后。
         */
        private suspend fun materialWithNotes(
            material: ReviewMaterial,
            subjectLabel: String,
        ): ReviewMaterial {
            val notes =
                runCatching { noteRepository.forReviewMaterial(Subjects.codeOf(subjectLabel)) }
                    .getOrDefault(emptyList())
            if (notes.isEmpty()) return material
            val section =
                ReviewSection(
                    title = NOTES_SECTION_TITLE,
                    blocks = listOf(ReviewBlock(ReviewBlockStyle.PARAGRAPH, listOf(NOTES_INTRO))),
                    subsections =
                        notes.map { note ->
                            ReviewSubsection(
                                title = note.title,
                                blocks =
                                    listOf(
                                        ReviewBlock(
                                            ReviewBlockStyle.PARAGRAPH,
                                            note.body.lines().filter { it.isNotBlank() },
                                        ),
                                    ),
                            )
                        },
                )
            val sections = material.sections.toMutableList()
            // 找不到「易忘点」就放到最后：位置表是产品口径，但缺了这一章也不该让整节消失。
            val forgottenIndex = sections.indexOfFirst { it.title.contains(FORGOTTEN_KEYWORD) }
            sections.add(if (forgottenIndex >= 0) forgottenIndex + 1 else sections.size, section)
            return material.copy(sections = sections)
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
                        // 错因类型已在入库时归一为固定七类，直接透传给模型，
                        // 让它按「同类错因」合并归纳，而不是逐题复述。
                        errorType = record.errorType.label,
                    )
                }
            }.getOrDefault(emptyList())

        /** 题干按 questionId 读取；失败返回空串，让模型按错因推断即可。 */
        private suspend fun questionStemOf(questionId: String): String =
            runCatching { questionRepository.byId(questionId)?.stemText.orEmpty() }.getOrDefault("")

        /** 科目中文名转代码，供后续按科目取数据。 */
        fun subjectCode(): String = Subjects.codeOf(_uiState.value.selectedSubject)

        /**
         * 取本科目的易忘点。
         *
         * 只取前 6 条：复习资料是一份「考前看一眼」的材料，
         * 塞进二十个知识点反而没人看，也没法为每一条给出提醒。
         */
        private suspend fun loadForgotten(subjectLabel: String): List<String> =
            runCatching {
                val subjectCode = Subjects.codeOf(subjectLabel)
                insights.forgottenOfSubject(subjectCode).take(MAX_FORGOTTEN).map { it.name }
            }.getOrDefault(emptyList())

        private companion object {
            /** 一次最多纳入几条易忘点，见 loadForgotten 的说明。 */
            const val MAX_FORGOTTEN: Int = 6

            /** 「我的笔记」这一节的标题。 */
            const val NOTES_SECTION_TITLE: String = "我的笔记"

            /**
             * 这一节的开场说明。
             *
             * 写死这句是产品口径：让学生一眼看出「这些是我自己问过的」，
             * 而不是又一段系统推荐的通用要点。
             */
            const val NOTES_INTRO: String = "以下是你自己在陪伴作业里问过、核过的内容，原文照录，没有改写。"

            /** 用来定位「我的笔记」该插在谁的后面。 */
            const val FORGOTTEN_KEYWORD: String = "易忘点"
        }
    }
