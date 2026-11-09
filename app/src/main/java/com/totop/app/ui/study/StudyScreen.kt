package com.totop.app.ui.study

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.totop.app.domain.model.ExamKind
import com.totop.app.domain.model.ExamRecord
import com.totop.app.domain.model.ScorePoint
import com.totop.app.domain.model.Subjects
import com.totop.app.domain.model.TrendEmptyReason
import com.totop.app.ui.components.SectionCard
import com.totop.app.ui.theme.Tokens
import kotlin.math.abs

/**
 * 学情页（原「学情报告」，docs/学情与成绩趋势.md）。
 *
 * 五段纵向结构，只有一个时间维度（学期）：
 * 头卡、趋势、考试列表、本学期概览、AI 解读。
 *
 * 明确不做：归因、过程趋势线、错因分布、折叠区。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudyScreen(
    onBack: () -> Unit = {},
    onOpenKnowledge: () -> Unit = {},
    viewModel: StudyViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<ExamRecord?>(null) }
    var entryOpen by remember { mutableStateOf(false) }
    var targetOpen by remember { mutableStateOf(false) }
    var termStartOpen by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = Tokens.Background,
        topBar = {
            TopAppBar(
                title = { Text(text = "学情") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            editing = null
                            entryOpen = true
                        },
                    ) { Text(text = "＋ 录入") }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp)
                    .padding(top = 6.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            FilterRow(
                state = state,
                onSubject = viewModel::selectSubject,
                onTerm = viewModel::selectTerm,
                onEditTermStart = { termStartOpen = true },
            )

            state.errorMessage?.let { message ->
                ErrorBanner(message = message, onDismiss = viewModel::dismissError)
            }

            HeaderCard(state = state, onTargetClick = { targetOpen = true })

            when {
                state.loading -> LoadingCard()

                state.trend.emptyReason == TrendEmptyReason.NO_RECORDS ->
                    EmptyCard(
                        title = "本学期还没有成绩记录",
                        body = "录一次月考或周测，这里就会出现你的得分率和排名变化。",
                        actionText = "录入成绩",
                        onAction = {
                            editing = null
                            entryOpen = true
                        },
                    )

                state.trend.emptyReason == TrendEmptyReason.NO_COMPLETE_EXAM ->
                    EmptyCard(
                        title = "还没有一次考全所选科目的记录",
                        body = completeExamHint(state.effectiveSubjects),
                        actionText = "录入整场成绩",
                        onAction = {
                            editing = null
                            entryOpen = true
                        },
                    )

                else -> {
                    TrendCard(state = state, onKind = viewModel::selectKind, onOpenKnowledge = onOpenKnowledge)
                    ExamListCard(
                        exams = state.exams,
                        onEdit = { record ->
                            editing = record
                            entryOpen = true
                        },
                    )
                    OverviewCard(overview = state.overview)
                }
            }

            if (state.trend.hasPoints) {
                AiCard(
                    state = state.ai,
                    onGenerate = viewModel::generateNarration,
                )
            }
        }
    }

    if (entryOpen) {
        ExamEntryDialog(
            initial = editing,
            subjects = state.mySubjects.ifEmpty { state.subjectOptions },
            defaultFullScoreOf = state::fullScoreOf,
            defaultClassSize = state.classSize,
            defaultGradeSize = state.gradeSize,
            onDismiss = { entryOpen = false },
            onSave = { record ->
                viewModel.save(record) { entryOpen = false }
            },
            onDelete =
                editing?.let { record ->
                    {
                        viewModel.delete(record.id)
                        entryOpen = false
                    }
                },
        )
    }

    if (targetOpen) {
        ValueDialog(
            title = if (state.selectedSubject == null) "全局目标" else state.subjectLabel + " 目标",
            hint = "填得分率百分比，例如 85",
            initial = state.currentTarget?.let { formatRate(it) }.orEmpty(),
            numeric = true,
            onDismiss = { targetOpen = false },
            onConfirm = { raw ->
                val value = raw.toDoubleOrNull()
                if (value == null || value <= 0.0 || value > 100.0) {
                    false
                } else {
                    viewModel.setTarget(state.selectedSubject, value)
                    targetOpen = false
                    true
                }
            },
        )
    }

    if (termStartOpen) {
        ValueDialog(
            title = "本学期开学日",
            hint = "格式 2026-09-01；留空则按 2 月 / 8 月自动推断",
            initial = state.termStartOverride?.let { formatFullDay(it) }.orEmpty(),
            numeric = false,
            onDismiss = { termStartOpen = false },
            onConfirm = { raw ->
                if (raw.isBlank()) {
                    viewModel.setTermStart(null)
                    termStartOpen = false
                    true
                } else {
                    val day = parseFullDay(raw)
                    if (day == null) {
                        false
                    } else {
                        viewModel.setTermStart(day)
                        termStartOpen = false
                        true
                    }
                }
            },
        )
    }
}

/**
 * 单值输入弹窗（目标分、开学日共用）。
 *
 * [onConfirm] 返回 false 表示输入不合法，弹窗保持打开并给出提示。
 */
