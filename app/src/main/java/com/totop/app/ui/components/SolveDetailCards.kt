package com.totop.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.totop.app.domain.model.FirstError
import com.totop.app.domain.model.KpointAssignment
import com.totop.app.domain.model.StudentStepStatus
import com.totop.app.domain.model.SummaryCard
import com.totop.app.ui.chat.ErrorTypeOption

/**
 * 学生步骤对齐视图（FR-1.6：correct / wrong / missing 三态）。
 *
 * 设计：
 * - 每条学生步骤一行，左侧固定宽度的三态标签（正确 / 错误 / 缺失）；
 * - 首个错误步骤加粗边框并标注错因，帮助学生一眼定位「从哪一步开始错」；
 * - 不显示学生没写的步骤内容（missing 状态只提示「这一步缺失」）。
 */
@Composable
fun StudentAlignmentList(
    steps: List<com.totop.app.ui.chat.AlignedStudentStep>,
    modifier: Modifier = Modifier,
) {
    if (steps.isEmpty()) {
        Text(
            text = "没有提供学生过程，无法定位错因。下次可以把自己写的几步草稿贴进来。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )
        return
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        steps.forEach { step -> StudentStepRow(step = step) }
    }
}

/** 单条学生步骤。 */
@Composable
private fun StudentStepRow(step: com.totop.app.ui.chat.AlignedStudentStep) {
    val tone = SolveColors.of(step.status)
    val background = if (step.isFirstError) tone.copy(alpha = 0.08f) else Color.Transparent
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(background, RoundedCornerShape(8.dp))
                .padding(vertical = 6.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        StatusChip(status = step.status)
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(
                text = "第 " + step.idx + " 步",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (step.status == StudentStepStatus.MISSING) {
                Text(
                    text = "这一步缺失（跳步或未写）",
                    style = MaterialTheme.typography.bodyMedium,
                    color = tone,
                )
            } else {
                FormulaText(text = step.content, style = MaterialTheme.typography.bodyMedium)
            }
            if (step.isFirstError && !step.errorLabel.isNullOrBlank()) {
                Text(
                    text = "首个错误点 · " + step.errorLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = tone,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/** 三态标签。 */
@Composable
fun StatusChip(status: StudentStepStatus) {
    val tone = SolveColors.of(status)
    Row(
        modifier =
            Modifier
                .background(tone.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector =
                when (status) {
                    StudentStepStatus.CORRECT -> Icons.Filled.CheckCircle
                    StudentStepStatus.WRONG -> Icons.Filled.Cancel
                    StudentStepStatus.MISSING -> Icons.AutoMirrored.Filled.HelpOutline
                },
            contentDescription = null,
            tint = tone,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = " " + SolveColors.labelOf(status),
            style = MaterialTheme.typography.labelMedium,
            color = tone,
        )
    }
}

/**
 * 错因卡（FR-1.4 / FR-1.5）。
 *
 * 内容三块：
 * 1. 首个错误步骤与一句话说明；
 * 2. **固定七类错因枚举**（选中项高亮，其余置灰但仍展示，帮助学生建立错因体系）；
 * 3. 正确思考方式 vs 学生当时的思路的对比。
 */
@Composable
fun ErrorCard(
    firstError: FirstError?,
    errorOptions: List<ErrorTypeOption>,
    modifier: Modifier = Modifier,
    studentThought: String? = null,
) {
    if (firstError == null) {
        SolveCard(title = "错因分析", modifier = modifier, accent = SolveColors.Muted) {
            Text(
                text =
                    "本次未定位到明确的错误步骤，可能是解题过程太简略。" +
                        "下次可以把每一步写清楚，AI 才能指出具体是在哪一步偏掉的。",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        return
    }
    SolveCard(
        title = "错因分析",
        modifier = modifier,
        accent = SolveColors.Wrong,
        subtitle = "从第 " + firstError.stepIdx + " 步开始偏掉",
    ) {
        FormulaText(text = firstError.explain, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = "错因分类（固定七类）",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ErrorTypeGrid(options = errorOptions)
        if (!studentThought.isNullOrBlank()) {
            ComparisonBlock(label = "你当时的思路", body = studentThought, tone = SolveColors.Wrong)
        }
        ComparisonBlock(
            label = "本题应有的思考入口",
            body = firstError.correctThinking,
            tone = SolveColors.Correct,
        )
    }
}

/** 七类错因标签网格（静态排版，不使用流式动画）。 */
@Composable
private fun ErrorTypeGrid(options: List<ErrorTypeOption>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.chunked(ERROR_CHIPS_PER_ROW).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { option -> ErrorTypeChip(option) }
            }
        }
    }
}

private const val ERROR_CHIPS_PER_ROW = 4

/** 单个错因标签。 */
@Composable
private fun ErrorTypeChip(option: ErrorTypeOption) {
    val background =
        if (option.selected) {
            SolveColors.Wrong.copy(alpha = 0.14f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        }
    val foreground = if (option.selected) SolveColors.Wrong else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier =
            Modifier
                .background(background, RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text = option.type.label,
            style = MaterialTheme.typography.labelMedium,
            color = foreground,
            fontWeight = if (option.selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

/**
 * 对比块：左侧竖线 + 标签 + 正文。
 *
 * 用于「你当时的思路」与「本题应有的思考入口」的并列对照（FR-1.4）。
 */
@Composable
fun ComparisonBlock(
    label: String,
    body: String,
    tone: Color,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier =
                    Modifier
                        .size(width = 3.dp, height = 12.dp)
                        .background(tone),
            )
            Text(
                text = " " + label,
                style = MaterialTheme.typography.labelLarge,
                color = tone,
                fontWeight = FontWeight.SemiBold,
            )
        }
        FormulaText(
            text = body,
            modifier = Modifier.padding(start = 7.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/**
 * 知识点卡（FR-1.8）：可点击跳转 kpoint_detail。
 *
 * 只展示受控 ID 的条目（AI 自造 ID 在映射阶段已被过滤），
 * 因此这里的每一条都能安全跳转。点击回调由调用方接到导航。
 */
@Composable
fun KpointCard(
    assignments: List<KpointAssignment>,
    onOpenKpoint: (kpointId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (assignments.isEmpty()) {
        SolveCard(title = "涉及知识点", modifier = modifier, accent = SolveColors.Muted) {
            Text(
                text = "本次未能匹配到内置知识点，可能是题目超纲或表述特殊。",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        return
    }
    SolveCard(
        title = "涉及知识点",
        modifier = modifier,
        accent = MaterialTheme.colorScheme.primary,
        subtitle = "点击查看定义、通用思路与我的错题",
    ) {
        assignments.forEach { assignment ->
            KpointRow(assignment = assignment, onClick = { onOpenKpoint(assignment.kpointId) })
        }
    }
}

/** 单条知识点。 */
@Composable
private fun KpointRow(
    assignment: KpointAssignment,
    onClick: () -> Unit,
) {
    ClickableRow(onClick = onClick, enabled = assignment.hasControlledId) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = assignment.name.ifBlank { assignment.kpointId },
                style = MaterialTheme.typography.titleMedium,
            )
            if (assignment.usage.isNotBlank()) {
                FormulaText(text = assignment.usage, style = MaterialTheme.typography.bodySmall)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = assignment.kpointId,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "  ·  相关度 " + formatConfidence(assignment.confidence),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun formatConfidence(value: Double): String {
    val percent = (value * 100).toInt()
    return percent.toString() + "%"
}

/**
 * 三级提示卡（FR-2 GUIDED 模式）。
 *
 * 交互：
 * - 已解锁的级别显示正文；
 * - 未解锁的级别只显示一条提示语与「解锁」按钮，逐级解锁；
 * - 三级全部解锁后按钮变为禁用文案，引导学生「我已想通，看全解」。
 *
 * 严禁：本组件任何分支都不会渲染最终答案。
 */
@Composable
fun HintCard(
    hints: List<com.totop.app.ui.chat.HintLevel>,
    unlockedLevel: Int,
    revealed: Boolean,
    onUnlock: () -> Unit,
    onReveal: () -> Unit,
    modifier: Modifier = Modifier,
    showRevealAction: Boolean = true,
    hintRecorded: Boolean = false,
) {
    SolveCard(
        title = "三级提示",
        modifier = modifier,
        accent = SolveColors.Hint,
        subtitle = if (hintRecorded) "已记录本次提示（用于后续学情统计）" else "想不出来再逐级解锁，别直接看答案",
    ) {
        hints.forEach { hint ->
            val unlocked = hint.level <= unlockedLevel
            HintRow(hint = hint, unlocked = unlocked)
        }
        if (revealed) {
            Text(
                text = "你已选择查看全解，提示卡不再增加新的梯子。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SolveCard
        }
        val canUnlock = unlockedLevel < hints.size
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (canUnlock) {
                OutlinedButton(onClick = onUnlock) {
                    Icon(
                        imageVector = Icons.Filled.Lightbulb,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(text = " 解锁第 " + (unlockedLevel + 1) + " 级提示")
                }
            } else if (hints.isNotEmpty()) {
                Text(
                    text = "三级提示已全部解锁。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (showRevealAction) {
                TextButton(onClick = onReveal) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(text = " 我已想通，看全解")
                }
            }
        }
    }
}

/** 单级提示行。 */
@Composable
private fun HintRow(
    hint: com.totop.app.ui.chat.HintLevel,
    unlocked: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = hint.title,
            style = MaterialTheme.typography.labelLarge,
            color = if (unlocked) SolveColors.Hint else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
        )
        if (unlocked) {
            FormulaText(text = hint.text, style = MaterialTheme.typography.bodyMedium)
        } else {
            Text(
                text = "未解锁 · 先自己再想 1 分钟，确实卡住再点下面的按钮",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 摘要卡（FR-1.11：本题要点回顾）。
 */
@Composable
fun SummaryCardView(
    summary: SummaryCard?,
    modifier: Modifier = Modifier,
) {
    if (summary == null || summary.isEmpty) return
    SolveCard(
        title = summary.title.ifBlank { "本题小结" },
        modifier = modifier,
        accent = MaterialTheme.colorScheme.tertiary,
    ) {
        summary.bullets.forEach { bullet ->
            Row {
                Text(text = "· ", style = MaterialTheme.typography.bodyMedium)
                FormulaText(text = bullet, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** 「本次消耗 N tokens」提示（FR-1.12 成本可见）。 */
@Composable
fun TokenUsageLine(
    inputTokens: Int,
    outputTokens: Int,
    modifier: Modifier = Modifier,
) {
    val total = inputTokens + outputTokens
    Text(
        text = "本次消耗 " + total + " tokens（输入 " + inputTokens + " / 输出 " + outputTokens + "）",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/** 主行动按钮（发送）。 */
@Composable
fun PrimaryAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(),
    ) {
        Text(text = text)
    }
}
