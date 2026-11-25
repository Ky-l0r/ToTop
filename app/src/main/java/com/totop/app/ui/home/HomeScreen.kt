package com.totop.app.ui.home

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.EventNote
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.ReportProblem
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.totop.app.ui.components.CardTone
import com.totop.app.ui.components.SurfaceCard
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
    onOpenMistakes: () -> Unit,
    onOpenReviewMaterial: () -> Unit,
    onOpenHub: () -> Unit,
    onOpenKnowledgePoint: (String) -> Unit = {},
    onOpenCompanion: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier =
            modifier.fillMaxSize().background(Tokens.Background)
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

        // 三块，顺序即优先级：先看哪块最弱，再看最近学得怎么样，最后才是去别处。
        // 0.9 删掉了顶部的标语与「拍一道不会的题」大卡：拍题本来就在底栏正中，
        // 首页再放一张同义的大卡只是把下面的内容挤下去。

        Spacer(Modifier.height(18.dp))
        SectionTitle(title = "最近不熟的知识点")
        Spacer(Modifier.height(8.dp))
        val forgotten = state.forgottenKpoints
        val weak = state.weakKpoints
        if (forgotten.isEmpty() && weak.isEmpty()) {
            EmptyBlock(text = "还没有记录。拍一道不会的题，或在错题本里标一道，这里就会把最不熟的知识点排出来。")
        } else {
            forgotten.forEach { item ->
                WeakKpointRow(
                    name = item.name,
                    subject = "写作业时问过 " + item.askCount + " 次",
                    count = item.askCount,
                    onClick = { onOpenKnowledgePoint(item.kpointId) },
                )
                Spacer(Modifier.height(8.dp))
            }
            weak.forEach { item ->
                WeakKpointRow(
                    name = item.name,
                    subject = item.subjectLabel,
                    count = item.mistakeCount,
                    onClick = { onOpenKnowledgePoint(item.id) },
                )
                Spacer(Modifier.height(8.dp))
            }
        }

        Spacer(Modifier.height(18.dp))
        SectionTitle(title = "近期学情")
        Spacer(Modifier.height(8.dp))
        // 指标块永远有四个（数值为 0 也照常显示）：
        // 空态交给下面那句提示，而不是把整块藏起来——藏起来更像「坏了」。
        state.metrics.chunked(2).forEach { rowMetrics ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                rowMetrics.forEach { metric ->
                    MetricCard(metric = metric, modifier = Modifier.weight(1f))
                }
                repeat(2 - rowMetrics.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        if (state.overviewHint.isNotBlank()) {
            Text(
                text = state.overviewHint,
                style = MaterialTheme.typography.bodySmall,
                color = Tokens.OnSurfaceVariant,
                modifier = Modifier.padding(horizontal = 2.dp),
            )
        }

        Spacer(Modifier.height(18.dp))
        SectionTitle(title = "常用功能")
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QuickEntry(
                title = "陪伴作业",
                subtitle = "写作业时用：每题先自己想五分钟",
                icon = Icons.Filled.Timer,
                onClick = onOpenCompanion,
                modifier = Modifier.weight(1f),
            )
            QuickEntry(
                title = "错题本",
                subtitle = "不会、做错、存疑的都在这",
                icon = Icons.AutoMirrored.Filled.MenuBook,
                onClick = onOpenMistakes,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QuickEntry(
                title = "复习资料",
                subtitle = "考试前打印一份",
                icon = Icons.Filled.PictureAsPdf,
                onClick = onOpenReviewMaterial,
                modifier = Modifier.weight(1f),
            )
            QuickEntry(
                title = "全部功能",
                subtitle = "知识点、学情、设置",
                icon = Icons.Filled.Apps,
                onClick = onOpenHub,
                modifier = Modifier.weight(1f),
            )
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

/**
 * 单个概览指标卡：图标圆标 + 数字 + 标签 + 一句说明（可选进度条）。
 *
 * 图标按指标语义区分（复习用闹钟、错题用本子、多练用警示、已掌握用对勾），
 * 让四张卡在扫视时先靠形状分开，再看数字。
 */
@Composable
private fun MetricCard(
    metric: HomeMetric,
    modifier: Modifier = Modifier,
) {
    SurfaceCard(modifier = modifier, tone = CardTone.Tint) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier =
                    Modifier.size(Tokens.IconBoxSmall)
                        .background(Tokens.Surface, RoundedCornerShape(Tokens.RadiusIcon)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = metricIconOf(metric.key),
                    contentDescription = null,
                    tint = Tokens.Primary,
                    modifier = Modifier.size(17.dp),
                )
            }
            Spacer(Modifier.width(Tokens.SpaceSm))
            Text(
                text = metric.label,
                style = MaterialTheme.typography.labelMedium,
                color = Tokens.OnSurfaceVariant,
            )
        }
        Spacer(Modifier.height(Tokens.SpaceSm))
        Text(
            text = metric.value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = Tokens.Primary,
        )
        if (metric.caption.isNotBlank()) {
            Text(
                text = metric.caption,
                style = MaterialTheme.typography.labelSmall,
                color = Tokens.OnSurfaceVariant,
            )
        }
        // 进度条只在有明确完成度时出现（目前只有「今天待复习」）
        metric.progress?.let { value ->
            Spacer(Modifier.height(Tokens.SpaceSm))
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(Tokens.Outline, RoundedCornerShape(2.dp)),
            ) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth(value.coerceIn(0f, 1f))
                            .height(4.dp)
                            .background(
                                if (value >= 1f) Tokens.Success else Tokens.Primary,
                                RoundedCornerShape(2.dp),
                            ),
                )
            }
        }
    }
}

/** 指标 key -> 图标。未定义的 key 退回通用图标。 */
private fun metricIconOf(key: String): androidx.compose.ui.graphics.vector.ImageVector =
    when (key) {
        "due" -> Icons.Filled.NotificationsActive
        "week" -> Icons.AutoMirrored.Filled.EventNote
        "weak" -> Icons.Filled.ReportProblem
        "mastered" -> Icons.Filled.CheckCircle
        else -> Icons.Filled.Book
    }

/**
 * 快捷入口卡：图标 + 标题 + 说明。
 *
 * 每个入口都配图标而不是只有文字：首页原来的四张卡全是纯文字块，
 * 眼睛扫过去分不清哪张是哪张，加了图标之后可以按形状找。
 */
@Composable
private fun QuickEntry(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SurfaceCard(modifier = modifier, onClick = onClick) {
        Box(
            modifier =
                Modifier.size(Tokens.IconBoxSmall)
                    .background(Tokens.PrimaryContainer, RoundedCornerShape(Tokens.RadiusIcon)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Tokens.Primary,
                modifier = Modifier.size(16.dp),
            )
        }
        Spacer(Modifier.height(Tokens.SpaceSm))
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

@Composable
private fun WeakKpointRow(
    name: String,
    subject: String,
    count: Int,
    onClick: () -> Unit,
) {
    SurfaceCard(modifier = Modifier.fillMaxWidth(), tone = CardTone.Tint, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
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
}

/** 一块内容的空态：一句话说清「做什么才会出现」。 */
@Composable
private fun EmptyBlock(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = Tokens.OnSurfaceVariant,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}
