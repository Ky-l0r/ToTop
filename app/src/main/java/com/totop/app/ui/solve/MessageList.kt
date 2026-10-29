package com.totop.app.ui.solve

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.totop.app.domain.model.TeachingMode
import com.totop.app.ui.chat.ConversationMessage
import com.totop.app.ui.chat.GuidePolicy
import com.totop.app.ui.chat.HintLevel
import com.totop.app.ui.chat.SolutionPayload
import com.totop.app.ui.chat.StepSections
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
    thinkingHint: String,
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
                thinkingHint = thinkingHint,
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
                // 已经发出去的题目必须**看得见**。
                //
                // 旧实现只在气泡里写一行「共 N 页题目」：学生消息发出去后找不到图，
                // 分不清到底传没传、传了几页，只能靠再发一遍确认。
                // 这里直接把图片按页序横向排出来，与输入框上方的缩略图一一对应。
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    itemsIndexed(message.pages) { index, path ->
                        Box(
                            modifier =
                                Modifier
                                    .size(72.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color.White.copy(alpha = 0.7f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            coil.compose.AsyncImage(
                                model = java.io.File(path),
                                contentDescription = "已发送的第 " + (index + 1) + " 页题目",
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
                if (message.pages.size > 1) {
                    Text(
                        text = "共 " + message.pages.size + " 页题目",
                        style = MaterialTheme.typography.labelSmall,
                        color = Tokens.Primary,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(top = 4.dp),
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

/**
 * 「AI 正在思考」占位。
 *
 * 只让一个小圆点做透明度呼吸（6dp），不做位移、缩放或骨架屏：
 * 这类动画在低端机上几乎不花钱，但足以让用户知道「还在跑，不是卡死」。
 */
@Composable
private fun ThinkingIndicator(
    label: String,
    hint: String,
) {
    val transition = rememberInfiniteTransition(label = "thinking")
    val dotAlpha by
        transition.animateFloat(
            initialValue = 0.2f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 650, easing = LinearEasing), RepeatMode.Reverse),
            label = "thinking-dot",
        )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier =
                Modifier
                    .size(6.dp)
                    .alpha(dotAlpha)
                    .clip(CircleShape)
                    .background(Tokens.Primary),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label + "…",
            style = MaterialTheme.typography.labelSmall,
            color = Tokens.OnSurfaceVariant,
        )
        if (hint.isNotBlank()) {
            Text(
                text = " · " + hint,
                style = MaterialTheme.typography.labelSmall,
                color = Tokens.OnSurfaceVariant,
            )
        }
    }
}

/** AI 普通文字气泡（追问的回答 / 流式生成中）。 */
@Composable
private fun AssistantTextBubble(
    message: ConversationMessage,
    thinkingHint: String,
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
                // 追问回答按语义分行渲染：思路（为什么）与计算（算式）用不同颜色，
                // 结论单独强调。整段直接甩出来的话，一屏七八条公式会糊成一片。
                AnswerBody(text = message.text, style = MaterialTheme.typography.bodyMedium)
            }
            if (message.streaming) {
                // 解题期间不再显示模型的原始增量（那是一大段 JSON），
                // 只给一个「AI 正在思考」占位 + 当前阶段。追问的增量是真回答，
                // 会同时出现在上面，这里只补一行更细的状态。
                Spacer(Modifier.height(4.dp))
                ThinkingIndicator(
                    label =
                        if (message.text.isBlank()) {
                            "AI 正在思考"
                        } else {
                            "AI 正在回答"
                        },
                    hint = thinkingHint,
                )
            }
            // 订正回执：纠错到底有没有进错题本，必须让用户看见
            message.correctionNote?.let { note ->
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = Tokens.Success,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = "  " + note,
                        style = MaterialTheme.typography.labelSmall,
                        color = Tokens.Success,
                    )
                }
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
                // 选项必须渲染出来。
                //
                // 多选题最容易出的错是「只给一个选项」，而用户在界面上连选项都看不到，
                // 既没法核对答案，也没法判断模型是不是漏选了。
                if (payload.options.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "选项",
                            style = MaterialTheme.typography.labelMedium,
                            color = Tokens.OnSurfaceVariant,
                        )
                        payload.options.forEach { option ->
                            FormulaText(
                                text = option,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
                // 答案个数与题型对不上时，就地说明——比让学生自己猜「是不是漏选了」更有用。
                // 判据来自阶段 A 识别出的题型，不再是「题干里有没有『多选』字样」这种猜法。
                payload.choiceViolation()?.let { problem ->
                    Text(
                        text = problem,
                        style = MaterialTheme.typography.labelSmall,
                        color = Tokens.Warning,
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
 * 关于「提示总是分三次给」：
 * 旧实现严格一级一级解锁，学生要点三次才看到"给到关键式子"。
 * 但这几级并不是等价的线索——第 1 级只是方法名，第 2 级才是"下一步做什么"，
 * 真正能让他往下写的往往是第 2 级，逐级点等于逼他多点一次废话。
 *
 * 现在的策略是**按需给，按内容决定给多少**：
 * - 第一次点：一次给到第 2 级（方向 + 下一步），这是多数人卡住时真正需要的信息量；
 * - 如果这道题只有 2 级提示，第一次点就给全，不再留第二次点击；
 * - 只有第 3 级（展开顺序与式子）才需要再点一次——
 *   它是"就差最后一步"时才要的东西，提前给等于把答案喂到嘴边。
 *
 * 三条保留的设计决定：
 * 1. 未解锁的级别不隐藏，而是显示成「还有更具体的提示」；
 * 2. 正文用 bodyLarge（与解答步骤同级）；
 * 3. 用编号圆标代替「第 1 级：给方向」这类长标签。
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
                        text =
                            when {
                                // 还没给过：一次给到第 2 级（方向 + 下一步）
                                unlocked == 0 -> "给我提示"
                                // 还剩最后一级：这是"就差这一步"
                                else -> "再具体一点"
                            },
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

/**
 * 已揭示的完整解答卡。
 *
 * 大题按小问分组显示：步骤若以「(1)」「(2)」开头，就按小问切开，
 * 每个小问单独一个标题。旧实现把所有步骤平铺成 1..n，
 * 一道三问的大题会变成一长串十六步，学生根本对不上「我现在看的是哪一问」。
 */
@Composable
private fun SolutionStepsCard(payload: SolutionPayload) {
    val sections = StepSections.group(payload.steps)
    BubbleCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "标准解答",
                style = MaterialTheme.typography.labelLarge,
                color = Tokens.OnSurfaceVariant,
            )
            sections.forEach { section ->
                if (section.title.isNotBlank()) {
                    Text(
                        text = section.title,
                        style = MaterialTheme.typography.labelLarge,
                        color = Tokens.Primary,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                section.steps.forEach { step ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        FormulaText(
                            text = step.idx.toString() + ". " + step.content,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (step.why.isNotBlank()) {
                            // 「为什么」是思路，不是计算：统一用思路色，
                            // 与上下的算式行形成稳定区分，学生扫读时不用逐字读也能分清两类内容。
                            ReasoningNote(text = step.why)
                        }
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

/**
 * 一条「思路」注记：左侧思路色竖标 + 小标 + 正文。
 *
 * 只用颜色区分是不够的（色弱用户看不出差别），因此同时给了「思路」这两个字。
 */
@Composable
private fun ReasoningNote(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 1.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = "思路",
            style = MaterialTheme.typography.labelSmall,
            color = Tokens.Reasoning,
            fontWeight = FontWeight.Medium,
        )
        Box(
            modifier =
                Modifier
                    .width(2.dp)
                    .height(12.dp)
                    .background(Tokens.Reasoning),
        )
        Box(modifier = Modifier.weight(1f)) {
            FormulaText(
                text = text,
                style = MaterialTheme.typography.bodySmall.copy(color = Tokens.Reasoning),
            )
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
