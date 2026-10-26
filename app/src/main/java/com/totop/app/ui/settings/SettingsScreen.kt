package com.totop.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.ChevronRight
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
 * 页面本身只是**目录**：每个条目跳到一个独立子页面。
 * 备份/导出等具体动作都在「存储与数据」子页面里，本页不再直接持有它们的回调。
 *
 * @param onOpenModel 模型与连接（BYOK 表单 + 连通性校验）
 * @param onOpenTeaching 教学偏好
 * @param onOpenStorage 存储与数据（缓存、备份、导出、清空）
 * @param onOpenAbout 关于与帮助（重看引导、数据去向）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    onOpenModel: () -> Unit = {},
    onOpenTeaching: () -> Unit = {},
    onOpenStorage: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
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
        // 设置本身只做「目录」：一屏四五个条目，点进去是独立的子页面。
        //
        // 旧版把所有分组塞在一个长列表里，且「模型与连接」默认展开，
        // 每次进设置第一眼都是十几个输入框，真正想改的东西反而要往下翻很久。
        // 现在每个条目只显示一行摘要（例如「已配置 / 未配置」），
        // 具体表单在子页面里，进得去也退得出。
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!uiState.storageAvailable) {
                WarningBox(
                    text =
                        "本机密钥存储不可用（可能是系统 Keystore 异常）。请重新填写 Key；" +
                            "在修复前应用会以本地模式运行。",
                )
            }
            if (uiState.localMode) {
                WarningBox(
                    text =
                        "当前未配置 API Key，应用处于本地模式：解题与 AI 解读不可用，" +
                            "错题本、复习、掌握度与周报仍可正常使用。",
                )
            }

            SettingsGroup(title = "模型") {
                SettingsEntry(
                    title = "模型与连接",
                    subtitle =
                        if (uiState.keyStored) {
                            (uiState.form.visionModel.ifBlank { uiState.form.model }).ifBlank { "已保存 Key" }
                        } else {
                            "未配置：点进去填 Base URL、Key 与模型名"
                        },
                    status = if (uiState.keyStored) "已配置" else "未配置",
                    statusOk = uiState.keyStored,
                    onClick = onOpenModel,
                )
            }

            SettingsGroup(title = "学习") {
                SettingsEntry(
                    title = "教学偏好",
                    subtitle = "讲解模式、朗读、周报解读与练习题",
                    onClick = onOpenTeaching,
                )
            }

            SettingsGroup(title = "数据") {
                SettingsEntry(
                    title = "存储与数据",
                    subtitle = "缓存占用 " + uiState.cacheStats.displayText + "、备份、导出、清空",
                    onClick = onOpenStorage,
                )
                SettingsEntry(
                    title = "关于与帮助",
                    subtitle = "重新查看新手引导、数据去向说明",
                    onClick = onOpenAbout,
                )
            }

            DisclaimerBox()
        }
    }
}

/**
 * 子页面内部的普通卡片容器：标题 + 说明 + 内容，始终展开。
 *
 * 子页面进来就是为了改这一件事，再折叠只会多一次点击。
 */
