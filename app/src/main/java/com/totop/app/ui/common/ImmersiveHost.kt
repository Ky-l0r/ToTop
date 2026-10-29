package com.totop.app.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 页面级「沉浸态」开关（隐藏底部导航栏，把整块屏幕让给内容）。
 *
 * 为什么需要它：底部导航栏画在外壳 Scaffold 上，外壳按内边距把内容区压到导航栏之上，
 * 页面自己再怎么调布局也换不回那 80-90dp。框选页最需要这块高度——
 * 图片按 Fit 缩放，高度少一截，能框到的细节就少一截。
 *
 * 用法：在需要沉浸的页面里 `ImmersiveEffect(active)`，
 * 离开组合（或 active 变回 false）时自动恢复，不会把导航栏永久藏掉。
 */
class ImmersiveHost internal constructor(private val onChange: (Boolean) -> Unit) {
    internal fun set(active: Boolean) = onChange(active)

    companion object {
        /** 没有外壳时的空实现（预览、单测独立使用页面时）。 */
        val NoOp: ImmersiveHost = ImmersiveHost {}
    }
}

/** 由外壳提供；页面只读。 */
val LocalImmersiveHost = staticCompositionLocalOf { ImmersiveHost.NoOp }

/**
 * 声明「本页此刻是否要沉浸」。
 *
 * 用 DisposableEffect 而不是 LaunchedEffect：进入/退出都必须成对发生，
 * 页面被移出组合时要能立刻把导航栏还回去。
 */
@Composable
fun ImmersiveEffect(active: Boolean) {
    val host = LocalImmersiveHost.current
    DisposableEffect(host, active) {
        host.set(active)
        onDispose { if (active) host.set(false) }
    }
}
