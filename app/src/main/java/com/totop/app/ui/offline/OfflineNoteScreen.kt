package com.totop.app.ui.offline

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.totop.app.ui.theme.Tokens

/**
 * 离线记题列表。
 *
 * 与在线解题的关键区别：**完全不调用模型**。
 * 拍照后只把原图、框选区域与页序保存在本机，标记为「待联网再解」，
 * 因此没网、没 Key、或不想花钱时都能用。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineNoteScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OfflineNoteViewModel = hiltViewModel(),
) {
    val state: OfflineNoteUiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("离线记题") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Tokens.Background)
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = "先记下来，联网再解",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = Tokens.OnSurface,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "这些题只存在本机，不会上传，也不会产生任何费用。等你有网、配好模型后再一键解题。",
                style = MaterialTheme.typography.bodyMedium,
                color = Tokens.OnSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))

            state.message?.let { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = Tokens.Error,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            if (state.items.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Tokens.SurfaceVariant, RoundedCornerShape(Tokens.RadiusMedium))
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("还没有离线记录", style = MaterialTheme.typography.bodyLarge, color = Tokens.OnSurface)
                    Text(
                        text = "在拍题页选「先记下来（不联网）」，就会出现在这里。",
                        style = MaterialTheme.typography.bodySmall,
                        color = Tokens.OnSurfaceVariant,
                    )
                }
            } else {
                // 用 Column 承载，并用 for 循环（而非 forEach）：
                // @Composable 调用只能出现在 @Composable 上下文中，
                // forEach 的 lambda 在 Kotlin 层面不携带该上下文。
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (item in state.items) {
                        OfflineNoteRow(
                            item = item,
                            onDelete = { viewModel.delete(item.id) },
                        )
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 单条离线记题。拆成独立 Composable，避免在 forEach 里嵌太多布局导致可读性下降。 */
@Composable
private fun OfflineNoteRow(
    item: com.totop.app.data.repo.OfflineNote,
    onDelete: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(Tokens.SurfaceVariant, RoundedCornerShape(Tokens.RadiusMedium))
                .padding(14.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyLarge,
                color = Tokens.OnSurface,
            )
            Text(
                text = item.subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = Tokens.OnSurfaceVariant,
            )
            if (item.studentProcess.isNotBlank()) {
                Text(
                    text = "你的思路：" + item.studentProcess.take(40),
                    style = MaterialTheme.typography.bodySmall,
                    color = Tokens.OnSurfaceVariant,
                )
            }
        }
        TextButton(onClick = onDelete) {
            Text(text = "删除", color = Tokens.Error, style = MaterialTheme.typography.labelMedium)
        }
    }
}
