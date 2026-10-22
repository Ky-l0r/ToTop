package com.totop.app.ui.mistakes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.totop.app.data.repo.KnowledgeRepository
import com.totop.app.data.repo.MistakeRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 错题本页状态（FR-4.1）。
 *
 * @param items 已加载的错题卡片（分页累积）
 * @param total 满足筛选条件的总条数
 * @param loading 首屏加载中（骨架 + 文案）
 * @param loadingMore 追加下一页中
 * @param errorMessage 失败原因；非空时页面必须显示可读提示，禁止静默失败
 */
data class MistakesUiState(
    val items: List<MistakeCardUi> = emptyList(),
    val total: Int = 0,
    val filter: MistakeFilter = MistakeFilter(),
    val subjectOptions: List<String> = emptyList(),
    /** 当前科目下可选的知识点（随科目变化级联收敛）。 */
    val kpointOptions: List<KpointOption> = emptyList(),
    /** 全量知识点（未按科目收敛）；切换科目时用它在内存里重新收敛，避免重复查库。 */
    val allKpoints: List<com.totop.app.domain.model.KnowledgePoint> = emptyList(),
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val errorMessage: String? = null,
    val lastQueryElapsedMs: Long = 0L,
) {
    /** 是否还有下一页。 */
    val hasMore: Boolean get() = items.size < total

    /** 空数据且不在加载中：用于「本机还没有错题」与「筛选没有命中」两种空态的区分。 */
    val isEmpty: Boolean get() = !loading && items.isEmpty() && errorMessage == null

    /** 空态文案：有筛选时提示放宽条件，否则提示先去解题。 */
    val emptyMessage: String
        get() =
            if (filter.isActive) {
                "当前筛选条件下没有错题。可以清除筛选，或换一个关键词再试。"
            } else {
                "本机还没有错题记录。先去「解题」页拍一道做错的题，系统会把它收进这里。"
            }

    /** 顶部计数文案。 */
    val countText: String
        get() =
            when {
                loading -> "正在读取本机错题…"
                errorMessage != null -> "读取失败"
                total == 0 -> "共 0 道"
                else -> "共 " + total + " 道" + if (items.size < total) "（已加载 " + items.size + " 道）" else ""
            }
}

/**
 * 错题本 ViewModel（FR-4.1 / FR-4.5）。
 *
 * 架构约束：
 * - **不直接调用 Retrofit / OkHttp**：错题本完全离线，数据只来自 Room；
 * - 列表查询经 [MistakeBrowseGateway] 参数化分页，ViewModel 不接触 SQL；
 * - 每次筛选 / 搜索变化都从第 0 页重查，翻页只追加，避免重复行；
 * - 任何失败都写入 [MistakesUiState.errorMessage] 并在页面展示，禁止静默失败。
 */
