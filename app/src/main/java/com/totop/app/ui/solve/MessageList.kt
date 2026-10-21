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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
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
import com.totop.app.ui.chat.SolutionPayload
import com.totop.app.ui.theme.Tokens

/**
 * 单条消息的渲染分发。
 *
 * 三类渲染：
 * 1. 学生消息 → 右侧气泡（附带页数角标）；
 * 2. AI 普通回答（追问的答案）→ 左侧文字气泡；
 * 3. AI 解题结果 → 左侧分块卡片（题目 / 步骤 / 答案 / 错因 / 知识点）。
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
                Text(text = message.text, style = MaterialTheme.typography.bodyMedium, color = Tokens.OnSurface)
            }
        }
    }
}

/** 宽度上限：气泡不要横贯整屏，留出对话的呼吸感。 */
private fun Modifier.widthInMax(): Modifier = this.fillMaxWidth(0.82f)

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
                    .fillMaxWidth(0.88f)
                    .clip(RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp))
                    .background(Tokens.Surface)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            if (message.text.isNotBlank()) {
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Tokens.OnSurface,
                )
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
 * 把四阶段结果按阅读顺序拼成**一条**左对齐卡片流，
 * 避免旧版「十几个独立卡片堆满一屏」的观感问题。
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
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // 题目 + 标准步骤 + 答案
        BubbleCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (payload.stem.isNotBlank()) {
                    Text(
                        text = payload.stem,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Tokens.OnSurface,
                    )
                }
                if (payload.subjectLabel.isNotBlank()) {
                    Text(
                        text = payload.subjectLabel + if (payload.method.isNotBlank()) " · " + payload.method else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = Tokens.OnSurfaceVariant,
                    )
                }
                if (GuidePolicy.showsSolutionSteps(mode)) {
                    Spacer(Modifier.height(2.dp))
                    payload.steps.forEach { step ->
                        Text(
                            text = step.idx.toString() + ". " + step.content,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Tokens.OnSurface,
                        )
                        if (step.why.isNotBlank()) {
                            Text(
                                text = "   为什么：" + step.why,
                                style = MaterialTheme.typography.bodySmall,
                                color = Tokens.OnSurfaceVariant,
                            )
                        }
                    }
                }
                val answer = payload.finalAnswer
                if (answer.isNotBlank() && GuidePolicy.showsFinalAnswer(mode, payload.fullSolutionRevealed)) {
                    Text(
                        text = "答案：" + answer,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Tokens.Primary,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }

        // 引导模式：三级提示 + 看全解
        if (GuidePolicy.showsHints(mode) && payload.hints.isNotEmpty()) {
            BubbleCard {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "提示（已解锁 " + payload.unlockedHintLevel + "/" + payload.hints.size + "）",
                        style = MaterialTheme.typography.labelLarge,
                        color = Tokens.Primary,
                    )
                    payload.hints.forEach { hint ->
                        if (hint.level <= payload.unlockedHintLevel) {
                            Text(
                                text = hint.title,
                                style = MaterialTheme.typography.labelMedium,
                                color = Tokens.OnSurfaceVariant,
                            )
                            Text(
                                text = hint.text,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Tokens.OnSurface,
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (payload.unlockedHintLevel < payload.hints.size) {
                            SmallAction(text = "再给一点提示", onClick = { onUnlockHint(message.id) })
                        }
                        if (GuidePolicy.showsRevealAction(mode, payload.fullSolutionRevealed)) {
                            SmallAction(text = "我已想通，看全解", onClick = { onRevealSolution(message.id) })
                        }
                    }
                }
            }
        }

        // 错因
        val firstError = payload.firstError
        if (firstError != null) {
            BubbleCard {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "第 " + firstError.stepIdx + " 步 · " + firstError.typeLabel,
                        style = MaterialTheme.typography.labelLarge,
                        color = Tokens.Error,
                    )
                    Text(
                        text = firstError.explain,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Tokens.OnSurface,
                    )
                    if (firstError.correctThinking.isNotBlank()) {
                        Text(
                            text = "下次这样想：" + firstError.correctThinking,
                            style = MaterialTheme.typography.bodySmall,
                            color = Tokens.OnSurfaceVariant,
                        )
                    }
                }
            }
        }

        // 知识点
        if (payload.kpoints.isNotEmpty()) {
            BubbleCard {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "这题考的是",
                        style = MaterialTheme.typography.labelMedium,
                        color = Tokens.OnSurfaceVariant,
                    )
                    payload.kpoints.forEach { kp ->
                        Text(
                            text = kp.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Tokens.Primary,
                            modifier = Modifier.clickable { onOpenKnowledgePoint(kp.id) },
                        )
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

/** 统一的左对齐白底卡片。 */
@Composable
private fun BubbleCard(content: @Composable () -> Unit) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth(0.94f)
                .clip(RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp))
                .background(Tokens.Surface)
                .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        content()
    }
}

/** 小号胶囊按钮。 */
@Composable
private fun SmallAction(
    text: String,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .clip(RoundedCornerShape(50))
                .background(Tokens.PrimaryContainer)
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(text = text, style = MaterialTheme.typography.labelMedium, color = Tokens.Primary)
    }
}
