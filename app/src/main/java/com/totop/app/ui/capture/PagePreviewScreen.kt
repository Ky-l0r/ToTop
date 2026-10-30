package com.totop.app.ui.capture

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.totop.app.domain.model.QuestionDraft
import com.totop.app.ui.theme.Tokens
import com.totop.app.util.ImageFit
import com.totop.app.util.NormalizedRect
import com.totop.app.util.PixelRect

/**
 * 拍照后的照片预览与框选页。
 *
 * 承载三件事：
 * 1. **框选题目**：在照片上直接拖出选区，松手后选区保留，可拖角调整；
 * 2. **多页管理**：底部缩略图列表支持加拍、删除、调序；
 * 3. **确认回填**：确认后回到对话页，照片挂在输入框上方等用户写思路——
 *    **不会直接发送**。这一点是刻意改的：题目要不要解、思路写没写，
 *    由用户自己决定，而不是拍完就替他提交。
 *
 *
 * @param bitmap 用于框选的高清预览图（长边 [PREVIEW_LONG_SIDE]），
 *   刻意不用列表缩略图：缩略图长边只有 320，放全屏会糊成一片，用户根本瞄不准要框哪一行。
 */
@Composable
fun PagePreviewScreen(
    draft: QuestionDraft,
    currentIndex: Int,
    bitmap: android.graphics.Bitmap?,
    loading: Boolean,
    onCropChanged: (NormalizedRect?) -> Unit,
    onResetCrop: () -> Unit,
    onAddPage: () -> Unit,
    onDeletePage: (Int) -> Unit,
    onSelectPage: (Int) -> Unit,
    onMovePage: (Int, Int) -> Unit,
    onConfirm: () -> Unit,
    onRetake: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val page = draft.pages.getOrNull(currentIndex)
    // 图片在控件里的实际绘制区域。
    //
    // 这一步是「框选后发送的仍是未框选原图」的正面修法：预览用 ContentScale.Fit，
    // 控件里必然有 letterbox 空白；框选层铺满控件，若直接拿控件尺寸归一化，
    // 归一化坐标与图片像素整体错位，裁剪出来的区域自然对不上用户框的地方。
    // 这里先算出真正的绘制矩形，交给框选层按它换算。
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val imageRect =
        remember(bitmap, viewport) {
            if (bitmap == null || viewport.width == 0 || viewport.height == 0) {
                PixelRect(0, 0, viewport.width, viewport.height)
            } else {
                ImageFit.contentRect(
                    imageWidth = bitmap.width,
                    imageHeight = bitmap.height,
                    viewWidth = viewport.width,
                    viewHeight = viewport.height,
                )
            }
        }
    Column(modifier = modifier.fillMaxSize().background(Color.Black)) {
        // 顶部：取消 + 页序 + 重拍（重拍从整行按钮收进标题栏，省下一整行高度）
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onCancel) { Text("取消", color = Color.White) }
            Text(
                text =
                    if (draft.isMultiPage) {
                        "第 " + (currentIndex + 1) + "/" + draft.pageCount + " 页（同一道题）"
                    } else {
                        "框出题目区域更准"
                    },
                color = Color.White.copy(alpha = 0.9f),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRetake) { Text("重拍这页", color = Color.White) }
        }

        // 中间：照片 + 框选层
        //
        // 这一块是整页唯一被拉伸的区域（weight 1f），其余控件都压到最小：
        // 图片按 Fit 缩放，上下留白越多，能框到的细节越少。
        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onSizeChanged { viewport = it },
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "刚拍的照片",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp,
                            color = Color.White,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("正在打开照片…", color = Color.White.copy(alpha = 0.7f))
                    }
                }
            }
            CropOverlay(
                rect = page?.cropRect,
                contentRect = imageRect,
                onRectChanged = onCropChanged,
            )
        }

        // 框选操作 + 明确的框选状态反馈。
        //
        // 状态用**颜色 + 图标 + 文案**三重表达，而不是只改一行浅灰小字：
        // 学生框完题目抬头找确认，如果只是底部一行灰字变了，很容易漏看，
        // 结果就是「我都不知道自己有没有框上」。
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val cropped = page?.hasCrop == true
            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(Tokens.RadiusMedium))
                        .background(if (cropped) Color(0xFF2E7D5B) else Color.White.copy(alpha = 0.14f))
                        .padding(horizontal = 10.dp, vertical = 7.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (cropped) Icons.Filled.CheckCircle else Icons.Filled.CropFree,
                        contentDescription = null,
                        tint = if (cropped) Color.White else Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (cropped) "已框选：只上传框内的部分" else "未框选：将上传整张图",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            OutlinedButton(onClick = onResetCrop, enabled = cropped) {
                Text("用整张图", color = Color.White)
            }
        }

        // 多页缩略图：加拍 / 删除 / 排序（高度压到 52dp，只保留辨认页序所需的信息）
        LazyRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            itemsIndexed(draft.pages) { index, _ ->
                PageThumb(
                    pageNumber = index + 1,
                    selected = index == currentIndex,
                    onClick = { onSelectPage(index) },
                    onDelete =
                        if (draft.pageCount > 1) {
                            { onDeletePage(index) }
                        } else {
                            null
                        },
                    onMoveLeft =
                        if (index > 0) {
                            { onMovePage(index, index - 1) }
                        } else {
                            null
                        },
                    onMoveRight =
                        if (index < draft.pageCount - 1) {
                            { onMovePage(index, index + 1) }
                        } else {
                            null
                        },
                )
            }
            item {
                Box(
                    modifier =
                        Modifier
                            .size(THUMB_SIZE)
                            .background(Color.White.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                            .clickable(onClick = onAddPage),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "加拍一页", tint = Color.White)
                }
            }
        }

        // 底部动作
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
            Button(
                onClick = onConfirm,
                enabled = !loading && draft.pages.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                Text(if (loading) "图片处理中…" else "确认，去写解题思路")
            }
        }
    }
}

