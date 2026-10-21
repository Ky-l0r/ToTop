package com.totop.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay

/** 公式内容的解析结果。 */
sealed class FormulaContent {
    /** 不含任何有效公式定界符的纯文本。 */
    data class PlainText(val text: String) : FormulaContent()

    /** 含公式的混排片段，按原文顺序。 */
    data class Pieces(val pieces: List<Piece>) : FormulaContent()

    /** 混排中的一个片段。 */
    sealed class Piece {
        /** 普通文字。 */
        data class Text(val value: String) : Piece()

        /**
         * 一条公式。
         *
         * @param latex 不含定界符的 LaTeX 源码
         * @param displayMode true 为行间公式（$$...$$ / \\[...\\]），false 为行内公式
         */
        data class Formula(val latex: String, val displayMode: Boolean) : Piece()
    }
}

/**
 * 公式定界符解析（纯 Kotlin，无 Android 依赖，可在 JVM 单测中穷举覆盖）。
 *
 * 支持：\\( \\)（行内）、\\[ \\]（行间）、$...$（行内）、$$...$$（行间）。
 *
 * 解析规则刻意保守，避免误伤普通文本：
 * - 定界符未闭合时按普通文字处理，绝不吞掉后文；
 * - 公式内容为空白时按普通文字处理；
 * - 只有一个 $ 且后面紧跟数字、又找不到配对时，按「货币符号」处理（例如「3$ 5$」）。
 */
object FormulaParser {
    /** 解析文本。 */
    fun parse(raw: String): FormulaContent {
        if (raw.isEmpty()) return FormulaContent.PlainText("")
        if (!containsDelimiter(raw)) return FormulaContent.PlainText(raw)
        val pieces = ArrayList<FormulaContent.Piece>()
        val plain = StringBuilder()
        var index = 0
        while (index < raw.length) {
            val marker = markerAt(raw, index)
            if (marker == null) {
                plain.append(raw[index])
                index++
                continue
            }
            val closeAt = raw.indexOf(marker.close, index + marker.open.length)
            if (closeAt < 0) {
                plain.append(raw[index])
                index++
                continue
            }
            val latex = raw.substring(index + marker.open.length, closeAt)
            if (latex.isBlank()) {
                plain.append(raw, index, closeAt + marker.close.length)
                index = closeAt + marker.close.length
                continue
            }
            if (plain.isNotEmpty()) {
                pieces.add(FormulaContent.Piece.Text(plain.toString()))
                plain.setLength(0)
            }
            pieces.add(FormulaContent.Piece.Formula(latex = latex, displayMode = marker.display))
            index = closeAt + marker.close.length
        }
        if (plain.isNotEmpty()) pieces.add(FormulaContent.Piece.Text(plain.toString()))
        if (pieces.none { it is FormulaContent.Piece.Formula }) return FormulaContent.PlainText(raw)
        return FormulaContent.Pieces(pieces)
    }

    /** 是否含任何可能的公式定界符（快速路径：纯文本完全不碰 WebView）。 */
    fun containsDelimiter(raw: String): Boolean = raw.contains("\\(") || raw.contains("\\[") || raw.contains('$')

    /** 文本中是否含行间公式（决定排版方式）。 */
    fun hasDisplayFormula(raw: String): Boolean =
        when (val parsed = parse(raw)) {
            is FormulaContent.PlainText -> false
            is FormulaContent.Pieces ->
                parsed.pieces.any {
                    it is FormulaContent.Piece.Formula && it.displayMode
                }
        }

    /** 去掉所有定界符，得到可供朗读/复制的纯文本。 */
    fun stripDelimiters(raw: String): String =
        when (val parsed = parse(raw)) {
            is FormulaContent.PlainText -> parsed.text
            is FormulaContent.Pieces ->
                parsed.pieces.joinToString("") { piece ->
                    when (piece) {
                        is FormulaContent.Piece.Text -> piece.value
                        is FormulaContent.Piece.Formula -> piece.latex
                    }
                }
        }

