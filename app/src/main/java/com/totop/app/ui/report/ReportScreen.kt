package com.totop.app.ui.report

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.totop.app.R
import com.totop.app.domain.usecase.ReportPeriod
import com.totop.app.domain.usecase.ReportStats

/**
 * 学情周报 / 月报（FR-5，M5）。
 *
 * 页面结构：
 * 1. 周期切换（周报 / 月报）+ AI 解读开关（默认取设置值）；
 * 2. 六项指标卡片：新增错题、复发 Top、错因分布、复习完成率、学习时长、活跃天数；
 *    数字全部由 domain 的模板渲染，页面只负责排版；
 * 3. AI 解读段落：明确标注「AI 生成」，失败时降级为纯统计版并说明原因；
 * 4. 导出：本地长图（PNG）与 PDF，渲染完全离线。
 *
 * 文案约束：全页不出现评判性表述，只描述学习行为本身（对应测试 ReportScreenLogicTest 的断言）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    viewModel: ReportViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.bootstrap() }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.nav_report)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(onClick = viewModel::regenerate) { Text(text = "重新生成") }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PeriodSelector(
                period = uiState.period,
                aiEnabled = uiState.aiEnabled,
                onSelectPeriod = viewModel::setPeriod,
                onToggleAi = viewModel::toggleAi,
            )

            OfflineBanner()

            when {
                uiState.loading -> Row(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }

                uiState.errorMessage != null -> ErrorCard(
                    message = uiState.errorMessage.orEmpty(),
                    onRetry = viewModel::regenerate,
                )

                uiState.noDataMessage != null -> NoDataCard(
                    message = uiState.noDataMessage.orEmpty(),
                    onRetry = viewModel::regenerate,
                )

                else -> uiState.stats?.let { stats ->
                    StatsOverview(stats = stats, period = uiState.period)
                    RecurringKpoints(stats = stats)
                    ErrorTypeBreakdown(stats = stats)
                    AiCommentCard(
                        aiComment = uiState.aiComment,
                        aiDegraded = uiState.aiDegraded,
                        aiEnabled = uiState.aiEnabled,
                        statusText = uiState.aiStatusText,
                        statusTip = uiState.exportTip,
                    )
                    ExportCard(
                        state = uiState.exportState,
                        onExportPng = { viewModel.export(ReportExportFormat.PNG) },
                        onExportPdf = { viewModel.export(ReportExportFormat.PDF) },
                        onDismiss = viewModel::dismissExport,
                    )
                }
            }
        }
    }
}

/** 周期与 AI 开关。 */
@Composable
private fun PeriodSelector(
    period: ReportPeriod,
    aiEnabled: Boolean,
    onSelectPeriod: (ReportPeriod) -> Unit,
    onToggleAi: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReportPeriod.entries.forEach { candidate ->
                FilterChip(
                    selected = candidate == period,
                    onClick = { onSelectPeriod(candidate) },
                    label = { Text(text = candidate.label) },
                )
            }
            Box(modifier = Modifier.weight(1f))
            Icon(
                imageVector = Icons.Filled.Refresh,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "AI 解读", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = "关闭后输出纯统计版，0 token；开启会额外调用一次模型写约 200 字建议。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = aiEnabled, onCheckedChange = onToggleAi)
        }
    }
}

/** 离线标注（FR-6.5）。 */
@Composable
private fun OfflineBanner() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            text = "离线统计 · 报告数字全部由本机 daily_stats 汇总生成，不上传任何数据。",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 六项指标总览。 */
@Composable
private fun StatsOverview(stats: ReportStats, period: ReportPeriod) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = (if (period == ReportPeriod.WEEKLY) "学情周报 · " else "学情月报 · ") + stats.periodKey,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        MetricRow(label = "新增错题", value = stats.newMistakeCount.toString() + " 道")
        MetricRow(label = "完成复习", value = stats.reviewDoneCount.toString() + " 次")
        MetricRow(label = "复习完成率", value = stats.reviewRateText)
        MetricRow(label = "学习时长", value = stats.studyDurationText)
        MetricRow(label = "活跃天数", value = stats.activeDays.toString() + " / " + stats.periodDays + " 天")
        MetricRow(
            label = "错因记录",
            value = stats.errorTypeDistribution.sumOf { it.count }.toString() + " 条",
        )
    }
}

