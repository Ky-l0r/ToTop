package com.totop.app.ui.common

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.totop.app.R
import com.totop.app.ui.theme.Tokens

/**
 * 启动动画（应用内）。
 *
 * 与系统 splash（`windowSplashScreenBackground`）的分工：
 * 系统 splash 只负责「冷启动第一帧不是黑屏」，它无法做动画；
 * 这里接在它后面，用同一套品牌视觉（图标 + 名称 + 一句话）完成过渡，
 * 因此用户看到的是「一张静态启动图 -> 轻微放大淡入的品牌页 -> 首页」，而不是突兀跳转。
 *
 * 动画刻意只做两件最便宜的事：透明度与整体缩放（0.92 -> 1.0）。
 * 不用位移、不用逐元素错峰、不做循环动画——低端机上这些都要重新计算布局。
 */
@Composable
fun SplashScreen(modifier: Modifier = Modifier) {
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { started = true }
    val scale by
        animateFloatAsState(
            targetValue = if (started) 1f else 0.92f,
            animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
            label = "splash-scale",
        )
    val alpha by
        animateFloatAsState(
            targetValue = if (started) 1f else 0f,
            animationSpec = tween(durationMillis = 380, easing = FastOutSlowInEasing),
            label = "splash-alpha",
        )

    Box(
        modifier = modifier.fillMaxSize().background(Tokens.Background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier =
                Modifier
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    }
                    .alpha(alpha),
        ) {
            Image(
                painter = painterResource(id = R.mipmap.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier.size(132.dp),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "To Top",
                style = MaterialTheme.typography.headlineSmall,
                color = Tokens.Primary,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "把不会的题，一步一步弄懂",
                style = MaterialTheme.typography.bodySmall,
                color = Tokens.OnSurfaceVariant,
            )
        }
    }
}
