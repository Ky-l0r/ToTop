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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.totop.app.data.repo.SolveSession
import com.totop.app.domain.model.TeachingMode
import com.totop.app.ui.chat.ComposerPhase
import com.totop.app.ui.chat.ConversationMessage
import com.totop.app.ui.chat.GuidePolicy
import com.totop.app.ui.chat.ModeMeta
import com.totop.app.ui.chat.SolveDraft
import com.totop.app.ui.theme.Tokens

/**
 * 对话式拍题页（核心交互）。
 *
 * 布局对齐主流对话式 AI 应用的使用习惯（只借鉴交互组织方式，不使用任何第三方素材）：
 *
 * ```
 * ┌──────────────────────────┐
 * │ 顶部：标题 / 历史 / 新对话  │
 * ├──────────────────────────┤
 * │                          │
 * │   消息区（可滚动）         │  ← 学生消息 + AI 解答 + 追问，全部保留
 * │                          │
 * ├──────────────────────────┤
 * │ [图片][图片][+]           │  ← 拍好的图片在输入框**正上方**
 * │ ┌──────────────────┐ 📷  │
 * │ │ 说说你的思路…      │  ↑  │
 * │ └──────────────────┘     │
 * └──────────────────────────┘
 * ```
 *
 * 本次修复的三个交互缺陷：
 * 1. **发送后清空输入框**：文字已经进入消息流，留在输入框里会让人以为没发出去；
 * 2. **追问不重跑流程**：已有解答后输入的文字走追问通道，带上下文回答；
 * 3. **多对话管理**：顶部「历史」可以回到之前任何一条对话，「新对话」显式结束当前题。
 */
@Composable
fun SolveConversationScreen(
    messages: List<ConversationMessage>,
    draft: SolveDraft,
    pages: List<ComposerPage>,
    pageCount: Int,
    processing: Boolean,
    phase: ComposerPhase,
    networkOnline: Boolean,
    mode: TeachingMode,
    sessions: List<SolveSession>,
    currentSessionId: String?,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onOpenCamera: () -> Unit,
    onOpenAlbum: () -> Unit,
    onRemovePage: (Int) -> Unit,
    onOpenPage: (Int) -> Unit,
    onOpenSettings: () -> Unit,
    onRetry: () -> Unit,
    onStartNewSession: () -> Unit,
    onOpenSession: (String) -> Unit,
    onDeleteSession: (String) -> Unit,
    onSetMode: (TeachingMode) -> Unit,
    onOpenKnowledgePoint: (String) -> Unit,
    onUnlockHint: (String) -> Unit,
    onRevealSolution: (String) -> Unit,
    message: String? = null,
    onDismissMessage: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val isSolving = phase == ComposerPhase.SOLVING
    var historyOpen by remember { mutableStateOf(false) }

    // 只在「消息条数变化」时滚到底部。
    //
    // 不能直接用 messages 做 key：流式生成期间每条消息的文本都在变，
    // 会让 animateScrollToItem 每帧触发一次，既掉帧又和用户的手动滚动打架。
    LaunchedEffect(messages.size) {
        val count = listState.layoutInfo.totalItemsCount
        if (count > 0) listState.animateScrollToItem(count - 1)
    }

    Column(modifier = modifier.fillMaxSize().background(Tokens.Background)) {
        ConversationTopBar(
            title = sessions.firstOrNull { it.id == currentSessionId }?.displayTitle ?: "拍题答疑",
            onOpenHistory = { historyOpen = true },
            onStartNewSession = onStartNewSession,
        )
        ConversationList(
            messages = messages,
            mode = mode,
            listState = listState,
            onOpenSettings = onOpenSettings,
            onRetry = onRetry,
            onSetMode = onSetMode,
            onOpenKnowledgePoint = onOpenKnowledgePoint,
            onUnlockHint = onUnlockHint,
            onRevealSolution = onRevealSolution,
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

    if (historyOpen) {
        SessionHistoryDialog(
            sessions = sessions,
            currentSessionId = currentSessionId,
            onOpen = { id ->
                onOpenSession(id)
                historyOpen = false
            },
            onDelete = onDeleteSession,
            onDismiss = { historyOpen = false },
        )
    }
}

/** 顶部栏：标题 + 历史 + 新对话。 */
@Composable
private fun ConversationTopBar(
    title: String,
    onOpenHistory: () -> Unit,
    onStartNewSession: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(Tokens.Surface)
                .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = Tokens.OnSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 8.dp),
        )
        IconButton(onClick = onOpenHistory) {
            Icon(Icons.Filled.History, contentDescription = "历史对话", tint = Tokens.OnSurfaceVariant)
        }
        IconButton(onClick = onStartNewSession) {
            Icon(Icons.Filled.Add, contentDescription = "开始新对话", tint = Tokens.Primary)
        }
    }
}

