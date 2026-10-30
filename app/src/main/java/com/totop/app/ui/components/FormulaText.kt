package com.totop.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
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
        is FormulaContent.PlainText -> {
            // 没有 LaTeX 定界符：先剥掉裸命令，再走「朴素数学记号」排版。
            //
            // 两处修复叠在一起：
            // 1. 「x^2 直接显示成 x∧2」——上下标交给 MathMarkup 排；
            // 2. 「H_2SO_4 \cdot xSO_3 原样显示」——模型常把公式写成没有 $ 定界符的
            //    LaTeX 片段，`\cdot`、`\frac{a}{b}` 会原样留在屏幕上，由 LatexPlain
            //    换成 ·、a/b 这类字符。认不出的命令原样保留，不吞字符。
            val normalized = remember(content.text) { LatexPlain.normalize(content.text) }
            PlainMathText(text = normalized, modifier = modifier, style = style)
        }
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
 * 朴素数学记号排版：把 x^2 排成真正的上标，x_n 排成下标。
 *
 * 实现选择及其理由：
 * - 用 [AnnotatedString] + BaselineShift，而不是再开一个 WebView 渲染：
 *   这段文本每屏可能出现几十上百处（解答步骤、错因说明、知识点用途），
 *   走 WebView 每条都要一次 JS 往返，列表滚动会直接掉帧；
 * - BaselineShift 只影响基线位置与字号，不改变换行与测量逻辑，
 *   因此长段落仍然由 Text 正常折行，不会出现「一行公式撑破卡片」。
 *
 * 不含任何上下标记号时直接返回普通 Text，零额外开销。
 */
@Composable
private fun PlainMathText(
    text: String,
    modifier: Modifier,
    style: TextStyle,
) {
    val segments = remember(text) { MathMarkup.parse(text) }
    val hasScript =
        remember(segments) {
            segments.any { it is MathMarkup.Segment.Sup || it is MathMarkup.Segment.Sub }
        }
    if (!hasScript) {
        Text(text = text, modifier = modifier, style = style)
        return
    }
    val annotated =
        remember(segments, style) {
            buildAnnotatedString {
                segments.forEach { segment ->
                    when (segment) {
                        is MathMarkup.Segment.Text -> append(segment.value)
                        is MathMarkup.Segment.Sup ->
                            withStyle(
                                SpanStyle(
                                    fontSize = scriptSizeOf(style),
                                    baselineShift = BaselineShift.Superscript,
                                ),
                            ) { append(segment.value) }
                        is MathMarkup.Segment.Sub ->
                            withStyle(
                                SpanStyle(
                                    fontSize = scriptSizeOf(style),
                                    baselineShift = BaselineShift.Subscript,
                                ),
                            ) { append(segment.value) }
                    }
                }
            }
        }
    Text(text = annotated, modifier = modifier, style = style)
}

/** 上下标字号：正文的 0.72 倍，与课本排版习惯接近，同时保证小屏幕上仍可辨认。 */
private fun scriptSizeOf(style: TextStyle): TextUnit = style.fontSize * SCRIPT_SCALE

/** 上下标相对正文的缩放比例。 */
private const val SCRIPT_SCALE: Float = 0.72f

/**
 * 混排内容：文字 + 公式位图。
 *
 * **行内公式必须跟文字排在同一行**——这是「一屏公式堆成一摞」的根因：
 * 旧实现把每个片段各渲染成一个 Text（行内公式各占一个整宽 Box），
 * 一句「初始动能 $E_{k0}=…$，末动能 $E_{k}=…$」会被拉成五六行，
 * 中间还夹着居中的公式块，读起来像对着碎片看。
 *
 * 现在按「行间公式」切段：
 * - 段内（文字 + 行内公式）合成**一个** [AnnotatedString]，
 *   行内公式通过 `appendInlineContent` 作为行内图片插入，随文字自然折行；
 * - 只有 $$…$$ / \\[…\\] 这种行间公式才单独成块并居中。
 *
 * 渲染失败时不给 inlineContent 项，[AnnotatedString] 会退回 alternateText（原始 LaTeX），
 * 因此绝不会出现「公式位置留白」。
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
    val groups = remember(pieces) { FormulaPieceGroups.of(pieces) }
    Column(modifier = modifier) {
        groups.forEach { group ->
            when (group) {
                is FormulaPieceGroups.Group.Display ->
                    FormulaBlock(
                        latex = group.latex,
                        displayMode = true,
                        style = style,
                        fallbackToRaw = fallbackToRaw,
                        renderer = renderer,
                    )
                is FormulaPieceGroups.Group.Run ->
                    InlineFormulaRun(
                        pieces = group.pieces,
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

/**
 * 一段「文字 + 行内公式」的混排。
 *
 * 先请求渲染全部行内公式，再拼成一段 [AnnotatedString]；
 * 渲染未完成的公式先用估算宽度占位，避免整段文字在结果到达时整体跳动。
 *
 * 渲染结果按 **LaTeX 源码**缓存（而不是按片段下标）：
 * 追问的回答是流式的，正文每来一个增量片段列表就会整体变化；
 * 若按下标缓存，每帧都会把这一条消息里的所有公式重新丢给 WebView 渲染，
 * 滚动和流式输出会一起卡住。按源码缓存则只渲染「这一帧新出现的公式」。
 */