@Composable
private fun ValueDialog(
    title: String,
    hint: String,
    initial: String,
    numeric: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Boolean,
) {
    var text by remember { mutableStateOf(initial) }
    var invalid by remember { mutableStateOf(false) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
        text = {
            Column {
                androidx.compose.material3.OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        invalid = false
                    },
                    singleLine = true,
                    label = { Text(text = if (numeric) "百分比" else "日期") },
                )
                Text(
                    text = if (invalid) "输入不合法，请检查后再保存" else hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (invalid) Tokens.Error else Tokens.OnSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (!onConfirm(text)) invalid = true
                },
            ) { Text(text = "保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = "取消") }
        },
    )
}

/** 顶部两个下拉：科目、学期。 */
@Composable
private fun FilterRow(
    state: StudyUiState,
    onSubject: (String?) -> Unit,
    onTerm: (Int) -> Unit,
    onEditTermStart: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PickerField(
            text = state.subjectLabel,
            modifier = Modifier.weight(1f),
        ) { dismiss ->
            DropdownMenuItem(
                text = { Text(text = "全部科目") },
                onClick = {
                    onSubject(null)
                    dismiss()
                },
            )
            state.subjectOptions.forEach { code ->
                DropdownMenuItem(
                    text = { Text(text = Subjects.label(code)) },
                    onClick = {
                        onSubject(code)
                        dismiss()
                    },
                )
            }
        }
        PickerField(
            text = state.selectedTerm?.label ?: "本学期",
            modifier = Modifier.weight(1f),
        ) { dismiss ->
            state.terms.forEachIndexed { index, term ->
                DropdownMenuItem(
                    text = { Text(text = term.label) },
                    onClick = {
                        onTerm(index)
                        dismiss()
                    },
                )
            }
            DropdownMenuItem(
                text = {
                    Text(
                        text = "改本学期开学日…",
                        style = MaterialTheme.typography.labelMedium,
                        color = Tokens.OnSurfaceVariant,
                    )
                },
                onClick = {
                    dismiss()
                    onEditTermStart()
                },
            )
        }
    }
}

/** 通用下拉：一个胶囊 + DropdownMenu。 */
@Composable
private fun PickerField(
    text: String,
    modifier: Modifier = Modifier,
    items: @Composable (dismiss: () -> Unit) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(Tokens.SurfaceVariant, RoundedCornerShape(50))
                    .clickable { open = true }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = Tokens.OnSurface,
                modifier = Modifier.weight(1f),
            )
            Text(text = "▾", style = MaterialTheme.typography.labelLarge, color = Tokens.OnSurfaceVariant)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            items { open = false }
        }
    }
}

/** 头卡：得分率 + 排名 + 目标进度。 */
@Composable
private fun HeaderCard(
    state: StudyUiState,
    onTargetClick: () -> Unit,
) {
    val trend = state.trend
    val latest = trend.latest
    SectionCard(modifier = Modifier) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = latest?.let { formatRate(it.rate) + "%" } ?: "—",
                fontSize = 34.sp,
                fontWeight = FontWeight.SemiBold,
                color = Tokens.OnSurface,
            )
            Spacer(Modifier.width(8.dp))
            val delta = trend.delta
            if (delta != null) {
                val positive = delta >= 0
                Text(
                    text = (if (positive) "▲ " else "▼ ") + String.format(java.util.Locale.CHINA, "%.1f", abs(delta)),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (positive) Tokens.Success else Tokens.Error,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = if (state.selectedSubject == null) "各科等权平均" else "该科得分率",
                style = MaterialTheme.typography.labelSmall,
                color = Tokens.OnSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        RankLine(latest = latest, subjectView = state.selectedSubject != null)

        // 目标进度
        val target = state.currentTarget
        if (target != null) {
            val rate = latest?.rate ?: 0.0
            val progress = (rate / target).toFloat().coerceIn(0f, 1f)
            Column(modifier = Modifier.clickable(onClick = onTargetClick)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "目标 " + formatRate(target) + "%",
                        style = MaterialTheme.typography.labelMedium,
                        color = Tokens.OnSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = targetHint(trend.remainingToTarget, rate, target),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (rate >= target) Tokens.Success else Tokens.OnSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    color = if (rate >= target) Tokens.Success else Tokens.Primary,
                    trackColor = Tokens.SurfaceVariant,
                )
            }
        } else {
            // 从未设过目标时也必须给出入口：旧实现整块目标区只在 target 非空时渲染，
            // 结果是「没设过就永远没有地方设」，学生找不到入口。
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onTargetClick)
                        .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "设一个目标分",
                    style = MaterialTheme.typography.labelMedium,
                    color = Tokens.Primary,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = if (state.selectedSubject == null) "全局目标 ›" else state.subjectLabel + " 目标 ›",
                    style = MaterialTheme.typography.labelMedium,
                    color = Tokens.Primary,
                )
            }
        }
    }
}

