package com.totop.app.ui.solve

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.rememberUpdatedState
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
import com.totop.app.ml.rememberRecordAudioPermissionRequester
import com.totop.app.ml.rememberVoiceInput
import com.totop.app.ui.chat.ComposerPhase
import com.totop.app.ui.chat.ConversationMessage
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
    onUnderstood: () -> Unit,
    onOpenSession: (String) -> Unit,
    onDeleteSession: (String) -> Unit,
    onSetMode: (TeachingMode) -> Unit,
    onOpenKnowledgePoint: (String) -> Unit,
    onUnlockHint: (String) -> Unit,
    onRevealSolution: (String) -> Unit,
    /** 生成期间的状态文案（当前阶段），用于「AI 正在思考」占位。 */
    thinkingHint: String = "",
    onNotice: (String) -> Unit = {},
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

    // 「我已理解」只在「这道题已经答完」之后出现：
    // 已经有结构化解答，且最后一条是已结束、没有报错的 AI 回复。
    val canFinish =
        messages.any { it.solution != null } &&
            messages.lastOrNull()?.let { !it.fromStudent && !it.streaming && it.errorText == null } == true

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().background(Tokens.Background)) {
            ConversationTopBar(
                title = sessions.firstOrNull { it.id == currentSessionId }?.displayTitle ?: "拍题答疑",
                onOpenHistory = { historyOpen = true },
                onStartNewSession = onStartNewSession,
            )
            ConversationList(
                messages = messages,
                mode = mode,
                listState = listState,
                thinkingHint = thinkingHint,
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
            if (canFinish) {
                UnderstoodBar(onUnderstood = onUnderstood)
            }
            Composer(
                draft = draft,
                pages = pages,
                pageCount = pageCount,
                processing = processing,
                isSolving = isSolving,
                hasSolution = messages.any { it.solution != null },
                onDraftChange = onDraftChange,
                onSend = onSend,
                onStop = onStop,
                onOpenCamera = onOpenCamera,
                onOpenAlbum = onOpenAlbum,
                onRemovePage = onRemovePage,
                onOpenPage = onOpenPage,
                onNotice = onNotice,
            )
        }

        // 侧边栏从左侧覆盖在对话之上，而不是把对话挤走：
        // 挤走会让消息区重新测量、滚动位置跳一下，切回来找不到刚才读到哪儿。
        if (historyOpen) {
            SessionDrawer(
                sessions = sessions,
                currentSessionId = currentSessionId,
                onOpen = { id ->
                    onOpenSession(id)
                    historyOpen = false
                },
                onDelete = onDeleteSession,
                onStartNew = {
                    onStartNewSession()
                    historyOpen = false
                },
                onDismiss = { historyOpen = false },
            )
        }
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
                .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 左上角的菜单键：主流对话客户端的会话列表入口都在这里。
        IconButton(onClick = onOpenHistory) {
            Icon(
                imageVector = Icons.Filled.Menu,
                contentDescription = "打开会话列表",
                tint = Tokens.OnSurface,
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = Tokens.OnSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 2.dp),
        )
        IconButton(onClick = onStartNewSession) {
            Icon(Icons.Filled.Add, contentDescription = "开始新对话", tint = Tokens.Primary)
        }
    }
}

@Composable
private fun ConversationList(
    messages: List<ConversationMessage>,
    mode: TeachingMode,
    listState: androidx.compose.foundation.lazy.LazyListState,
    thinkingHint: String,
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
                thinkingHint = if (item.streaming) thinkingHint else "",
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
            text =
                "拍完可以在下面写你是怎么想的。有了你的思路，我才能指出第一个错在哪一步；" +
                    "不写也能给标准解答。",
            style = MaterialTheme.typography.bodyMedium,
            color = Tokens.OnSurfaceVariant,
        )
        Text(
            text =
                "一条对话只对应一道题：题目跨页就接着拍，多张会当作同一道题。" +
                    "解答之后还能继续追问；如果我没看清，你再补拍一张，也算这道题的补充。" +
                    "想换下一题，点右上角「+」或下面的「我已理解」。",
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

/**
 * 「我已理解」条：这道题答完之后，学生用一个动作结束这一题。
 *
 * 位置刻意放在输入框正上方而不是消息流末尾：消息流会继续往下长（追问越多越长），
 * 放在末尾意味着每次都要先滑到底；而这一条回答的是「现在这道题讲完了吗」，
 * 属于输入区的语义，跟着输入框走才不会被埋掉。
 *
 * 点击后的动作是**新建对话**（清空输入区、归档当前会话），
 * 而不是「什么都不做只显示一个已理解标记」——后者对学生没有实际去处。
 */
@Composable
private fun UnderstoodBar(onUnderstood: () -> Unit) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(Tokens.RadiusMedium))
                .background(Tokens.PrimaryContainer)
                .padding(start = 12.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "这道题讲清楚了？",
            style = MaterialTheme.typography.bodySmall,
            color = Tokens.OnPrimaryContainer,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onUnderstood) {
            Text(
                text = "我已理解",
                style = MaterialTheme.typography.labelLarge,
                color = Tokens.Primary,
            )
        }
    }
}

