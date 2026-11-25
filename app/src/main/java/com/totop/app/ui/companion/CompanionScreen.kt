package com.totop.app.ui.companion

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.totop.app.data.prefs.VoiceEnginePrefs
import com.totop.app.domain.model.StudyNoteKind
import com.totop.app.ml.rememberRecordAudioPermissionRequester
import com.totop.app.ml.rememberVoiceInput
import com.totop.app.ui.common.HoldToTalk
import com.totop.app.ui.common.VoiceCapturePanel
import com.totop.app.ui.common.VoiceTranscriptStrip
import com.totop.app.ui.components.FormulaText
import com.totop.app.ui.theme.Tokens

/**
 * 陪伴作业。
 *
 * 使用场景：学生晚上写作业，手机放在手边（架着或随手能拿）。
 *
 * 定位是**随手问**，计时只是顺便记着。因此这一页不是控制台，是一条问答流：
 *
 * ```
 * ┌──────────────────────────┐
 * │ 陪伴          已写 34 分钟 │  ← 计时退成一行小字
 * ├──────────────────────────┤
 * │              链式法则是什么 │  ← 问过的都在，能翻回去
 * │  外层导数乘内层导数……      │
 * │                    ln(3x) 呢 │
 * │  1/x。外层 ln 出 1/3x……    │
 * ├──────────────────────────┤
 * │ [键盘/麦克风][按住 说话][相机]│  ← 与拍题页同一套语音手势
 * └──────────────────────────┘
 * ```
 *
 * 与上一版的差别见 [CompanionViewModel] 的类注释：没有「这一题」这一层，
 * 没有开始/暂停/写完，进来即计时，离开即结账。
 */
@Composable
fun CompanionScreen(
    modifier: Modifier = Modifier,
    viewModel: CompanionViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    // 新增一条问答、或当前这条答完时滚到底部。
    // 不用「答案每长一个字就滚」：流式增量约 10Hz，反复滚动会把手动翻看顶掉。
    LaunchedEffect(state.items.size, state.asking) {
        if (state.items.isNotEmpty()) listState.animateScrollToItem(state.items.lastIndex)
    }

    Column(modifier = modifier.fillMaxSize().background(Tokens.Background)) {
        CompanionHeader()

        if (state.items.isEmpty()) {
            EmptyStream(Modifier.weight(1f))
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.items.size) { index ->
                    QaBubbles(
                        item = state.items[index],
                        onSaveNote = { kind -> viewModel.saveNote(index, kind) },
                        // 任意一条失败都能重试：增量按这条的下标回填，
                        // 对中间某条重试不会把答案写到别处。
                        // 正在等别的回答时不给入口，免得点了没反应。
                        onRetry = if (state.asking) null else ({ viewModel.retryAt(index) }),
                    )
                }
            }
        }

        AskComposer(
            question = state.question,
            asking = state.asking,
            canAsk = state.canAsk,
            onQuestionChange = viewModel::updateQuestion,
            onAsk = viewModel::ask,
        )

        // 页面级公式渲染宿主：陪伴页的回答里常带公式（「酯基是什么」就会画出结构式），
        // 没有这个宿主时每段公式各自兜底挂载，宿主挂在气泡内部，滚动回收后随之 detach，
        // 之后整页公式都渲染不出来——真机截图里的空白正是这一现象。
        com.totop.app.ui.components.FormulaRenderHost()
    }
}

/**
 * 顶栏：只有标题。没有返回箭头——它是底栏顶层页。
 *
 * 0.9 去掉了右侧那行「今天已写 N 分钟」：陪伴页的定位是随手问，
 * 挂一条不断跳动的数字会让人以为这页在考核坐了多久。
 */
@Composable
private fun CompanionHeader() {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(Tokens.Surface)
                .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "陪伴",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Medium,
            color = Tokens.OnSurface,
        )
    }
}

/**
 * 空态。
 *
 * 底栏里「拍题」和「陪伴」并列，第一次进来会犹豫该点哪个，这里直接把区别说开：
 * 这页不用拍、也不用点开始，开口就行。想拍题的下一步就是底栏那一项。
 */
