package com.totop.app.ui.common

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.totop.app.ui.theme.Tokens

/*
 * 「按住说话」的一套公共件：手势区、录音面板、识别结果条。
 *
 * 为什么放在 common 而不是各自留一份：拍题页与陪伴页用的是同一套语音交互
 * （长按开始、上滑锁定、松手结束、声纹反馈），两处各写一遍必然在
 * 「锁定的判定阈值」「引擎不上报音量时怎么显示」这类细节上分叉。
 * 拍题页是原始实现，这里只是把它搬出来，**行为一行未改**。
 *
 * 依赖方向：本文件只依赖 [com.totop.app.ml.VoiceInputController] 暴露的只读状态与回调，
 * 不持有控制器本身——录音生命周期仍由调用页的 `rememberVoiceInput()` 管理。
 */

/** 上滑触发锁定所需的最小位移。24dp 低于常见的滑动手势阈值，不容易误触。 */
internal const val LOCK_SLIDE_DP: Float = 24f

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
internal fun HoldToTalk(
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
 * 声纹的两种驱动方式：
 * - 引擎上报音量（绝大多数机型）：用平滑后的 [level] 直接改高度，
 *   不加逐条动画——`onRmsChanged` 约 10Hz，12 条各自跑一个动画在低端机上比直接改高度更卡；
 * - 引擎完全不上报音量（部分国产 ROM）：`level` 恒为 0，旧实现看起来就是「条子死了」。
 *   这时改用一条很轻的呼吸波（相位逐条错开），并在文案上说明「正在拾音」，
 *   既不假装能测音量，也不让人以为录音没开始。
 */
@Composable
internal fun VoiceCapturePanel(
    level: Float,
    hasVolume: Boolean,
    speaking: Boolean,
    locked: Boolean,
    finishing: Boolean,
    onFinish: () -> Unit,
) {
    val transition = rememberInfiniteTransition(label = "voice-wave")
    val phase by
        transition.animateFloat(
            initialValue = 0f,
            targetValue = ((2 * Math.PI).toFloat()),
            animationSpec = infiniteRepeatable(tween(durationMillis = 1500, easing = LinearEasing), RepeatMode.Restart),
            label = "voice-phase",
        )
    val live = hasVolume || finishing
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
            WAVE_WEIGHTS.forEachIndexed { index, weight ->
                val amplitude =
                    if (live || finishing) {
                        level
                    } else {
                        // 0.28..0.62 的正弦起伏：静止时也看得出在录音，又不会夸张到像在假装。
                        0.28f + 0.34f * kotlin.math.abs(kotlin.math.sin(phase + index * 0.55f))
                    }
                Box(
                    modifier =
                        Modifier
                            .width(3.dp)
                            .height((6f + 22f * amplitude * weight).dp)
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
                    speaking || hasVolume -> "正在听 · 松手结束"
                    else -> "正在拾音 · 松手结束"
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
internal fun VoiceTranscriptStrip(
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
