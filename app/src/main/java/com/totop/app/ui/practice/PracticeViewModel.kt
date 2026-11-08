package com.totop.app.ui.practice

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.totop.app.data.prefs.SecretStore
import com.totop.app.data.remote.ExerciseGateway
import com.totop.app.data.repo.ExerciseRepository
import com.totop.app.data.repo.MistakeRepository
import com.totop.app.data.repo.PageRequest
import com.totop.app.domain.model.AnswerText
import com.totop.app.domain.model.Exercise
import com.totop.app.domain.model.ExerciseDifficulty
import com.totop.app.domain.model.MistakeStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/** 刷题页状态。 */
data class PracticeUiState(
    val loading: Boolean = true,
    val message: String? = null,
    val kpointId: String = "",
    val kpointName: String = "",
    /** 当前第几题（0 起）。 */
    val index: Int = 0,
    val total: Int = 0,
    val stem: String = "",
    val answer: String = "",
    val revealed: Boolean = false,
    /** 学生自己写的答案（可以不写，直接看答案）。 */
    val studentAnswer: String = "",
    /** 自评「对上了」的题数。 */
    val matched: Int = 0,
    val finished: Boolean = false,
    val passed: Boolean = false,
) {
    /** 「第 2 / 3 题」。没有题时为空串，避免出现「第 1 / 0 题」。 */
    val progressLabel: String
        get() = if (total <= 0) "" else "第 " + (index + 1) + " / " + total + " 题"

    val canReveal: Boolean get() = !loading && !finished && !revealed && stem.isNotBlank()
}

/**
 * 变式刷题与过关（0.7.0）。
 *
 * 流程按定稿：一次三题 → 学生先作答 → 看答案（只展示，不判对错）→ 自评对上 / 没对上
 * → 三题里对上两题即记为过关。**刻意不做自动判卷**：答案写法千差万别，
 * 让模型判「对不对」既贵又不可靠，而这个应用从第一天起就不做判卷。
 *
 * 出题必须过「独立重解校验」：模型先出题、再只看题干重算一遍，答案一致才给学生做。
 * 不一致的题直接丢掉——宁可少一道题，也不要让学生在错答案上练。
 */