/** 目标提示文案。 */
private fun targetHint(
    remaining: Int?,
    rate: Double,
    target: Double,
): String =
    when {
        rate >= target -> "已达成"
        // 这里是「得分率差几个点」，不是卷面分差多少分：写「29 分」会被理解成
        // 150 分制还差 29 分（其实差 43.5 分），单位必须是百分比
        remaining == null -> "还差 " + formatRate(target - rate) + "%"
        remaining == 0 -> "已达成"
        else -> "还差 " + formatRate(target - rate) + "% · 约 " + remaining + " 次"
    }

/** 排名行：年级为主，班级为辅。 */
@Composable
private fun RankLine(
    latest: ScorePoint?,
    subjectView: Boolean,
) {
    if (latest == null) return
    val prefix = if (subjectView) "该科" else "总分"
    val parts = ArrayList<String>(2)
    latest.gradeRank?.let { rank ->
        val suffix = latest.gradePercentile?.let { "（前 " + it + "%）" }.orEmpty()
        val sizeText = latest.gradeSize?.let { " / " + it }.orEmpty()
        parts.add("年级 " + rank + sizeText + suffix)
    }
    latest.classRank?.let { rank ->
        val sizeText = latest.classSize?.let { " / " + it }.orEmpty()
        parts.add("班级 " + rank + sizeText)
    }
    if (parts.isEmpty()) {
        Text(
            text = prefix + "排名：未填",
            style = MaterialTheme.typography.labelMedium,
            color = Tokens.OnSurfaceVariant,
        )
    } else {
        Text(
            text = prefix + " · " + parts.joinToString("　"),
            style = MaterialTheme.typography.labelMedium,
            color = Tokens.OnSurfaceVariant,
        )
    }
}

/** 趋势卡。 */
@Composable
private fun TrendCard(
    state: StudyUiState,
    onKind: (ExamKind?) -> Unit,
    onOpenKnowledge: () -> Unit,
) {
    SectionCard(
        title = "趋势",
        trailing = {
            PickerField(
                text =
                    when (state.kindFilter) {
                        null -> "全部"
                        ExamKind.MAJOR -> "大考"
                        ExamKind.MINOR -> "小考"
                    },
                modifier = Modifier.width(84.dp),
            ) { dismiss ->
                listOf<Pair<String, ExamKind?>>(
                    "全部" to null,
                    "大考" to ExamKind.MAJOR,
                    "小考" to ExamKind.MINOR,
                ).forEach { (label, kind) ->
                    DropdownMenuItem(
                        text = { Text(text = label) },
                        onClick = {
                            onKind(kind)
                            dismiss()
                        },
                    )
                }
            }
        },
    ) {
        TrendChart(points = state.trend.points, target = state.currentTarget)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "● 大考　○ 小考　-- 目标",
                style = MaterialTheme.typography.labelSmall,
                color = Tokens.OnSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "多练的知识点 →",
                style = MaterialTheme.typography.labelMedium,
                color = Tokens.Primary,
                modifier = Modifier.clickable(onClick = onOpenKnowledge),
            )
        }
        if (state.trend.points.size == 1) {
            Text(
                text = "再录一次考试就能看到变化。",
                style = MaterialTheme.typography.labelSmall,
                color = Tokens.OnSurfaceVariant,
            )
        }
    }
}

/** 考试列表：点一行进入修改。 */
@Composable
private fun ExamListCard(
    exams: List<ExamRecord>,
    onEdit: (ExamRecord) -> Unit,
) {
    SectionCard(title = "每次考得怎么样") {
        if (exams.isEmpty()) {
            Text(
                text = "本学期还没有记录。",
                style = MaterialTheme.typography.bodyMedium,
                color = Tokens.OnSurfaceVariant,
            )
            return@SectionCard
        }
        exams.take(MAX_LIST_ROWS).forEach { record ->
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable { onEdit(record) }
                        .padding(vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = formatMonthDay(record.occurredOn),
                    style = MaterialTheme.typography.labelMedium,
                    color = Tokens.OnSurfaceVariant,
                    modifier = Modifier.width(46.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = record.displayName.ifEmpty { record.kind.label },
                        style = MaterialTheme.typography.bodyMedium,
                        color = Tokens.OnSurface,
                    )
                    Text(
                        text = record.kind.label + " · " + record.countableScores.size + " 科",
                        style = MaterialTheme.typography.labelSmall,
                        color = Tokens.OnSurfaceVariant,
                    )
                }
                Text(
                    text = record.averageRate?.let { formatRate(it) + "%" } ?: "—",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = Tokens.OnSurface,
                )
                Text(
                    text = record.gradeRank?.let { "  年级 " + it } ?: "",
                    style = MaterialTheme.typography.labelMedium,
                    color = Tokens.OnSurfaceVariant,
                    modifier = Modifier.width(72.dp),
                )
            }
        }
        if (exams.size > MAX_LIST_ROWS) {
            Text(
                text = "仅显示最近 " + MAX_LIST_ROWS + " 次。",
                style = MaterialTheme.typography.labelSmall,
                color = Tokens.OnSurfaceVariant,
            )
        }
    }
}

