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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.totop.app.domain.model.QuestionDraft
import com.totop.app.util.NormalizedRect

/**
 * 拍照后的照片预览与框选页。
 *
 * 承载三件事：
 * 1. **框选题目**：在照片上直接拖出选区，可重置为整图；
 * 2. **多页管理**：底部缩略图列表支持加拍、删除、拖动排序；
 * 3. **确认提交**：确认后由上层直接调用视觉模型，不再二次询问。
 *
 * 离线记题入口同样在这里，但走的是本地保存分支，不调用模型。
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
    onSaveOffline: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val page = draft.pages.getOrNull(currentIndex)
    Column(modifier = modifier.fillMaxSize().background(Color.Black)) {
        // 顶部：取消 + 页序
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onCancel) { Text("取消", color = Color.White) }
            Spacer(Modifier.width(4.dp))
            Text(
                text =
                    if (draft.isMultiPage) {
                        "第 " + (currentIndex + 1) + "/" + draft.pageCount + " 页（同一道题）"
                    } else {
                        "框出题目区域更准"
                    },
                color = Color.White.copy(alpha = 0.9f),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        // 中间：照片 + 框选层
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "刚拍的照片",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("正在打开照片…", color = Color.White.copy(alpha = 0.7f))
                }
            }
            CropOverlay(
                rect = page?.cropRect,
                onRectChanged = onCropChanged,
            )
        }

        // 框选操作
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = onResetCrop, enabled = page?.hasCrop == true) {
                Text("用整张图", color = Color.White)
            }
            OutlinedButton(onClick = onRetake) { Text("重拍这页", color = Color.White) }
        }

        Spacer(Modifier.height(10.dp))

        // 多页缩略图：加拍 / 删除 / 排序
        LazyRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            itemsIndexed(draft.pages) { index, item ->
                PageThumb(
                    pageNumber = index + 1,
                    selected = index == currentIndex,
                    onClick = { onSelectPage(index) },
                    onDelete = if (draft.pageCount > 1) { { onDeletePage(index) } } else null,
                    onMoveLeft = if (index > 0) { { onMovePage(index, index - 1) } } else null,
                    onMoveRight =
                        if (index < draft.pageCount - 1) { { onMovePage(index, index + 1) } } else null,
                )
            }
            item {
                Box(
                    modifier =
                        Modifier
                            .size(64.dp)
                            .background(Color.White.copy(alpha = 0.15f), RoundedCornerShape(10.dp))
                            .clickable(onClick = onAddPage),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "加拍一页", tint = Color.White)
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // 底部动作
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
            Button(
                onClick = onConfirm,
                enabled = !loading && draft.pages.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                Text(if (loading) "正在解题…" else "确认，开始解题")
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onSaveOffline, modifier = Modifier.weight(1f)) {
                    Text("先记下来（不联网）", color = Color.White.copy(alpha = 0.85f))
                }
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
                .size(64.dp)
                .background(
                    if (selected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.2f),
                    RoundedCornerShape(10.dp),
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
                Icon(Icons.Filled.Close, contentDescription = "删除这一页", tint = Color.White, modifier = Modifier.size(12.dp))
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
