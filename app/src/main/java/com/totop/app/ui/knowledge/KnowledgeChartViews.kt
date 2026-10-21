package com.totop.app.ui.knowledge

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/*
 * 掌握度可视化组件（FR-3）。
 *
 * 渲染方式：**Compose Canvas 静态绘制**，只画一帧，不启动任何动画。
 *
 * 关于图表库：产品原方案拟用 WebView + ECharts(canvas)。本构建环境完全离线，
 * 无法获取 ECharts 发行包（与 assets/katex 缺少 KaTeX 官方包同源问题），
 * 且引入第三方 JS 需要额外的体积审计。因此这里用 Canvas 直接实现同一组图形：
 * 雷达（错题最多的知识点）、趋势折线（降采样 <= 60 点）、错因饼图、章节热力条。
 * 三者都是「禁用动画、禁用实时力导向」的静态图，与方案 7.1 的性能约束一致；
 * 后续若补齐 ECharts 资源，只需替换本文件的绘制函数，数据装配（KnowledgeCharts）无需改动。
 */

/**
 * 雷达图（错题最多的 6-8 个知识点）。
 *
 * 少于 3 个轴时无法围成多边形，改为横向条形图，避免画出退化的图形。
 */
@Composable
fun RadarChart(
    data: RadarData,
    modifier: Modifier = Modifier,
) {
    if (!data.drawable) {
        RadarFallback(data = data, modifier = modifier)
        return
    }
    val axisColor = MaterialTheme.colorScheme.outlineVariant
    val fillColor = MaterialTheme.colorScheme.primary
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(modifier = Modifier.fillMaxWidth().aspectRatio(1.25f)) {
            val centerX = size.width / 2f
            val centerY = size.height / 2f
            val radius = (minOf(size.width, size.height) / 2f) * RADAR_RADIUS_RATIO
            // 网格：4 圈
            for (ring in 1..RADAR_RINGS) {
                val scale = ring.toDouble() / RADAR_RINGS.toDouble()
                val vertices =
                    KnowledgeCharts.radarVertices(
                        count = data.axes.size,
                        radius = radius,
                        centerX = centerX,
                        centerY = centerY,
                        scale = { scale },
                    )
                drawPath(closedPolygon(vertices), color = axisColor, style = Stroke(width = 1f))
            }
            // 轴线
            val spokes =
                KnowledgeCharts.radarVertices(
                    count = data.axes.size,
                    radius = radius,
                    centerX = centerX,
                    centerY = centerY,
                    scale = { 1.0 },
                )
            spokes.forEach {
                    (x, y) ->
                drawLine(color = axisColor, start = Offset(centerX, centerY), end = Offset(x, y))
            }
            // 数据多边形
            val values =
                KnowledgeCharts.radarVertices(
                    count = data.axes.size,
                    radius = radius,
                    centerX = centerX,
                    centerY = centerY,
                    scale = { index -> data.axes[index].normalized },
                )
            drawPath(closedPolygon(values), color = fillColor.copy(alpha = FILL_ALPHA))
            drawPath(closedPolygon(values), color = fillColor, style = Stroke(width = 2.5f))
            values.forEach { (x, y) -> drawCircle(color = fillColor, radius = 3f, center = Offset(x, y)) }
        }
        RadarLegend(data = data)
    }
}

