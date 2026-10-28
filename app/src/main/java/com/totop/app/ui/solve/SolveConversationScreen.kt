package com.totop.app.ui.solve

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
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
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreHoriz
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.totop.app.data.prefs.VoiceEnginePrefs
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
    onMastered: () -> Unit,
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
                masteredEnabled = canFinish,
                onMastered = onMastered,
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
    masteredEnabled: Boolean,
    onMastered: () -> Unit,
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
        // 「已掌握」放在新建对话左边：答完题之后，这是一个比「再开一条」更有意义的动作，
        // 但它的优先级不该高过换题，所以用图标而不是按钮，且未答完时置灰。
        IconButton(onClick = onMastered, enabled = masteredEnabled) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = "这道题已掌握，开下一题",
                tint = if (masteredEnabled) Tokens.Success else Tokens.OutlineStrong,
            )
        }
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
 * 底部输入区：图片条 + 语音/文字双态输入 + 常驻相机与更多。
 *
 * 布局取舍（0.5.0，对齐参考图的三段式）：
 *
 * ```
 *   [ 键盘/麦克风 ] [ 输入框 或 按住说话 ] [ 相机 ] [ 更多 ]
 * ```
 *
 * - 左侧按钮切换语音与文字：语音态中间整块是「按住 说话」，文字态才是输入框；
 * - 相机常驻在最右侧（拍题是最高频动作），相册收进「更多」；
 * - 发送键只在有内容时出现，语音态识别出文字后同样出现；
 * - 语音期间在上方浮出相纹面板与识别结果条，不占输入区宽度。
 *
 * 上一版把拍照/相册一起收进「+」菜单，结果每次拍题都要多点一次；
 * 这一版把相机拿回来，只把相册留在菜单里。
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
    // 默认停在语音态：一句话的思路说出来比打出来快。已经有草稿时停在文字态，
    // 否则用户看不到自己写了什么（草稿是跨会话保留的，进来常常不是空的）。
    var voiceMode by remember { mutableStateOf(draft.studentProcess.isBlank()) }
    // 切到键盘态后要拉起的输入法：不能在同一次点击里 requestFocus，
    // 那时 TextField 还没被组合出来，会抛 IllegalStateException。
    var focusRequested by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val context = LocalContext.current
    val voice = rememberVoiceInput()
    val requestMicPermission = rememberRecordAudioPermissionRequester()

    // 语音识别会给多次「部分结果」，每次都是这一轮的完整句子，
    // 因此以「开口前的文本」为前缀拼接，而不是把每次结果叠加。
    var voiceBase by remember { mutableStateOf("") }
    // 手指是否还按着：首次授权时系统弹框会打断手势，
    // 授权回来时手指早就松了，这时不能再开始录音，否则会一直录到超时。
    var pressHeld by remember { mutableStateOf(false) }
    val currentText by rememberUpdatedState(draft.studentProcess)

    LaunchedEffect(focusRequested, voiceMode) {
        if (focusRequested && !voiceMode) {
            focusRequested = false
            runCatching { focusRequester.requestFocus() }
        }
    }

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

        if (voice.listening || voice.finishing) {
            VoiceCapturePanel(
                level = voice.level,
                locked = voice.locked,
                finishing = voice.finishing,
                onFinish = voice::finish,
            )
        } else if (voiceMode && draft.studentProcess.isNotBlank()) {
            VoiceTranscriptStrip(
                text = draft.studentProcess,
                onEdit = {
                    voiceMode = false
                    focusRequested = true
                },
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
                        .padding(horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = {
                        if (voiceMode) {
                            voice.cancel()
                            voiceMode = false
                            focusRequested = true
                        } else {
                            voiceMode = true
                        }
                    },
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = if (voiceMode) Icons.Filled.Keyboard else Icons.Filled.Mic,
                        contentDescription = if (voiceMode) "切换到键盘输入" else "切换到语音输入",
                        tint = Tokens.OnSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                    )
                }

                if (voiceMode) {
                    HoldToTalk(
                        listening = voice.listening,
                        locked = voice.locked,
                        modifier = Modifier.weight(1f),
                        onPress = {
                            pressHeld = true
                            requestMicPermission {
                                if (!pressHeld) {
                                    onNotice("已开启录音权限，再按住说一次")
                                    return@requestMicPermission
                                }
                                voiceBase = currentText
                                voice.press(
                                    engineId = VoiceEnginePrefs.selectedId(context),
                                    onText = { spoken -> onDraftChange(voiceBase + spoken) },
                                    onError = onNotice,
                                    onEngineSwitched = { engine ->
                                        onNotice("已自动切换到「" + engine.label + "」继续识别")
                                    },
                                )
                            }
                        },
                        onRelease = {
                            pressHeld = false
                            voice.release()
                        },
                        onLock = voice::lock,
                        onFinish = voice::finish,
                    )
                } else {
                    TextField(
                        value = draft.studentProcess,
                        onValueChange = onDraftChange,
                        modifier =
                            Modifier
                                .weight(1f)
                                .heightIn(min = 44.dp, max = 120.dp)
                                .focusRequester(focusRequester),
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
                }

                ComposerActionIcons(
                    menuOpen = attachMenuOpen,
                    onMenuOpenChange = { attachMenuOpen = it },
                    onOpenCamera = {
                        voice.cancel()
                        onOpenCamera()
                    },
                    onOpenAlbum = {
                        voice.cancel()
                        onOpenAlbum()
                    },
                )
            }

            // 语音态且什么都没拍、什么都没说时不占位：这时候发送没有意义。
            val showSend = !voiceMode || draft.studentProcess.isNotBlank() || pages.isNotEmpty()
            if (showSend) {
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
}

/** 上滑触发锁定所需的最小位移。24dp 低于常见的滑动手势阈值，不容易误触。 */
private const val LOCK_SLIDE_DP: Float = 24f

/** 声纹条的相对权重：固定形状比随机跳动的方块更好读，也免掉每条都做一次动画。 */
private val WAVE_WEIGHTS: List<Float> =
    listOf(0.45f, 0.7f, 1f, 0.85f, 0.55f, 0.9f, 0.65f, 1f, 0.75f, 0.5f, 0.85f, 0.6f)

/**
 * 「按住 说话」区域。
 *
 * 手势用 [awaitEachGesture] 手写而不是 [detectDragGesturesAfterLongPress]：
 * 后者必须先等长按判定结束才回调拖动，而这里需要「按下就开始录音」——
 * 等 500ms 再开口会让第一个字被吃掉。
 *
 * 状态只从参数读一次（用 [rememberUpdatedState] 包住），
 * 因为锁定会触发重组，而重组会取消正在进行的 pointerInput 协程。
 */
@Composable
private fun HoldToTalk(
    listening: Boolean,
    locked: Boolean,
    onPress: () -> Unit,
    onRelease: () -> Unit,
    onLock: () -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    // 手势协程只创建一次，暴露的回调必须通过状态取最新值，
    // 否则重组后拿到的还是第一帧那份 lambda。
    val lockedNow by rememberUpdatedState(locked)
    val onPressNow by rememberUpdatedState(onPress)
    val onReleaseNow by rememberUpdatedState(onRelease)
    val onLockNow by rememberUpdatedState(onLock)
    val onFinishNow by rememberUpdatedState(onFinish)
    val threshold = with(LocalDensity.current) { LOCK_SLIDE_DP.dp.toPx() }

    Box(
        modifier =
            modifier
                .height(44.dp)
                .clip(RoundedCornerShape(Tokens.RadiusXLarge))
                .background(if (listening) Tokens.PrimaryContainer else Color.Transparent)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        if (lockedNow) {
                            // 锁定态下再按一次 = 结束，而不是再开一轮。
                            onFinishNow()
                            waitForUpOrCancellation()
                            return@awaitEachGesture
                        }
                        onPressNow()
                        var reachedLock = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                change.consume()
                                break
                            }
                            if (!reachedLock && down.position.y - change.position.y > threshold) {
                                reachedLock = true
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onLockNow()
                            }
                            change.consume()
                        }
                        onReleaseNow()
                    }
                },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (listening) "松开 结束" else "按住 说话",
            style = MaterialTheme.typography.bodyMedium,
            color = if (listening) Tokens.Primary else Tokens.OnSurfaceVariant,
            fontWeight = if (listening) FontWeight.Medium else FontWeight.Normal,
        )
    }
}

