package com.totop.app.ui.solve

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.totop.app.ui.chat.AssistantBubble
import com.totop.app.ui.chat.GuidePolicy
import com.totop.app.ui.chat.ModeMeta
import com.totop.app.ui.chat.SolveDraft
import com.totop.app.ui.chat.SolveUiState
import com.totop.app.ui.chat.StageProgress
import com.totop.app.ui.chat.StageStatus
import com.totop.app.ui.theme.Tokens

/**
 * 对话式拍题页（核心交互）。
 *
 * 布局对齐主流对话式 AI 应用的使用习惯（只借鉴交互组织方式，不使用任何第三方素材）：
 *
 * ```
 * ┌──────────────────────────┐
 * │ 顶部：标题 / 设置          │
 * ├──────────────────────────┤
 * │                          │
 * │   消息区（可滚动）         │  ← 解题结果以助手气泡逐条出现
 * │                          │
 * ├──────────────────────────┤
 * │ [图片][图片][+]           │  ← 拍好的图片在输入框**正上方**
 * │ ┌──────────────────┐ 📷  │
 * │ │ 说说你的思路…      │  ↑  │
 * │ └──────────────────┘     │
 * └──────────────────────────┘
 * ```
 *
 * 关键取舍：
 * 1. **不再显示 OCR 识别出的题干原文**。识别文本只作为路由判断的输入，
 *    展示出来既占屏又会让用户困惑「这串乱码是什么」。用户要看的是自己的照片。
 * 2. **输入框用于写解题思路**，不是写题干。拍照后题目内容来自图片本身。
 * 3. 图片放在输入框上方而不是消息流里，保证写思路时始终能看到拍了哪几页。
 */
@Composable
fun SolveConversationScreen(
    uiState: SolveUiState,
    draft: SolveDraft,
    pages: List<ComposerPage>,
    pageCount: Int,
    processing: Boolean,
    networkOnline: Boolean,
    mode: com.totop.app.domain.model.TeachingMode,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onOpenCamera: () -> Unit,
    onOpenAlbum: () -> Unit,
    onRemovePage: (Int) -> Unit,
    onOpenPage: (Int) -> Unit,
    onOpenSettings: () -> Unit,
    onRetry: () -> Unit,
    onAskAgain: () -> Unit,
    onSetMode: (com.totop.app.domain.model.TeachingMode) -> Unit,
    onOpenKnowledgePoint: (String) -> Unit,
    message: String? = null,
    onDismissMessage: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val isSolving = uiState is SolveUiState.Solving
    // 只在「状态种类」变化时滚到底部。
    //
    // 不能直接用 uiState 做 key：Solving 期间每次增量文本都会产生一个新对象，
    // 会让 animateScrollToItem 每帧触发一次，既掉帧又和用户的手动滚动打架。
    LaunchedEffect(uiState::class) {
        val count = listState.layoutInfo.totalItemsCount
        if (count > 0) listState.animateScrollToItem(count - 1)
    }

    Column(modifier = modifier.fillMaxSize().background(Tokens.Background)) {
        ConversationList(
            uiState = uiState,
            mode = mode,
            listState = listState,
            onOpenSettings = onOpenSettings,
            onRetry = onRetry,
            onAskAgain = onAskAgain,
            onSetMode = onSetMode,
            onOpenKnowledgePoint = onOpenKnowledgePoint,
            modifier = Modifier.weight(1f),
        )
        if (!networkOnline) OfflineHint()
        message?.let { text ->
            InlineHint(text = text, onDismiss = onDismissMessage)
        }
        Composer(
            draft = draft,
            pages = pages,
            pageCount = pageCount,
            processing = processing,
            isSolving = isSolving,
            onDraftChange = onDraftChange,
            onSend = onSend,
            onStop = onStop,
            onOpenCamera = onOpenCamera,
            onOpenAlbum = onOpenAlbum,
            onRemovePage = onRemovePage,
            onOpenPage = onOpenPage,
        )
    }
}

