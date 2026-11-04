package com.totop.app.ui.reviewmaterial

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp

/**
 * 复习资料的行内排版：`**加粗**` + 轻数学记号（下标、上标、分式、根号）。
 *
 * 为什么不用 WebView + KaTeX：复习资料一屏少说几百段文字，公式散落在每一条要点里，
 * 每条都走一次「JS 往返 + 截图」会让页面直接卡死（解题页只处理十来条公式，
 * 量级完全不同）。分式与根号在这份资料里只有两种形态，用 Compose 自己排更稳也更省。
 *
 * 排版口径：
 * - 上下标用 [BaselineShift]，字号取正文的 0.72，跟随文字流折行；
 * - 分式是「分子 / 横线 / 分母」三层的行内块，横线宽度等于较宽的那一边；
 * - 根号是「√ 符号 + 一条覆盖被开方内容的横线」，那条横线就是课本里根号的顶横；
 * - 这些块都按行内内容（inlineContent）插入，因此仍随中文正常折行，
 *   不会出现「一行公式撑破卡片」或「公式单独占一行」。
 */
@Composable
internal fun ReviewRichText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    bold: Boolean = false,
) {
    val runs = remember(text) { ReviewText.splitBold(text) }
    val hasMath = remember(runs) { runs.any { ReviewMath.hasMath(it.text) } }
    if (!hasMath) {
        Text(
            text = remember(runs, bold) { plainAnnotated(runs, bold) },
            modifier = modifier,
            style = style,
            color = color,
            fontWeight = if (bold) FontWeight.Bold else null,
        )
        return
    }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val runsWithMath = remember(runs) { runs.map { MathRun(ReviewMath.parse(it.text), it.bold) } }
    val content =
        remember(runsWithMath, style, color, bold, density, measurer) {
            MathContent.of(
                runs = runsWithMath,
                style = style,
                color = color,
                forceBold = bold,
                measurer = measurer,
                density = density,
            )
        }
    Text(
        text = content.text,
        modifier = modifier,
        style = style,
        color = color,
        fontWeight = if (bold) FontWeight.Bold else null,
        inlineContent = content.inline,
    )
}

/** 一段已经解析成记号的加粗片段。 */
private class MathRun(
    val tokens: List<ReviewMath.Token>,
    val bold: Boolean,
)

/** 解析好的行内文本：可排版的字符串 + 分式/根号的行内块。 */
private class MathContent(
    val text: AnnotatedString,
    val inline: Map<String, InlineTextContent>,
) {
    companion object {
        fun of(
            runs: List<MathRun>,
            style: TextStyle,
            color: Color,
            forceBold: Boolean,
            measurer: TextMeasurer,
            density: Density,
        ): MathContent {
            val inline = LinkedHashMap<String, InlineTextContent>()
            var counter = 0
            val text =
                buildAnnotatedString {
                    runs.forEach { run ->
                        val bold = run.bold || forceBold
                        withStyle(if (bold) SpanStyle(fontWeight = FontWeight.Bold) else SpanStyle()) {
                            run.tokens.forEach { token ->
                                when (token) {
                                    is ReviewMath.Token.Text -> append(token.value)
                                    is ReviewMath.Token.Sup ->
                                        appendScript(token.value, style, BaselineShift.Superscript)

                                    is ReviewMath.Token.Sub ->
                                        appendScript(token.value, style, BaselineShift.Subscript)

                                    is ReviewMath.Token.Fraction -> {
                                        val id = INLINE_ID_PREFIX + "frac-" + counter++
                                        val size = fractionSizeOf(token, style, bold, measurer, density)
                                        inline[id] = fractionInline(token, style, color, bold, size)
                                        appendInlineContent(id, altTextOf(token))
                                    }

                                    is ReviewMath.Token.Radical -> {
                                        val id = INLINE_ID_PREFIX + "root-" + counter++
                                        val size = radicalSizeOf(token, style, bold, measurer, density)
                                        inline[id] = radicalInline(token, style, color, bold, size)
                                        appendInlineContent(id, altTextOf(token))
                                    }
                                }
                            }
                        }
                    }
                }
            return MathContent(text, inline)
        }
    }
}

/** 上下标：字号缩到 0.72，靠基线位移排到上方/下方。 */
private fun AnnotatedString.Builder.appendScript(
    value: String,
    style: TextStyle,
    shift: BaselineShift,
) {
    withStyle(
        SpanStyle(
            fontSize = scriptSizeOf(style),
            baselineShift = shift,
        ),
    ) { append(value) }
}

