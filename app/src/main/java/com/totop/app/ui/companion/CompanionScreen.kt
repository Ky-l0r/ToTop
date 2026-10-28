package com.totop.app.ui.companion

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.totop.app.ml.rememberRecordAudioPermissionRequester
import com.totop.app.ml.rememberVoiceInput
import com.totop.app.ui.theme.Tokens

/**
 * 陪伴作业。
 *
 * 使用场景：学生晚上写作业，把手机架在旁边。
 *
 * 与上一版的根本差别：**不做倒计时**。上一版把「五分钟内不许求助」当成规则，
 * 学生卡住时反而被拦住；现在改成正计时，随时可以用语音或文字问一句
 * 「那个公式是什么」，问的时候计时自动停，回答完自动继续。
 *
 * 计时只回答一个问题：这一轮到底写了多久。不做排名、不做打卡。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompanionScreen(
    /** 作为底栏顶层页进入时为 null：顶层页面不该有返回箭头。 */
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    viewModel: CompanionViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val focusRequester = remember { FocusRequester() }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("陪伴") },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(Tokens.Background)
                    .padding(innerPadding)
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(12.dp))

            if (state.roundFinished) {
                RoundSummary(state = state, onRestart = viewModel::startNewRound)
                return@Column
            }

            TimerDial(state = state)
            Spacer(Modifier.height(12.dp))
            Text(
                text = state.statusText,
                style = MaterialTheme.typography.bodyMedium,
                color = Tokens.OnSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))

            when (state.phase) {
                CompanionPhase.IDLE ->
                    Button(onClick = viewModel::start, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(text = "  开始这一题")
                    }

                CompanionPhase.TIMING, CompanionPhase.PAUSED ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = {
                                if (state.phase == CompanionPhase.TIMING) viewModel.pause() else viewModel.start()
                            },
                            modifier = Modifier.weight(1f).height(48.dp),
                        ) {
                            Icon(
                                imageVector =
                                    if (state.phase == CompanionPhase.TIMING) {
                                        Icons.Filled.Pause
                                    } else {
                                        Icons.Filled.PlayArrow
                                    },
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(text = if (state.phase == CompanionPhase.TIMING) "  暂停" else "  继续")
                        }
                        Button(onClick = viewModel::finishQuestion, modifier = Modifier.weight(1f).height(48.dp)) {
                            Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                            Text(text = "  写完了")
                        }
                    }
            }

            if (state.showHint) {
                Spacer(Modifier.height(12.dp))
                HintBar(onAsk = { focusRequester.requestFocus() })
            }

            Spacer(Modifier.height(18.dp))
            AskCard(
                state = state,
                focusRequester = focusRequester,
                onQuestionChange = viewModel::updateQuestion,
                onSend = viewModel::ask,
            )

            Spacer(Modifier.height(20.dp))
            RoundProgress(state = state)
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                OutlinedButton(onClick = viewModel::finishRound) {
                    Icon(Icons.Filled.Timer, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text(text = "  结束本轮")
                }
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

/** 正计时圆环：外圈只做「在走」的暗示，进度按 5 分钟一轮回填。 */
@Composable
private fun TimerDial(state: CompanionUiState) {
    val cycle = CompanionDefaults.HINT_AFTER_SECONDS
    val progress = ((state.elapsedSeconds % cycle).toFloat() / cycle).coerceIn(0f, 1f)
    Box(modifier = Modifier.size(180.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            progress = { 1f },
            modifier = Modifier.size(180.dp),
            strokeWidth = 10.dp,
            color = Tokens.SurfaceVariant,
        )
        CircularProgressIndicator(
            progress = { progress },
            modifier = Modifier.size(180.dp),
            strokeWidth = 10.dp,
            color = if (state.phase == CompanionPhase.PAUSED) Tokens.OutlineStrong else Tokens.Primary,
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = state.clockText,
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.SemiBold,
                color = Tokens.OnSurface,
            )
            Text(
                text =
                    when (state.phase) {
                        CompanionPhase.TIMING -> "专注中"
                        CompanionPhase.PAUSED -> "已暂停"
                        CompanionPhase.IDLE -> "未开始"
                    },
                style = MaterialTheme.typography.labelMedium,
                color = Tokens.OnSurfaceVariant,
            )
        }
    }
}

/** 5 分钟提醒：只提示，不拦人。 */
@Composable
private fun HintBar(onAsk: () -> Unit) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Tokens.WarningContainer)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "这题已经 5 分钟了，卡住可以问我。",
            style = MaterialTheme.typography.bodySmall,
            color = Tokens.Warning,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(onClick = onAsk) {
            Text(text = "问一句")
        }
    }
}

/**
 * 提问卡：文字 + 按住说话。
 *
 * 语音沿用拍题页那套长按手势，但**不做上滑锁定**——这里问的是一句话，
 * 连续录音的收益抵不过多一个手势态的复杂度。
 */