/**
 * 录音面板：声纹 + 操作提示。
 *
 * 声纹取当前音量做高度，不加逐条动画：`onRmsChanged` 约 10Hz，
 * 12 条各自跑一个动画在低端机上比直接改高度更卡，看起来也更像抖动。
 */
@Composable
private fun VoiceCapturePanel(
    level: Float,
    locked: Boolean,
    finishing: Boolean,
    onFinish: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .clip(RoundedCornerShape(Tokens.RadiusMedium))
                .background(Tokens.PrimaryContainer)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WAVE_WEIGHTS.forEach { weight ->
                Box(
                    modifier =
                        Modifier
                            .width(3.dp)
                            .height((6f + 22f * level * weight).dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(if (finishing) Tokens.OutlineStrong else Tokens.Primary),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text =
                when {
                    finishing -> "正在整理…"
                    locked -> "继续录音 · 点一下结束"
                    else -> "松手结束 · 上滑锁定"
                },
            style = MaterialTheme.typography.labelMedium,
            color = Tokens.OnPrimaryContainer,
            modifier = Modifier.weight(1f),
        )
        if (locked && !finishing) {
            TextButton(onClick = onFinish) {
                Text(text = "结束", style = MaterialTheme.typography.labelLarge, color = Tokens.Primary)
            }
        }
    }
}

/** 语音识别出来的文字条：点一下切到键盘态去改，语音态本身看不到光标。 */
@Composable
private fun VoiceTranscriptStrip(
    text: String,
    onEdit: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .clip(RoundedCornerShape(Tokens.RadiusMedium))
                .background(Tokens.PrimaryContainer)
                .clickable(onClick = onEdit)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "已识别：" + text,
            style = MaterialTheme.typography.bodySmall,
            color = Tokens.OnPrimaryContainer,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "改一下",
            style = MaterialTheme.typography.labelMedium,
            color = Tokens.Primary,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/** 输入框右侧的常驻动作：拍照 + 更多（相册）。 */
@Composable
private fun ComposerActionIcons(
    menuOpen: Boolean,
    onMenuOpenChange: (Boolean) -> Unit,
    onOpenCamera: () -> Unit,
    onOpenAlbum: () -> Unit,
) {
    IconButton(onClick = onOpenCamera, modifier = Modifier.size(40.dp)) {
        Icon(
            imageVector = Icons.Filled.CameraAlt,
            contentDescription = "拍照",
            tint = Tokens.Primary,
            modifier = Modifier.size(22.dp),
        )
    }
    Box {
        IconButton(onClick = { onMenuOpenChange(true) }, modifier = Modifier.size(40.dp)) {
            Icon(
                imageVector = Icons.Filled.MoreHoriz,
                contentDescription = "更多",
                tint = Tokens.OnSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { onMenuOpenChange(false) }) {
            DropdownMenuItem(
                text = { Text("从相册选") },
                leadingIcon = { Icon(Icons.Filled.PhotoLibrary, contentDescription = null) },
                onClick = {
                    onMenuOpenChange(false)
                    onOpenAlbum()
                },
            )
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
