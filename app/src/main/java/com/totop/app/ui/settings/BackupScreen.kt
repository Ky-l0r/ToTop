package com.totop.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.totop.app.R
import com.totop.app.util.BackupLayout

/**
 * 本地备份与恢复（FR-10.4）。
 *
 * 本页由 ml 成员负责备份/恢复的读写实现（Zip + SAF）；本任务只交付
 * 「设置页 -> 备份入口」的导航与页面说明，保证入口存在且说明与产品口径一致：
 * - 备份文件包含数据库与图片，用于换机迁移；
 * - 备份**不含** API Key 与提示词缓存（缓存可再生，Key 属于本机凭据）；
 * - 恢复前会校验 manifest 的格式版本与数据库版本，不兼容时明确拒绝而不是冒险覆盖。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.nav_backup)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.nav_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            InfoCard(
                title = "备份会包含什么",
                body =
                    "错题本、题目原图与缩略图、知识点掌握度、复习记录、周报、设置项。" +
                        "文件为 ZIP，格式版本 " + BackupLayout.FORMAT_VERSION + "，文件名形如 " +
                        BackupLayout.FILE_PREFIX + "20250101-0930" + BackupLayout.SUFFIX + "。",
            )
            InfoCard(
                title = "什么不会被备份",
                body =
                    "API Key 与任何凭据都不会进入备份包；换机后请在设置页重新填写自己的 Key。" +
                        "提示词缓存与 AI 调用日志也不备份，它们可以再生。",
            )
            InfoCard(
                title = "恢复前会做什么检查",
                body =
                    "读取 manifest.json，校验格式版本与数据库版本；" +
                        "如果备份来自更新版本的应用，会明确拒绝并说明原因，不会覆盖你现有的数据。",
            )
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Text(
                    text =
                        "备份与恢复的读写实现由 M7 里程碑交付；当前页面用于确认入口与产品口径，" +
                            "读写逻辑不会在本页做任何降级或静默处理。",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 说明卡。 */
@Composable
private fun InfoCard(
    title: String,
    body: String,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(text = title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(text = body, style = MaterialTheme.typography.bodyMedium)
    }
}
