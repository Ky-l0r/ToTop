package com.totop.app.ui.study

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.totop.app.domain.model.ExamKind
import com.totop.app.domain.model.ScorePoint
import com.totop.app.ui.theme.Tokens
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * 得分率折线（Canvas 静态一帧，无任何动画）。
 *
 * 性能约定（架构设计第十节）：
 * - 只画一帧，不建动画状态、不做时间轴插值；
 * - 点数超过 [MAX_POINTS] 时右端截断并提示，避免一次画上百个点；
 * - 文字用 [rememberTextMeasurer] 复用测量结果，不为每个点新建 Layout。
 *
 * 视觉约定（docs/学情与成绩趋势.md 4.6）：
 * - 大考实心点并标名称，小考空心小点不标名；
 * - 目标线为虚线，颜色与主色同族但不抢折线。
 */
@Composable
fun TrendChart(
    points: List<ScorePoint>,
    target: Double?,
    modifier: Modifier = Modifier,
) {
    if (points.isEmpty()) return
    val measurer = rememberTextMeasurer()
    val axisStyle = TextStyle(fontSize = 10.sp, color = Tokens.OnSurfaceVariant)
    val nameStyle = TextStyle(fontSize = 9.sp, color = Tokens.OnSurfaceVariant)

    val visible = points.takeLast(MAX_POINTS)
    val rates = visible.map { it.rate }
    val candidates = if (target != null) rates + target else rates
    val rawMin = candidates.min()
    val rawMax = candidates.max()
    var low = max(0.0, floor((rawMin - 5.0) / 5.0) * 5.0)
    var high = min(100.0, ceil((rawMax + 5.0) / 5.0) * 5.0)
    if (high - low < 10.0) {
        low = max(0.0, low - 5.0)
        high = min(100.0, high + 5.0)
    }
    if (high - low < 10.0) high = low + 10.0

    Canvas(
        modifier =
            modifier
                .fillMaxWidth()
                .height(ChartHeight),
    ) {
        val left = 30.dp.toPx()
        val right = size.width - 8.dp.toPx()
        val top = 18.dp.toPx()
        val bottom = size.height - 10.dp.toPx()
        val plotWidth = max(1f, right - left)
        val plotHeight = max(1f, bottom - top)

        fun yOf(value: Double): Float = top + ((high - value) / (high - low)).toFloat() * plotHeight

        fun xOf(index: Int): Float =
            if (visible.size == 1) left + plotWidth / 2f else left + plotWidth * index / (visible.size - 1).toFloat()

        // 三条水平参考线：上界、中线、下界
        listOf(high, (high + low) / 2.0, low).forEach { value ->
            val y = yOf(value)
            drawLine(
                color = Tokens.Outline,
                start = Offset(left, y),
                end = Offset(right, y),
                strokeWidth = 1f,
            )
            val label = formatRate(value)
            val measured = measurer.measure(label, axisStyle)
            drawText(
                textLayoutResult = measured,
                topLeft = Offset(0f, y - measured.size.height / 2f),
            )
        }

        // 目标线
        if (target != null) {
            val y = yOf(target)
            drawLine(
                color = Tokens.Primary.copy(alpha = 0.45f),
                start = Offset(left, y),
                end = Offset(right, y),
                strokeWidth = 1.5f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)),
            )
            val measured = measurer.measure("目标 " + formatRate(target) + "%", axisStyle)
            drawText(
                textLayoutResult = measured,
                topLeft = Offset(right - measured.size.width, y - measured.size.height - 2.dp.toPx()),
            )
        }

        // 折线
        val path = Path()
        visible.forEachIndexed { index, point ->
            val x = xOf(index)
            val y = yOf(point.rate)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path = path,
            color = Tokens.Primary,
            style = Stroke(width = 2f),
        )

        // 点：大考实心并标名，小考空心
        visible.forEachIndexed { index, point ->
            val center = Offset(xOf(index), yOf(point.rate))
            if (point.kind == ExamKind.MAJOR) {
                drawCircle(color = Tokens.Primary, radius = 4.dp.toPx(), center = center)
                val label = point.name.take(MAX_NAME_CHARS)
                if (label.isNotEmpty() && visible.size <= MAX_LABELLED_POINTS) {
                    val measured = measurer.measure(label, nameStyle)
                    val x = (center.x - measured.size.width / 2f).coerceIn(0f, size.width - measured.size.width)
                    drawText(
                        textLayoutResult = measured,
                        topLeft = Offset(x, center.y - measured.size.height - 5.dp.toPx()),
                    )
                }
            } else {
                drawCircle(
                    color = Tokens.Primary,
                    radius = 3.dp.toPx(),
                    center = center,
                    style = Stroke(width = 1.5f),
                )
            }
        }
    }
}

/** 图表高度：低端机一屏放得下头卡与图表。 */
private val ChartHeight = 176.dp

/** 最多画 [MAX_POINTS] 个点。 */
private const val MAX_POINTS: Int = 60

/** 超过该点数不再标名称，否则会糊成一片。 */
private const val MAX_LABELLED_POINTS: Int = 8

/** 点名最长显示字数。 */
private const val MAX_NAME_CHARS: Int = 6

/** 得分率展示：整数不带小数，便于扫读。 */
internal fun formatRate(value: Double): String {
    val rounded = Math.round(value).toInt()
    return rounded.toString()
}

/** 保留一位小数的变化量，用于「▲3.2」这类文案。 */
internal fun formatDelta(value: Double): String {
    val sign = if (value >= 0) "+" else "-"
    val abs = String.format(java.util.Locale.CHINA, "%.1f", kotlin.math.abs(value))
    return sign + abs
}