/** 不含数学记号的普通（可加粗）文本：绝大多数行走的快速路径。 */
private fun plainAnnotated(
    runs: List<ReviewTextRun>,
    bold: Boolean,
): AnnotatedString =
    buildAnnotatedString {
        runs.forEach { run ->
            if (run.bold || bold) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(run.text) }
            } else {
                append(run.text)
            }
        }
    }

/** 分式块：分子在上、横线居中、分母在下。 */
private fun fractionInline(
    token: ReviewMath.Token.Fraction,
    style: TextStyle,
    color: Color,
    bold: Boolean,
    size: BlockSize,
): InlineTextContent {
    val partStyle = fractionPartStyleOf(style, bold)
    val numerator = partAnnotated(token.numerator, partStyle, bold)
    val denominator = partAnnotated(token.denominator, partStyle, bold)
    val placeholder = Placeholder(size.width, size.height, PlaceholderVerticalAlign.Center)
    return InlineTextContent(placeholder) {
        Column(
            modifier = Modifier.width(size.widthDp).height(size.heightDp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(text = numerator, style = partStyle, color = color, maxLines = 1, softWrap = false)
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(FRACTION_RULE_HEIGHT)
                        .background(color),
            )
            Text(text = denominator, style = partStyle, color = color, maxLines = 1, softWrap = false)
        }
    }
}

/** 根号块：`√` 符号 + 覆盖被开方内容的横线（课本里根号的顶横）。 */
private fun radicalInline(
    token: ReviewMath.Token.Radical,
    style: TextStyle,
    color: Color,
    bold: Boolean,
    size: BlockSize,
): InlineTextContent {
    val bodyStyle = radicalBodyStyleOf(style, bold)
    val body = partAnnotated(token.radicand, bodyStyle, bold)
    val signStyle = style.copy(fontSize = style.fontSize * RADICAL_SIGN_SCALE)
    val placeholder = Placeholder(size.width, size.height, PlaceholderVerticalAlign.TextBottom)
    return InlineTextContent(placeholder) {
        Row(
            modifier = Modifier.width(size.widthDp).height(size.heightDp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                text = RADICAL_SIGN_TEXT,
                style = signStyle,
                color = color,
                maxLines = 1,
                softWrap = false,
            )
            Column {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(VINCULUM_HEIGHT)
                            .background(color),
                )
                Text(text = body, style = bodyStyle, color = color, maxLines = 1, softWrap = false)
            }
        }
    }
}

/** 分式/根号在行内占的尺寸（口径与 [Placeholder] 一致）。 */
private class BlockSize(
    val width: TextUnit,
    val height: TextUnit,
    val widthDp: Dp,
    val heightDp: Dp,
)

/** 分式块尺寸：宽度取分子分母的较宽者，高度为两行小字加横线。 */
private fun fractionSizeOf(
    token: ReviewMath.Token.Fraction,
    style: TextStyle,
    bold: Boolean,
    measurer: TextMeasurer,
    density: Density,
): BlockSize {
    val partStyle = fractionPartStyleOf(style, bold)
    val numerator = measurePart(token.numerator, partStyle, bold, measurer)
    val denominator = measurePart(token.denominator, partStyle, bold, measurer)
    val width = maxOf(numerator.first, denominator.first) + with(density) { FRACTION_SIDE_PADDING.toPx() }
    val height = numerator.second + denominator.second + with(density) { FRACTION_RULE_HEIGHT.toPx() }
    return BlockSize(
        width = textUnitOf(width, density),
        height = textUnitOf(height, density),
        widthDp = with(density) { width.toDp() },
        heightDp = with(density) { height.toDp() },
    )
}

/** 根号块尺寸：`√` 宽度 + 被开方内容宽度，高度取两者较高者。 */
private fun radicalSizeOf(
    token: ReviewMath.Token.Radical,
    style: TextStyle,
    bold: Boolean,
    measurer: TextMeasurer,
    density: Density,
): BlockSize {
    val bodyStyle = radicalBodyStyleOf(style, bold)
    val body = measurePart(token.radicand, bodyStyle, bold, measurer)
    val signStyle = style.copy(fontSize = style.fontSize * RADICAL_SIGN_SCALE)
    val sign = measurer.measure(AnnotatedString(RADICAL_SIGN_TEXT), signStyle, softWrap = false, maxLines = 1)
    val width = sign.size.width + body.first + with(density) { RADICAL_SIDE_PADDING.toPx() }
    val height = maxOf(sign.size.height.toFloat(), body.second + with(density) { VINCULUM_HEIGHT.toPx() })
    return BlockSize(
        width = textUnitOf(width, density),
        height = textUnitOf(height, density),
        widthDp = with(density) { width.toDp() },
        heightDp = with(density) { height.toDp() },
    )
}

