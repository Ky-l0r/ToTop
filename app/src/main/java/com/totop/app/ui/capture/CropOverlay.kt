package com.totop.app.ui.capture

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.totop.app.util.NormalizedRect

/**
 * 框选遮罩层：在照片上拖出矩形选区，松手后**保留选区并可继续调整**。
 *
 * 交互约定（对齐主流拍题 App 的习惯）：
 * - 单指在空白处拖动 = 新建选框，无需先点「开始框选」；
 * - 松手后选区**保留**并显示四角把手，用户看得见自己框了什么；
 * - 拖动四角把手 = 调整选区；在选区内拖动 = 整体平移；
 * - 在选区外拖动 = 重新框选（不需要先清除）；
 * - 选区外压暗，让用户清楚会裁掉哪部分。
 *
 * 修复的既有缺陷：
 * 旧实现用 pointerInput(Unit)，手势 lambda 只创建一次，
 * 闭包里捕获的是**首次组合时的 rect（几乎总是 null）**。
 * 于是 onDragEnd 里读到的永远是 null，判定「没框或框太小」后立刻回调
 * onRectChanged(null)——用户看到的就是「一松手框就没了」。
 * 这里改为拖动过程只维护本地像素矩形，松手时用本地结果提交，
 * 并让 pointerInput 以 rect 为 key 重建，保证读到最新选区。
 *
 * @param rect 当前选区（归一化）；null 表示未框选（使用整张图）
 * @param onRectChanged 仅在**松手/调整结束**时回调，
 *   拖动过程每秒几十帧，若每帧都写 ViewModel 会触发大量重组导致卡顿
 */
@Composable
fun CropOverlay(
    rect: NormalizedRect?,
    onRectChanged: (NormalizedRect?) -> Unit,
    modifier: Modifier = Modifier,
    dimColor: Color = Color.Black.copy(alpha = 0.5f),
    borderColor: Color = Color.White,
) {
    val density = LocalDensity.current
    val handleTouchPx = with(density) { HANDLE_TOUCH_DP.dp.toPx() }
    val borderStrokePx = with(density) { 2.dp.toPx() }
    val handleRadiusPx = with(density) { HANDLE_DRAW_DP.dp.toPx() }

    // 拖动过程中的工作矩形（像素坐标）。非 null 表示正在拖。
    var working by remember(rect) { mutableStateOf<Rect?>(null) }
    var mode by remember(rect) { mutableStateOf(DragMode.NEW) }
    var anchor by remember(rect) { mutableStateOf(Offset.Zero) }
    var grabOffset by remember(rect) { mutableStateOf(Offset.Zero) }
    var baseRect by remember(rect) { mutableStateOf<Rect?>(null) }

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .pointerInput(rect) {
                    val viewWidth = size.width.toFloat()
                    val viewHeight = size.height.toFloat()
                    detectDragGestures(
                        onDragStart = { offset ->
                            val existing = rect?.toCanvasRect(viewWidth, viewHeight)
                            val hit = existing?.let { hitTest(it, offset, handleTouchPx) } ?: DragMode.NEW
                            mode = hit
                            baseRect = existing
                            anchor = offset
                            grabOffset =
                                if (hit == DragMode.MOVE && existing != null) {
                                    offset - existing.topLeft
                                } else {
                                    Offset.Zero
                                }
                            working =
                                when (hit) {
                                    DragMode.NEW -> Rect(offset, offset)
                                    else -> existing
                                }
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            working =
                                when (mode) {
                                    DragMode.NEW ->
                                        Rect(
                                            left = minOf(anchor.x, change.position.x),
                                            top = minOf(anchor.y, change.position.y),
                                            right = maxOf(anchor.x, change.position.x),
                                            bottom = maxOf(anchor.y, change.position.y),
                                        )
                                    DragMode.MOVE -> {
                                        val base = baseRect
                                        if (base == null) {
                                            null
                                        } else {
                                            clampInside(
                                                Rect(
                                                    left = change.position.x - grabOffset.x,
                                                    top = change.position.y - grabOffset.y,
                                                    right = change.position.x - grabOffset.x + base.width,
                                                    bottom = change.position.y - grabOffset.y + base.height,
                                                ),
                                                viewWidth,
                                                viewHeight,
                                            )
                                        }
                                    }
                                    else -> {
                                        val base = baseRect
                                        if (base == null) null else adjust(base, mode, change.position)
                                    }
                                }
                        },
                        onDragEnd = {
                            val current = working
                            working = null
                            baseRect = null
                            if (current != null) {
                                val normalized = toNormalized(current)
                                // 太小的框视为误触：原本有框就保留原框，原本没框就保持整图
                                onRectChanged(normalized ?: rect)
                            }
                        },
                        onDragCancel = {
                            working = null
                            baseRect = null
                        },
                    )
                },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val active = working ?: rect?.toCanvasRect(size.width, size.height) ?: return@Canvas
            val left = active.left
            val top = active.top
            val boxWidth = active.width.coerceAtLeast(1f)
            val boxHeight = active.height.coerceAtLeast(1f)
            val canvasWidth = size.width
            val canvasHeight = size.height

            // 压暗四块区域（上/下/左/右），中间选区保持原亮度
            drawRect(dimColor, size = Size(canvasWidth, top.coerceIn(0f, canvasHeight)))
            drawRect(
                dimColor,
                topLeft = Offset(0f, (top + boxHeight).coerceIn(0f, canvasHeight)),
                size = Size(canvasWidth, (canvasHeight - top - boxHeight).coerceAtLeast(0f)),
            )
            drawRect(dimColor, topLeft = Offset(0f, top), size = Size(left.coerceAtLeast(0f), boxHeight))
            drawRect(
                dimColor,
                topLeft = Offset(left + boxWidth, top),
                size = Size((canvasWidth - left - boxWidth).coerceAtLeast(0f), boxHeight),
            )
            // 选区边框
            drawRect(
                color = borderColor,
                topLeft = Offset(left, top),
                size = Size(boxWidth, boxHeight),
                style = Stroke(width = borderStrokePx),
            )
            // 四角把手：明确告诉用户「这块可以再调」
            listOf(
                Offset(left, top),
                Offset(left + boxWidth, top),
                Offset(left, top + boxHeight),
                Offset(left + boxWidth, top + boxHeight),
            ).forEach { corner ->
                drawCircle(
                    color = Color.Black.copy(alpha = 0.3f),
                    radius = handleRadiusPx * 1.4f,
                    center = corner,
                )
                drawCircle(color = borderColor, radius = handleRadiusPx, center = corner)
            }
        }

        // 空态提示：给用户一个明确的「怎么开始」的引导
        if (rect == null && working == null) {
            Box(
                modifier =
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 16.dp)
                        .background(Color.Black.copy(alpha = 0.45f), MaterialTheme.shapes.small)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    text = "用手指框出这道题（不框就用整张图）",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White,
                )
            }
        }
    }
}