@HiltViewModel
class PracticeViewModel
    @Inject
    constructor(
        private val secretStore: SecretStore,
        private val gateway: ExerciseGateway,
        private val exerciseRepository: ExerciseRepository,
        private val mistakeRepository: MistakeRepository,
        private val knowledgeRepository: com.totop.app.data.repo.KnowledgeRepository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(PracticeUiState())
        val uiState: StateFlow<PracticeUiState> = _uiState.asStateFlow()

        private var questions: List<ExerciseGateway.Draft> = emptyList()
        private var loadedKey: String = ""

        /** 载入并按知识点出题（幂等：同一知识点不重复出题）。 */
        fun start(
            kpointId: String,
            kpointName: String,
        ) {
            val key = kpointId + "|" + kpointName
            if (loadedKey == key && !_uiState.value.loading) return
            loadedKey = key
            _uiState.value = PracticeUiState(loading = true, kpointId = kpointId, kpointName = kpointName)
            viewModelScope.launch {
                // 路由只带知识点 ID：名字从受控树里取，避免把中文名编码进路由（编码错一处就是乱码标题）。
                val resolvedName =
                    kpointName.ifBlank {
                        runCatching {
                            knowledgeRepository.catalogEnsuringSeeded().knowledgePoint(kpointId)?.name
                        }.getOrNull().orEmpty()
                    }
                _uiState.value = _uiState.value.copy(kpointName = resolvedName)
                val credentials = runCatching { secretStore.read() }.getOrNull()
                if (credentials == null || !credentials.canCall) {
                    _uiState.value =
                        _uiState.value.copy(
                            loading = false,
                            message = "还没配置模型。请到设置页填写 Base URL、API Key 与模型名，再来刷题。",
                        )
                    return@launch
                }
                val generated =
                    runCatching {
                        gateway.generate(
                            credentials = credentials,
                            kpointName = resolvedName,
                            difficultyLabel = ExerciseDifficulty.MEDIUM.label,
                            count = QUESTION_COUNT,
                            sourceStem = "",
                            requestId = UUID.randomUUID().toString(),
                        )
                    }.getOrElse { failure ->
                        _uiState.value =
                            _uiState.value.copy(
                                loading = false,
                                message = "出题失败：" + (failure.message ?: "未知原因"),
                            )
                        return@launch
                    }
                // 逐题独立重解校验：不一致的题不进刷题队列。
                val verified =
                    generated.filter { draft ->
                        val derived =
                            runCatching {
                                gateway.solveIndependently(
                                    credentials = credentials,
                                    stem = draft.stem,
                                    requestId = UUID.randomUUID().toString(),
                                )
                            }.getOrNull()
                        derived != null && AnswerText.sameAs(derived, draft.answer)
                    }
                if (verified.isEmpty()) {
                    _uiState.value =
                        _uiState.value.copy(
                            loading = false,
                            message = "这次的题没通过独立重算校验，一道都没留下。再点一次「重新出题」。",
                        )
                    return@launch
                }
                questions = verified
                persist(verified, kpointId)
                _uiState.value =
                    _uiState.value.copy(
                        loading = false,
                        message = null,
                        total = verified.size,
                        index = 0,
                        stem = verified.first().stem,
                        answer = verified.first().answer,
                        revealed = false,
                        studentAnswer = "",
                    )
            }
        }

        fun updateStudentAnswer(text: String) {
            _uiState.value = _uiState.value.copy(studentAnswer = text)
        }

        /** 看答案：只展示，不判断对错。 */
        fun reveal() {
            val state = _uiState.value
            if (!state.canReveal) return
            _uiState.value = state.copy(revealed = true)
        }

        /**
         * 自评：对上了 / 没对上。
         *
         * 最后一题答完就结算：三题里对上两题算过关。为什么是 2/3 而不是全对：
         * 变式题本来就比原题难一档，全对门槛会把「基本会了」的学生卡在门外。
         */
        fun rate(matched: Boolean) {
            val state = _uiState.value
            if (!state.revealed || state.finished) return
            val matchedCount = if (matched) state.matched + 1 else state.matched
            val next = state.index + 1
            if (next < questions.size) {
                val question = questions[next]
                _uiState.value =
                    state.copy(
                        index = next,
                        stem = question.stem,
                        answer = question.answer,
                        revealed = false,
                        studentAnswer = "",
                        matched = matchedCount,
                    )
                return
            }
            val passed = matchedCount * QUESTION_COUNT >= questions.size * PASS_NUMERATOR
            _uiState.value = state.copy(matched = matchedCount, finished = true, passed = passed, revealed = false)
            if (passed) markUnderstood(state.kpointId)
        }

        /** 重新出题：清掉当前队列，重新走一遍生成与校验。 */
        fun retry() {
            loadedKey = ""
            start(_uiState.value.kpointId, _uiState.value.kpointName)
        }

        /** 入库：只存通过校验的题，供知识点详情页统计「已通过校验的练习题」。 */
        private fun persist(
            drafts: List<ExerciseGateway.Draft>,
            kpointId: String,
        ) {
            viewModelScope.launch {
                val now = System.currentTimeMillis()
                runCatching {
                    exerciseRepository.saveAll(
                        drafts.map { draft ->
                            Exercise(
                                id = UUID.randomUUID().toString(),
                                kpointId = kpointId,
                                stem = draft.stem,
                                answer = draft.answer,
                                difficulty = ExerciseDifficulty.MEDIUM,
                                verified = true,
                                createdAtUtc = now,
                            )
                        },
                    )
                }
            }
        }

        /**
         * 过关：把这个知识点下最近一道错题标成「理解」。
         *
         * 为什么标错题而不是新建一条记录：错题状态是学生能看见、能筛选的东西，
         * 过关的实际意义就是「这道题我不用再复习了」。没有错题时什么都不做。
         */
        private fun markUnderstood(kpointId: String) {
            viewModelScope.launch {
                runCatching {
                    val latest = mistakeRepository.pageByKpoint(kpointId, PageRequest(page = 0, size = 1)).firstOrNull()
                    if (latest != null) mistakeRepository.setStatus(latest.id, MistakeStatus.UNDERSTOOD)
                }
            }
        }

        private companion object {
            /** 一次出三题：两题对上即过关，第三题是缓冲。 */
            const val QUESTION_COUNT: Int = 3

            /** 过关比例：对上 2 / 3。 */
            const val PASS_NUMERATOR: Int = 2
        }
    }
