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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.totop.app.data.prefs.VoiceEnginePrefs
import com.totop.app.data.repo.SolveSession
import com.totop.app.domain.model.SolutionAsk
import com.totop.app.domain.model.TeachingMode
import com.totop.app.ml.rememberRecordAudioPermissionRequester
import com.totop.app.ml.rememberVoiceInput
import com.totop.app.ui.chat.ComposerPhase
import com.totop.app.ui.chat.ConversationMessage
import com.totop.app.ui.chat.ImportSolutionUi
import com.totop.app.ui.chat.ModeMeta
import com.totop.app.ui.chat.QuickThinkUi
import com.totop.app.ui.chat.SolutionPayload
import com.totop.app.ui.chat.SolveDraft
import com.totop.app.ui.common.HoldToTalk
import com.totop.app.ui.common.VoiceCapturePanel
import com.totop.app.ui.common.VoiceTranscriptStrip
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
 * │ ┌──────────────────┐ [相机] │
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
    /** 把这一轮解答存进资料库（参数是来源标注）。 */
    onSaveMaterial: (String, String) -> Unit = { _, _ -> },
    /** 自检「我核对过，一致」。 */
    onSelfCheckAgree: (String?) -> Unit = {},
    onRewriteSolution: (SolutionPayload) -> Unit = {},
    /** 自检「核出来不一样」：把这题预填进追问。 */
    onSelfCheckDisagree: (String?) -> Unit = {},
    /** 解答卡「这题我不会」：就地把这道题改回「不会」。 */
    onMarkUnknown: (String?) -> Unit = {},
    /** 输入框「+」菜单：要一份标准解法 / 另一种做法。 */
    onRequestSolution: (SolutionAsk) -> Unit = {},
    /** 考场思维闪记的当前状态与操作。 */
    quickThink: QuickThinkUi = QuickThinkUi(),
    onRequestQuickThink: () -> Unit = {},
    onDismissQuickThink: () -> Unit = {},
    /** 导入标准答案：读图 → 展示 → 确认覆盖。 */
    importSolution: ImportSolutionUi = ImportSolutionUi(),
    onImportSolution: () -> Unit = {},
    onConfirmImport: () -> Unit = {},
    onDismissImport: () -> Unit = {},
    /** 当前这道题是否已经被标记为「已掌握」；顶部勾选只有它为真时才变绿。 */
    masteredQuestionId: String? = null,
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

    // 「已掌握」的可用条件：这道题已经有结构化解答，且当前不在生成中。
    //
    // 旧实现还要求「最后一条消息是正常结束的 AI 回复」，于是最后一条若是
    // 一次追问的提示、或流式标记因为断网没有回落，勾选键就一直是灰的——
    // 学生看到的是一个按了没反应的按钮（真机反馈的原话）。
    // 现在只拦「真的还没有解答」和「正在生成」两种情况，
    // 且按钮始终可点，点不动时给一句说明，而不是静默失效。
    val canFinish = messages.any { it.solution != null } && !isSolving

    // 绿色只能表示「这道题已经标记过掌握」，不能表示「可以标记」。
    //
    // 旧实现直接用 canFinish 决定颜色：拿到解答就变绿，学生还没自己重做一遍，
    // 看到的就是「我还没掌握，勾选已经是绿的」。现在分成三档：
    // 已标记 -> 绿；有解答可标记 -> 主色（提示这里能点）；其余 -> 中性灰。
    val currentQuestionId = messages.lastOrNull { it.solution != null }?.solution?.questionId
    val mastered = masteredQuestionId != null && masteredQuestionId == currentQuestionId

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().background(Tokens.Background)) {
            ConversationTopBar(
                title = sessions.firstOrNull { it.id == currentSessionId }?.displayTitle ?: "拍题答疑",
                masteredEnabled = canFinish,
                mastered = mastered,
                // 不可用时也要有反馈：灰按钮点了什么都不发生，是「点击无响应」的直接来源。
                onMastered = {
                    if (canFinish) {
                        onMastered()
                    } else {
                        onNotice(
                            if (isSolving) {
                                "还在生成解答，答完就能标记「理解」"
                            } else {
                                "这道题还没有解答，拍一张或写一句话先问一问"
                            },
                        )
                    }
                },
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
                onSaveMaterial = { messageId, source -> onSaveMaterial(messageId, source) },
                onSelfCheckAgree = onSelfCheckAgree,
                onRewriteSolution = onRewriteSolution,
                onSelfCheckDisagree = onSelfCheckDisagree,
                onMarkUnknown = onMarkUnknown,
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
                onRequestSolution = onRequestSolution,
                quickThink = quickThink,
                onRequestQuickThink = onRequestQuickThink,
                onDismissQuickThink = onDismissQuickThink,
                importSolution = importSolution,
                onImportSolution = onImportSolution,
                onConfirmImport = onConfirmImport,
                onDismissImport = onDismissImport,
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

        // 页面级公式渲染宿主：WebView 必须先 attach 到窗口，取像素才可靠。
        // 不在这里放时，靠每条公式自己兜底挂载——而兜底宿主挂在消息项内部，
        // 列表回收那一项后宿主随之 detach，后面的公式于是全部渲染失败（表现为一片空白）。
        com.totop.app.ui.components.FormulaRenderHost()
    }
}

