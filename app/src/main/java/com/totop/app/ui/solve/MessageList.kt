package com.totop.app.ui.solve

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.totop.app.domain.model.TeachingMode
import com.totop.app.ui.chat.ConversationMessage
import com.totop.app.ui.chat.GuidePolicy
import com.totop.app.ui.chat.HintLevel
import com.totop.app.ui.chat.SolutionPayload
import com.totop.app.ui.components.FormulaText
import com.totop.app.ui.theme.Tokens

/**
 * 单条消息的渲染分发。
 *
 * 三类渲染：
 * 1. 学生消息 → 右侧气泡（附带页数角标）；
 * 2. AI 普通回答（追问的答案）→ 左侧文字气泡；
 * 3. AI 解题结果 → 左侧分块卡片（题目 / 提示 / 错因 / 知识点 / 标准解答）。
 */
@Composable
internal fun MessageItem(
    message: ConversationMessage,
    mode: TeachingMode,
    onOpenSettings: () -> Unit,
    onRetry: () -> Unit,
    onOpenKnowledgePoint: (String) -> Unit,
    onUnlockHint: (String) -> Unit,
    onRevealSolution: (String) -> Unit,
) {
    when {
        message.fromStudent -> StudentBubble(message)
        message.solution != null ->
            SolutionBubble(
                message = message,
                payload = message.solution,
                mode = mode,
                onOpenKnowledgePoint = onOpenKnowledgePoint,
                onUnlockHint = onUnlockHint,
                onRevealSolution = onRevealSolution,
            )
        else ->
            AssistantTextBubble(
                message = message,
                onRetry = onRetry,
                onOpenSettings = onOpenSettings,
            )
    }
}

