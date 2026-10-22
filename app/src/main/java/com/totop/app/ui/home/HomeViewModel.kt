package com.totop.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.totop.app.data.repo.MistakeRepository
import com.totop.app.domain.model.Subjects
import com.totop.app.ui.mistakes.ReviewCandidate
import com.totop.app.ui.mistakes.ReviewGateway
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 首页 ViewModel。
 *
 * 只做三件事：汇总错题数、挑出需要多练的知识点、统计待复习数量。
 * 不调用任何模型，进入首页零 token 消耗。
 *
 * 失败策略：任何一项查询失败都不让整页空白，
 * 而是保留已拿到的部分并把原因写进 [HomeUiState.error]，
 * 让用户知道「为什么这里是 0」而不是看到一个空壳页面。
 */
@HiltViewModel
class HomeViewModel
    @Inject
    constructor(
        private val mistakes: MistakeRepository,
        private val reviewGateway: ReviewGateway,
        private val knowledgeRepository: com.totop.app.data.repo.KnowledgeRepository,
        private val clock: com.totop.app.domain.usecase.port.DomainClock,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(HomeUiState())
        val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

        init {
            refresh()
        }

        /** 重新汇总首页数据。 */
        fun refresh() {
            viewModelScope.launch {
                val errors = mutableListOf<String>()

                val total =
                    runCatching { mistakes.count() }
                        .getOrElse {
                            errors.add("错题数读取失败：" + describe(it))
                            0
                        }

                // 待复习：本机已排期且今天到期的条目数（零 token，纯本地排期）
                val due: List<ReviewCandidate> =
                    runCatching {
                        reviewGateway.dueCandidates(nowUtc = clock.nowUtcMillis())
                    }.getOrElse { failure ->
                        errors.add("复习计划读取失败：" + describe(failure))
                        emptyList()
                    }

                // 需要多练：按知识点统计错题次数，取前 3。
                // 统计窗口取「全部历史」，首页关心的是长期积累，而不是某一天。
                // 目录是异步加载的（首次会从 assets 种子数据建表），因此必须先 await。
                val catalog: com.totop.app.domain.model.KnowledgeCatalog? =
                    runCatching { knowledgeRepository.catalogEnsuringSeeded() }.getOrElse { failure ->
                        errors.add("知识点目录读取失败：" + describe(failure))
                        null
                    }
                // 目录拿不到时宁可少展示「还需要多练」，也要把错题数与待复习照常显示，
                // 避免一个次要模块失败导致整页空白。
                val weak =
                    runCatching {
                        mistakes
                            .kpointDistribution(fromUtc = 0L, toUtc = Long.MAX_VALUE)
                            .entries
                            .sortedByDescending { it.value }
                            .take(WEAK_LIMIT)
                            .mapNotNull { entry ->
                                val point =
                                    catalog?.knowledgePoint(entry.key)
                                        ?: return@mapNotNull null
                                WeakKpointItem(
                                    id = point.id,
                                    name = point.name,
                                    subjectLabel = Subjects.label(point.subject),
                                    mistakeCount = entry.value,
                                )
                            }
                    }.getOrElse {
                        errors.add("知识点统计失败：" + describe(it))
                        emptyList()
                    }

                _uiState.value =
                    HomeUiState(
                        greeting = _uiState.value.greeting,
                        subtitle = _uiState.value.subtitle,
                        mistakeCount = total,
                        weakCount = weak.size,
                        dueReviewCount = due.size,
                        overviewHint = buildHint(total, weak.size, due.size),
                        weakKpoints = weak,
                        loading = false,
                        error = errors.takeIf { it.isNotEmpty() }?.joinToString("；"),
                    )
            }
        }

        /** 把数字翻译成一句人话，避免只给整数没有解释。 */
        private fun buildHint(
            total: Int,
            weakCount: Int,
            dueCount: Int,
        ): String =
            when {
                total == 0 -> "还没有错题记录。拍下第一道不会的题，我从这里开始陪你。"
                weakCount > 0 && dueCount > 0 ->
                    "你在 " + weakCount + " 个知识点上错得比较多，今天有 " + dueCount + " 题值得再练一遍。"
                weakCount > 0 -> "你在 " + weakCount + " 个知识点上错得比较多，先把它们攻下来。"
                dueCount > 0 -> "今天有 " + dueCount + " 题到点该复习了。"
                else -> "错题都复习过了，保持这个节奏。"
            }

        private fun describe(throwable: Throwable): String =
            throwable.message?.takeIf { it.isNotBlank() } ?: throwable.javaClass.simpleName

        companion object {
            /** 首页最多展示几个需要多练的知识点。 */
            const val WEAK_LIMIT: Int = 3
        }
    }
