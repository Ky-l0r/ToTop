package com.totop.app.ui.knowledge

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/*
 * 掌握度可视化组件（FR-3）。
 *
 * 渲染方式：**Compose Canvas 静态绘制**，只画一帧，不启动任何动画。
 *
 * 关于图表库：产品原方案拟用 WebView + ECharts(canvas)。本构建环境完全离线，
 * 无法获取 ECharts 发行包（与 assets/katex 缺少 KaTeX 官方包同源问题），
 * 且引入第三方 JS 需要额外的体积审计。因此这里用 Canvas 直接实现图表。
 *
 * 0.9 起只保留**章节热力条**：掌握度雷达、趋势折线、错因饼图已从页面移除，
 * 三者的数据装配（KnowledgeCharts）与单测保留不动——删它们要连带删掉二十多条测试，
 * 换来的是省几毫秒计算，不划算；页面不再画，就没有占位与空图。
 */

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
