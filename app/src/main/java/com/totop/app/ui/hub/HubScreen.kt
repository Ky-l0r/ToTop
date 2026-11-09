package com.totop.app.ui.hub

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.totop.app.ui.theme.Tokens

/** 功能中心的一个入口。 */
data class HubEntry(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
)

/**
 * 功能中心：三列卡片网格，一屏看完。
 *
 * 版本沿革：
 * - 最初是三列「卡片 + 分组标题 + 大间距」，八个入口占三屏，设置沉底；
 * - 曾改成四列无底色图标，一屏放下了，但学生反馈「太淡、格子小、挤在最上方」；
 * - 现在回到三列卡片（浅色底 + 图标容器 + 一行说明），去掉分组标题与多余留白，
 *   七个入口三行排满，设置仍在一屏内。
 *
 * 为什么不用 LazyVerticalGrid：整页要跟着一起滚（页面本身只有一个宫格），
 * 在 verticalScroll 里套可滚动的 LazyVerticalGrid 会直接崩（无限高度约束）。
 * 用 chunked(3) 手动分行，项数固定，也没有懒加载的必要。
 */
@Composable
fun HubScreen(
    onOpenMistakes: () -> Unit,
    onOpenKnowledge: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenReport: () -> Unit,
    onOpenReviewMaterial: () -> Unit,
    onOpenReview: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 顺序即展示顺序：学习类在前，资料类居中，自我认知与设置收尾。
    // 「陪伴」不在这里：底栏第二项已是它，再放一格只会重复。
    val entries =
        listOf(
            HubEntry("易错题", "按错因与知识点归档", Icons.Filled.Warning, onOpenMistakes),
            HubEntry("多练的知识点", "薄弱点排行与变式训练", Icons.Filled.Star, onOpenKnowledge),
            HubEntry("复习打卡", "今天该复习的都在这", Icons.Filled.Refresh, onOpenReview),
            HubEntry("复习资料", "AI 生成的考前资料", Icons.AutoMirrored.Filled.List, onOpenReviewMaterial),
            HubEntry("资料库", "自己攒的好解析", Icons.Filled.Book, onOpenLibrary),
            HubEntry("学情", "成绩趋势与学习报告", Icons.Filled.DateRange, onOpenReport),
            HubEntry("设置", "模型、偏好与数据", Icons.Filled.Tune, onOpenSettings),
        )

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(Tokens.Background)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(18.dp))
        Text(
            text = "全部功能",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = Tokens.OnSurface,
        )
        Text(
            text = "点一下就到。",
            style = MaterialTheme.typography.bodySmall,
            color = Tokens.OnSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        entries.chunked(GRID_COLUMNS).forEach { rowEntries ->
            Row(
                // IntrinsicSize.Min 让同一行的卡片等高：说明文字有长有短，
                // 不锁等高的话「设置」会比同行矮一截，看起来像没画完。
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min)
                        .padding(bottom = RowGap),
                horizontalArrangement = Arrangement.spacedBy(ColumnGap),
            ) {
                rowEntries.forEach { entry ->
                    HubCell(entry = entry, modifier = Modifier.weight(1f).fillMaxHeight())
                }
                // 最后一行不满三格时补空位，否则会被拉伸成不等宽
                repeat(GRID_COLUMNS - rowEntries.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
        Spacer(Modifier.height(18.dp))
    }
}

/** 一格：浅色卡片底 + 图标容器 + 名称 + 一行说明。 */
@Composable
private fun HubCell(
    entry: HubEntry,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .clip(RoundedCornerShape(Tokens.RadiusMedium))
                .background(Tokens.SurfaceVariant)
                .clickable(onClick = entry.onClick)
                .padding(horizontal = 6.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // 图标容器：让每格有一个视觉重心，纯图标会显得「平」
        Box(
            modifier =
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(Tokens.RadiusSmall))
                    .background(Tokens.PrimaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = entry.icon,
                contentDescription = null,
                tint = Tokens.Primary,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = entry.title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
            color = Tokens.OnSurface,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = entry.subtitle,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 13.sp),
            color = Tokens.OnSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 宫格列数：三列在 360dp 窄屏上每格约 100dp，图标容器 + 名称 + 说明刚好。 */
private const val GRID_COLUMNS: Int = 3

/** 列间距。 */
private val ColumnGap = 10.dp

/** 行间距。 */
private val RowGap = 10.dp