/** 量一段片段：返回宽高（像素）。 */
private fun measurePart(
    tokens: List<ReviewMath.Token>,
    style: TextStyle,
    bold: Boolean,
    measurer: TextMeasurer,
): Pair<Float, Float> {
    val result =
        measurer.measure(
            text = partAnnotated(tokens, style, bold),
            style = style,
            softWrap = false,
            maxLines = 1,
        )
    return result.size.width.toFloat() to result.size.height.toFloat()
}

/** 分式/根号内部的内容：上下标仍然真排，更深的结构压平成文字。 */
private fun partAnnotated(
    tokens: List<ReviewMath.Token>,
    style: TextStyle,
    bold: Boolean,
): AnnotatedString =
    buildAnnotatedString {
        tokens.forEach { token ->
            if (bold) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { appendPart(token, style) }
            } else {
                appendPart(token, style)
            }
        }
    }

private fun AnnotatedString.Builder.appendPart(
    token: ReviewMath.Token,
    style: TextStyle,
) {
    when (token) {
        is ReviewMath.Token.Text -> append(token.value)
        is ReviewMath.Token.Sup -> appendScript(token.value, style, BaselineShift.Superscript)
        is ReviewMath.Token.Sub -> appendScript(token.value, style, BaselineShift.Subscript)
        // 分式里再套分式/根号：压平成一行文字。中学物理里没有这种写法，
        // 真排第二层嵌套在 PDF 与行内两套度量里都要再走一遍，收益不成比例。
        is ReviewMath.Token.Fraction, is ReviewMath.Token.Radical ->
            append(ReviewMath.flattenScripted(listOf(token)))
    }
}

/** 行内块的替代文字：无障碍朗读与复制时拿到的是可读式子而不是空白。 */
private fun altTextOf(token: ReviewMath.Token): String = ReviewMath.flattenScripted(listOf(token))

/** 像素 -> sp：与 Placeholder 的尺寸口径对齐。 */
private fun textUnitOf(
    px: Float,
    density: Density,
): TextUnit = with(density) { px.toDp().toSp() }

/** 上下标字号：正文的 0.72 倍。 */
private fun scriptSizeOf(style: TextStyle): TextUnit = style.fontSize * SCRIPT_SCALE

/** 分式里的分子分母：比正文略小，行高收紧，避免分式块把整行顶开。 */
private fun fractionPartStyleOf(
    style: TextStyle,
    bold: Boolean,
): TextStyle =
    style.copy(
        fontSize = style.fontSize * FRACTION_SCALE,
        lineHeight = style.fontSize * FRACTION_SCALE * FRACTION_LINE_RATIO,
        fontWeight = if (bold) FontWeight.Bold else style.fontWeight,
    )

/**
 * 被开方内容的样式。
 *
 * 行高必须压到字号的 1.15 倍：默认行高（26sp）会在字顶上方留一条空白，
 * 根号的横线就会飘在半空，看着像「横线没压住数」。
 */
private fun radicalBodyStyleOf(
    style: TextStyle,
    bold: Boolean,
): TextStyle =
    style.copy(
        lineHeight = style.fontSize * RADICAND_LINE_RATIO,
        fontWeight = if (bold) FontWeight.Bold else style.fontWeight,
    )

/** 行内块的 id 前缀。 */
private const val INLINE_ID_PREFIX: String = "review-math-"

private const val RADICAL_SIGN_TEXT: String = "√"

/** 上下标相对正文的缩放比例。 */
private const val SCRIPT_SCALE: Float = 0.72f

/** 分子分母相对正文的缩放比例。 */
private const val FRACTION_SCALE: Float = 0.86f

/** 分子分母的行高倍数：贴近字形，分式块才不会虚高。 */
private const val FRACTION_LINE_RATIO: Float = 1.05f

/** 被开方内容的行高倍数。 */
private const val RADICAND_LINE_RATIO: Float = 1.15f

/** 根号符号相对正文的放大倍数：要比被开方内容高，才像覆盖住。 */
private const val RADICAL_SIGN_SCALE: Float = 1.18f

/** 分式横线、根号顶横的粗细。 */
private val FRACTION_RULE_HEIGHT = 1.dp

private val VINCULUM_HEIGHT = 1.dp

/** 分式左右各留一点空：横线略宽于数字是课本的排法。 */
private val FRACTION_SIDE_PADDING = 1.5.dp

/** 根号右侧留一点空，避免和下一个字粘住。 */
private val RADICAL_SIDE_PADDING = 0.5.dp
