package com.totop.app.ui.knowledge

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.totop.app.R
import com.totop.app.ui.common.ALL_SUBJECTS
import com.totop.app.ui.common.SubjectTabs

/**
 * 知识点掌握度页（FR-3，M4）。
 *
 * 页面结构（自上而下）：
 * 1. 摘要行：平均掌握度 / 薄弱知识点数 / 累计错题；
 * 2. 雷达图：错题最多的 6-8 个知识点；
 * 3. 章节树热力列表：按章节折叠，行内红/黄/绿条（未练习为灰「待练习」）；
 * 4. 趋势折线：掌握度历史，降采样到 <= 60 点；
 * 5. 错因分布饼图：固定七类。
 *
 * 图表全部为 Canvas 静态绘制，无动画、无实时力导向（方案 7.1）。
 *
 * @param onOpenKnowledgePoint 跳转知识点详情
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KnowledgeScreen(
    onOpenKnowledgePoint: (kpointId: String) -> Unit,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    viewModel: KnowledgeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.bootstrap() }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.nav_knowledge)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = "重新计算")
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding =
                androidx.compose.foundation.layout.PaddingValues(
                    start = 14.dp,
                    end = 14.dp,
                    top = 8.dp,
                    bottom = 24.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "summary") { SummaryRow(uiState = uiState) }

            if (uiState.loading) {
                item(key = "loading") {
                    Row(modifier = Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                }
            }

            uiState.errorMessage?.let { message ->
                item(key = "error") { ErrorRow(message = message, onRetry = viewModel::refresh) }
            }

            if (uiState.hasNoMastery) {
                item(key = "empty") { EmptyRow() }
            }

            item(key = "radar") {
                ChartCard(title = "掌握度雷达", subtitle = "按错题最多的知识点排序，越靠外越熟练") {
                    RadarChart(data = uiState.radar)
                }
            }

            item(key = "trend") {
                ChartCard(
                    title = "掌握度趋势",
                    subtitle = KnowledgeViewModel.trendRangeText(uiState.trend),
                ) {
                    TrendChart(data = uiState.trend)
                }
            }

            item(key = "pie") {
                ChartCard(title = "错因分布", subtitle = "固定七类错因，全部本机统计") {
                    ErrorTypePieChart(data = uiState.pie)
                }
            }

            item(key = "heat-title") {
                Text(
                    text = "章节热力（红 <40 · 黄 40-70 · 绿 >70）",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            // 科目切换：十科不能平铺，先用一排可滚动的科目页签收敛范围，
            // 下面的章节只属于当前科目。
            if (uiState.subjectGroups.isNotEmpty()) {
                item(key = "subject-tabs") {
                    SubjectTabs(
                        available = uiState.subjectGroups.filter { it.hasData }.map { it.subject }.toSet(),
                        // 未勾选的科目已经从 subjectGroups 里滤掉，页签同步收敛
                        allowed = uiState.subjectGroups.map { it.subject }.toSet(),
                        selected = uiState.selectedSubject ?: ALL_SUBJECTS,
                        onSelect = { code ->
                            viewModel.selectSubject(if (code == ALL_SUBJECTS) null else code)
                        },
                    )
                }
            }

            val visible = uiState.visibleSubjectGroups
            if (visible.isEmpty() && !uiState.loading) {
                item(key = "heat-empty") {
                    Text(
                        text = "还没有知识点数据。首次启动会从内置知识点树载入，若长时间为空请检查安装包是否完整。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            items(items = visible, key = { it.subject }) { subjectGroup ->
                SubjectHeatSection(
                    group = subjectGroup,
                    // 默认收起：十科的章节全展开时，这一页先滚十几屏才看到自己那一科，
                    // 学生反馈「一进来全是展开的条框，不知道从哪看」。
                    // 只有明确点了某个科目页签（等于已经选定范围）时才默认展开。
                    expandedByDefault = uiState.selectedSubject != null,
                    onOpenKnowledgePoint = onOpenKnowledgePoint,
                )
            }
        }
    }
}

/** 摘要行。 */
@Composable
private fun SummaryRow(uiState: KnowledgeUiState) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = uiState.summaryText,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "离线结果 · 掌握度只由复习自评与错题频次算出，不引入任何机器评分口径。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

/** 图表卡片。 */
@Composable
private fun ChartCard(
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(text = title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        content()
    }
}

/**
 * 一个科目的热力区：科目头 + 科目内的章节折叠卡。
 *
 * 科目头显示该科目的平均掌握度与错题数，学生一眼能看出「哪科最弱」，
 * 而不是在一张跨十科的扁平列表里自己心算。
 */
@Composable
private fun SubjectHeatSection(
    group: SubjectHeatGroup,
    expandedByDefault: Boolean,
    onOpenKnowledgePoint: (String) -> Unit,
) {
    var expanded by rememberSaveable(group.subject) { mutableStateOf(expandedByDefault) }
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = group.subjectLabel,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                text =
                    if (group.scoredCount == 0) {
                        "待练习 · 错 " + group.mistakeTotal
                    } else {
                        "均 " + Math.round(group.averageScore) + " · 错 " + group.mistakeTotal
                    },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "折叠" else "展开",
                modifier = Modifier.size(18.dp),
            )
        }
        if (expanded) {
            group.chapters.forEach { chapter ->
                ChapterHeatCard(group = chapter, onOpenKnowledgePoint = onOpenKnowledgePoint)
            }
        }
    }
}

/** 章节折叠卡：默认收起，由学生自己点开。 */
@Composable
private fun ChapterHeatCard(
    group: ChapterHeatGroup,
    onOpenKnowledgePoint: (String) -> Unit,
) {
    // key 带上科目：不同科目完全可能同名章节（例如「函数」），
    // 只用章节名做 saveable key 会让两个科目的展开状态互相覆盖。
    var expanded by rememberSaveable(group.subject + "/" + group.chapter) { mutableStateOf(false) }
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = group.chapter,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "错 " + group.mistakeTotal + " · 均 " + Math.round(group.averageScore) + " · " + group.bandLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "折叠" else "展开",
                modifier = Modifier.size(18.dp),
            )
        }
        // 展开/折叠不做动画：方案 7.1 禁止大面积动画，直接切换可见性
        if (expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                group.rows.forEach { row ->
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable { onOpenKnowledgePoint(row.kpointId) }
                                .padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        HeatBar(row = row, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/** 错误态。 */
@Composable
private fun ErrorRow(
    message: String,
    onRetry: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text = message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        OutlinedButton(onClick = onRetry) { Text(text = "重试") }
    }
}

/** 空态。 */
@Composable
private fun EmptyRow() {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "掌握度还没有数据。掌握度来自两处本机记录：错题（按知识点归档）与复习自评。",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = "先做一道题并把它标为错题，或在「错题本 -> 复习」里勾选自评，这里就会出现雷达与热力。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "图表在数据出现后自动生成，无需手动刷新。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
