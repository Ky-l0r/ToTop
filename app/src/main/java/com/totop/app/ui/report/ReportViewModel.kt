package com.totop.app.ui.report

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.totop.app.data.repo.SettingsRepository
import com.totop.app.domain.usecase.BuildWeeklyReportUseCase
import com.totop.app.domain.usecase.ReportOutcome
import com.totop.app.domain.usecase.ReportPeriod
import com.totop.app.domain.usecase.ReportStats
import com.totop.app.domain.usecase.port.DomainClock
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 长图/PDF 导出状态。 */
sealed interface ReportExportState {
    /** 尚未导出。 */
    data object Idle : ReportExportState

    /** 渲染中。 */
    data object Running : ReportExportState

    /** 渲染成功。 */
    data class Done(val report: RenderedReport) : ReportExportState

    /** 渲染失败（必然带可读原因，禁止静默失败）。 */
    data class Failed(val message: String) : ReportExportState
}

/** 周报页状态（FR-5）。 */
data class ReportUiState(
    val period: ReportPeriod = ReportPeriod.WEEKLY,
    val loading: Boolean = true,
    val stats: ReportStats? = null,
    val markdown: String = "",
    val aiComment: String? = null,
    /** AI 解读被请求但失败，已降级为纯统计版（FR-5.4）。 */
    val aiDegraded: Boolean = false,
    /** 用户对本次生成的 AI 解读开关（默认取设置值）。 */
    val aiEnabled: Boolean = false,
    val noDataMessage: String? = null,
    val errorMessage: String? = null,
    val exportState: ReportExportState = ReportExportState.Idle,
    /** 生成本次报告用到的 token（AI 关闭或失败时为 0）。 */
    val exportTip: String = "",
) {
    /** 标题。 */
    val title: String get() = if (period == ReportPeriod.WEEKLY) "学情周报" else "学情月报"

    /** 是否有可展示的报告。 */
    val hasReport: Boolean get() = stats != null && markdown.isNotBlank()

    /** AI 解读状态说明（必须显式标注 AI 生成）。 */
    val aiStatusText: String
        get() =
            when {
                aiComment != null -> "AI 解读（AI 生成，仅供参考）"
                aiDegraded -> "AI 解读失败，已降级为纯统计版"
                aiEnabled -> "AI 解读生成中或未开启"
                else -> "纯统计版（AI 解读已关闭）"
            }
}

/**
 * 周报 / 月报 ViewModel（FR-5）。
 *
 * 关键约束：
 * 1. **数字全部来自模板**：统计与 Markdown 都由 domain 的 [BuildWeeklyReportUseCase] 产出，
 *    UI 不重新计算任何指标，也不允许 AI 改写数字；
 * 2. **AI 解读可关闭**：关闭时不发起任何调用，正文最后一节直接消失（纯统计版）；
 * 3. **失败不静默**：无论统计失败还是 AI 失败，页面都给出可读原因与下一步动作；
 * 4. **不直接调用 Retrofit**：AI 解读经 [com.totop.app.domain.usecase.port.ReportNarrator] 端口，
 *    其实现只使用 data 层的 LlmClient 门面。
 */
