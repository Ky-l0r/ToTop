package com.totop.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.totop.app.R
import com.totop.app.domain.model.TeachingMode
import com.totop.app.ml.TtsSpeed

/**
 * 设置页（FR-6 / 方案 6.1）。
 *
 * 分区（自上而下）：
 * 1. BYOK：Base URL + API Key + 模型名，预置厂商模板，校验前不落盘；
 * 2. 连通性校验：max_tokens=8 的最小请求，结果按类别给出下一步；清除 Key 入口；
 * 3. 教学偏好：默认引导模式、TTS 开关与语速（含试听）、AI 周报解读、是否生成练习题；
 * 4. 存储：缓存占用与清理、备份与恢复入口、一键清除全部数据（二次确认）。
 *
 * 安全约定：Key 输入默认掩码显示，任何提示与日志都不回显明文。
 *
 * @param onOpenBackup 进入本地备份与恢复
 * @param onOpenExport 进入导出（Markdown / CSV / Anki）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onOpenBackup: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenExport: () -> Unit = {},
    onBack: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { viewModel.bootstrap() }

    LaunchedEffect(uiState.message) {
        val message = uiState.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.consumeMessage()
    }

    if (uiState.confirmClearAll) {
        AlertDialog(
            onDismissRequest = viewModel::dismissClearAll,
            title = { Text(text = DangerCopy.CLEAR_ALL_TITLE) },
            text = { Text(text = DangerCopy.CLEAR_ALL_BODY) },
            confirmButton = {
                TextButton(onClick = viewModel::clearAllData) {
                    Text(text = "确认清除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissClearAll) { Text(text = "取消") }
            },
        )
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.nav_settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!uiState.storageAvailable) {
                WarningBox(text = "本机密钥存储不可用（可能是系统 Keystore 异常）。请重新填写 Key；" +
                    "在修复前应用会以本地模式运行。")
            }
            if (uiState.localMode) {
                WarningBox(text = "当前未配置 API Key，应用处于本地模式：解题与 AI 解读不可用，" +
                    "错题本、复习、掌握度与周报仍可正常使用。")
            }

            ByokSection(uiState = uiState, viewModel = viewModel)
            ConnectionSection(uiState = uiState, viewModel = viewModel)
            PreferenceSection(uiState = uiState, viewModel = viewModel)
            StorageSection(
                uiState = uiState,
                onClearCache = viewModel::clearCache,
                onRefreshCache = viewModel::refreshCacheStats,
                onOpenBackup = onOpenBackup,
                onOpenExport = onOpenExport,
                onRequestClearAll = viewModel::requestClearAll,
            )
            DisclaimerBox()
        }
    }
}

/** BYOK 表单。 */
@Composable
private fun ByokSection(uiState: SettingsUiState, viewModel: SettingsViewModel) {
    var keyVisible by remember { mutableStateOf(false) }
    var templateMenuOpen by remember { mutableStateOf(false) }
    val form = uiState.form

    SectionCard(
        title = "模型与连接",
        subtitle = "填自己的 API Key 直连厂商；Key 只存本机加密存储",
        initiallyExpanded = true,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                OutlinedButton(onClick = { templateMenuOpen = true }) { Text(text = "选择厂商模板") }
                DropdownMenu(expanded = templateMenuOpen, onDismissRequest = { templateMenuOpen = false }) {
                    uiState.templates.forEach { template ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(text = template.displayName, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        text = template.baseUrl,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                            onClick = {
                                templateMenuOpen = false
                                viewModel.applyTemplate(template.id)
                            },
                        )
                    }
                }
            }
            if (uiState.keyStored) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = "已保存 Key",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        OutlinedTextField(
            value = form.baseUrl,
            onValueChange = viewModel::updateBaseUrl,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(text = "Base URL") },
            placeholder = { Text(text = "https://api.deepseek.com/v1") },
            isError = !form.baseUrlValid,
            supportingText = {
                Text(
                    text = if (!form.baseUrlValid) {
                        "必须是 http(s) 地址；本应用会自动补 /chat/completions，不要重复填写该路径"
                    } else {
                        "填到 /v1 这一层即可，例如 https://dashscope.aliyuncs.com/compatible-mode/v1"
                    },
                )
            },
        )

        OutlinedTextField(
            value = form.apiKey,
            onValueChange = viewModel::updateApiKey,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(text = "API Key") },
            placeholder = { Text(text = "sk-…（只保存在本机，不会上传）") },
            visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
            leadingIcon = { Icon(Icons.Filled.Key, contentDescription = null, modifier = Modifier.size(18.dp)) },
            trailingIcon = {
                IconButton(onClick = { keyVisible = !keyVisible }) {
                    Icon(
                        imageVector = if (keyVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (keyVisible) "隐藏 Key" else "显示 Key",
                        modifier = Modifier.size(18.dp),
                    )
                }
            },
            supportingText = {
                Text(
                    text = if (form.apiKey.isBlank()) {
                        "本地 Ollama 可留空；远端厂商必须填"
                    } else {
                        "当前：" + form.maskedKey
                    },
                )
            },
        )

        OutlinedTextField(
            value = form.model,
            onValueChange = viewModel::updateModel,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(text = "模型名") },
            placeholder = { Text(text = "deepseek-chat / qwen-plus / moonshot-v1-8k") },
        )

        OutlinedTextField(
            value = form.textModel,
            onValueChange = viewModel::updateTextModel,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(text = "文本档模型（可选）") },
            supportingText = { Text(text = "留空则与上面的模型名相同；纯文本阶段用这个更省钱") },
        )

        OutlinedTextField(
            value = form.visionModel,
            onValueChange = viewModel::updateVisionModel,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(text = "视觉档模型（可选）") },
            supportingText = { Text(text = "含图 / 手写 / 低置信度时使用；留空则回退到模型名") },
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = form.localOnlyAcknowledged,
                onCheckedChange = viewModel::setLocalOnlyAcknowledged,
            )
            Text(
                text = "  我已了解：填写 Key 后，题干与图片会直接发送给我选择的模型厂商，不经过开发者服务器。",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
        }

        form.validationMessage?.let { validation ->
            Text(
                text = validation,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = viewModel::testConnection, enabled = !uiState.busy) { Text(text = "校验连通性") }
            OutlinedButton(onClick = viewModel::saveCredentials, enabled = form.dirty || !uiState.keyStored) {
                Text(text = "保存")
            }
            if (uiState.keyStored) {
                TextButton(onClick = viewModel::clearApiKey) {
                    Text(text = "清除 Key", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

/** 连通性结果（分类提示）。 */
@Composable
private fun ConnectionSection(uiState: SettingsUiState, viewModel: SettingsViewModel) {
    when (val connection = uiState.connection) {
        is ConnectionState.Idle -> Unit
        is ConnectionState.Checking -> Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Text(text = "  正在用 max_tokens=8 的最小请求校验…", style = MaterialTheme.typography.bodySmall)
        }
        is ConnectionState.Ok -> ResultBox(
            ok = true,
            title = "连接成功 · 模型 " + connection.model,
            body = "耗时 " + connection.latencyMs + " ms。模型回复片段：" +
                connection.replyPreview.take(40).ifBlank { "（空）" },
        )
        is ConnectionState.Failed -> ResultBox(
            ok = false,
            title = "连接失败 · " + connection.category,
            body = connection.message + if (connection.retryable) {
                "（该错误可重试，稍后点「校验连通性」再试一次）"
            } else {
                "（按提示修好后重新校验；也可以先保存，稍后再来）"
            },
            action = if (connection.retryable) {
                { TextButton(onClick = viewModel::testConnection) { Text(text = "重新校验") } }
            } else {
                null
            },
        )
    }
}

/** 教学偏好。 */
@Composable
private fun PreferenceSection(uiState: SettingsUiState, viewModel: SettingsViewModel) {
    val toggles = uiState.toggles
    SectionCard(title = "教学偏好", subtitle = "影响下一次对话，不改动已有记录") {
        Text(text = "默认讲解模式", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TeachingMode.entries.forEach { mode ->
                FilterChip(
                    selected = toggles.mode == mode,
                    onClick = { viewModel.setMode(mode) },
                    label = { Text(text = mode.label) },
                )
            }
        }
        Text(
            text = toggles.modeDescription,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        ToggleRow(
            title = "语音朗读（系统 TTS）",
            subtitle = "离线朗读步骤；中文语音包状态：" +
                uiState.ttsAvailabilityText.ifBlank { "未检测" },
            checked = toggles.ttsEnabled,
            onCheckedChange = viewModel::setTtsEnabled,
        )
        if (toggles.ttsEnabled) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TtsSpeed.entries.forEach { speed ->
                    FilterChip(
                        selected = toggles.ttsSpeed == speed,
                        onClick = { viewModel.setTtsSpeed(speed) },
                        label = { Text(text = speed.label) },
                    )
                }
                TextButton(onClick = viewModel::previewTts) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text(text = " 试听")
                }
            }
            Text(
                text = "语速倍率：" + toggles.ttsSpeed.rate,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        ToggleRow(
            title = "AI 周报解读",
            subtitle = "关闭后周报只显示数字（0 token）；开启会额外调用一次模型写约 200 字建议",
            checked = toggles.reportAiEnabled,
            onCheckedChange = viewModel::setReportAiEnabled,
        )
        ToggleRow(
            title = "解题时生成练习题",
            subtitle = "关闭后不再自动出变式题，可省 token；知识点详情页仍可手动生成",
            checked = toggles.generateExercise,
            onCheckedChange = viewModel::setGenerateExercise,
        )
    }
}

/** 存储与数据。 */
@Composable
private fun StorageSection(
    uiState: SettingsUiState,
    onClearCache: () -> Unit,
    onRefreshCache: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenExport: () -> Unit,
    onRequestClearAll: () -> Unit,
) {
    SectionCard(title = "存储与数据", subtitle = "全部数据只在本机；换机请用备份") {
        Text(
            text = uiState.cacheStats.displayText,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = "磁盘上限 100 MB：超限时优先删除可再生的缩略图与最久未用的缓存。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onClearCache, enabled = !uiState.busy) { Text(text = "清理缓存") }
            TextButton(onClick = onRefreshCache) { Text(text = "刷新") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onOpenBackup) { Text(text = "备份与恢复") }
            OutlinedButton(onClick = onOpenExport) { Text(text = "导出错题本") }
        }
        Text(
            text = DangerCopy.CLEAR_ALL_TITLE,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.error,
        )
        Text(
            text = "删除全部错题、掌握度、复习记录与设置，不可撤销。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = onRequestClearAll, enabled = !uiState.busy) {
            Text(text = "清除全部数据", color = MaterialTheme.colorScheme.error)
        }
    }
}

/** 开关行。 */
@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** 校验结果框。 */
@Composable
private fun ResultBox(
    ok: Boolean,
    title: String,
    body: String,
    action: (@Composable () -> Unit)? = null,
) {
    val tone = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(tone.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (ok) Icons.Filled.CheckCircle else Icons.Filled.Error,
                contentDescription = null,
                tint = tone,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = " " + title,
                style = MaterialTheme.typography.labelLarge,
                color = tone,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Text(text = body, style = MaterialTheme.typography.bodySmall, color = tone)
        action?.invoke()
    }
}

/** 警示条。 */
@Composable
private fun WarningBox(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

/** 合规声明。 */
@Composable
private fun DisclaimerBox() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            text = stringResource(R.string.ai_disclaimer) +
                "。本应用不内置任何 Key，也不提供自建后端；所有数据只保存在本机。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 可展开/折叠的设置分组面板。
 *
 * 为什么改成折叠：设置项数量已经超过一屏，全部展开时用户需要滚很久才能找到
 * 「模型与连接」这类低频但关键的配置；折叠后标题本身就是目录，扫一眼就能定位。
 *
 * 交互约定：
 * - 默认折叠，点击标题行展开；
 * - 展开状态用 rememberSaveable 记住，旋转屏幕或返回本页不会丢；
 * - 折叠时不组合内容（而非隐藏），避免展开时集中创建大量组件造成卡顿。
 */
@Composable
private fun SectionCard(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false,
    content: @Composable () -> Unit,
) {
    var expanded by rememberSaveable(title) { mutableStateOf(initiallyExpanded) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector =
                    if (expanded) {
                        Icons.Filled.ExpandLess
                    } else {
                        Icons.Filled.ExpandMore
                    },
                contentDescription = if (expanded) "收起" else "展开",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded) {
            Column(
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                content()
            }
        }
    }
}
