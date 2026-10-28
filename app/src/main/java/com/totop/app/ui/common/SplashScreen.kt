package com.totop.app.ui.common

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.totop.app.ui.theme.Tokens
import kotlinx.coroutines.delay

/** 每个字母的出现间隔。五个字母合计 350ms，比一次淡入更有「写上去」的感觉，又不拖沓。 */
private const val LETTER_STAGGER_MS: Long = 70L

/** 单个字母的淡入时长。略长于间隔，让前后两个字母的动画有重叠，看起来是连续写字而不是逐个闪。 */
private const val LETTER_FADE_MS: Int = 160

/** 字母起始位置比最终位置低多少：只有上移，不做缩放，低端机上只多一次图层平移。 */
private const val LETTER_RISE_DP: Float = 8f

/**
 * 启动页（应用内）。
 *
 * 与系统启动窗口的分工：
 * 低端机冷启动要 2 至 3 秒才出第一帧，这段时间屏幕上是 `window_background`（淡紫），
 * 因此这里的底色必须与它逐位一致（[Tokens.Splash]），否则会出现「淡紫 -> 另一种淡紫」的跳变。
 *
 * 动效只做一件事：五个字母逐个淡入并各上移 8dp。
 * 不做缩放、不做图标、不做循环动画——启动页的职责是「别让人觉得卡住」，不是展示动效。
 */
@Composable
fun SplashScreen(modifier: Modifier = Modifier) {
    val letters = remember { WORDMARK.toList() }
    var revealed by remember { mutableIntStateOf(0) }

    // 逐个点亮：先置位再等待，保证第一个字母在进入组合的同一帧就开始淡入。
    LaunchedEffect(Unit) {
        letters.indices.forEach { index ->
            revealed = index + 1
            delay(LETTER_STAGGER_MS)
        }
    }

    Box(
        modifier = modifier.fillMaxSize().background(Tokens.Splash),
        contentAlignment = Alignment.Center,
    ) {
        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            letters.forEachIndexed { index, letter ->
                val shown = index < revealed
                val alpha by
                    animateFloatAsState(
                        targetValue = if (shown) 1f else 0f,
                        animationSpec = tween(durationMillis = LETTER_FADE_MS, easing = FastOutSlowInEasing),
                        label = "splash-letter-alpha-$index",
                    )
                val rise by
                    animateFloatAsState(
                        targetValue = if (shown) 0f else LETTER_RISE_DP,
                        animationSpec = tween(durationMillis = LETTER_FADE_MS, easing = FastOutSlowInEasing),
                        label = "splash-letter-rise-$index",
                    )
                Text(
                    text = letter.toString(),
                    style = WORDMARK_STYLE,
                    color = Tokens.Primary,
                    modifier =
                        Modifier.graphicsLayer {
                            this.alpha = alpha
                            translationY = rise.dp.toPx()
                        },
                )
            }
        }
    }
}

/** 品牌字标。逐字渲染，因此不能带额外空格或换行。 */
private const val WORDMARK = "ToTop"

private val WORDMARK_STYLE =
    TextStyle(
        fontSize = 40.sp,
        lineHeight = 48.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 2.sp,
    )