/** 单行指标。 */
@Composable
private fun MetricRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** 复发 Top 知识点。 */
@Composable
private fun RecurringKpoints(stats: ReportStats) {
    SectionCard(title = "复发 Top（本期重复出错）") {
        if (stats.recurringKpoints.isEmpty()) {
            Text(
                text = "本期没有重复出错的知识点，保持住。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            stats.recurringKpoints.forEachIndexed { index, item ->
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = (index + 1).toString() + ". " + item.name,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "错 " + item.count + " 次",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/** 错因分布。 */
@Composable
private fun ErrorTypeBreakdown(stats: ReportStats) {
    SectionCard(title = "错因分布（固定七类）") {
        val nonZero = stats.errorTypeDistribution.filter { it.count > 0 }
        if (nonZero.isEmpty()) {
            Text(
                text = "本期没有记录到错因。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }
        val max = nonZero.maxOf { it.count }
        nonZero.sortedByDescending { it.count }.forEach { item ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.label,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.size(width = 96.dp, height = 20.dp),
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .size(height = 10.dp, width = 0.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(5.dp)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(item.count.toFloat() / max.toFloat())
                            .size(height = 10.dp, width = 0.dp)
                            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(5.dp)),
                    )
                }
                Text(
                    text = " " + item.count + " 次",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

/** AI 解读卡（必须标注 AI 生成）。 */
@Composable
private fun AiCommentCard(
    aiComment: String?,
    aiDegraded: Boolean,
    aiEnabled: Boolean,
    statusText: String,
    statusTip: String,
) {
    SectionCard(title = "解读与建议") {
        Text(
            text = statusText,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        when {
            aiComment != null -> {
                Text(text = aiComment, style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = "以上解读由 AI 生成，仅供参考；其中的数字来自本机统计。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            aiDegraded -> Text(
                text = statusTip.ifBlank { "AI 解读失败，已输出纯统计版。" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            !aiEnabled -> Text(
                text = "AI 解读已关闭：本报告只有数字，不消耗任何 token。可以在上方开关按需打开。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> Text(
                text = "正在生成 AI 解读…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 导出卡：本地长图 / PDF。 */
@Composable
private fun ExportCard(
    state: ReportExportState,
    onExportPng: () -> Unit,
    onExportPdf: () -> Unit,
    onDismiss: () -> Unit,
) {
    SectionCard(title = "保存与分享") {
        Text(
            text = "导出在本机完成：长图适合发群，PDF 适合打印。文件写入应用私有目录，只在系统分享时临时授权。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onExportPng, enabled = state !is ReportExportState.Running) {
                Icon(
                    imageVector = Icons.Filled.Share,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Text(text = " 导出长图")
            }
            OutlinedButton(onClick = onExportPdf, enabled = state !is ReportExportState.Running) {
                Icon(
                    imageVector = Icons.Filled.PictureAsPdf,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Text(text = " 导出 PDF")
            }
        }
        when (state) {
            is ReportExportState.Idle -> Unit
            is ReportExportState.Running -> Text(
                text = "正在本机渲染…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            is ReportExportState.Done -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "已生成：" + state.report.file.name,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = state.report.format.label + " · " + formatSize(state.report.sizeBytes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onDismiss) { Text(text = "知道了") }
            }
            is ReportExportState.Failed -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = onDismiss) { Text(text = "知道了") }
            }
        }
    }
}

/** 文件大小展示。 */
private fun formatSize(bytes: Long): String {
    if (bytes < 1024L) return bytes.toString() + " B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return String.format(java.util.Locale.CHINA, "%.1f KB", kb)
    return String.format(java.util.Locale.CHINA, "%.1f MB", kb / 1024.0)
}

/** 无数据。 */
@Composable
private fun NoDataCard(message: String, onRetry: () -> Unit) {
    SectionCard(title = "这一周期还没有学习记录") {
        Text(text = message, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = "先去做一道题，或去错题本完成一次复习，本页会自动有数据。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = onRetry) { Text(text = "重新读取") }
    }
}

/** 错误。 */
@Composable
private fun ErrorCard(message: String, onRetry: () -> Unit) {
    SectionCard(title = "生成失败") {
        Text(text = message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        OutlinedButton(onClick = onRetry) { Text(text = "重试") }
    }
}

/** 统一卡片。 */
@Composable
private fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text = title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        content()
    }
}