@HiltViewModel
class ReportViewModel
    @Inject
    constructor(
        private val useCaseFactory: ReportUseCaseFactory,
        private val renderer: ReportImageRenderer,
        private val settingsRepository: SettingsRepository,
        private val clock: DomainClock,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(ReportUiState())
        val uiState: StateFlow<ReportUiState> = _uiState.asStateFlow()

        /** 首屏：读设置里的 AI 开关，再按开关生成。 */
        fun bootstrap() {
            if (_uiState.value.hasReport) return
            viewModelScope.launch {
                val enabled =
                    runCatching {
                        settingsRepository.getBoolean(SettingsRepository.Keys.REPORT_AI_ENABLED, false)
                    }.getOrDefault(false)
                _uiState.value = _uiState.value.copy(aiEnabled = enabled)
                generate()
            }
        }

        /** 切换周报 / 月报。 */
        fun setPeriod(period: ReportPeriod) {
            if (_uiState.value.period == period) return
            _uiState.value = _uiState.value.copy(period = period, exportState = ReportExportState.Idle)
            generate()
        }

        /** 临时切换 AI 解读（不改设置，仅本次生成生效）。 */
        fun toggleAi(enabled: Boolean) {
            _uiState.value = _uiState.value.copy(aiEnabled = enabled)
            generate()
        }

        /** 重新生成。 */
        fun regenerate() {
            generate()
        }

        private fun generate() {
            val period = _uiState.value.period
            val aiEnabled = _uiState.value.aiEnabled
            _uiState.value = _uiState.value.copy(loading = true, errorMessage = null, noDataMessage = null)
            viewModelScope.launch {
                try {
                    val outcome =
                        useCaseFactory.create().build(
                            period = period,
                            nowUtc = clock.nowUtcMillis(),
                            forceAiComment = aiEnabled,
                        )
                    when (outcome) {
                        is ReportOutcome.Generated ->
                            _uiState.value =
                                _uiState.value.copy(
                                    loading = false,
                                    stats = outcome.stats,
                                    markdown = outcome.markdown,
                                    aiComment = outcome.aiComment,
                                    aiDegraded = outcome.aiDegraded,
                                    noDataMessage = null,
                                    errorMessage = null,
                                    exportTip =
                                        if (outcome.aiDegraded) {
                                            "AI 解读失败，已输出纯统计版；数字部分不受影响。"
                                        } else {
                                            ""
                                        },
                                )
                        is ReportOutcome.NoData ->
                            _uiState.value =
                                _uiState.value.copy(
                                    loading = false,
                                    stats = null,
                                    markdown = "",
                                    aiComment = null,
                                    aiDegraded = false,
                                    noDataMessage = outcome.userMessage + "（周期：" + outcome.periodKey + "）",
                                    errorMessage = null,
                                )
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (throwable: Throwable) {
                    _uiState.value =
                        _uiState.value.copy(
                            loading = false,
                            errorMessage = "生成报告失败：" + describe(throwable),
                        )
                }
            }
        }

        /** 导出长图或 PDF（本地渲染，不联网）。 */
        fun export(format: ReportExportFormat) {
            val state = _uiState.value
            if (!state.hasReport || state.exportState is ReportExportState.Running) return
            _uiState.value = state.copy(exportState = ReportExportState.Running)
            viewModelScope.launch {
                try {
                    val stem = state.title + "_" + (state.stats?.periodKey ?: "")
                    val report =
                        when (format) {
                            ReportExportFormat.PNG ->
                                renderer.renderLongImage(
                                    title = state.title,
                                    markdown = state.markdown,
                                    fileNameStem = stem,
                                )
                            ReportExportFormat.PDF ->
                                renderer.renderPdf(
                                    title = state.title,
                                    markdown = state.markdown,
                                    fileNameStem = stem,
                                )
                        }
                    _uiState.value = _uiState.value.copy(exportState = ReportExportState.Done(report))
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (throwable: Throwable) {
                    _uiState.value =
                        _uiState.value.copy(
                            exportState = ReportExportState.Failed("导出失败：" + describe(throwable)),
                        )
                }
            }
        }

        /** 关闭导出结果提示。 */
        fun dismissExport() {
            _uiState.value = _uiState.value.copy(exportState = ReportExportState.Idle)
        }

        private fun describe(throwable: Throwable): String =
            throwable.message?.takeIf { it.isNotBlank() } ?: throwable.javaClass.simpleName
    }

/**
 * 报告用例工厂（每次生成新建一个用例实例，保证时间基准一致且无状态残留）。
 *
 * 之所以用工厂而不是直接持有单例用例：UseCase 内部会在 build() 时读取设置与写库，
 * 复用同一实例容易把上一次的强制开关带进下一次。
 */
class ReportUseCaseFactory(
    private val useCase: BuildWeeklyReportUseCase,
) {
    fun create(): BuildWeeklyReportUseCase = useCase
}
