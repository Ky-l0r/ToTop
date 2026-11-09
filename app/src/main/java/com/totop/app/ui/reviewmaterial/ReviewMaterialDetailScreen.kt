package com.totop.app.ui.reviewmaterial

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.totop.app.ui.theme.Tokens

/**
 * 复习资料详情页：一份历史资料的正文 + PDF 导出。
 *
 * 从单页拆出来的动机：正文一次七八千字，塞在列表页底部，
 * 学生每次进来都要滑很久才能翻到历史；拆开后列表保持轻，
 * 点进来的这一页才是「读资料」的地方。
 *
 * @param createdAtUtc 存档时间戳（文件主键）；null/非正数表示链接参数不完整，
 *   页面只显示可返回的错误态，不崩溃、也不触发生成
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewMaterialDetailScreen(
    createdAtUtc: Long?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ReviewMaterialDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val pendingIntent by viewModel.pendingIntent.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // 路由参数是字符串，这里只负责转交：加载状态由 ViewModel 统一管理，
    // 旋转重建后 load() 对同一 ID 不重复读盘。
    LaunchedEffect(createdAtUtc) {
        viewModel.load(createdAtUtc ?: 0L)
    }

    // 打开 / 分享：ViewModel 只产出 Intent，真正的 startActivity 必须在有 Context 的这一层做。
    LaunchedEffect(pendingIntent) {
        val intent = pendingIntent ?: return@LaunchedEffect
        runCatching { context.startActivity(intent) }
        viewModel.consumePendingIntent()
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(state.material?.subjectLabel ?: "复习资料") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(Tokens.Background)
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(12.dp))

            val material = state.material
            when {
                state.loading -> {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                }

                state.error != null -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Tokens.ErrorContainer),
                        shape = RoundedCornerShape(Tokens.RadiusMedium),
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text(
                                text = state.error.orEmpty(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = Tokens.Error,
                            )
                            Spacer(Modifier.height(6.dp))
                            OutlinedButton(onClick = onBack) { Text("回到列表") }
                        }
                    }
                }

                material != null -> {
                    MaterialContent(
                        material = material,
                        exporting = state.exporting,
                        exported = state.exportedFile,
                        onExportPdf = viewModel::exportPdf,
                        onOpen = viewModel::openExported,
                        onShare = viewModel::shareExported,
                    )
                }
            }

            state.message?.let { message ->
                Spacer(Modifier.height(12.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = Tokens.OnSurfaceVariant,
                )
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}