@Composable
private fun ConversationList(
    uiState: SolveUiState,
    mode: com.totop.app.domain.model.TeachingMode,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onOpenSettings: () -> Unit,
    onRetry: () -> Unit,
    onAskAgain: () -> Unit,
    onSetMode: (com.totop.app.domain.model.TeachingMode) -> Unit,
    onOpenKnowledgePoint: (String) -> Unit,
    // 由调用方传入 Modifier.weight(1f)：weight 是 ColumnScope 的扩展，
    // 在被抽出的独立 Composable 里没有 ColumnScope 上下文，只能从外面带进来。
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (uiState is SolveUiState.Idle) {
            item { EmptyConversationHint() }
            return@LazyColumn
        }
        item { ModeRow(mode = mode, onSetMode = onSetMode) }

        when (uiState) {
            is SolveUiState.Solving -> {
                item { StageLine(uiState.stages) }
                item { AssistantBubbleContent(text = "正在解题，请稍等…", streaming = true) }
            }
            is SolveUiState.Solved ->
                item {
                    SolvedBubbles(
                        state = uiState,
                        mode = mode,
                        onOpenKnowledgePoint = onOpenKnowledgePoint,
                    )
                }
            is SolveUiState.Failed -> {
                item {
                    AssistantBubbleContent(
                        text = failureHeadline(uiState.message),
                        failed = uiState.technicalDetail ?: uiState.message,
                        onRetry = if (uiState.retryable) onRetry else null,
                    )
                }
                if (!uiState.retryable) {
                    item { SettingsPrompt(onOpenSettings = onOpenSettings) }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextAction(text = "重新提问", onClick = onAskAgain)
                    }
                }
            }
            is SolveUiState.Cancelled -> {
                item {
                    AssistantBubbleContent(
                        text = "已停止本次生成" + if (uiState.hasPartialResult) "（已收到的内容保留）" else "",
                        onRetry = onRetry,
                    )
                }
            }
            else -> Unit
        }
    }
}

@Composable
private fun EmptyConversationHint() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 48.dp, start = 20.dp, end = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "把题目拍下来，我来帮你看看",
            style = MaterialTheme.typography.titleMedium,
            color = Tokens.OnSurface,
        )
        Text(
            text = "拍完可以在下面写你是怎么想的。有了你的思路，我才能指出第一个错在哪一步；" +
                "不写也能给标准解答。",
            style = MaterialTheme.typography.bodyMedium,
            color = Tokens.OnSurfaceVariant,
        )
        Text(
            text = "题目跨页就接着拍，多张会当作同一道题来看。",
            style = MaterialTheme.typography.bodySmall,
            color = Tokens.OnSurfaceVariant,
        )
    }
}

@Composable
private fun ModeRow(
    mode: com.totop.app.domain.model.TeachingMode,
    onSetMode: (com.totop.app.domain.model.TeachingMode) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        com.totop.app.domain.model.TeachingMode.entries.forEach { candidate ->
            val selected = candidate == mode
            Box(
                modifier =
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (selected) Tokens.PrimaryContainer else Tokens.SurfaceVariant)
                        .clickable { onSetMode(candidate) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    text = ModeMeta.titleOf(candidate),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selected) Tokens.Primary else Tokens.OnSurfaceVariant,
                )
            }
        }
    }
}

/** 合并后的助手气泡：把四阶段结果按阅读顺序拼成一条，避免旧版「十几个卡片」的堆叠。 */
@Composable
private fun SolvedBubbles(
    state: SolveUiState.Solved,
    mode: com.totop.app.domain.model.TeachingMode,
    onOpenKnowledgePoint: (String) -> Unit,
) {
    val content = state.content
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AssistantBubble(content = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = content.question.stemText.ifBlank { "（题目）" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Tokens.OnSurface,
                )
                if (GuidePolicy.showsSolutionSteps(mode)) {
                    Spacer(Modifier.height(2.dp))
                    content.standardSolution.steps.forEach { step ->
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
                val answer = content.standardSolution.finalAnswer
                if (answer.isNotBlank() &&
                    GuidePolicy.showsFinalAnswer(mode, state.fullSolutionRevealed)
                ) {
                    Text(
                        text = "答案：" + answer,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Tokens.Primary,
                    )
                }
            }
        })
        val firstError = content.firstError
        if (firstError != null) {
            AssistantBubble(content = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "第 " + firstError.stepIdx + " 步 · " + firstError.type.label,
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
            })
        } else {
            AssistantBubble(content = {
                Text(
                    text = "这次没有你的解题过程，所以只给了标准解。把思路写进输入框再发一次，我就能指出具体错在哪一步。",
                    style = MaterialTheme.typography.bodySmall,
                    color = Tokens.OnSurfaceVariant,
                )
            })
        }
        val kpoints = content.kpointAssignments
        if (kpoints.isNotEmpty()) {
            AssistantBubble(content = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("这题考的是", style = MaterialTheme.typography.labelMedium, color = Tokens.OnSurfaceVariant)
                    kpoints.forEach { kp ->
                        Text(
                            text = kp.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Tokens.Primary,
                            modifier = Modifier.clickable { onOpenKnowledgePoint(kp.kpointId) },
                        )
                    }
                }
            })
        }
        if (state.persistMessage != null) {
            AssistantBubble(content = {
                Text(
                    text = state.persistMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = Tokens.Error,
                )
            })
        }
    }
}

@Composable
private fun AssistantBubbleContent(
    text: String,
    streaming: Boolean = false,
    failed: String? = null,
    onRetry: (() -> Unit)? = null,
) {
    AssistantBubble(
        content = {
            Text(text = text, style = MaterialTheme.typography.bodyMedium, color = Tokens.OnSurface)
        },
        streaming = streaming,
        failed = failed,
        onRetry = onRetry,
    )
}

