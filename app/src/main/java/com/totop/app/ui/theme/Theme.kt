package com.totop.app.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColors =
    lightColorScheme(
        primary = Tokens.Primary,
        onPrimary = Color.White,
        primaryContainer = Tokens.PrimaryContainer,
        onPrimaryContainer = Tokens.OnPrimaryContainer,
        secondary = Tokens.PrimaryDark,
        onSecondary = Color.White,
        secondaryContainer = Tokens.PrimaryContainer,
        onSecondaryContainer = Tokens.OnPrimaryContainer,
        background = Tokens.Background,
        onBackground = Tokens.OnSurface,
        surface = Tokens.Surface,
        onSurface = Tokens.OnSurface,
        surfaceVariant = Tokens.SurfaceVariant,
        onSurfaceVariant = Tokens.OnSurfaceVariant,
        outline = Tokens.Outline,
        error = Tokens.Error,
        onError = Color.White,
        errorContainer = Tokens.ErrorContainer,
        onErrorContainer = Tokens.Error,
    )

private val DarkColors =
    darkColorScheme(
        primary = Color(0xFFC3B9FF),
        onPrimary = Color(0xFF221C4A),
        primaryContainer = Color(0xFF3A3170),
        onPrimaryContainer = Color(0xFFE7E2FF),
        secondary = Color(0xFFB9AEF5),
        onSecondary = Color(0xFF221C4A),
        secondaryContainer = Color(0xFF3A3170),
        onSecondaryContainer = Color(0xFFE7E2FF),
        background = Color(0xFF131218),
        onBackground = Color(0xFFE7E5EE),
        surface = Color(0xFF1A1920),
        onSurface = Color(0xFFE7E5EE),
        surfaceVariant = Color(0xFF26242E),
        onSurfaceVariant = Color(0xFFBDB9C9),
        outline = Color(0xFF4A4657),
        error = Color(0xFFF2B8B5),
        onError = Color(0xFF3B0A0A),
        errorContainer = Color(0xFF5C1A1A),
        onErrorContainer = Color(0xFFF2B8B5),
    )

/**
 * 全局主题（淡紫主色 + 白底）。
 *
 * 明确约定（沿用项目既有硬性规范）：
 * 1. 不启用动态取色，保证各机型配色一致；
 * 2. 不使用毛玻璃、视差与大面积弹性动画；
 * 3. 仅使用 Material3 标准组件，不引入第三方 UI 库。
 *
 * **深色模式默认关闭**（仍保留参数以便单独验证深色配色）：
 * 需求明确要求「淡紫主色 + 白色背景」，而原先跟随系统时，
 * 系统处于深色模式的手机打开本应用会整体变黑，与要求正好相反。
 * 另外学习类应用长时间阅读题目与解答，白底黑字的可读性更稳定。
 */
@Composable
fun ToTopTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = TotopTypography,
        content = content,
    )
}
