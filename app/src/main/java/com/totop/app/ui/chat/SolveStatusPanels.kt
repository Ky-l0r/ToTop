package com.totop.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.totop.app.domain.model.AiStage
import com.totop.app.ui.components.SolveCard
import com.totop.app.ui.components.SolveColors

/** 顶部网络状态条（FR-1.9）。离线时明确提示「检查网络」而不是空转。 */
@Composable
fun NetworkStatusBar(
    status: NetworkStatus,
    modifier: Modifier = Modifier,
) {
    val (icon, text, tone) =
        when (status) {
            NetworkStatus.ONLINE -> Triple(Icons.Filled.Wifi, "网络正常", SolveColors.Correct)
            NetworkStatus.OFFLINE -> Triple(Icons.Filled.CloudOff, "当前无网络，发送会失败，请先连接网络", SolveColors.Wrong)
            NetworkStatus.UNKNOWN -> Triple(Icons.Filled.Wifi, "网络状态未知", SolveColors.Muted)
        }
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .background(tone.copy(alpha = 0.08f))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tone,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = " " + text,
            style = MaterialTheme.typography.bodySmall,
            color = tone,
        )
    }
}

/**
 * 四阶段进度（SSE 流式期间的可视反馈）。
 *
 * 不使用大面积动画：只有一条线性进度条 + 每阶段的状态文本，
 * 低端机不产生额外合成开销。
 */
@Composable
fun StageProgressPanel(
    stages: List<StageProgress>,
    modifier: Modifier = Modifier,
) {
    if (stages.isEmpty()) {
        SkeletonBlock(modifier = modifier, rows = 3)
        return
    }
    val doneCount = stages.count { it.status == StageStatus.DONE }
    val fraction = doneCount.toFloat() / stages.size
    SolveCard(title = "解题进行中", modifier = modifier, accent = MaterialTheme.colorScheme.primary) {
        LinearProgressIndicator(
            progress = { fraction },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(4.dp),
        )
        stages.forEach { stage -> StageRow(stage = stage) }
    }
}

