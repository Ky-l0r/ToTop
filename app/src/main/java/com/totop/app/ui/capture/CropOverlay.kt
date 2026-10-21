package com.totop.app.ui.capture

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.totop.app.util.NormalizedRect

/**
 * 框选遮罩层：在照片上拖出矩形选区。
 *
 * 交互约定（对齐主流拍题 App 的习惯）：
 * - 单指拖动即开始新的选框，无需先点「开始框选」；
 * - 从任意方向拖都可以，内部会重新排序坐标；
 * - **不框选就表示使用整张图**，不强制用户操作；
 * - 选区外压暗，让用户清楚会裁掉哪部分。
 *
 * @param rect 当前选区（归一化）；null 表示未框选
 * @param onRectChanged 拖动过程中持续回调，便于上层实时显示「已选区域」
 */
@Composable
fun CropOverlay(
    rect: NormalizedRect?,
    onRectChanged: (NormalizedRect?) -> Unit,
    modifier: Modifier = Modifier,
    dimColor: Color = Color.Black.copy(alpha = 0.45f),
    borderColor: Color = Color.White,
) {
    var dragging by remember { mutableStateOf(false) }
    var anchor by remember { mutableStateOf(Offset.Zero) }
    var current by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            dragging = true
                            anchor = offset
                            current = offset
                        },
                        onDrag = { change, _ ->
                            current = change.position
                            onRectChanged(
                                NormalizedRect.fromDrag(
                                    x1 = anchor.x,
                                    y1 = anchor.y,
                                    x2 = current.x,
                                    y2 = current.y,
                                    viewWidth = size.width,
                                    viewHeight = size.height,
                                ),
                            )
                        },
                        onDragEnd = {
                            dragging = false
                            // 拖得太小视为误触，直接回到「整图」
                            val r = rect
                            if (r == null || r.widthRatio < MIN_RATIO || r.heightRatio < MIN_RATIO) {
                                onRectChanged(null)
                            }
                        },
                        onDragCancel = { dragging = false },
                    )
                },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val active =
                if (dragging) {
                    NormalizedRect.fromDrag(
                        anchor.x, anchor.y, current.x, current.y,
                        size.width.toInt(), size.height.toInt(),
                    )
                } else {
                    rect
                }
            if (active == null) return@Canvas
            val left = active.left * size.width
            val top = active.top * size.height
            val width = (active.right - active.left) * size.width
            val height = (active.bottom - active.top) * size.height

            // 压暗四块区域（上/下/左/右），中间选区保持原亮度
            drawRect(dimColor, size = Size(size.width, top))
            drawRect(dimColor, topLeft = Offset(0f, top + height), size = Size(size.width, size.height - top - height))
            drawRect(dimColor, topLeft = Offset(0f, top), size = Size(left, height))
            drawRect(
                dimColor,
                topLeft = Offset(left + width, top),
                size = Size(size.width - left - width, height),
            )
            // 选区边框
            drawRect(
                color = borderColor,
                topLeft = Offset(left, top),
                size = Size(width, height),
                style = Stroke(width = with(density) { 2.dp.toPx() }),
            )
        }

        if (rect == null && !dragging) {
            Box(
                modifier =
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 16.dp)
                        .background(Color.Black.copy(alpha = 0.45f), MaterialTheme.shapes.small)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    text = "在照片上框出题目，不框就用整张图",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White,
                )
            }
        }
    }
}

/** 小于该比例的选区视为误触（相对整图）。 */
private const val MIN_RATIO: Float = 0.06f