@Composable
private fun PageThumb(
    pageNumber: Int,
    selected: Boolean,
    onClick: () -> Unit,
    onDelete: (() -> Unit)?,
    onMoveLeft: (() -> Unit)?,
    onMoveRight: (() -> Unit)?,
) {
    Box(
        modifier =
            Modifier
                .size(THUMB_SIZE)
                .background(
                    if (selected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.2f),
                    RoundedCornerShape(8.dp),
                )
                .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text("第" + pageNumber + "页", color = Color.White, style = MaterialTheme.typography.labelMedium)
        onDelete?.let { delete ->
            Box(
                modifier =
                    Modifier.align(Alignment.TopEnd).padding(2.dp)
                        .size(18.dp)
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(50))
                        .clickable(onClick = delete),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "删除这一页",
                    tint = Color.White,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
        // 排序：左右移动按钮比拖拽更可靠（低端机拖拽易误触）
        Row(modifier = Modifier.align(Alignment.BottomCenter)) {
            onMoveLeft?.let { move ->
                Text("‹", color = Color.White, modifier = Modifier.clickable(onClick = move).padding(horizontal = 6.dp))
            }
            onMoveRight?.let { move ->
                Text("›", color = Color.White, modifier = Modifier.clickable(onClick = move).padding(horizontal = 6.dp))
            }
        }
    }
}

/**
 * 框选预览图的长边像素上限。
 *
 * 取值理由：框选是对着屏幕用手指画的，太糊看不清行，太大又会在低端机上 OOM。
 * 1280 与上传图长边一致——预览里看到的就是最终上传的内容，所见即所得。
 */
const val PREVIEW_LONG_SIDE: Int = 1280

/** 页缩略图边长：够看清页序与大致内容，又不和框选区抢高度。 */
private val THUMB_SIZE = 52.dp