/** 学生消息气泡（右侧、主色浅底）。 */
@Composable
private fun StudentBubble(message: ConversationMessage) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        Column(
            modifier =
                Modifier
                    .widthInMax()
                    .clip(RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp))
                    .background(Tokens.PrimaryContainer)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            if (message.pages.isNotEmpty()) {
                Row(
                    modifier =
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.White.copy(alpha = 0.7f))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text =
                            if (message.pages.size > 1) {
                                "共 " + message.pages.size + " 页题目"
                            } else {
                                "已拍题 1 页"
                            },
                        style = MaterialTheme.typography.labelMedium,
                        color = Tokens.Primary,
                        fontWeight = FontWeight.Medium,
                    )
                }
                if (message.text.isNotBlank()) Spacer(Modifier.height(8.dp))
            }
            if (message.text.isNotBlank()) {
                FormulaText(text = message.text, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** 学生气泡占总宽度的比例：不要横贯整屏，留出对话的呼吸感。 */
private const val STUDENT_BUBBLE_WIDTH_RATIO: Float = 0.82f

/** 宽度上限：气泡不要横贯整屏，留出对话的呼吸感。 */
private fun Modifier.widthInMax(): Modifier = this.fillMaxWidth(STUDENT_BUBBLE_WIDTH_RATIO)

/** AI 普通文字气泡（追问的回答 / 流式生成中）。 */
@Composable
private fun AssistantTextBubble(
    message: ConversationMessage,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val failed = message.errorText
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Start,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth(0.9f)
                    .clip(RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp))
                    .background(Tokens.SurfaceVariant)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            if (message.text.isNotBlank()) {
                FormulaText(text = message.text, style = MaterialTheme.typography.bodyMedium)
            }
            if (message.streaming) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "正在作答…",
                    style = MaterialTheme.typography.labelSmall,
                    color = Tokens.OnSurfaceVariant,
                )
            }
            if (failed != null) {
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Tokens.ErrorContainer)
                            .padding(10.dp),
                ) {
                    Text(text = failed, style = MaterialTheme.typography.bodySmall, color = Tokens.Error)
                }
                if (message.retryable) {
                    TextButton(onClick = onRetry, modifier = Modifier.padding(top = 2.dp)) {
                        Text("重试这一次", style = MaterialTheme.typography.labelMedium)
                    }
                } else {
                    TextButton(onClick = onOpenSettings, modifier = Modifier.padding(top = 2.dp)) {
                        Text("去设置", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

/**
 * 解题结果气泡。
 *
 * 阅读顺序按「学生此刻最需要看到什么」排：
 *
 * ```
 * 1. 题目原文（排版后，带科目标签）      ← 先确认拍对了
 * 2. 提示卡（引导模式下是主角）          ← 引导模式首屏就是它
 * 3. 错因分析（如果学生写了思路）        ← 回答「我错在哪」
 * 4. 标准解答（未揭示时只有入口）        ← 学生主动索取
 * 5. 这题考的是（知识点）                ← 收尾，可点进详情
 * ```
 *
 * 引导模式下第 2 项必须排在第 4 项之前，并且用正文级字号——
 * 旧实现把提示塞在标准步骤下面的小字注脚里，学生根本不会注意到。
 */
@Composable
private fun SolutionBubble(
    message: ConversationMessage,
    payload: SolutionPayload,
    mode: TeachingMode,
    onOpenKnowledgePoint: (String) -> Unit,
    onUnlockHint: (String) -> Unit,
    onRevealSolution: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 1. 题目原文
        BubbleCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SubjectLine(payload)
                if (payload.stem.isNotBlank()) {
                    FormulaText(text = payload.stem, style = MaterialTheme.typography.bodyMedium)
                }
                if (payload.known.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            text = "已知",
                            style = MaterialTheme.typography.labelMedium,
                            color = Tokens.OnSurfaceVariant,
                        )
                        payload.known.forEach { item ->
                            FormulaText(
                                text = "· " + item,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
                if (payload.ask.isNotBlank()) {
                    FormulaText(
                        text = "求：" + payload.ask,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        val withheld = GuidePolicy.withholdsSolution(mode, payload.fullSolutionRevealed)

        // 2. 提示卡：引导模式下这是主体
        if (withheld && payload.hints.isNotEmpty()) {
            HintBoard(
                hints = payload.hints,
                unlocked = payload.unlockedHintLevel,
                onUnlock = { onUnlockHint(message.id) },
                onReveal = { onRevealSolution(message.id) },
            )
        }

        // 3. 错因
        val firstError = payload.firstError
        if (firstError != null) {
            BubbleCard {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "第 " + firstError.stepIdx + " 步 · " + firstError.typeLabel,
                        style = MaterialTheme.typography.labelLarge,
                        color = Tokens.Error,
                        fontWeight = FontWeight.SemiBold,
                    )
                    FormulaText(text = firstError.explain, style = MaterialTheme.typography.bodyMedium)
                    if (firstError.correctThinking.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "下次这样想",
                            style = MaterialTheme.typography.labelMedium,
                            color = Tokens.Primary,
                            fontWeight = FontWeight.Medium,
                        )
                        FormulaText(
                            text = firstError.correctThinking,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }

        // 4. 标准解答
        if (GuidePolicy.showsSolutionSteps(mode) || payload.fullSolutionRevealed) {
            SolutionStepsCard(payload)
        } else {
            LockedSolutionCard(
                onReveal = { onRevealSolution(message.id) },
                canReveal = GuidePolicy.showsRevealAction(mode, payload.fullSolutionRevealed),
            )
        }

        // 5. 知识点
        if (payload.kpoints.isNotEmpty()) {
            BubbleCard {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "这题考的是",
                        style = MaterialTheme.typography.labelMedium,
                        color = Tokens.OnSurfaceVariant,
                    )
                    payload.kpoints.forEach { kp ->
                        Text(
                            text = kp.name + "  ›",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Tokens.Primary,
                            modifier = Modifier.clickable { onOpenKnowledgePoint(kp.id) },
                        )
                        if (kp.usage.isNotBlank()) {
                            FormulaText(
                                text = kp.usage,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }

        // 落库提示：入库成功给一句确认，用户才知道错题本里有东西了
        if (payload.mistakeId != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = Tokens.Success,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = "  已收进错题本",
                    style = MaterialTheme.typography.labelSmall,
                    color = Tokens.Success,
                )
            }
        }
        if (payload.persistMessage != null) {
            Text(
                text = payload.persistMessage,
                style = MaterialTheme.typography.labelSmall,
                color = Tokens.Error,
            )
        }
        if (message.errorText != null) {
            Text(
                text = message.errorText,
                style = MaterialTheme.typography.labelSmall,
                color = Tokens.OnSurfaceVariant,
            )
        }
    }
}

/** 科目标签行：科目 · 方法。 */
@Composable
private fun SubjectLine(payload: SolutionPayload) {
    if (payload.subjectLabel.isBlank()) return
    Text(
        text = payload.subjectLabel + if (payload.method.isNotBlank()) " · " + payload.method else "",
        style = MaterialTheme.typography.labelMedium,
        color = Tokens.Primary,
        fontWeight = FontWeight.Medium,
    )
}

/**
 * 提示卡：**主体级强调**（对应真机反馈「建议用小字呈现，不易读，提示应该为主体强调」）。
 *
 * 三条设计决定：
 * 1. 未解锁的级别不隐藏，而是显示成「还有更具体的提示」——
 *    学生才知道「再点一下还有东西」，否则会以为这里就这么多；
 * 2. 正文用 bodyLarge（与解答步骤同级），不用 label/bodySmall；
 * 3. 用编号圆标代替「第 1 级：给方向」这类长标签，视觉上更干净。
 */
@Composable
private fun HintBoard(
    hints: List<HintLevel>,
    unlocked: Int,
    onUnlock: () -> Unit,
    onReveal: () -> Unit,
) {
    BubbleCard(emphasis = true) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Lightbulb,
                    contentDescription = null,
                    tint = Tokens.Primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "先自己想一步，卡住了再看提示",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Tokens.Primary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
            }
            hints.forEach { hint ->
                if (hint.level <= unlocked) {
                    RevealedHint(hint)
                }
            }
            if (unlocked == 0) {
                Text(
                    text =
                        "这道题的答案我先不给。想不起来怎么下手，就点下面的按钮，我给一点线索；" +
                            "实在想不出来，也可以直接看完整解答。",
                    style = MaterialTheme.typography.bodySmall,
                    color = Tokens.OnSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (unlocked < hints.size) {
                    PrimaryAction(
                        text = if (unlocked == 0) "给我一点提示" else "还是不行，再给一点",
                        onClick = onUnlock,
                    )
                }
                SmallAction(text = "我已想通，看全解", onClick = onReveal)
            }
        }
    }
}

/** 一条已解锁的提示：编号圆标 + 正文级字号。 */
@Composable
private fun RevealedHint(hint: HintLevel) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(Tokens.Primary),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = hint.badge,
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                fontWeight = FontWeight.Bold,
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = hint.purpose,
                style = MaterialTheme.typography.labelMedium,
                color = Tokens.OnSurfaceVariant,
            )
            FormulaText(text = hint.text, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/** 已揭示的完整解答卡。 */
@Composable
private fun SolutionStepsCard(payload: SolutionPayload) {
    BubbleCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "标准解答",
                style = MaterialTheme.typography.labelLarge,
                color = Tokens.OnSurfaceVariant,
            )
            payload.steps.forEach { step ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    FormulaText(
                        text = step.idx.toString() + ". " + step.content,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (step.why.isNotBlank()) {
                        FormulaText(
                            text = "为什么：" + step.why,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            if (payload.finalAnswer.isNotBlank()) {
                Text(
                    text = "答案：" + payload.finalAnswer,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Tokens.Primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/** 未揭示时的占位卡：明确告诉学生「答案在这里，但要你自己点开」。 */
@Composable
private fun LockedSolutionCard(
    onReveal: () -> Unit,
    canReveal: Boolean,
) {
    BubbleCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "完整解答暂不展开",
                style = MaterialTheme.typography.bodyMedium,
                color = Tokens.OnSurface,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "自己先写一遍再看，记得更牢。真想不出来，可以直接展开。",
                style = MaterialTheme.typography.bodySmall,
                color = Tokens.OnSurfaceVariant,
            )
            if (canReveal) {
                SmallAction(text = "直接看完整解答", onClick = onReveal)
            }
        }
    }
}

/** 统一的左对齐卡片。[emphasis] 为 true 时用主色浅底，表示这是当前该看的重点。 */
@Composable
private fun BubbleCard(
    emphasis: Boolean = false,
    content: @Composable () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth(0.96f)
                .clip(RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp))
                .background(if (emphasis) Tokens.PrimaryContainer else Tokens.Surface)
                .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        content()
    }
}

/** 主色实心按钮（提示卡里的主行动）。 */
@Composable
private fun PrimaryAction(
    text: String,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .clip(RoundedCornerShape(50))
                .background(Tokens.Primary)
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 9.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = Color.White,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** 小号胶囊按钮（次要动作）。 */
@Composable
private fun SmallAction(
    text: String,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .clip(RoundedCornerShape(50))
                .background(Tokens.SurfaceVariant)
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Text(text = text, style = MaterialTheme.typography.labelMedium, color = Tokens.OnSurface)
    }
}