    private class Marker(val open: String, val close: String, val display: Boolean)

    private fun markerAt(
        raw: String,
        index: Int,
    ): Marker? =
        when {
            raw.startsWith("$$", index) -> Marker("$$", "$$", true)
            raw.startsWith("\\[", index) -> Marker("\\[", "\\]", true)
            raw.startsWith("\\(", index) -> Marker("\\(", "\\)", false)
            raw[index] == '$' -> Marker("$", "$", false)
            else -> null
        }
}

/**
 * 公式渲染宿主的生命周期锚点（页面级）。
 *
 * 用法：在承载公式的页面最外层放一次 `FormulaRenderHost()`。
 * 它负责把 [FormulaRenderer] 的单例 WebView 挂进组合树，并在页面退出时销毁。
 *
 * 为什么需要它：
 * - WebView 必须先 attach 到窗口，measure/layout/draw 截图才可靠；
 * - 若由每个文本组件自行挂载/销毁，列表滚动回收会导致宿主反复 detach，
 *   甚至把共享 WebView 提前销毁，从而让同页其他公式渲染失败。
 *
 * 没有放置本组件时，[FormulaText] 会自行兜底挂载一次（见 FormulaRichContent），
 * 因此单测/预览里单独使用 FormulaText 也不会白屏。
 */
@Composable
fun FormulaRenderHost(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val renderer = remember { FormulaRenderer.obtain(context) }
    DisposableEffect(renderer) {
        onDispose { renderer.dispose() }
    }
    Box(modifier = modifier.size(HOST_SLOT_SIZE)) {
        AndroidView(
            factory = { viewContext -> renderer.attachHost(viewContext) },
            modifier = Modifier.size(HOST_SLOT_SIZE),
        )
    }
}

/** 宿主占位尺寸：不影响排版，但保证 WebView 真实 attach。 */
private val HOST_SLOT_SIZE = 1.dp

/**
 * 公式文本组件（FR-1.3：题干与解题步骤中的 LaTeX 渲染）。
 *
 * 行为契约：
 * 1. 文本里没有公式定界符时**完全不创建 WebView**，直接渲染 Text（纯文本是绝大多数场景）；
 * 2. 含公式时使用 [FormulaRenderer] 的共享 WebView 渲染（实例复用，不跨页面泄漏）；
 * 3. 渲染成功 -> 位图展示，按可用宽度等比缩放；
 * 4. 渲染失败或超过 300ms 超时 -> 降级为纯文本（[fallbackToRaw] 为 true 时显示原始 LaTeX），
 *    绝不出现空白块；
 * 5. 只使用本地 assets/katex，禁止 MathJax 与任何 CDN。
 *
 * @param text 可能包含 LaTeX 的原始文本
 * @param modifier 外部修饰
 * @param style 正文字体样式（字号与颜色）
 * @param fallbackToRaw 渲染失败时是否显示原始文本（默认 true，保证信息不丢）
 */
@Composable
fun FormulaText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    fallbackToRaw: Boolean = true,
) {
    val content = remember(text) { FormulaParser.parse(text) }
    when (content) {
        is FormulaContent.PlainText -> Text(text = content.text, modifier = modifier, style = style)
        is FormulaContent.Pieces ->
            FormulaRichContent(
                pieces = content.pieces,
                modifier = modifier,
                style = style,
                fallbackToRaw = fallbackToRaw,
            )
    }
}

/**
 * 混排内容：文字 + 公式位图。
 *
 * 宿主由页面级 [FormulaRenderHost] 提供；若调用方没放，
 * 这里会在「当前还没有宿主」时兜底挂载一次，保证独立使用也能渲染。
 */
