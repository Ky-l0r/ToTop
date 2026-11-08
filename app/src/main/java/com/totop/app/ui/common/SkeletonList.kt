package com.totop.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/**
 * 首帧骨架：数据没到时先画出「这里将会有几行」。
 *
 * 为什么需要它：二级页有 260ms 的转场动画，而数据来自 Room 的异步查询。
 * 旧实现在这段时间里是一片空白 + 一个转圈，动画跑在空白上，观感就是「没有动画」；
 * 骨架条给了动画可动的对象，也提前把版面高度占住，数据到达时不会整页跳一下。
 *
 * 刻意不做闪烁动画：规范里只允许位移 / 淡入 / 极轻缩放，循环的呼吸条属于装饰性动效。
 *
 * @param rows 占位行数，一般与首屏能看到的卡片数相当
 */
@Composable
fun SkeletonList(
    rows: Int = 3,
    modifier: Modifier = Modifier,
) {
    val barColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        repeat(rows) { index ->
            // 首行短一点当标题，其余行长短交替：整排等宽看起来像表格，不像内容。
            val widthRatio =
                when {
                    index == 0 -> HEADER_WIDTH_RATIO
                    index % 2 == 0 -> BODY_WIDTH_RATIO
                    else -> BODY_WIDTH_RATIO_ALT
                }
            val barHeight = if (index == 0) HEADER_HEIGHT else BODY_HEIGHT
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth(widthRatio)
                        .height(barHeight)
                        .clip(RoundedCornerShape(BAR_RADIUS))
                        .background(barColor),
            )
        }
    }
}

/** 首行（标题位）宽度占比。 */
private const val HEADER_WIDTH_RATIO: Float = 0.45f

/** 正文占位条宽度占比。 */
private const val BODY_WIDTH_RATIO: Float = 0.92f

/** 正文占位条宽度占比（长短交替用）。 */
private const val BODY_WIDTH_RATIO_ALT: Float = 0.78f

private val HEADER_HEIGHT = 16.dp

private val BODY_HEIGHT = 11.dp

private val BAR_RADIUS = 6.dp