/** 把技术性失败信息转成一句人话，细节放在折叠区。 */
private fun failureHeadline(message: String): String {
    val trimmed = message.trim()
    return if (trimmed.isEmpty()) "这次没能完成，可以重试一次" else trimmed
}

@Composable
private fun SettingsPrompt(onOpenSettings: () -> Unit) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Tokens.RadiusMedium))
                .background(Tokens.WarningContainer)
                .clickable(onClick = onOpenSettings)
                .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "需要先配置模型才能解题，点这里去设置",
            style = MaterialTheme.typography.bodyMedium,
            color = Tokens.OnSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 行内一次性提示：不用 Snackbar，避免被输入法顶起时位置漂移。 */
@Composable
private fun InlineHint(
    text: String,
    onDismiss: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .clip(RoundedCornerShape(Tokens.RadiusMedium))
                .background(Tokens.SurfaceVariant)
                .clickable(onClick = onDismiss)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = Tokens.OnSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun OfflineHint() {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(Tokens.WarningContainer)
                .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "当前没有网络，发送会失败",
            style = MaterialTheme.typography.bodySmall,
            color = Tokens.Warning,
        )
    }
}

@Composable
private fun TextAction(
    text: String,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .clip(RoundedCornerShape(50))
                .background(Tokens.SurfaceVariant)
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(text = text, style = MaterialTheme.typography.labelMedium, color = Tokens.OnSurfaceVariant)
    }
}

/** 阶段进度压成一行文字，不再占用一整张卡片。 */
@Composable
private fun StageLine(stages: List<StageProgress>) {
    val running = stages.firstOrNull { it.status == StageStatus.RUNNING }
    val done = stages.count { it.status == StageStatus.DONE }
    Text(
        text =
            if (running != null) {
                "正在" + running.label + "（第 " + (done + 1) + "/" + stages.size + " 步）"
            } else {
                "准备中…"
            },
        style = MaterialTheme.typography.bodySmall,
        color = Tokens.OnSurfaceVariant,
        modifier = Modifier.padding(vertical = 2.dp),
    )
}

/**
 * 底部输入区：图片条 + 输入框 + 发送/停止。
 *
 * 这里的布局顺序是本次改造的关键——图片必须在输入框**上方**且同一容器内，
 * 用户写思路时才能一直看到自己拍了哪几页。
 */
@Composable
private fun Composer(
    draft: SolveDraft,
    pages: List<ComposerPage>,
    pageCount: Int,
    processing: Boolean,
    isSolving: Boolean,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onOpenCamera: () -> Unit,
    onOpenAlbum: () -> Unit,
    onRemovePage: (Int) -> Unit,
    onOpenPage: (Int) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(Tokens.Surface)
                .navigationBarsPadding()
                .imePadding()
                .padding(top = 6.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (pages.isNotEmpty()) {
            ComposerAttachments(
                pages = pages,
                onAddPage = onOpenCamera,
                onRemovePage = onRemovePage,
                onOpenPage = onOpenPage,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ComposerActionButton(
                icon = Icons.Filled.CameraAlt,
                label = "拍照",
                onClick = onOpenCamera,
                highlighted = pages.isEmpty(),
            )
            ComposerActionButton(
                icon = Icons.Filled.PhotoLibrary,
                label = "相册",
                onClick = onOpenAlbum,
            )
            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(Tokens.RadiusXLarge))
                        .background(Tokens.SurfaceVariant),
            ) {
                TextField(
                    value = draft.studentProcess,
                    onValueChange = onDraftChange,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp, max = 120.dp),
                    placeholder = {
                        Text(
                            text = if (pageCount > 0) "说说你的思路（可不填）" else "写下题目，或先拍一张照片",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Tokens.OnSurfaceVariant,
                        )
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    colors =
                        TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                    maxLines = 5,
                )
            }
            if (isSolving) {
                StopCircleButton(onClick = onStop)
            } else {
                SendCircleButton(
                    enabled = !processing && (pages.isNotEmpty() || draft.studentProcess.isNotBlank()),
                    processing = processing,
                    onClick = onSend,
                )
            }
        }
    }
}

@Composable
private fun ComposerActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    highlighted: Boolean = false,
) {
    Column(
        modifier = Modifier.clip(RoundedCornerShape(Tokens.RadiusMedium)).clickable(onClick = onClick).padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (highlighted) Tokens.Primary else Tokens.OnSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.height(1.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (highlighted) Tokens.Primary else Tokens.OnSurfaceVariant,
        )
    }
}

@Composable
private fun SendCircleButton(
    enabled: Boolean,
    processing: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(if (enabled) Tokens.Primary else Tokens.OutlineStrong)
                .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (processing) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
        } else {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Send,
                contentDescription = "发送",
                tint = Color.White,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun StopCircleButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(44.dp).clip(CircleShape).background(Tokens.Error).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Stop,
            contentDescription = "停止生成",
            tint = Color.White,
            modifier = Modifier.size(20.dp),
        )
    }
}