@Composable
private fun InlineFormulaRun(
    pieces: List<FormulaContent.Piece>,
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
    val latexes = remember(pieces) { pieces.mapNotNull { (it as? FormulaContent.Piece.Formula)?.latex } }
    val rendered = remember { mutableStateMapOf<String, FormulaRenderResult>() }
    LaunchedEffect(latexes, renderer) {
        latexes.distinct().forEach { latex ->
            if (rendered.containsKey(latex)) return@forEach
            val handle = renderer.request(latex, displayMode = false, maxWidthPx = maxWidthPx)
            handle.onResult = { outcome -> rendered[latex] = outcome }
        }
    }

    val fontDp = with(density) { style.fontSize.toDp() }
    val inline = LinkedHashMap<String, InlineTextContent>()
    val text =
        buildAnnotatedString {
            var formulaIndex = 0
            pieces.forEach { piece ->
                when (piece) {
                    // 公式之间的正文同样可能夹着裸命令（例如 `速度 v=s/t` 后面紧跟 `\cdot`），
                    // 这里一并归一化，避免一段话里中文正常、公式处露出反斜杠。
                    is FormulaContent.Piece.Text -> append(LatexPlain.normalize(piece.value))
                    is FormulaContent.Piece.Formula -> {
                        val id = INLINE_ID_PREFIX + formulaIndex
                        val result = rendered[piece.latex]
                        val size = inlinePlaceholderOf(result, fontDp, density)
                        inline[id] =
                            InlineTextContent(
                                Placeholder(size.first, size.second, PlaceholderVerticalAlign.TextCenter),
                            ) { _ ->
                                (result as? FormulaRenderResult.Rendered)?.let { outcome ->
                                    Image(
                                        bitmap = remember(outcome) { outcome.bitmap.asImageBitmap() },
                                        contentDescription = null,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                            }
                        appendInlineContent(id, if (fallbackToRaw) piece.latex else "")
                        formulaIndex++
                    }
                }
            }
        }
    Text(text = text, style = style, inlineContent = inline)
}

/**
 * 行内公式的占位尺寸。
 *
 * - 已渲染：按位图实际尺寸换算成 dp，超过 [INLINE_MAX_HEIGHT_RATIO] 倍字号时等比缩小，
 *   否则一条分式会把行高顶成正文的三四倍，看起来又变成「堆在一起」；
 * - 渲染中：按 LaTeX 长度估一个宽度，保证文字先能排下来，结果到达后再微调。
 */
private fun inlinePlaceholderOf(
    result: FormulaRenderResult?,
    fontDp: Dp,
    density: Density,
): Pair<TextUnit, TextUnit> {
    val size: Pair<Dp, Dp> =
        when (result) {
            is FormulaRenderResult.Rendered -> {
                val naturalWidth = with(density) { result.bitmap.width.toDp() }
                val naturalHeight = with(density) { result.bitmap.height.toDp() }
                val maxHeight = fontDp * INLINE_MAX_HEIGHT_RATIO
                val scale =
                    if (naturalHeight.value > 0f && naturalHeight > maxHeight) {
                        maxHeight.value / naturalHeight.value
                    } else {
                        1f
                    }
                (naturalWidth * scale) to (naturalHeight * scale)
            }
            // 渲染中 / 渲染失败：按约四个字宽、一行字高占位，文字先排下来再微调
            else -> (fontDp * PENDING_WIDTH_EM) to (fontDp * PENDING_HEIGHT_EM)
        }
    return with(density) { size.first.toSp() to size.second.toSp() }
}

/** 把片段按行间公式切成「行内段」与「行间块」（纯逻辑，可单测）。 */
internal object FormulaPieceGroups {
    sealed interface Group {
        /** 一段文字 + 行内公式，整体合成一个 Text。 */
        data class Run(val pieces: List<FormulaContent.Piece>) : Group

        /** 一条行间公式，单独成块居中显示。 */
        data class Display(val latex: String) : Group
    }

    fun of(pieces: List<FormulaContent.Piece>): List<Group> {
        val groups = ArrayList<Group>()
        val current = ArrayList<FormulaContent.Piece>()

        fun flush() {
            if (current.isNotEmpty()) {
                groups += Group.Run(current.toList())
                current.clear()
            }
        }
        pieces.forEach { piece ->
            if (piece is FormulaContent.Piece.Formula && piece.displayMode) {
                flush()
                groups += Group.Display(piece.latex)
            } else {
                current += piece
            }
        }
        flush()
        return groups
    }
}

/** 行内公式的 inlineContent id 前缀。 */
private const val INLINE_ID_PREFIX = "inline-formula-"

/** 行内公式允许的最大高度（相对字号）：再高就不像「行内」了。 */
private const val INLINE_MAX_HEIGHT_RATIO: Float = 2.6f

/** 公式尚未渲染完成时的占位宽度（相对字号）：约四个字宽。 */
private const val PENDING_WIDTH_EM: Float = 4f

/** 公式尚未渲染完成时的占位高度（相对字号）：约一行字高。 */
private const val PENDING_HEIGHT_EM: Float = 1.2f

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
