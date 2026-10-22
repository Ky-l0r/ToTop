package com.totop.app.ui.solve

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.totop.app.ui.theme.Tokens

/**
 * 输入框上方的图片条（对话式拍题的核心视觉）。
 *
 * 交互对齐主流对话式 AI 应用的习惯，只借鉴布局逻辑，不使用任何第三方素材：
 * - 图片以缩略图横向排列，右侧固定一个「+」用于继续加页；
 * - 每张缩略图右上角有删除按钮，点缩略图可重新框选；
 * - 缩略图左上角标注页码，多页时用户能看出上传顺序。
 *
 * 为什么图片要放在输入框上方而不是正文里：
 * 用户在输入框里写解题思路时，需要同时看到自己上传了哪几页。
 * 若图片滚在对话历史里，一写长文本就看不见了，容易重复拍同一页。
 *
 * @param pages 每页的缩略图（可能尚未解码完成，为 null 时显示占位）
 * @param onAddPage 继续添加一页
 * @param onRemovePage 删除某页
 * @param onOpenPage 点开某页重新框选
 */
@Composable
fun ComposerAttachments(
    pages: List<ComposerPage>,
    onAddPage: () -> Unit,
    onRemovePage: (Int) -> Unit,
    onOpenPage: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (pages.isEmpty()) return
    LazyRow(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(pages) { index, page ->
            AttachmentThumb(
                page = page,
                pageNumber = index + 1,
                total = pages.size,
                onOpen = { onOpenPage(index) },
                onRemove = { onRemovePage(index) },
            )
        }
        item {
            AddPageButton(onClick = onAddPage)
        }
    }
}

/** 一个待上传页面的展示数据。 */
data class ComposerPage(
    /** 缩略图位图；解码未完成时为 null。 */
    val thumbnail: android.graphics.Bitmap?,
    /** 是否已经框选过（用于显示小角标）。 */
    val cropped: Boolean,
)

@Composable
private fun AttachmentThumb(
    page: ComposerPage,
    pageNumber: Int,
    total: Int,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
) {
    Box(modifier = Modifier.size(64.dp)) {
        Box(
            modifier =
                Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(Tokens.RadiusMedium))
                    .background(Tokens.SurfaceVariant)
                    .clickable(onClick = onOpen),
            contentAlignment = Alignment.Center,
        ) {
            val bitmap = page.thumbnail
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "第 " + pageNumber + " 页",
                    modifier = Modifier.size(64.dp),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Text(
                    text = "载入中",
                    style = MaterialTheme.typography.labelSmall,
                    color = Tokens.OnSurfaceVariant,
                )
            }
        }

        // 页码角标：多页时才有意义，单页不显示以免干扰
        if (total > 1) {
            Box(
                modifier =
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(3.dp)
                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
            ) {
                Text(
                    text = "第" + pageNumber + "页",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                )
            }
        }

        // 删除按钮：始终可见，避免用户找不到如何撤销误拍
        Box(
            modifier =
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(2.dp)
                    .size(18.dp)
                    .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                    .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "删除第 " + pageNumber + " 页",
                tint = Color.White,
                modifier = Modifier.size(12.dp),
            )
        }
    }
}

@Composable
private fun AddPageButton(onClick: () -> Unit) {
    Box(
        modifier =
            Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(Tokens.RadiusMedium))
                .background(Tokens.SurfaceVariant)
                .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = "继续加一页",
                tint = Tokens.Primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "加一页",
                style = MaterialTheme.typography.labelSmall,
                color = Tokens.Primary,
            )
        }
    }
}