@Composable
private fun EmptyStream(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 30.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "想问什么，说一句就行",
            style = MaterialTheme.typography.titleMedium,
            color = Tokens.OnSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "这页不用拍照，也不用点开始。写着写着卡住了，按住下面的按钮说一遍：哪个公式想不起来、哪一步推不下去。",
            style = MaterialTheme.typography.bodyMedium,
            color = Tokens.OnSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** 一条问答：右上是自己那句，左下是回答。 */
@Composable
private fun QaBubbles(
    item: CompanionQa,
    onSaveNote: (StudyNoteKind) -> Unit,
    onRetry: (() -> Unit)? = null,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = item.question,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
            modifier =
                Modifier
                    .align(Alignment.End)
                    .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp, bottomEnd = 4.dp, bottomStart = 14.dp))
                    .background(Tokens.Primary)
                    .padding(horizontal = 14.dp, vertical = 9.dp),
        )
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp, bottomEnd = 14.dp, bottomStart = 4.dp))
                    .background(Tokens.PrimaryContainer)
                    .padding(14.dp),
        ) {
            when {
                item.error != null ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = item.error,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Tokens.Error,
                        )
                        // 重试只重发原问题，不新增气泡：写作业时最烦的是「网断了还得重新打一遍」
                        if (onRetry != null) {
                            Text(
                                text = "重试这一问",
                                style = MaterialTheme.typography.labelMedium,
                                color = Tokens.Primary,
                                modifier =
                                    Modifier
                                        .clickable(onClick = onRetry)
                                        .padding(horizontal = 4.dp, vertical = 2.dp),
                            )
                        }
                    }

                item.answer.isEmpty() ->
                    Text(
                        text = "正在想…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Tokens.OnPrimaryContainer,
                    )

                else -> FormulaText(text = item.answer, style = MaterialTheme.typography.bodyMedium)
            }
        }
        // 存为笔记：写在气泡右下角，跟着这一条走。
        //
        // 分类必须让学生点一下，而不是给默认值：两类混在一起，复习时就得自己再分一次。
        // 也不用弹窗——两个两个字并排就够清楚，多点一次只是多一次打断。
        if (item.answer.isNotBlank() && !item.streaming) {
            Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                if (item.savedKind != null) {
                    Text(
                        text = "已存为「" + item.savedKind.label + "」",
                        style = MaterialTheme.typography.labelMedium,
                        color = Tokens.OnSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        NoteSaveAction(text = "记重点", onClick = { onSaveNote(StudyNoteKind.KEY) })
                        NoteSaveAction(text = "记卡点", onClick = { onSaveNote(StudyNoteKind.STUCK) })
                    }
                }
                item.noteError?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/** 气泡右下角的一个「存为笔记」动作。 */
@Composable
private fun NoteSaveAction(
    text: String,
    onClick: () -> Unit,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = Tokens.Primary,
        modifier =
            Modifier
                .clickable(onClick = onClick)
                .padding(horizontal = 4.dp, vertical = 2.dp),
    )
}

/**
 * 底部输入区。
 *
 * 与拍题页共用同一套语音手势（长按开始、上滑锁定、松手结束、声纹反馈），
 * 但少了附件条与相册菜单——这里问的是一句话，不是一道题。
 *
 * 默认停在语音态：写作业时手上有笔，说一句比打一行快。
 */