/** 拖动模式。 */
private enum class DragMode { NEW, MOVE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

/** 命中测试：先看四角把手，再看是否落在框内，都不是则当作新建。 */
private fun hitTest(
    rect: Rect,
    point: Offset,
    touchRadius: Float,
): DragMode {
    val corners =
        listOf(
            DragMode.TOP_LEFT to rect.topLeft,
            DragMode.TOP_RIGHT to Offset(rect.right, rect.top),
            DragMode.BOTTOM_LEFT to Offset(rect.left, rect.bottom),
            DragMode.BOTTOM_RIGHT to rect.bottomRight,
        )
    corners.forEach { (mode, corner) ->
        if ((point - corner).getDistance() <= touchRadius) return mode
    }
    return if (rect.contains(point)) DragMode.MOVE else DragMode.NEW
}

/** 拖动某个角：只改对应的两条边。 */
private fun adjust(
    base: Rect,
    mode: DragMode,
    position: Offset,
): Rect =
    when (mode) {
        DragMode.TOP_LEFT -> orderRect(position.x, position.y, base.right, base.bottom)
        DragMode.TOP_RIGHT -> orderRect(base.left, position.y, position.x, base.bottom)
        DragMode.BOTTOM_LEFT -> orderRect(position.x, base.top, base.right, position.y)
        DragMode.BOTTOM_RIGHT -> orderRect(base.left, base.top, position.x, position.y)
        else -> base
    }

/** 保证四边有序（拖动时可能越过对边）。 */
private fun orderRect(
    x1: Float,
    y1: Float,
    x2: Float,
    y2: Float,
): Rect =
    Rect(
        left = minOf(x1, x2),
        top = minOf(y1, y2),
        right = maxOf(x1, x2),
        bottom = maxOf(y1, y2),
    )

/** 平移后夹到画布内：避免把框拖到看不见的地方。 */
private fun clampInside(
    rect: Rect,
    canvasWidth: Float,
    canvasHeight: Float,
): Rect {
    val dx =
        when {
            rect.left < 0f -> -rect.left
            rect.right > canvasWidth -> canvasWidth - rect.right
            else -> 0f
        }
    val dy =
        when {
            rect.top < 0f -> -rect.top
            rect.bottom > canvasHeight -> canvasHeight - rect.bottom
            else -> 0f
        }
    return Rect(
        left = rect.left + dx,
        top = rect.top + dy,
        right = rect.right + dx,
        bottom = rect.bottom + dy,
    )
}

/** 像素矩形 -> 归一化矩形；太小返回 null（视为误触）。 */
private fun toNormalized(rect: Rect): NormalizedRect? {
    val ordered = orderRect(rect.left, rect.top, rect.right, rect.bottom)
    if (ordered.width < MIN_SIZE_PX || ordered.height < MIN_SIZE_PX) return null
    val left = ordered.left.coerceIn(0f, 1f)
    val top = ordered.top.coerceIn(0f, 1f)
    val right = ordered.right.coerceIn(left, 1f)
    val bottom = ordered.bottom.coerceIn(top, 1f)
    if (right <= left || bottom <= top) return null
    return NormalizedRect(left = left, top = top, right = right, bottom = bottom)
}

/** 归一化矩形 -> 画布像素矩形（相对框选层自身尺寸）。 */
internal fun NormalizedRect.toCanvasRect(
    canvasWidth: Float,
    canvasHeight: Float,
): Rect =
    Rect(
        left = left * canvasWidth,
        top = top * canvasHeight,
        right = right * canvasWidth,
        bottom = bottom * canvasHeight,
    )

/** 角把手绘制半径（dp）。 */
private const val HANDLE_DRAW_DP = 6f

/** 角把手触摸半径（dp，比绘制更大，便于手指点中）。 */
private const val HANDLE_TOUCH_DP = 34f

/** 小于该像素宽高的框视为误触。 */
private const val MIN_SIZE_PX = 24f