/**
 * 底部输入区：图片条 + 输入框 + 发送/停止。
 *
 * 布局取舍（0.4.0）：
 * 旧版在输入框左侧并排放「拍照」「相册」两个**带文字的竖排按钮**，右侧是发送圆钮，
 * 中间留给输入框的宽度只剩一半，一行里挤了五个可点元素。
 * 现在把拍照/相册收进输入框左侧一个「+」菜单（同一入口，两个选项），
 * 语音按钮放进输入框内部右侧，发送按钮留在外面——
 * 可点元素从五个降到三个，输入框宽度回到主位。
 */
@Composable
private fun Composer(
    draft: SolveDraft,
    pages: List<ComposerPage>,
    pageCount: Int,
    processing: Boolean,
    isSolving: Boolean,
    hasSolution: Boolean,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onOpenCamera: () -> Unit,
    onOpenAlbum: () -> Unit,
    onRemovePage: (Int) -> Unit,
    onOpenPage: (Int) -> Unit,
    onNotice: (String) -> Unit,
) {
    var attachMenuOpen by remember { mutableStateOf(false) }
    val voice = rememberVoiceInput()
    val requestMicPermission = rememberRecordAudioPermissionRequester()
    // 语音识别会给多次「部分结果」，每次都是这一轮的完整句子，
    // 因此以「开口前的文本」为前缀拼接，而不是把每次结果叠加。
    var voiceBase by remember { mutableStateOf("") }
    val currentText by rememberUpdatedState(draft.studentProcess)

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(Tokens.Surface)
                .navigationBarsPadding()
                .imePadding()
                .padding(top = 6.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (pages.isNotEmpty()) {
            ComposerAttachments(
                pages = pages,
                onAddPage = onOpenCamera,
                onRemovePage = onRemovePage,
                onOpenPage = onOpenPage,
            )
        }
        if (voice.listening) {
            Text(
                text = "正在听你说… 说完会自动填进输入框",
                style = MaterialTheme.typography.labelSmall,
                color = Tokens.Primary,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier =
                    Modifier
                        .weight(1f)
                        .heightIn(min = 46.dp)
                        .clip(RoundedCornerShape(Tokens.RadiusXLarge))
                        .background(Tokens.SurfaceVariant)
                        .padding(start = 2.dp, end = 2.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Box {
                    IconButton(onClick = { attachMenuOpen = true }, modifier = Modifier.size(40.dp)) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = "添加题目照片",
                            tint = Tokens.Primary,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    DropdownMenu(expanded = attachMenuOpen, onDismissRequest = { attachMenuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("拍照") },
                            leadingIcon = { Icon(Icons.Filled.CameraAlt, contentDescription = null) },
                            onClick = {
                                attachMenuOpen = false
                                onOpenCamera()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("从相册选") },
                            leadingIcon = { Icon(Icons.Filled.PhotoLibrary, contentDescription = null) },
                            onClick = {
                                attachMenuOpen = false
                                onOpenAlbum()
                            },
                        )
                    }
                }
                TextField(
                    value = draft.studentProcess,
                    onValueChange = onDraftChange,
                    modifier = Modifier.weight(1f).heightIn(min = 44.dp, max = 120.dp),
                    placeholder = {
                        Text(
                            text =
                                when {
                                    hasSolution -> "继续追问，或补拍一张更清楚的"
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
                IconButton(
                    onClick = {
                        requestMicPermission {
                            if (voice.listening) {
                                voice.stop()
                            } else {
                                voiceBase = currentText
                                voice.start(
                                    onText = { spoken -> onDraftChange(voiceBase + spoken) },
                                    onError = onNotice,
                                )
                            }
                        }
                    },
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = if (voice.listening) Icons.Filled.MicOff else Icons.Filled.Mic,
                        contentDescription = if (voice.listening) "停止语音输入" else "语音输入",
                        tint = if (voice.listening) Tokens.Primary else Tokens.OnSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                    )
                }
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
