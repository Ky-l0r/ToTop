package com.totop.app.ui.companion

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.totop.app.ui.theme.Tokens

/**
 * 陪伴作业模式。
 *
 * 使用场景：学生晚上写作业，把手机架在旁边。
 * 每道题先进入五分钟倒计时，这段时间里**不给求助入口**——
 * 目的是拦住「一卡住就拍照问 AI」的习惯；五分钟到点后仍然卡着，
 * 才允许去拍题求解，并把这道题记进「求助次数」。
 *
 * 本轮结束给一份朴素复盘：做了几题、求助几次、平均每题多久。
 * 不做排名、不做连续打卡、不发通知——陪伴模式只负责「看着时间」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompanionScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CompanionViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.bootstrap() }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("陪伴作业") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
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
            Spacer(Modifier.height(16.dp))

            Text(
                text = state.statusText,
                style = MaterialTheme.typography.bodyMedium,
                color = Tokens.OnSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(20.dp))

            // 时长选择：只在空闲/结束时可改，计时中改动会让剩余时间与进度对不上。
            if (state.phase == CompanionPhase.IDLE) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "每题给自己多少时间",
                        style = MaterialTheme.typography.labelLarge,
                        color = Tokens.OnSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CompanionDefaults.PRESETS.forEach { seconds ->
                            FilterChip(
                                selected = state.perQuestionSeconds == seconds,
                                onClick = { viewModel.setPerQuestionSeconds(seconds) },
                                label = { Text(text = (seconds / 60).toString() + " 分钟") },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(18.dp))
                Button(onClick = viewModel::start, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(text = "  开始这一题")
                }
            }

            if (state.phase == CompanionPhase.TIMING || state.phase == CompanionPhase.PAUSED) {
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

            if (state.phase == CompanionPhase.OVERTIME) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(Tokens.WarningContainer, RoundedCornerShape(14.dp))
                            .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "五分钟到了，还没头绪？",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Tokens.Warning,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text =
                            "先写下你能想到的第一步（哪怕是猜想），再决定要不要问。" +
                                "问一次也算一次求助，复盘时会看到。",
                        style = MaterialTheme.typography.bodySmall,
                        color = Tokens.Warning,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = viewModel::recordHint) {
                            Icon(Icons.Filled.Lightbulb, contentDescription = null, modifier = Modifier.size(16.dp))
                            Text(text = "  问一下 AI")
                        }
                        OutlinedButton(onClick = viewModel::finishQuestion) {
                            Text(text = "我再想想，先记完成")
                        }
                    }
                }
            }

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

/** 计时圆环：外圈进度 + 中间剩余时间。 */
@Composable
private fun TimerDial(state: CompanionUiState) {
    Box(
        modifier = Modifier.size(180.dp),
        contentAlignment = Alignment.Center,
    ) {
        // 底层灰环
        CircularProgressIndicator(
            progress = { 1f },
            modifier = Modifier.size(180.dp),
            strokeWidth = 10.dp,
            color = Tokens.SurfaceVariant,
        )
        // 上层进度环：超时后换成警示色
        CircularProgressIndicator(
            progress = { state.progress },
            modifier = Modifier.size(180.dp),
            strokeWidth = 10.dp,
            color =
                when (state.phase) {
                    CompanionPhase.OVERTIME -> Tokens.Warning
                    CompanionPhase.PAUSED -> Tokens.OutlineStrong
                    else -> Tokens.Primary
                },
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
                        CompanionPhase.OVERTIME -> "时间到"
                        CompanionPhase.PAUSED -> "已暂停"
                        else -> "自己写"
                    },
                style = MaterialTheme.typography.labelMedium,
                color = Tokens.OnSurfaceVariant,
            )
        }
    }
}

/** 本轮进度：做了几题 / 求助几次 / 累计时长。 */
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
        StatCell(value = state.finishedCount.toString(), label = "已完成")
        StatCell(value = state.hintCount.toString(), label = "求助过")
        StatCell(value = formatMinutes(state.totalSeconds), label = "累计思考")
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
            "自己写完 " + state.finishedCount + " 题，其中 " + state.hintCount +
                " 题用到了 AI 提示，累计思考 " + formatMinutes(state.totalSeconds) + "。",
        style = MaterialTheme.typography.bodyMedium,
        color = Tokens.OnSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(6.dp))
    Text(
        text =
            if (state.hintCount == 0) {
                "这一轮一道题都没求助，很难得。"
            } else {
                "求助不是问题，问题是一卡住就求助；下次试着把停顿再拉长一点。"
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
    Spacer(Modifier.height(10.dp))
    Text(
        text = "提示：把手机架起来看时间就好，别一直盯着屏幕。",
        style = MaterialTheme.typography.labelSmall,
        color = Tokens.OnSurfaceVariant,
    )
    Spacer(Modifier.height(28.dp))
}

/** 秒数转「N 分 M 秒」的简报，只显示到分钟，避免复盘卡上一堆精确数字。 */
private fun formatMinutes(seconds: Int): String {
    if (seconds < 60) return seconds.toString() + " 秒"
    val minutes = seconds / 60
    val rest = seconds % 60
    return if (rest == 0) minutes.toString() + " 分钟" else minutes.toString() + " 分 " + rest + " 秒"
}
