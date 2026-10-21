package com.totop.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.totop.app.ui.theme.Tokens

/**
 * 用户消息气泡（右侧、主色浅底）。
 *
 * 多页题目在气泡内显示「共 N 页」角标，点击图片可全屏预览。
 */
@Composable
fun UserBubble(
    text: String,
    pageCount: Int,
    onOpenImages: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        Column(
            modifier =
                Modifier
                    .widthIn(max = 300.dp)
                    .clip(RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp))
                    .background(Tokens.PrimaryContainer)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            if (pageCount > 0) {
                // 图片入口：明确标注页数，让用户知道模型一共看到了几页
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White.copy(alpha = 0.7f))
                        .clickable(onClick = onOpenImages)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (pageCount > 1) "共 " + pageCount + " 页题目" else "已拍题 1 页",
                        style = MaterialTheme.typography.labelMedium,
                        color = Tokens.Primary,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.size(6.dp))
                    Text("查看", style = MaterialTheme.typography.labelSmall, color = Tokens.Primary)
                }
                if (text.isNotBlank()) Spacer(Modifier.height(8.dp))
            }
            if (text.isNotBlank()) {
                Text(text = text, style = MaterialTheme.typography.bodyMedium, color = Tokens.OnSurface)
            }
        }
    }
}

/**
 * 助手消息气泡（左侧、白底描边）。
 *
 * @param streaming true 表示还在流式输出，末尾显示光标，让用户知道没卡住
 * @param failed 非空表示这一轮失败，展示可读原因与重试入口
 */
@Composable
fun AssistantBubble(
    content: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    streaming: Boolean = false,
    failed: String? = null,
    onRetry: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Start,
    ) {
        Column(
            modifier =
                Modifier
                    .widthIn(max = 340.dp)
                    .clip(RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp))
                    .background(Tokens.Surface)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            content()
            if (streaming) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "正在作答…",
                    style = MaterialTheme.typography.labelSmall,
                    color = Tokens.OnSurfaceVariant,
                )
            }
            if (failed != null) {
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Tokens.ErrorContainer)
                        .padding(10.dp),
                ) {
                    Text(
                        text = failed,
                        style = MaterialTheme.typography.bodySmall,
                        color = Tokens.Error,
                    )
                }
                if (onRetry != null) {
                    TextButton(onClick = onRetry, modifier = Modifier.padding(top = 2.dp)) {
                        Text("重试这一次", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}