@Composable
private fun FormulaRichContent(
    pieces: List<FormulaContent.Piece>,
    modifier: Modifier,
    style: TextStyle,
    fallbackToRaw: Boolean,
) {
    val context = LocalContext.current
    val renderer = remember { FormulaRenderer.obtain(context) }
    // 只在组合期的第一帧判断一次：已经有宿主就不再抢，避免把别的 AndroidView 里的宿主挪走
    val needsHost = remember(renderer) { !renderer.hasHost() }
    Column(modifier = modifier) {
        pieces.forEach { piece ->
            when (piece) {
                is FormulaContent.Piece.Text -> Text(text = piece.value, style = style)
                is FormulaContent.Piece.Formula ->
                    FormulaBlock(
                        latex = piece.latex,
                        displayMode = piece.displayMode,
                        style = style,
                        fallbackToRaw = fallbackToRaw,
                        renderer = renderer,
                    )
            }
        }
    }
    if (needsHost) {
        Box(modifier = Modifier.size(HOST_SLOT_SIZE)) {
            AndroidView(
                factory = { viewContext -> renderer.attachHost(viewContext) },
                modifier = Modifier.size(HOST_SLOT_SIZE),
            )
        }
    }
}

/** 单条公式：渲染 -> 位图；失败 -> 原文降级。 */
@Composable
private fun FormulaBlock(
    latex: String,
    displayMode: Boolean,
    style: TextStyle,
    fallbackToRaw: Boolean,
    renderer: FormulaRenderer,
) {
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val maxWidthPx =
        remember(configuration, density) {
            (configuration.screenWidthDp * CONTENT_WIDTH_RATIO * density.density).toInt()
        }
    val key = if (displayMode) "D:" + latex else "I:" + latex
    var result by remember(key) { mutableStateOf<FormulaRenderResult?>(null) }
    val currentStyle by rememberUpdatedState(style)

    LaunchedEffect(key) {
        val handle = renderer.request(latex, displayMode, maxWidthPx)
        handle.onResult = { outcome -> result = outcome }
        // 双重保险：渲染器自身有 300ms 超时，这里再加一层宽限，确保迟到也不会卡住
        delay(FormulaRenderer.RENDER_BUDGET_MILLIS + RENDER_GRACE_MILLIS)
        if (result == null) {
            handle.cancel()
            result = FormulaRenderResult.Failed(RENDER_TIMEOUT_TEXT)
        }
    }

    when (val current = result) {
        is FormulaRenderResult.Rendered -> RenderedFormula(current, displayMode)
        is FormulaRenderResult.Failed ->
            if (fallbackToRaw) {
                Text(
                    text = if (displayMode) "\n" + latex + "\n" else latex,
                    style = currentStyle.copy(fontFamily = FontFamily.Monospace),
                )
            }
        null -> PlaceholderBar(style = currentStyle, displayMode = displayMode)
    }
}

/** 渲染中的占位条：静态（无动画，遵守方案 7.1 禁用大面积动画）。 */
@Composable
private fun PlaceholderBar(
    style: TextStyle,
    displayMode: Boolean,
) {
    val heightFactor = if (displayMode) 2.2f else 1.3f
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height((style.fontSize.value * heightFactor).dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(text = " ", style = style)
    }
}

/** 渲染成功的位图：按可用宽度等比缩放，行间公式居中。 */
@Composable
private fun RenderedFormula(
    result: FormulaRenderResult.Rendered,
    displayMode: Boolean,
) {
    val density = LocalDensity.current
    val image = remember(result) { result.bitmap.asImageBitmap() }
    val sourceWidthDp = with(density) { result.bitmap.width.toDp() }
    val sourceHeightDp = with(density) { result.bitmap.height.toDp() }
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (displayMode) Alignment.Center else Alignment.CenterStart,
    ) {
        Image(
            bitmap = image,
            contentDescription = null,
            modifier =
                Modifier
                    .width(sourceWidthDp)
                    .height(sourceHeightDp),
        )
    }
}

/** 等待渲染回调的额外宽限（毫秒）。 */
private const val RENDER_GRACE_MILLIS: Long = 120L

/** 正文可用宽度占屏幕宽度的比例（扣除卡片内边距后的大致值）。 */
private const val CONTENT_WIDTH_RATIO: Float = 0.82f

private const val RENDER_TIMEOUT_TEXT: String = "渲染超时（超过 300ms）"