/** 顶部栏：标题 + 历史 + 新对话。 */
@Composable
private fun ConversationTopBar(
    title: String,
    masteredEnabled: Boolean,
    mastered: Boolean,
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
        // 但它的优先级不该高过换题，所以用图标而不是按钮。
        // 始终 enabled：不可用时由上层弹一句说明，而不是让按钮变成灰的、点了没反应。
        IconButton(onClick = onMastered) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription =
                    when {
                        mastered -> "这道题已标记为理解"
                        masteredEnabled -> "标记这道题为理解，并开下一题"
                        else -> "这道题还没有解答，暂时不能标记"
                    },
                tint =
                    when {
                        mastered -> Tokens.Success
                        // 可标记但还没标记：用主色提示「这里能点」，绿色留给真正标记过的题
                        masteredEnabled -> Tokens.Primary
                        else -> Tokens.OutlineStrong
                    },
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
    onSaveMaterial: (String, String) -> Unit,
    onSelfCheckAgree: (String?) -> Unit,
    onSelfCheckDisagree: (String?) -> Unit,
    onMarkUnknown: (String?) -> Unit,
    onRewriteSolution: (SolutionPayload) -> Unit,
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
                onSaveMaterial = { source -> onSaveMaterial(item.id, source) },
                onSelfCheckAgree = onSelfCheckAgree,
                onSelfCheckDisagree = onSelfCheckDisagree,
                onMarkUnknown = onMarkUnknown,
                onRewriteSolution = onRewriteSolution,
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
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
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
    onRequestSolution: (SolutionAsk) -> Unit,
    quickThink: QuickThinkUi,
    onRequestQuickThink: () -> Unit,
    onDismissQuickThink: () -> Unit,
    importSolution: ImportSolutionUi,
    onImportSolution: () -> Unit,
    onConfirmImport: () -> Unit,
    onDismissImport: () -> Unit,
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
    // 发出去了就不要再把识别结果写回输入框：识别引擎的最终结果常比「松手」晚到，
    // 迟到的 onResults 会把刚清空的草稿又填回去，看起来像没发出去。
    var suppressVoice by remember { mutableStateOf(false) }
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

    // 三点菜单改成底部抽屉（0.9）：下拉菜单在小屏上贴着按钮展开，五个动作挤成一条窄列；
    // 抽屉能把图标、文字与分组都摆开，也顺手解决了「拍照只在下拉里出现过一次」的问题。
    if (attachMenuOpen) {
        ModalBottomSheet(
            onDismissRequest = { attachMenuOpen = false },
            containerColor = Tokens.Surface,
        ) {
            SheetAction(text = "拍照", icon = Icons.Filled.CameraAlt) {
                attachMenuOpen = false
                voice.cancel()
                onOpenCamera()
            }
            SheetAction(text = "从相册选", icon = Icons.Filled.PhotoLibrary) {
                attachMenuOpen = false
                voice.cancel()
                onOpenAlbum()
            }
            SolutionAsk.entries.forEach { kind ->
                SheetAction(
                    text = "看" + kind.label,
                    icon =
                        if (kind == SolutionAsk.STANDARD) {
                            Icons.AutoMirrored.Filled.MenuBook
                        } else {
                            Icons.Filled.Lightbulb
                        },
                ) {
                    attachMenuOpen = false
                    // 与拍照一致：先把语音识别掐掉，否则识别结果会在提问发出后才落进输入框。
                    voice.cancel()
                    onRequestSolution(kind)
                }
            }
            SheetAction(text = "导入标准答案", icon = Icons.Filled.PhotoLibrary) {
                attachMenuOpen = false
                voice.cancel()
                onImportSolution()
            }
            SheetAction(text = "考场思维闪记", icon = Icons.Filled.Psychology) {
                attachMenuOpen = false
                voice.cancel()
                onRequestQuickThink()
            }
            Spacer(Modifier.height(6.dp))
        }
    }

    if (quickThink.visible) {
        ModalBottomSheet(onDismissRequest = onDismissQuickThink, containerColor = Tokens.Surface) {
            QuickThinkPanel(state = quickThink, onRetry = onRequestQuickThink, onClose = onDismissQuickThink)
        }
    }

    if (importSolution.visible) {
        ImportSolutionDialog(
            state = importSolution,
            onConfirm = onConfirmImport,
            onDismiss = onDismissImport,
        )
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
                hasVolume = voice.hasVolume,
                speaking = voice.speaking,
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
                            suppressVoice = false
                            requestMicPermission {
                                if (!pressHeld) {
                                    onNotice("已开启录音权限，再按住说一次")
                                    return@requestMicPermission
                                }
                                voiceBase = currentText
                                voice.press(
                                    engineId = VoiceEnginePrefs.selectedId(context),
                                    onText = { spoken ->
                                        if (!suppressVoice) onDraftChange(voiceBase + spoken)
                                    },
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
                    onMenuOpenChange = { attachMenuOpen = it },
                    onOpenCamera = {
                        voice.cancel()
                        onOpenCamera()
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
                        onClick = {
                            // 与陪伴页同序：先掐识别、再丢迟到结果、最后发。
                            suppressVoice = true
                            voice.cancel()
                            onSend()
                        },
                    )
                }
            }
        }
    }
}

