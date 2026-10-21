package com.totop.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.totop.app.domain.model.SolutionStep
import com.totop.app.domain.model.StandardSolution
import com.totop.app.domain.model.TeachingMode

/**
 * 统一的卡片外壳：左侧语义色条 + 标题行 + 内容区。
 *
 * 刻意不使用阴影模糊 / 毛玻璃（方案 7.1 禁用），只用 1dp 描边区分层级，
 * 低端机上不会产生离屏合成开销。
 */
@Composable
fun SolveCard(
    title: String,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier =
                    Modifier
                        .width(4.dp)
                        .fillMaxHeight()
                        .background(accent),
            )
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    trailing?.invoke()
                }
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                content()
            }
        }
    }
}

/**
 * 题干卡（FR-1.3 第一步：先确认「题目是什么」）。
 *
 * 展示：学科 / 学段 / 已知条件 / 所求 / 选项 / 含图标记。
 * 所有文本都经 [FormulaText]，含 LaTeX 时自动渲染。
 */
@Composable
fun QuestionStemCard(
    subject: String,
    grade: String,
    stemText: String,
    known: List<String>,
    ask: String,
    options: List<String>,
    hasFigure: Boolean,
    modifier: Modifier = Modifier,
    aiGenerated: Boolean = true,
) {
    SolveCard(
        title = "题目",
        modifier = modifier,
        subtitle = buildSubjectLine(subject, grade),
        trailing = {
            if (hasFigure) {
                Box(
                    modifier =
                        Modifier
                            .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = "含图",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        },
    ) {
        FormulaText(text = stemText, style = MaterialTheme.typography.bodyLarge)
        if (known.isNotEmpty()) {
            BulletBlock(title = "已知", items = known)
        }
        if (ask.isNotBlank()) {
            BulletBlock(title = "所求", items = listOf(ask))
        }
        if (options.isNotEmpty()) {
            Text(
                text = "选项",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            options.forEach { option ->
                FormulaText(text = option, style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (aiGenerated) {
            Text(
                text = "题干由 AI 结构化识别，若有出入请以原题为准",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun buildSubjectLine(
    subject: String,
    grade: String,
): String {
    val subjectLabel =
        when (subject.lowercase()) {
            "math" -> "数学"
            "physics" -> "物理"
            "chemistry" -> "化学"
            else -> subject.ifBlank { "未识别" }
        }
    return subjectLabel + " · " + grade.ifBlank { "学段未知" }
}

/** 项目符号块。 */
@Composable
private fun BulletBlock(
    title: String,
    items: List<String>,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        items.forEach { item ->
            Row {
                Text(text = "· ", style = MaterialTheme.typography.bodyMedium)
                FormulaText(text = item, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/**
 * 标准解答单步卡（FR-1.3：做什么 / 为什么 / 结论）。
 *
 * @param step 领域步骤（idx / content / why）
 * @param showWhy 是否展示「为什么」（SOCRATIC 模式下整张卡都不渲染）
 */
@Composable
fun StepCard(
    step: SolutionStep,
    modifier: Modifier = Modifier,
    index: Int = step.idx,
    showWhy: Boolean = true,
) {
    SolveCard(
        title = "第 " + index + " 步",
        modifier = modifier,
        accent = MaterialTheme.colorScheme.primary,
    ) {
        FormulaText(text = step.content, style = MaterialTheme.typography.bodyLarge)
        if (showWhy && step.why.isNotBlank()) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.HelpOutline,
                    contentDescription = null,
                    modifier =
                        Modifier
                            .padding(top = 2.dp)
                            .size(16.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(text = " 为什么：" + step.why, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/**
 * 标准步骤列表卡组。
 *
 * @param solution 标准解答
 * @param mode 讲解模式；SOCRATIC 不渲染任何步骤（由调用方传 visible=false）
 */
@Composable
fun StepListSection(
    solution: StandardSolution,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
) {
    if (!visible) return
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (solution.method.isNotBlank()) {
            Text(
                text = "解题方法：" + solution.method,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        solution.steps.forEach { step -> StepCard(step = step) }
    }
}

/**
 * 最终答案卡。
 *
 * 仅在展示策略允许时渲染（DIRECT 直接显示；GUIDED 需学生主动「看全解」；
 * SOCRATIC 永不显示）。调用方必须用 GuidePolicy 判定后再决定是否渲染本组件。
 */
@Composable
fun FinalAnswerCard(
    finalAnswer: String,
    modifier: Modifier = Modifier,
    hiddenHint: String? = null,
) {
    if (finalAnswer.isBlank()) {
        if (hiddenHint.isNullOrBlank()) return
        SolveCard(title = "最终答案", modifier = modifier, accent = SolveColors.Muted) {
            Text(text = hiddenHint, style = MaterialTheme.typography.bodyMedium)
        }
        return
    }
    SolveCard(
        title = "最终答案",
        modifier = modifier,
        accent = SolveColors.Correct,
        trailing = {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = SolveColors.Correct,
                modifier = Modifier.size(20.dp),
            )
        },
    ) {
        FormulaText(
            text = finalAnswer,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

/** 讲解模式徽标（顶部模式切换的说明行）。 */
@Composable
fun ModeBadge(
    mode: TeachingMode,
    modifier: Modifier = Modifier,
) {
    val color =
        when (mode) {
            TeachingMode.DIRECT -> MaterialTheme.colorScheme.primary
            TeachingMode.GUIDED -> SolveColors.Hint
            TeachingMode.SOCRATIC -> SolveColors.Muted
        }
    Row(
        modifier =
            modifier
                .background(color.copy(alpha = 0.10f), RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (mode == TeachingMode.GUIDED) Icons.Filled.Lightbulb else Icons.Filled.ErrorOutline,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = " " + mode.label,
            style = MaterialTheme.typography.labelLarge,
            color = color,
        )
    }
}

/** 可点击的整行（知识点卡、跳转项共用）。 */
@Composable
fun ClickableRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f)) { content() }
        if (enabled) {
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** 单行省略文本。 */
@Composable
fun SingleLineText(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