/**
 * 历史对话列表。
 *
 * 这是用户明确要求的「像 DeepSeek 客户端那样的多对话管理」：
 * 可以回到之前任何一次问答继续回顾，也可以删掉不要的记录。
 */
@Composable
private fun SessionHistoryDialog(
    sessions: List<SolveSession>,
    currentSessionId: String?,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("历史对话") },
        text = {
            if (sessions.isEmpty()) {
                Text(
                    text = "还没有历史对话。拍一道题、问一句，这里就会有记录。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Tokens.OnSurfaceVariant,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(sessions.size) { index ->
                        val session = sessions[index]
                        SessionRow(
                            session = session,
                            selected = session.id == currentSessionId,
                            onOpen = { onOpen(session.id) },
                            onDelete = { onDelete(session.id) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

@Composable
private fun SessionRow(
    session: SolveSession,
    selected: Boolean,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Tokens.RadiusMedium))
                .background(if (selected) Tokens.PrimaryContainer else Tokens.SurfaceVariant)
                .clickable(onClick = onOpen)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = session.displayTitle,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) Tokens.Primary else Tokens.OnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text =
                    if (session.subtitle.isNotBlank()) {
                        session.subtitle
                    } else {
                        if (session.archived) "已结束" else session.roundCount.toString() + " 轮对话"
                    },
                style = MaterialTheme.typography.labelSmall,
                color = Tokens.OnSurfaceVariant,
            )
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = "删除这条对话",
                tint = Tokens.OnSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun ConversationList(
    messages: List<ConversationMessage>,
    mode: TeachingMode,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onOpenSettings: () -> Unit,
    onRetry: () -> Unit,
    onSetMode: (TeachingMode) -> Unit,
    onOpenKnowledgePoint: (String) -> Unit,
    onUnlockHint: (String) -> Unit,
    onRevealSolution: (String) -> Unit,
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
        if (messages.isEmpty()) {
            item { EmptyConversationHint() }
            item { ModeRow(mode = mode, onSetMode = onSetMode) }
            return@LazyColumn
        }
        item { ModeRow(mode = mode, onSetMode = onSetMode) }
        items(messages.size) { index ->
            val item = messages[index]
            MessageItem(
                message = item,
                mode = mode,
                onOpenSettings = onOpenSettings,
                onRetry = onRetry,
                onOpenKnowledgePoint = onOpenKnowledgePoint,
                onUnlockHint = onUnlockHint,
                onRevealSolution = onRevealSolution,
            )
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
            text = "题目跨页就接着拍，多张会当作同一道题来看。" +
                "解答之后还能继续追问，聊聊已经问过的这道题。",
            style = MaterialTheme.typography.bodySmall,
            color = Tokens.OnSurfaceVariant,
        )
    }
}

@Composable
private fun ModeRow(
    mode: TeachingMode,
    onSetMode: (TeachingMode) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TeachingMode.entries.forEach { candidate ->
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

@Composable
private fun SettingsEntry(onOpenSettings: () -> Unit) {
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
                            text =
                                when {
                                    pageCount > 0 -> "说说你的思路（可不填）"
                                    else -> "写下题目，或先拍一张照片"
                                },
                            style = MaterialTheme.typography.bodyMedium,
                            color = Tokens.OnSurfaceVariant,
                        )
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { onSend() }),
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