/**
 * 输入框右侧的常驻动作：拍照 + 更多。
 *
 * 菜单里三行分成两组，中间用分隔线隔开：
 * - 上组「从相册选」是**输入**动作，加的是这道题的素材；
 * - 下组「标准解法 / 其他解法」是**提问**动作，前提是这道题已经有解答。
 *
 * 为什么不做成禁用态而是让它一直可点：
 * 下拉菜单里灰掉的项没有解释、也不知道差什么条件，学生只会觉得「又坏了」；
 * 点下去由上层给一句「这道题还没有解答，先拍一张」更清楚（与顶栏「理解」一致）。
 */
@Composable
private fun ComposerActionIcons(
    onMenuOpenChange: (Boolean) -> Unit,
    onOpenCamera: () -> Unit,
) {
    IconButton(onClick = onOpenCamera, modifier = Modifier.size(40.dp)) {
        Icon(
            imageVector = Icons.Filled.CameraAlt,
            contentDescription = "拍照",
            tint = Tokens.Primary,
            modifier = Modifier.size(22.dp),
        )
    }
    IconButton(onClick = { onMenuOpenChange(true) }, modifier = Modifier.size(40.dp)) {
        Icon(
            imageVector = Icons.Filled.MoreHoriz,
            contentDescription = "更多",
            tint = Tokens.OnSurfaceVariant,
            modifier = Modifier.size(22.dp),
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

/** 抽屉里的一行：图标 + 文字，整行可点。 */
@Composable
private fun SheetAction(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 22.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Tokens.Primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(14.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = Tokens.OnSurface,
        )
    }
}

/**
 * 考场思维闪记面板。
 *
 * 三条状态：正在想 / 没拿到 / 拿到了。
 * 失败不清空上一次的结果——学生可能只是想再看一眼，重新要一次失败就抹掉反而更烦。
 */
@Composable
private fun QuickThinkPanel(
    state: QuickThinkUi,
    onRetry: () -> Unit,
    onClose: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 4.dp)) {
        Text(
            text = "考场思维闪记",
            style = MaterialTheme.typography.titleMedium,
            color = Tokens.OnSurface,
        )
        Text(
            text = "考场上最快的一条路，不是完整写法。",
            style = MaterialTheme.typography.labelSmall,
            color = Tokens.OnSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        Spacer(Modifier.height(12.dp))
        when {
            state.loading -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "正在想最短的那条路…",
                        style = MaterialTheme.typography.bodySmall,
                        color = Tokens.OnSurfaceVariant,
                    )
                }
            }

            state.steps.isEmpty() -> {
                Text(
                    text = state.message.ifBlank { "这次没生成出来" },
                    style = MaterialTheme.typography.bodySmall,
                    color = Tokens.OnSurfaceVariant,
                )
            }

            else -> {
                state.steps.forEachIndexed { index, step ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(
                            text = (index + 1).toString(),
                            style = MaterialTheme.typography.labelMedium,
                            color = Tokens.Primary,
                            modifier = Modifier.width(18.dp),
                        )
                        com.totop.app.ui.components.FormulaText(
                            text = step,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onRetry) {
                Text(if (state.steps.isEmpty()) "再试一次" else "换一份")
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onClose) { Text("关闭") }
        }
        Spacer(Modifier.height(6.dp))
    }
}

/**
 * 导入标准答案的确认框。
 *
 * 三种态：正在读 / 没读到 / 读到了。只有第三种才给「覆盖」按钮——
 * 读失败时如果还留一个确认键，学生很容易点下去，把错题本的解答清掉。
 */
@Composable
private fun ImportSolutionDialog(
    state: ImportSolutionUi,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (state.loading) "正在读这张答案" else "用这份答案覆盖错题本？") },
        text = {
            when {
                state.loading -> {
                    Text("正在把图里的解答整理成步骤，稍等一下。")
                }

                state.message.isNotBlank() -> {
                    Text(state.message)
                }

                else -> {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "读到：" + state.finalAnswer.ifBlank { "（图里没有写最终答案）" },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = if (state.stepCount > 0) "过程 " + state.stepCount + " 步" else "图里只有答案，没有过程",
                            style = MaterialTheme.typography.labelSmall,
                            color = Tokens.OnSurfaceVariant,
                        )
                        state.warnings.forEach { warning ->
                            Text(
                                text = "· " + warning,
                                style = MaterialTheme.typography.labelSmall,
                                color = Tokens.OnSurfaceVariant,
                            )
                        }
                        Text(
                            text = "覆盖后不可恢复。",
                            style = MaterialTheme.typography.labelSmall,
                            color = Tokens.OnSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (!state.loading && state.message.isBlank()) {
                TextButton(onClick = onConfirm) { Text("覆盖") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(if (state.loading) "取消" else "算了") }
        },
    )
}
