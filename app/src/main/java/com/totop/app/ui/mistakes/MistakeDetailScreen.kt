package com.totop.app.ui.mistakes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.totop.app.domain.model.StudentStepStatus
import com.totop.app.ui.theme.Tokens

/**
 * 错题回顾页（回答「我到底错在哪里」）。
 *
 * 版式（自上而下，与学生的疑问顺序一致）：
 * 1. 原题：题干 + 已知 + 求 + 选项 + 原图（点开可放大看细节）；
 * 2. 我当时写的：学生自己的步骤，逐条标出对/错/缺；
 * 3. 错因：AI 定位到的第一个错误 + 下次该怎么想；
 * 4. 标准解答：完整步骤与最终答案；
 * 5. 这题考的是：知识点标签。
 *
 * 与旧版的根本差别：**这是只读的回顾**，不会把学生丢进做题流程。
 * 想重做那道题，从复习入口走。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MistakeDetailScreen(
    mistakeId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MistakeDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(mistakeId) { viewModel.load(mistakeId) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("错题回顾") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (state.detail != null) {
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = "删除这道错题")
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            when {
                state.loading -> LoadingState()
                state.errorMessage != null ->
                    ErrorState(message = state.errorMessage.orEmpty(), onRetry = { viewModel.load(mistakeId) })
                state.notFound -> EmptyState(onBack = onBack)
                else -> state.detail?.let { detail -> DetailContent(detail) }
            }
        }
    }

    if (confirmDelete) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除这道错题？") },
            text = { Text("删除后无法恢复，相关的知识点掌握度会重新计算。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        viewModel.delete(onDone = onBack)
                    },
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun DetailContent(detail: MistakeDetailUi) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        MetaRow(detail)
        QuestionCard(detail)
        StudentWorkCard(detail)
        ErrorCard(detail)
        SolutionCard(detail)
        if (detail.kpointNames.isNotEmpty()) KpointCard(detail)
        Spacer(Modifier.height(16.dp))
    }
}

/** 顶部元信息：科目 · 时间 · 复发徽标。 */
@Composable
private fun MetaRow(detail: MistakeDetailUi) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = detail.subjectLabel,
            style = MaterialTheme.typography.labelLarge,
            color = Tokens.Primary,
            fontWeight = FontWeight.Medium,
        )
        if (detail.grade.isNotBlank()) {
            Text(
                text = "  ·  " + detail.grade,
                style = MaterialTheme.typography.labelMedium,
                color = Tokens.OnSurfaceVariant,
            )
        }
        Spacer(Modifier.weight(1f))
        if (detail.recurrenceIndex > 1) {
            Box(
                modifier =
                    Modifier
                        .background(Tokens.ErrorContainer, RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                Text(
                    text = "第 " + detail.recurrenceIndex + " 次错",
                    style = MaterialTheme.typography.labelSmall,
                    color = Tokens.Error,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/** 原题卡：题干、已知、求、选项，以及可点开的原图。 */
@Composable
private fun QuestionCard(detail: MistakeDetailUi) {
    DetailSection(title = "原题") {
        if (detail.stemText.isNotBlank()) {
            com.totop.app.ui.components.FormulaText(
                text = detail.stemText,
                style = MaterialTheme.typography.bodyLarge,
            )
        } else {
            Text(
                text = "（这道题没有保存题干，可能只存了图片）",
                style = MaterialTheme.typography.bodySmall,
                color = Tokens.OnSurfaceVariant,
            )
        }
        if (detail.known.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "已知",
                style = MaterialTheme.typography.labelMedium,
                color = Tokens.OnSurfaceVariant,
            )
            detail.known.forEach { item ->
                com.totop.app.ui.components.FormulaText(
                    text = "· " + item,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        if (detail.ask.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            com.totop.app.ui.components.FormulaText(
                text = "求：" + detail.ask,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        if (detail.options.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            detail.options.forEach { option ->
                com.totop.app.ui.components.FormulaText(
                    text = option,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        detail.imagePath?.let { path ->
            Spacer(Modifier.height(10.dp))
            OriginalImage(path = path)
        }
    }
}

/** 原图：点一下在页面内放大/还原，不引入第三方图片查看库。 */
@Composable
private fun OriginalImage(path: String) {
    var expanded by remember { mutableStateOf(false) }
    val height = if (expanded) 480.dp else 180.dp
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(height)
                .clip(RoundedCornerShape(Tokens.RadiusMedium))
                .background(Tokens.SurfaceVariant)
                .clickable { expanded = !expanded },
        contentAlignment = Alignment.Center,
    ) {
        coil.compose.AsyncImage(
            model = java.io.File(path),
            contentDescription = "这道题的原图",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
        Text(
            text = if (expanded) "点一下缩小" else "点一下放大",
            style = MaterialTheme.typography.labelSmall,
            color = Tokens.OnSurfaceVariant,
            modifier =
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .background(Tokens.Surface.copy(alpha = 0.85f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/** 「我当时写的」：逐条标出对/错/缺。 */
@Composable
private fun StudentWorkCard(detail: MistakeDetailUi) {
    DetailSection(title = "我当时写的") {
        if (!detail.hasStudentWork) {
            Text(
                text =
                    "这次没有留下解题过程，所以没法定位具体错在哪一步。" +
                        "下次先把想到的写下来，哪怕是错的，我也能看出你卡在哪儿。",
                style = MaterialTheme.typography.bodyMedium,
                color = Tokens.OnSurfaceVariant,
            )
            return@DetailSection
        }
        detail.studentSteps.forEach { step ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StepBadge(status = step.status)
                com.totop.app.ui.components.FormulaText(
                    text = step.content,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun StepBadge(status: StudentStepStatus) {
    val color =
        when (status) {
            StudentStepStatus.CORRECT -> Tokens.Success
            StudentStepStatus.WRONG -> Tokens.Error
            StudentStepStatus.MISSING -> Tokens.Warning
        }
    val label =
        when (status) {
            StudentStepStatus.CORRECT -> "对"
            StudentStepStatus.WRONG -> "错"
            StudentStepStatus.MISSING -> "缺"
        }
    Box(
        modifier = Modifier.size(20.dp).clip(CircleShape).background(color.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = color, fontWeight = FontWeight.Bold)
    }
}

/** 错因卡：第一个错误 + 下次怎么想。 */
@Composable
private fun ErrorCard(detail: MistakeDetailUi) {
    DetailSection(title = "错在哪里", accent = Tokens.Error) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier =
                    Modifier
                        .background(Tokens.ErrorContainer, RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                Text(
                    text = detail.errorTypeLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = Tokens.Error,
                    fontWeight = FontWeight.Medium,
                )
            }
            detail.firstErrorStepIdx?.let { stepIdx ->
                Text(
                    text = "  第 " + stepIdx + " 步",
                    style = MaterialTheme.typography.labelMedium,
                    color = Tokens.OnSurfaceVariant,
                )
            }
        }
        if (detail.errorExplain.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            com.totop.app.ui.components.FormulaText(
                text = detail.errorExplain,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** 标准解答卡。 */
@Composable
private fun SolutionCard(detail: MistakeDetailUi) {
    DetailSection(title = "标准解答") {
        if (detail.steps.isEmpty()) {
            Text(
                text = "这道题的详细步骤没有保存下来，只有最终答案。",
                style = MaterialTheme.typography.bodySmall,
                color = Tokens.OnSurfaceVariant,
            )
        } else {
            detail.steps.forEach { step ->
                Column(modifier = Modifier.padding(vertical = 3.dp)) {
                    com.totop.app.ui.components.FormulaText(
                        text = step.idx.toString() + ". " + step.content,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (step.why.isNotBlank()) {
                        com.totop.app.ui.components.FormulaText(
                            text = "为什么：" + step.why,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        val answer = detail.finalAnswer.ifBlank { detail.steps.lastOrNull()?.content.orEmpty() }
        if (detail.finalAnswer.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "答案：" + detail.finalAnswer,
                style = MaterialTheme.typography.bodyLarge,
                color = Tokens.Primary,
                fontWeight = FontWeight.SemiBold,
            )
        }
        // answer 变量仅用于兜底提示，避免未使用告警
        if (detail.finalAnswer.isBlank() && answer.isBlank() && detail.steps.isNotEmpty()) {
            Text(
                text = "最终答案没有单独记录。",
                style = MaterialTheme.typography.labelSmall,
                color = Tokens.OnSurfaceVariant,
            )
        }
    }
}

/** 知识点卡。 */
@Composable
private fun KpointCard(detail: MistakeDetailUi) {
    DetailSection(title = "这题考的是") {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            detail.kpointNames.forEach { name ->
                Box(
                    modifier =
                        Modifier
                            .background(Tokens.PrimaryContainer, RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.labelMedium,
                        color = Tokens.Primary,
                    )
                }
            }
        }
    }
}

/** 统一的分区卡片。 */
@Composable
private fun DetailSection(
    title: String,
    accent: androidx.compose.ui.graphics.Color = Tokens.OnSurfaceVariant,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Tokens.RadiusLarge))
                .background(Tokens.Surface)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = accent,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 1.dp),
        )
        content()
    }
}

@Composable
private fun LoadingState() {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
        Text(
            text = "正在读取这道错题…",
            style = MaterialTheme.typography.bodySmall,
            color = Tokens.OnSurfaceVariant,
        )
    }
}

@Composable
private fun ErrorState(
    message: String,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = message, style = MaterialTheme.typography.bodyMedium, color = Tokens.Error)
        Button(onClick = onRetry) {
            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(text = "  重试")
        }
    }
}

@Composable
private fun EmptyState(onBack: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "这道错题已经不存在了（可能刚被删除）。",
            style = MaterialTheme.typography.bodyMedium,
            color = Tokens.OnSurfaceVariant,
        )
        Button(onClick = onBack) { Text("返回错题本") }
    }
}