@Composable
private fun AskComposer(
    question: String,
    asking: Boolean,
    canAsk: Boolean,
    onQuestionChange: (String) -> Unit,
    onAsk: () -> Unit,
) {
    val hostContext = androidx.compose.ui.platform.LocalContext.current
    // 0.9：上次用语音还是键盘，与拍题页共用一套记忆（InputModePrefs）。
    var voiceMode by remember {
        mutableStateOf(
            if (com.totop.app.data.prefs.InputModePrefs.remember(hostContext)) {
                com.totop.app.data.prefs.InputModePrefs.lastVoice(hostContext) ?: true
            } else {
                true
            },
        )
    }
    androidx.compose.runtime.LaunchedEffect(voiceMode) {
        if (com.totop.app.data.prefs.InputModePrefs.remember(hostContext)) {
            com.totop.app.data.prefs.InputModePrefs.setLastVoice(hostContext, voiceMode)
        }
    }
    // 切到键盘态后要拉起的输入法：不能在同一次点击里 requestFocus，
    // 那时 TextField 还没被组合出来，会抛 IllegalStateException。
    var focusRequested by remember { mutableStateOf(false) }
    // 手指是否还按着：首次授权时系统弹框会打断手势，授权回来时手指早就松了，
    // 这时不能再开始录音，否则会一直录到超时。
    var pressHeld by remember { mutableStateOf(false) }
    // 语音识别给的是「这一轮的完整句子」，因此以开口前的文本为前缀拼接。
    var voiceBase by remember { mutableStateOf("") }
    // 发出去了就不要再把识别结果写回输入框。
    //
    // 这是「提问结束后文字不消失」的直接成因：识别引擎的最终结果常常比
    // 「松手」晚几十毫秒到达，学生已经在识别结果上点了发送、输入框清空了，
    // 迟到的 onResults 又把整句写回去，看起来就像没发出去。
    var suppressVoice by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    val focusRequester = remember { FocusRequester() }
    val context = LocalContext.current
    val voice = rememberVoiceInput()
    val requestMicPermission = rememberRecordAudioPermissionRequester()
    val currentQuestion by rememberUpdatedState(question)

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
                .imePadding()
                .padding(top = 6.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when {
            voice.listening || voice.finishing ->
                VoiceCapturePanel(
                    level = voice.level,
                    hasVolume = voice.hasVolume,
                    speaking = voice.speaking,
                    locked = voice.locked,
                    finishing = voice.finishing,
                    onFinish = voice::finish,
                )

            voiceMode && question.isNotBlank() ->
                VoiceTranscriptStrip(
                    text = question,
                    onEdit = {
                        voiceMode = false
                        focusRequested = true
                    },
                )

            notice != null ->
                Text(
                    text = notice.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = Tokens.Warning,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).clickable { notice = null },
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
                            notice = null
                            requestMicPermission {
                                if (!pressHeld) {
                                    notice = "已开启录音权限，再按住说一次"
                                    return@requestMicPermission
                                }
                                voiceBase = currentQuestion
                                voice.press(
                                    engineId = VoiceEnginePrefs.selectedId(context),
                                    onText = { spoken ->
                                        if (!suppressVoice) onQuestionChange(voiceBase + spoken)
                                    },
                                    onError = { reason -> notice = reason },
                                    onEngineSwitched = { engine ->
                                        notice = "已自动切换到「" + engine.label + "」继续识别"
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
                        value = question,
                        onValueChange = onQuestionChange,
                        modifier =
                            Modifier
                                .weight(1f)
                                .heightIn(min = 44.dp, max = 120.dp)
                                .focusRequester(focusRequester),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { onAsk() }),
                        colors =
                            TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                            ),
                        maxLines = 4,
                    )
                }
            }

            // 语音态且什么都没说时不占位：这时候发送没有意义。
            if (!voiceMode || question.isNotBlank() || asking) {
                AskButton(
                    enabled = canAsk,
                    asking = asking,
                    onClick = {
                        // 先掐掉识别、再丢弃迟到结果，最后才发：顺序反了就会把刚清空的
                        // 输入框又填回去。
                        suppressVoice = true
                        voice.cancel()
                        notice = null
                        onAsk()
                    },
                )
            }
        }
    }
}

@Composable
private fun AskButton(
    enabled: Boolean,
    asking: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(if (enabled || asking) Tokens.Primary else Tokens.OutlineStrong)
                .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (asking) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = Color.White,
            )
        } else {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Send,
                contentDescription = "提问",
                tint = Color.White,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