/** 少于 3 个轴时的条形图回退。 */
@Composable
private fun RadarFallback(
    data: RadarData,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text =
                if (data.axes.isEmpty()) {
                    "还没有足够的知识点数据：解几道题之后这里会出现雷达图。"
                } else {
                    "知识点少于 3 个，改用条形展示（雷达图至少要 3 个轴）。"
                },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        data.axes.forEach { axis ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = axis.label,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Box(
                    modifier =
                        Modifier.weight(1f).height(10.dp).background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(5.dp),
                        ),
                ) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth(axis.normalized.toFloat().coerceIn(0f, 1f))
                                .height(10.dp)
                                .background(MasteryColors.of(axis.band), RoundedCornerShape(5.dp)),
                    )
                }
                Text(
                    text = " " + Math.round(axis.score),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

/** 雷达图图例：轴名 + 分数 + 错题数（Canvas 内不画文字，避免字体度量开销）。 */
@Composable
private fun RadarLegend(data: RadarData) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        data.axes.chunked(RADAR_LEGEND_PER_ROW).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { axis ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier =
                                Modifier
                                    .size(8.dp)
                                    .background(MasteryColors.of(axis.band), RoundedCornerShape(2.dp)),
                        )
                        Text(
                            text =
                                " " + axis.label + " " + Math.round(axis.score) +
                                    if (axis.mistakeCount > 0) "（错 " + axis.mistakeCount + "）" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        Text(
            text = "越靠外越熟练；轴按「错题最多」排序，取前 " + RadarData.MAX_AXES + " 个知识点。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 趋势折线（降采样 <= 60 点）。
 *
 * 只画折线与数据点，不做平滑动画；纵轴按当前区间自适应。
 */
@Composable
fun TrendChart(
    data: TrendData,
    modifier: Modifier = Modifier,
) {
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (data.points.isEmpty()) {
            Text(
                text = "还没有掌握度历史：掌握度每天重算一次并保留 90 天。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        Canvas(modifier = Modifier.fillMaxWidth().height(140.dp)) {
            val innerWidth = size.width
            val innerHeight = size.height
            // 横向参考线：0 / 50 / 100 三档
            listOf(0f, 0.5f, 1f).forEach { ratio ->
                val y = innerHeight * (1f - ratio)
                drawLine(color = gridColor, start = Offset(0f, y), end = Offset(innerWidth, y), strokeWidth = 1f)
            }
            val vertices = KnowledgeCharts.lineVertices(data, innerWidth, innerHeight)
            if (vertices.size > 1) {
                val path =
                    Path().apply {
                        moveTo(vertices.first().first, vertices.first().second)
                        vertices.drop(1).forEach { (x, y) -> lineTo(x, y) }
                    }
                drawPath(path, color = lineColor, style = Stroke(width = 2.5f))
            }
            vertices.forEach { (x, y) -> drawCircle(color = lineColor, radius = 2.5f, center = Offset(x, y)) }
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "最低 " + Math.round(data.minValue) + " · 最高 " + Math.round(data.maxValue),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = data.note,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 错因分布饼图。
 *
 * 扇区从 12 点方向顺时针排列，颜色按固定七类索引取，保证同一错因在全应用同色。
 */
@Composable
fun ErrorTypePieChart(
    data: PieData,
    modifier: Modifier = Modifier,
) {
    if (data.isEmpty) {
        Text(
            text = "还没有记录到错因：错因由解错题时 AI 定位，共固定七类。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )
        return
    }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(modifier = Modifier.size(120.dp)) {
                val radius = minOf(size.width, size.height) / 2f
                val center = Offset(size.width / 2f, size.height / 2f)
                data.slices.forEach { slice ->
                    drawArc(
                        color = MasteryColors.pieColor(slice.colorIndex),
                        startAngle = slice.startAngle.toFloat() + ANGLE_OFFSET_DEGREES,
                        sweepAngle = slice.sweepAngle.toFloat(),
                        useCenter = true,
                        topLeft = Offset(center.x - radius, center.y - radius),
                        size = androidx.compose.ui.geometry.Size(radius * 2f, radius * 2f),
                    )
                }
            }
            Column(
                modifier = Modifier.padding(start = 12.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                data.slices.forEach { slice ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier =
                                Modifier
                                    .size(8.dp)
                                    .background(MasteryColors.pieColor(slice.colorIndex), RoundedCornerShape(2.dp)),
                        )
                        Text(
                            text = " " + slice.label + " " + slice.count + " 次（" + slice.percentText + "）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        Text(
            text = "共 " + data.total + " 条错因记录；扇区顺序固定为七类错因的产品定义顺序。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 章节热力条（红 <40 / 黄 40-70 / 绿 >70；未练习为灰）。 */
@Composable
fun HeatBar(
    row: HeatRow,
    modifier: Modifier = Modifier,
) {
    val color = if (row.untouched) MasteryColors.Untouched else MasteryColors.of(row.band)
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = row.name,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            fontWeight = if (row.untouched) FontWeight.Normal else FontWeight.Medium,
        )
        if (row.mistakeCount > 0) {
            Text(
                text = "错 " + row.mistakeCount + " · ",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box(
            modifier =
                Modifier
                    .size(width = 48.dp, height = 8.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp)),
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth((row.score / 100.0).toFloat().coerceIn(0f, 1f))
                        .height(8.dp)
                        .background(color, RoundedCornerShape(4.dp)),
            )
        }
        Text(
            text = " " + row.scoreText,
            style = MaterialTheme.typography.labelMedium,
            color = color,
        )
    }
}

/** 顶点列表转闭合多边形路径。 */
private fun closedPolygon(vertices: List<Pair<Float, Float>>): Path {
    val path = Path()
    if (vertices.isEmpty()) return path
    path.moveTo(vertices.first().first, vertices.first().second)
    vertices.drop(1).forEach { (x, y) -> path.lineTo(x, y) }
    path.close()
    return path
}

/** 雷达图半径占画布短边的比例（留出标签空间）。 */
private const val RADAR_RADIUS_RATIO = 0.86f

/** 雷达网格圈数。 */
private const val RADAR_RINGS = 4

/** 数据多边形填充透明度。 */
private const val FILL_ALPHA = 0.18f

/** 图例每行最多几个轴。 */
private const val RADAR_LEGEND_PER_ROW = 2

/** Canvas 的 0 度在 3 点方向；饼图数据从 12 点起算，因此偏移 -90 度。 */
private const val ANGLE_OFFSET_DEGREES = -90f