@Composable
private fun AskCard(
    state: CompanionUiState,
    focusRequester: FocusRequester,
    onQuestionChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    val voice = rememberVoiceInput()
    val requestMicPermission = rememberRecordAudioPermissionRequester()
    var voiceBase by remember { mutableStateOf("") }
    var voiceError by remember { mutableStateOf<String?>(null) }
    val current by rememberUpdatedState(state.question)

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Tokens.SurfaceVariant)
                .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "想不起来的地方，直接问",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = Tokens.OnSurface,
        )
        OutlinedTextField(
            value = state.question,
            onValueChange = onQuestionChange,
            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            placeholder = { Text("比如：求导的链式法则是什么", style = MaterialTheme.typography.bodyMedium) },
            minLines = 2,
            maxLines = 4,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HoldToTalkButton(
                listening = voice.listening,
                onPress = {
                    requestMicPermission {
                        voiceError = null
                        voiceBase = current
                        voice.press(
                            engineId = null,
                            onText = { spoken -> onQuestionChange(voiceBase + spoken) },
                            onError = { reason -> voiceError = reason },
                        )
                    }
                },
                onRelease = voice::release,
            )
            Text(
                text = voiceError ?: if (voice.listening) "正在听…" else "按住说话",
                style = MaterialTheme.typography.labelMedium,
                color = Tokens.OnSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (state.asking) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
            } else {
                IconButton(onClick = onSend, enabled = state.question.isNotBlank()) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "提问",
                        tint = if (state.question.isNotBlank()) Tokens.Primary else Tokens.OutlineStrong,
                    )
                }
            }
        }
    }

    LaunchedEffect(state.answer, state.answerError) {
        // 回答到达时收起输入法，把结果让出来（小屏上键盘会挡住半个回答）
        if (state.answer != null || state.answerError != null) voice.cancel()
    }

    if (state.answer != null || state.answerError != null) {
        Spacer(Modifier.height(12.dp))
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Tokens.PrimaryContainer)
                    .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "刚才那个点",
                style = MaterialTheme.typography.labelSmall,
                color = Tokens.OnPrimaryContainer,
            )
            if (state.answerError != null) {
                Text(
                    text = state.answerError,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Tokens.Error,
                )
            } else {
                com.totop.app.ui.components.FormulaText(
                    text = state.answer.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "看懂了就接着写，没懂可以再问细一点。",
                    style = MaterialTheme.typography.labelSmall,
                    color = Tokens.OnPrimaryContainer,
                )
            }
        }
    }
}

/** 「按住说话」小按钮：只做长按录音，不做锁定。 */
@Composable
private fun HoldToTalkButton(
    listening: Boolean,
    onPress: () -> Unit,
    onRelease: () -> Unit,
) {
    val pressNow by rememberUpdatedState(onPress)
    val releaseNow by rememberUpdatedState(onRelease)
    Box(
        modifier =
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(if (listening) Tokens.PrimaryContainer else Tokens.Surface)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false).consume()
                        pressNow()
                        waitForUpOrCancellation()
                        releaseNow()
                    }
                },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Mic,
            contentDescription = "按住说话",
            tint = if (listening) Tokens.Primary else Tokens.OnSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** 本轮进度：做了几题 / 问了几次 / 累计专注。 */
@Composable
private fun RoundProgress(state: CompanionUiState) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(Tokens.SurfaceVariant, RoundedCornerShape(14.dp))
                .padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        StatCell(value = state.finishedCount.toString(), label = "写完")
        StatCell(value = state.askCount.toString(), label = "问过")
        StatCell(value = formatMinutes(state.totalWithCurrent), label = "专注时长")
    }
}

@Composable
private fun StatCell(
    value: String,
    label: String,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = Tokens.Primary,
        )
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = Tokens.OnSurfaceVariant)
    }
}

/** 一轮结束的复盘卡。 */
@Composable
private fun RoundSummary(
    state: CompanionUiState,
    onRestart: () -> Unit,
) {
    Spacer(Modifier.height(24.dp))
    Box(
        modifier =
            Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(Tokens.SuccessContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = Tokens.Success,
            modifier = Modifier.size(48.dp),
        )
    }
    Spacer(Modifier.height(18.dp))
    Text(
        text = "这一轮结束",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold,
        color = Tokens.OnSurface,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text =
            "写完 " + state.finishedCount + " 题，专注 " + formatMinutes(state.totalWithCurrent) +
                "，中途问过 " + state.askCount + " 次。",
        style = MaterialTheme.typography.bodyMedium,
        color = Tokens.OnSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(6.dp))
    Text(
        text =
            if (state.askCount == 0) {
                "这一轮一次都没问，说明这些题你自己啃下来了。"
            } else {
                "问的那几个点，就是下次复习时该先看的地方。"
            },
        style = MaterialTheme.typography.bodySmall,
        color = Tokens.OnSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(20.dp))
    Button(onClick = onRestart, modifier = Modifier.fillMaxWidth().height(50.dp)) {
        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(text = "  再来一轮")
    }
    Spacer(Modifier.height(28.dp))
}

/** 秒数转「N 分 M 秒」的简报。 */
private fun formatMinutes(seconds: Int): String {
    if (seconds < 60) return seconds.toString() + " 秒"
    val minutes = seconds / 60
    val rest = seconds % 60
    return if (rest == 0) minutes.toString() + " 分钟" else minutes.toString() + " 分 " + rest + " 秒"
}