/** 本学期概览：四项行为指标，与成绩无关。 */
@Composable
private fun OverviewCard(overview: ExamOverview) {
    SectionCard(title = "本学期概览") {
        Row(modifier = Modifier.fillMaxWidth()) {
            OverviewCell("新增错题", overview.mistakeCount.toString() + " 道", Modifier.weight(1f))
            OverviewCell("完成复习", overview.reviewDoneCount.toString() + " 次", Modifier.weight(1f))
            OverviewCell("学习时长", overview.studyText, Modifier.weight(1f))
            OverviewCell("活跃天数", overview.activeDays.toString() + " 天", Modifier.weight(1f))
        }
    }
}

@Composable
private fun OverviewCell(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = Tokens.OnSurface,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Tokens.OnSurfaceVariant,
        )
    }
}

/** AI 解读卡：手动生成，失败降级为纯数据版。 */
@Composable
private fun AiCard(
    state: StudyAiState,
    onGenerate: () -> Unit,
) {
    SectionCard(
        title = "解读与建议",
        trailing = {
            TextButton(onClick = onGenerate) {
                Text(
                    text = if (state is StudyAiState.Done) "重新生成" else "生成解读",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        },
    ) {
        when (state) {
            is StudyAiState.Idle ->
                Text(
                    text = "点右上角生成一段基于最近几次考试的说明，只给正向建议，不点名知识点。不点就不消耗任何 token。",
                    style = MaterialTheme.typography.bodySmall,
                    color = Tokens.OnSurfaceVariant,
                )

            is StudyAiState.Running ->
                Text(
                    text = "正在生成…",
                    style = MaterialTheme.typography.bodySmall,
                    color = Tokens.OnSurfaceVariant,
                )

            is StudyAiState.Done -> {
                Text(text = state.text, style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = "以上内容由 AI 生成，仅供参考；其中的数字来自本机统计。",
                    style = MaterialTheme.typography.labelSmall,
                    color = Tokens.OnSurfaceVariant,
                )
            }

            is StudyAiState.Failed ->
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = Tokens.Error,
                )
        }
    }
}

/** 加载骨架（沿用项目「轻骨架、无动画」的做法）。 */
@Composable
private fun LoadingCard() {
    SectionCard {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .background(Tokens.SurfaceVariant, RoundedCornerShape(Tokens.RadiusSmall)),
        )
        Text(
            text = "正在读取成绩…",
            style = MaterialTheme.typography.bodySmall,
            color = Tokens.OnSurfaceVariant,
        )
    }
}

/** 空态。 */
@Composable
private fun EmptyCard(
    title: String,
    body: String,
    actionText: String,
    onAction: () -> Unit,
) {
    SectionCard(title = title) {
        Text(text = body, style = MaterialTheme.typography.bodyMedium, color = Tokens.OnSurface)
        OutlinedButton(onClick = onAction) { Text(text = actionText) }
    }
}

/** 错误提示条。 */
@Composable
private fun ErrorBanner(
    message: String,
    onDismiss: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(Tokens.ErrorContainer, RoundedCornerShape(Tokens.RadiusSmall))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = Tokens.Error,
            modifier = Modifier.weight(1f),
        )
        Button(onClick = onDismiss) { Text(text = "知道了") }
    }
}

/** 列表最多显示的条数。 */
private const val MAX_LIST_ROWS: Int = 20

/** 「全部科目」空态文案：把当前口径里的科目名念出来，学生才知道该录哪几科。 */
private fun completeExamHint(subjects: Set<String>): String {
    if (subjects.isEmpty()) return "「全部科目」的折线只统计各科都录入过的考试，可以先切到单科看趋势。"
    val names = Subjects.ORDER.filter { subjects.contains(it) }.joinToString("、") { Subjects.label(it) }
    return "「全部科目」的折线只统计" + names + " 都录入过的考试。只考了一两科的场次请切到单科查看。"
}