/** 单阶段行。 */
@Composable
private fun StageRow(stage: StageProgress) {
    val tone =
        when (stage.status) {
            StageStatus.DONE -> SolveColors.Correct
            StageStatus.RUNNING -> MaterialTheme.colorScheme.primary
            StageStatus.PENDING -> SolveColors.Muted
        }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier =
                Modifier
                    .size(18.dp),
            contentAlignment = Alignment.Center,
        ) {
            when (stage.status) {
                StageStatus.RUNNING ->
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = tone,
                    )
                StageStatus.DONE ->
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = tone,
                        modifier = Modifier.size(16.dp),
                    )
                StageStatus.PENDING ->
                    Box(
                        modifier =
                            Modifier
                                .size(8.dp)
                                .background(SolveColors.Muted.copy(alpha = 0.4f), CircleShape),
                    )
            }
        }
        Text(
            text = " 阶段 " + stage.letter + " · " + stage.label,
            style = MaterialTheme.typography.bodyMedium,
            color = tone,
        )
        val suffix = buildStageSuffix(stage)
        if (suffix.isNotEmpty()) {
            Text(
                text = "  " + suffix,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 阶段行尾注。
 *
 * 刻意**不再显示「命中缓存」「首字 xx ms」**：
 * - 「命中缓存 0 token」是内部实现细节，用户看到只会疑惑「为什么没联网」；
 * - 首字耗时是性能自检指标，属于开发者关心的事，放在界面上只会分散注意力。
 * 现在只保留对用户有意义的阶段结论（例如「解得 3 步」）。
 */
private fun buildStageSuffix(stage: StageProgress): String {
    if (stage.status == StageStatus.PENDING) return ""
    return stage.summary.takeIf { stage.status == StageStatus.DONE && it.isNotBlank() }.orEmpty()
}

/** 流式增量文本区（首字到达后即可阅读，避免等待整段 JSON）。 */
@Composable
fun StreamingTextPanel(
    streamText: String,
    modifier: Modifier = Modifier,
    onStop: () -> Unit,
) {
    if (streamText.isBlank()) return
    SolveCard(
        title = "正在生成",
        modifier = modifier,
        accent = MaterialTheme.colorScheme.primary,
        trailing = { StopButton(onStop = onStop) },
    ) {
        Text(
            text = streamText.takeLast(STREAM_PREVIEW_CHARS),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 只展示末尾若干字符：流式 JSON 前半段对用户没有信息量。 */
private const val STREAM_PREVIEW_CHARS = 600

/** 停止按钮（中断 socket）。 */
@Composable
private fun StopButton(onStop: () -> Unit) {
    Row(
        modifier =
            Modifier
                .background(SolveColors.Wrong.copy(alpha = 0.10f), RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.TextButton(onClick = onStop) {
            Icon(
                imageVector = Icons.Filled.Stop,
                contentDescription = null,
                tint = SolveColors.Wrong,
                modifier = Modifier.size(16.dp),
            )
            Text(text = " 停止", color = SolveColors.Wrong, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * 骨架屏（loading 占位）。
 *
 * 静态灰色块，不做 shimmer 动画（方案 7.1 禁止大面积动画）。
 */
@Composable
fun SkeletonBlock(
    modifier: Modifier = Modifier,
    rows: Int = 3,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(rows) { index ->
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth(if (index == rows - 1) 0.6f else 1f)
                        .height(14.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            RoundedCornerShape(4.dp),
                        ),
            )
        }
    }
}

/** 生成中的整页骨架。 */
@Composable
fun SolvingSkeleton(modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SolveCard(title = "题目", accent = SolveColors.Muted) {
            SkeletonBlock(rows = 3)
        }
        SolveCard(title = "标准解答", accent = SolveColors.Muted) {
            SkeletonBlock(rows = 4)
        }
    }
}

/** 空态（尚未导入题目）。 */
@Composable
fun SolveEmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "把题目拍下来，或者直接把题干贴进来",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = "有自己写的解题过程一起贴过来最好：AI 会逐步对照，指出第一个出错的步骤与错因。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "所有数据只留在本机，不经过任何开发者服务器。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 错误态卡片（可重试 / 需改设置两种）。 */
@Composable
fun SolveErrorCard(
    message: String,
    stage: AiStage,
    retryable: Boolean,
    technicalDetail: String?,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    onAskAgain: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SolveCard(
        title = "本次没能完成（阶段 " + stage.wire + " · " + stage.label + "）",
        modifier = modifier,
        accent = SolveColors.Wrong,
    ) {
        Text(text = message, style = MaterialTheme.typography.bodyMedium, color = SolveColors.Wrong)
        if (!technicalDetail.isNullOrBlank()) {
            Text(
                text = "技术细节：" + technicalDetail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (retryable) {
                androidx.compose.material3.Button(onClick = onRetry) { Text(text = "重试") }
            } else {
                androidx.compose.material3.Button(onClick = onOpenSettings) { Text(text = "去设置") }
            }
            androidx.compose.material3.OutlinedButton(onClick = onAskAgain) { Text(text = "重新提问") }
        }
    }
}

/** 已取消的提示条。 */
@Composable
fun CancelledBanner(
    message: String,
    hasPartial: Boolean,
    onRetry: () -> Unit,
    onAskAgain: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SolveCard(title = "已停止", modifier = modifier, accent = SolveColors.Muted) {
        Text(
            text = message + if (hasPartial) "（已收到的内容保留在上方）" else "",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            androidx.compose.material3.Button(onClick = onRetry) { Text(text = "继续生成") }
            androidx.compose.material3.OutlinedButton(onClick = onAskAgain) { Text(text = "重新提问") }
        }
    }
}

/** 未配置模型的引导条（FR-6.5 本地模式）。 */
@Composable
fun NotConfiguredBanner(
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SolveCard(title = "尚未配置模型", modifier = modifier, accent = SolveColors.Hint) {
        Text(
            text =
                "To Top 不内置任何 API Key。请到设置页填写你自己的 Base URL、API Key 与模型名，" +
                    "填写后即可开始解题。",
            style = MaterialTheme.typography.bodyMedium,
        )
        androidx.compose.material3.Button(onClick = onOpenSettings) { Text(text = "去设置") }
    }
}