@HiltViewModel
class MistakesViewModel
    @Inject
    constructor(
        private val gateway: MistakeBrowseGateway,
        private val mistakeRepository: MistakeRepository,
        private val knowledgeRepository: KnowledgeRepository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(MistakesUiState())
        val uiState: StateFlow<MistakesUiState> = _uiState.asStateFlow()

        private var loadJob: Job? = null

        private var searchJob: Job? = null

        /** 首屏初始化：载入筛选选项 + 第一页。 */
        fun bootstrap() {
            if (_uiState.value.subjectOptions.isNotEmpty() || _uiState.value.items.isNotEmpty()) return
            viewModelScope.launch {
                loadOptions()
                reload()
            }
        }

        // ---------- 筛选 ----------

        /**
         * 切换科目。
         *
         * 同时把知识点候选收敛到该科目，并在「已选的知识点不属于新科目」时清掉它。
         * 不清掉会出现「科目=物理、知识点=文言文虚词」这种必然零结果的组合，
         * 用户看到的是一片空白却不知道为什么。
         */
        fun setSubject(subject: String?) {
            val current = _uiState.value
            val options = kpointOptionsFor(current.allKpoints, subject)
            val keptKpoint =
                current.filter.kpointId?.takeIf { id -> options.any { it.id == id } }
            val filter =
                current.filter
                    .withSubject(subject)
                    .let { if (it.kpointId == keptKpoint) it else it.withKpoint(keptKpoint) }
            _uiState.value =
                _uiState.value.copy(
                    filter = filter,
                    items = emptyList(),
                    total = 0,
                    kpointOptions = options,
                )
            reload()
        }

        /** 切换知识点。 */
        fun setKpoint(kpointId: String?) {
            applyFilter(_uiState.value.filter.withKpoint(kpointId))
        }

        /** 切换错因。 */
        fun setErrorType(errorType: com.totop.app.domain.model.ErrorType?) {
            applyFilter(_uiState.value.filter.withErrorType(errorType))
        }

        /** 切换时间窗口。 */
        fun setTimeWindow(window: TimeWindow) {
            applyFilter(_uiState.value.filter.withTimeWindow(window))
        }

        /** 清除全部筛选（知识点候选同时恢复为全部科目）。 */
        fun clearFilter() {
            _uiState.value =
                _uiState.value.copy(
                    filter = MistakeFilter(),
                    items = emptyList(),
                    total = 0,
                    kpointOptions = kpointOptionsFor(_uiState.value.allKpoints, null),
                )
            reload()
        }

        /**
         * 关键词输入。
         *
         * 输入时立即更新界面（受控输入框），并在 [SEARCH_DEBOUNCE_MS] 后才查库，
         * 避免每敲一个字都发一次查询。
         */
        fun setKeyword(keyword: String) {
            val filter = _uiState.value.filter.withKeyword(keyword)
            _uiState.value = _uiState.value.copy(filter = filter)
            searchJob?.cancel()
            searchJob =
                viewModelScope.launch {
                    delay(SEARCH_DEBOUNCE_MS)
                    reload()
                }
        }

        /** 立即执行关键词搜索（键盘搜索键）。 */
        fun submitSearch() {
            searchJob?.cancel()
            reload()
        }

        private fun applyFilter(filter: MistakeFilter) {
            _uiState.value = _uiState.value.copy(filter = filter, items = emptyList(), total = 0)
            reload()
        }

        // ---------- 读取 ----------

        /** 从第 0 页重新加载。 */
        fun reload() {
            loadJob?.cancel()
            val filter = _uiState.value.filter
            _uiState.value = _uiState.value.copy(loading = true, errorMessage = null, items = emptyList(), total = 0)
            loadJob =
                viewModelScope.launch {
                    val startedAt = System.currentTimeMillis()
                    try {
                        val page =
                            gateway.page(
                                filter = filter,
                                page = 0,
                                pageSize = PAGE_SIZE,
                                nowUtc = System.currentTimeMillis(),
                            )
                        _uiState.value =
                            _uiState.value.copy(
                                items = page.items,
                                total = page.total,
                                loading = false,
                                loadingMore = false,
                                errorMessage = null,
                                lastQueryElapsedMs = System.currentTimeMillis() - startedAt,
                            )
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (throwable: Throwable) {
                        _uiState.value =
                            _uiState.value.copy(
                                loading = false,
                                loadingMore = false,
                                errorMessage = "读取错题本失败：" + describe(throwable),
                            )
                    }
                }
        }

        /** 追加下一页（滚动到底部触发）。 */
        fun loadMore() {
            val state = _uiState.value
            if (state.loading || state.loadingMore || !state.hasMore) return
            val nextPage = state.items.size / PAGE_SIZE
            _uiState.value = state.copy(loadingMore = true)
            viewModelScope.launch {
                try {
                    val page =
                        gateway.page(
                            filter = state.filter,
                            page = nextPage,
                            pageSize = PAGE_SIZE,
                            nowUtc = System.currentTimeMillis(),
                        )
                    val merged = mergeDistinct(state.items, page.items)
                    _uiState.value =
                        _uiState.value.copy(
                            items = merged,
                            total = page.total,
                            loadingMore = false,
                            errorMessage = null,
                        )
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (throwable: Throwable) {
                    _uiState.value =
                        _uiState.value.copy(
                            loadingMore = false,
                            errorMessage = "加载更多错题失败：" + describe(throwable),
                        )
                }
            }
        }

        /** 删除一道错题（长按菜单）。 */
        fun deleteMistake(mistakeId: String) {
            viewModelScope.launch {
                try {
                    mistakeRepository.delete(mistakeId)
                    reload()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (throwable: Throwable) {
                    _uiState.value = _uiState.value.copy(errorMessage = "删除失败：" + describe(throwable))
                }
            }
        }

        private suspend fun loadOptions() {
            val subjects = runCatching { gateway.subjects() }.getOrDefault(emptyList())
            val all =
                runCatching {
                    knowledgeRepository.catalogEnsuringSeeded().allKnowledgePoints()
                        .filter { it.parentId != null }
                        .sortedWith(compareBy({ it.chapter }, { it.orderIndex }))
                }.getOrDefault(emptyList())
            _uiState.value =
                _uiState.value.copy(
                    subjectOptions = subjects,
                    kpointOptions = kpointOptionsFor(all, null),
                    allKpoints = all,
                )
        }

        private fun describe(throwable: Throwable): String =
            throwable.message?.takeIf { it.isNotBlank() } ?: throwable.javaClass.simpleName

        companion object {
            /** 每页条数：一屏约 8-10 张卡片，20 条足够两屏。 */
            const val PAGE_SIZE: Int = 20

            /** 搜索防抖（毫秒）。 */
            const val SEARCH_DEBOUNCE_MS: Long = 280L

            /** 合并两页结果并按 mistakeId 去重（翻页期间有新数据写入时可能重复）。 */
            fun mergeDistinct(
                existing: List<MistakeCardUi>,
                incoming: List<MistakeCardUi>,
            ): List<MistakeCardUi> {
                if (incoming.isEmpty()) return existing
                val seen = existing.mapTo(HashSet()) { it.mistakeId }
                val merged = ArrayList<MistakeCardUi>(existing.size + incoming.size)
                merged += existing
                incoming.forEach { card -> if (seen.add(card.mistakeId)) merged += card }
                return merged
            }

            /**
             * 按当前科目收敛知识点候选（纯函数，可单测）。
             *
             * 修复的问题：以前知识点下拉永远是**全部十科混在一起**，
             * 选完「数学」再点知识点，列表里照样出现文言文、化学方程式这些节点，
             * 学生点进去必然一条都查不到，只会以为筛选坏了。
             *
             * @param all 全量知识点（已过滤掉章节根节点）
             * @param subject 当前选中的科目代码；null 表示不限
             */
            fun kpointOptionsFor(
                all: List<com.totop.app.domain.model.KnowledgePoint>,
                subject: String?,
            ): List<KpointOption> {
                val scoped =
                    if (subject.isNullOrBlank()) {
                        all
                    } else {
                        all.filter { it.subject.equals(subject, ignoreCase = true) }
                    }
                return scoped
                    .sortedWith(compareBy({ it.subject }, { it.chapter }, { it.orderIndex }))
                    .map { KpointOption(id = it.id, name = it.name) }
            }
        }
    }
