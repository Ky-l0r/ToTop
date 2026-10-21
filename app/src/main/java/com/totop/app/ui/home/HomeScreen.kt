package com.totop.app.ui.home

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.totop.app.ui.theme.Tokens

/**
 * 默认首页：启动即进入。
 *
 * 信息架构（参考主流学习类 App 的浏览顺序，不复制其视觉）：
 * 1. 顶部学习状态 + 拍题主入口；
 * 2. 学习概览卡：最近错题 / 还需要多练的知识点 / 待复习；
 * 3. 快捷入口四宫格；
 * 4. 最近学习记录与推荐复习。
 *
 * 文案全部口语化：内部叫「掌握度」「薄弱知识点」，界面上一律说人话。
 */
@Composable
fun HomeScreen(
    onOpenCapture: () -> Unit,
    onOpenMistakes: () -> Unit,
    onOpenReviewMaterial: () -> Unit,
    onOpenOffline: () -> Unit,
    onOpenHub: () -> Unit,
    onOpenKnowledgePoint: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = modifier.fillMaxSize().background(Tokens.Background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(16.dp))

        Text(
            text = state.greeting,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = Tokens.OnSurface,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = state.subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = Tokens.OnSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))

        // 拍题主入口：整块可点，不做小按钮
        Card(
            onClick = onOpenCapture,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(Tokens.RadiusLarge),
            colors = CardDefaults.cardColors(containerColor = Tokens.PrimaryContainer),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier.size(44.dp)
                        .background(Tokens.Primary, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.CameraAlt, contentDescription = null, tint = Color.White)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "拍一道不会的题",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Tokens.OnPrimaryContainer,
                    )
                    Text(
                        text = "拍下来，我陪你一步步想明白",
                        style = MaterialTheme.typography.bodySmall,
                        color = Tokens.OnPrimaryContainer.copy(alpha = 0.75f),
                    )
                }
                Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = Tokens.Primary)
            }
        }

        Spacer(Modifier.height(20.dp))

        // 学习概览
        SectionTitle(title = "最近学得怎么样")
        Spacer(Modifier.height(8.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(Tokens.RadiusLarge),
            colors = CardDefaults.cardColors(containerColor = Tokens.SurfaceVariant),
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    StatCell(value = state.mistakeCount.toString(), label = "最近错题", modifier = Modifier.weight(1f))
                    StatCell(value = state.weakCount.toString(), label = "还要多练", modifier = Modifier.weight(1f))
                    StatCell(value = state.dueReviewCount.toString(), label = "待复习", modifier = Modifier.weight(1f))
                }
                if (state.overviewHint.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = state.overviewHint,
                        style = MaterialTheme.typography.bodySmall,
                        color = Tokens.OnSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        SectionTitle(title = "常用功能")
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QuickEntry("错题本", "看看做错过什么", onOpenMistakes, Modifier.weight(1f))
            QuickEntry("复习资料", "考试前打印一份", onOpenReviewMaterial, Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QuickEntry("离线记题", "没网先拍下来", onOpenOffline, Modifier.weight(1f))
            QuickEntry("全部功能", "知识点、学情、设置", onOpenHub, Modifier.weight(1f))
        }

        Spacer(Modifier.height(20.dp))

        if (state.weakKpoints.isNotEmpty()) {
            SectionTitle(title = "还需要多练的知识点")
            Spacer(Modifier.height(8.dp))
            state.weakKpoints.forEach { item ->
                WeakKpointRow(
                    name = item.name,
                    subject = item.subjectLabel,
                    count = item.mistakeCount,
                    onClick = { onOpenKnowledgePoint(item.id) },
                )
                Spacer(Modifier.height(8.dp))
            }
            Spacer(Modifier.height(12.dp))
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = Tokens.OnSurface,
    )
}

@Composable
private fun StatCell(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = Tokens.Primary,
        )
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = Tokens.OnSurfaceVariant)
    }
}

@Composable
private fun QuickEntry(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(Tokens.RadiusMedium),
        colors = CardDefaults.cardColors(containerColor = Tokens.Surface),
        elevation = CardDefaults.cardElevation(defaultElevation = Tokens.Elevation),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                color = Tokens.OnSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(text = subtitle, style = MaterialTheme.typography.labelSmall, color = Tokens.OnSurfaceVariant)
        }
    }
}

@Composable
private fun WeakKpointRow(
    name: String,
    subject: String,
    count: Int,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Tokens.SurfaceVariant, RoundedCornerShape(Tokens.RadiusMedium))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = name, style = MaterialTheme.typography.bodyLarge, color = Tokens.OnSurface)
            Text(
                text = subject + " · 错过 " + count + " 次",
                style = MaterialTheme.typography.labelSmall,
                color = Tokens.OnSurfaceVariant,
            )
        }
        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = Tokens.OutlineStrong)
    }
}