@Composable
internal fun ContentCard(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column {
            Text(text = title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        content()
    }
}

/** BYOK 表单。 */
@Composable
internal fun ByokSection(
    uiState: SettingsUiState,
    viewModel: SettingsViewModel,
) {
    var keyVisible by remember { mutableStateOf(false) }
    var templateMenuOpen by remember { mutableStateOf(false) }
    val form = uiState.form

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ContentCard(
            title = "填自己的 Key 直连厂商",
            subtitle = "Key 只存本机加密存储，不经过开发者服务器",
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
                        text =
                            if (!form.baseUrlValid) {
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
                        text =
                            if (form.apiKey.isBlank()) {
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
}

/** 连通性结果（分类提示）。 */
@Composable
private fun ConnectionSection(
    uiState: SettingsUiState,
    viewModel: SettingsViewModel,
) {
    when (val connection = uiState.connection) {
        is ConnectionState.Idle -> Unit
        is ConnectionState.Checking ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(text = "  正在用 max_tokens=8 的最小请求校验…", style = MaterialTheme.typography.bodySmall)
            }
        is ConnectionState.Ok ->
            ResultBox(
                ok = true,
                title = "连接成功 · 模型 " + connection.model,
                body =
                    "耗时 " + connection.latencyMs + " ms。模型回复片段：" +
                        connection.replyPreview.take(40).ifBlank { "（空）" },
            )
        is ConnectionState.Failed ->
            ResultBox(
                ok = false,
                title = "连接失败 · " + connection.category,
                body =
                    connection.message +
                        if (connection.retryable) {
                            "（该错误可重试，稍后点「校验连通性」再试一次）"
                        } else {
                            "（按提示修好后重新校验；也可以先保存，稍后再来）"
                        },
                action =
                    if (connection.retryable) {
                        { TextButton(onClick = viewModel::testConnection) { Text(text = "重新校验") } }
                    } else {
                        null
                    },
            )
    }
}

/** 教学偏好（独立子页面）。 */
@Composable
fun TeachingSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
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
    SettingsSubScreen(
        title = "教学偏好",
        onBack = onBack,
        modifier = modifier,
        snackbarHostState = snackbarHostState,
    ) {
        PreferenceSection(uiState = uiState, viewModel = viewModel)
    }
}

@Composable
private fun PreferenceSection(
    uiState: SettingsUiState,
    viewModel: SettingsViewModel,
) {
    val toggles = uiState.toggles
    ContentCard(title = "讲解偏好", subtitle = "影响下一次对话，不改动已有记录") {
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
            subtitle =
                "离线朗读步骤；中文语音包状态：" +
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

/** 存储与数据（独立子页面）。 */
@Composable
fun StorageSettingsScreen(
    onOpenBackup: () -> Unit,
    onOpenExport: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        viewModel.bootstrap()
        viewModel.refreshCacheStats()
    }
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

    SettingsSubScreen(
        title = "存储与数据",
        onBack = onBack,
        modifier = modifier,
        snackbarHostState = snackbarHostState,
    ) {
        StorageSection(
            uiState = uiState,
            onClearCache = viewModel::clearCache,
            onRefreshCache = viewModel::refreshCacheStats,
            onOpenBackup = onOpenBackup,
            onOpenExport = onOpenExport,
            onRequestClearAll = viewModel::requestClearAll,
        )
    }
}

@Composable
private fun StorageSection(
    uiState: SettingsUiState,
    onClearCache: () -> Unit,
    onRefreshCache: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenExport: () -> Unit,
    onRequestClearAll: () -> Unit,
) {
    ContentCard(title = "本机存储", subtitle = "全部数据只在本机；换机请用备份") {
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
        modifier =
            Modifier
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
        modifier =
            Modifier
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

/**
 * 「模型与连接」独立子页面。
 *
 * 从设置目录点进来才展开表单：设置目录本身保持一屏干净，
 * 低频配置（Base URL / 多模型名 / 连通性校验）不再占据首屏。
 */
@Composable
fun ModelSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
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

    SettingsSubScreen(
        title = stringResource(R.string.nav_settings_model),
        onBack = onBack,
        modifier = modifier,
        snackbarHostState = snackbarHostState,
    ) {
        if (!uiState.storageAvailable) {
            WarningBox(
                text =
                    "本机密钥存储不可用（可能是系统 Keystore 异常）。请重新填写 Key；" +
                        "在修复前应用会以本地模式运行。",
            )
        }
        ByokSection(uiState = uiState, viewModel = viewModel)
        ConnectionSection(uiState = uiState, viewModel = viewModel)
        Text(
            text =
                "说明：Key 只写进本机的加密存储，任何提示、日志与导出文件里都不会出现明文；" +
                    "题目图片只会发给你在这里填的那家厂商。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 「关于与帮助」独立子页面。
 *
 * 除了免责声明，这里提供「重新看一遍新手引导」的入口：
 * 引导只会在首次启动自动出现，用户后来想再翻一遍（例如忘了怎么框选）
 * 必须有一个固定的落脚点，否则只能卸载重装。
 */
@Composable
fun AboutSettingsScreen(
    onBack: () -> Unit,
    onReplayOnboarding: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsSubScreen(
        title = stringResource(R.string.nav_settings_about),
        onBack = onBack,
        modifier = modifier,
    ) {
        ContentCard(title = "新手引导", subtitle = "一页一页介绍怎么用") {
            Text(
                text = "只在第一次打开时自动出现。想再翻一遍可以点下面这个按钮。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onReplayOnboarding) { Text(text = "重新查看新手引导") }
        }
        ContentCard(title = "你的数据在哪", subtitle = "这一节没有可操作项") {
            Text(
                text =
                    "错题、图片、掌握度、对话记录、设置全部存在本机应用目录里，没有开发者服务器，" +
                        "也没有任何统计或埋点 SDK。",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text =
                    "题目图片与题干只会在你拍题求解时，发送给你自己配置的那家模型厂商；" +
                        "不填 Key 时应用以本地模式运行，不发出任何请求。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        DisclaimerBox()
    }
}

/** 合规声明。 */
@Composable
private fun DisclaimerBox() {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            text =
                stringResource(R.string.ai_disclaimer) +
                    "。本应用不内置任何 Key，也不提供自建后端；所有数据只保存在本机。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 设置分组容器：一个浅底圆角块，内部若干条目，条目之间有细分隔线。
 *
 * 与旧的折叠面板的区别：这里**不折叠**。设置页已经退化成纯目录，
 * 每条只占一行；再给目录套一层折叠，用户要「展开分组 -> 再点条目」两步才能进去，
 * 而这两步没有任何信息增益。
 */
@Composable
private fun SettingsGroup(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 6.dp, bottom = 6.dp),
        )
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp)),
            content = content,
        )
    }
}

/**
 * 设置条目：标题 + 一句话说明 + 右侧状态与箭头，整行可点。
 *
 * @param status 右侧状态文案（例如「已配置」）；为空时不显示
 * @param statusOk 状态是否属于「正常」色（主色），否则用警示色
 */
@Composable
private fun SettingsEntry(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    status: String? = null,
    statusOk: Boolean = true,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (status != null) {
            Text(
                text = status,
                style = MaterialTheme.typography.labelSmall,
                color =
                    if (statusOk) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
            )
            Spacer(Modifier.size(6.dp))
        }
        Icon(
            imageVector = Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * 设置子页面的统一外壳：返回箭头 + 标题 + 可滚动内容。
 *
 * 每个子页面（模型 / 教学偏好 / 存储 / 关于）都套这一层，
 * 保证返回位置、标题字号、边距完全一致，不需要各写一套 Scaffold。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSubScreen(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(text = title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        snackbarHost = { snackbarHostState?.let { SnackbarHost(hostState = it) } },
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}
