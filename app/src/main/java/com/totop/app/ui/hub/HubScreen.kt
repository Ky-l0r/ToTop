package com.totop.app.ui.hub

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.totop.app.ui.theme.Tokens

/** 功能中心的一个入口。 */
data class HubEntry(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
)

/**
 * 功能中心：宫格式入口。
 *
 * 上一版是竖排列表，一屏只能放下五项，学生要滑两屏才能看到设置。
 * 现在改成三列宫格：图标 + 名称 + 一行说明，分组标题保留。
 *
 * 为什么不用 LazyVerticalGrid：整页要跟着一起滚（页面本身只有一个宫格），
 * 在 verticalScroll 里套一个可滚动的 LazyVerticalGrid 会直接崩（无限高度约束）。
 * 用 chunked(3) 手动分行，布局简单、项数固定（十几个），也不会有懒加载的必要。
 */
@Composable
fun HubScreen(
    onOpenMistakes: () -> Unit,
    onOpenKnowledge: () -> Unit,
    onOpenReport: () -> Unit,
    onOpenReviewMaterial: () -> Unit,
    onOpenReview: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenCompanion: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val groups =
        listOf(
            "学习" to
                listOf(
                    HubEntry("错题本", "做过错的题", Icons.Filled.Warning, onOpenMistakes),
                    HubEntry("多练的知识点", "反复出错的地方", Icons.Filled.Star, onOpenKnowledge),
                    HubEntry("复习打卡", "今天该复习的", Icons.Filled.Refresh, onOpenReview),
                    HubEntry("陪伴", "写作业时随手问", Icons.Filled.Timer, onOpenCompanion),
                ),
            "考前冲刺" to
                listOf(
                    HubEntry("复习资料", "按错题生成，可打印", Icons.Filled.List, onOpenReviewMaterial),
                ),
            "了解自己" to
                listOf(
                    HubEntry("学情报告", "最近学得怎么样", Icons.Filled.DateRange, onOpenReport),
                ),
            "其他" to
                listOf(
                    HubEntry("设置", "模型、语音、存储", Icons.Filled.Settings, onOpenSettings),
                ),
        )

    Column(
        modifier =
            modifier.fillMaxSize().background(Tokens.Background)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(16.dp))
        Text(
            text = "全部功能",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = Tokens.OnSurface,
        )
        Text(
            text = "按你想做的事分组。",
            style = MaterialTheme.typography.bodyMedium,
            color = Tokens.OnSurfaceVariant,
        )

        groups.forEach { (groupTitle, entries) ->
            Spacer(Modifier.height(18.dp))
            Text(
                text = groupTitle,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = Tokens.OnSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            entries.chunked(GRID_COLUMNS).forEach { rowEntries ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    rowEntries.forEach { entry ->
                        HubCell(entry = entry, modifier = Modifier.weight(1f))
                    }
                    // 最后一行不满三格时补空位，否则两张卡会被拉成一张宽、一张窄
                    repeat(GRID_COLUMNS - rowEntries.size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

/** 一格：图标 + 名称 + 一行说明。 */
@Composable
private fun HubCell(
    entry: HubEntry,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = entry.onClick,
        modifier = modifier,
        shape = RoundedCornerShape(Tokens.RadiusMedium),
        colors = CardDefaults.cardColors(containerColor = Tokens.SurfaceVariant),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(38.dp)
                        .background(Tokens.PrimaryContainer, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = entry.icon,
                    contentDescription = null,
                    tint = Tokens.Primary,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = entry.title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                color = Tokens.OnSurface,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = entry.subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = Tokens.OnSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 宫格列数：三列在 360dp 窄屏上每格约 104dp，图标 + 两行字刚好放下。 */
private const val GRID_COLUMNS: Int = 3
