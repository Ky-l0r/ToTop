package com.totop.app.ui.mistakes

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.totop.app.R
import com.totop.app.domain.model.RecallSelfResult

/**
 * 复习闭环（FR-4.2 / FR-4.3 / FR-4.4）。
 *
 * 流程：
 * 1. 显示题干与「你当时写的」，学生先自己重做一遍；
 * 2. 点「看标准解答」自检（内容本地已存，**不发起任何模型调用**）；
 * 3. 自评「做对了 / 还是不会」——只写一条 review_log，**零 token**；
 * 4. 顶部徽标显示本轮进度与「想通 / 还要再看」数量，结算页给出下次复习时间。
 *
 * 可选 AI 校验开关默认关闭；开启后才会发起一次独立求解，失败不影响自评。
 *
 * @param onBack 返回上一页
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ReviewViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val session = uiState.session
    var solutionVisible by remember(session.index) { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.bootstrap() }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.nav_review)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.nav_back),
                        )
                    }
                },
                actions = {
                    TextButton(onClick = viewModel::reload) { Text(text = "重新载入") }
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
            RoundBadge(session = session, progressText = uiState.progressText)
            if (session.candidates.isNotEmpty()) {
                LinearProgressIndicator(progress = { session.progress }, modifier = Modifier.fillMaxWidth())
            }

            OfflineNotice()

            when {
                uiState.loading ->
                    Text(
                        text = "正在读取待复习错题…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                uiState.errorMessage != null ->
                    ErrorBlock(
                        message = uiState.errorMessage.orEmpty(),
                        onRetry = viewModel::reload,
                    )

                session.isEmpty -> EmptyBlock(message = uiState.hintMessage)

                session.finished ->
                    SessionSummary(
                        session = session,
                        onReload = viewModel::reload,
                        onBack = onBack,
                    )

                else ->
                    CurrentQuestion(
                        session = session,
                        uiState = uiState,
                        solutionVisible = solutionVisible,
                        onToggleSolution = { solutionVisible = !solutionVisible },
                        onSelfResult = { result ->
                            solutionVisible = false
                            viewModel.submitSelfResult(result)
                        },
                        onSkip = {
                            solutionVisible = false
                            viewModel.skipCurrent()
                        },
                        onToggleAiCheck = viewModel::setAiCheckEnabled,
                        onRunAiCheck = viewModel::runAiCheck,
                    )
            }
        }
    }
}

/** 顶部徽标：本轮进度 + 想通/还要再看数量。 */
@Composable
private fun RoundBadge(
    session: ReviewSession,
    progressText: String,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = progressText,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = session.badgeText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

/** 离线标注：复习链路完全本机完成（FR-6.5「离线结果」标注）。 */
@Composable
private fun OfflineNotice() {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            text = "离线结果 · 复习与自评全部在本机完成，不联网、不消耗 token。",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 当前题。 */
@Suppress("LongParameterList")
@Composable
private fun CurrentQuestion(
    session: ReviewSession,
    uiState: ReviewUiState,
    solutionVisible: Boolean,
    onToggleSolution: () -> Unit,
    onSelfResult: (RecallSelfResult) -> Unit,
    onSkip: () -> Unit,
    onToggleAiCheck: (Boolean) -> Unit,
    onRunAiCheck: () -> Unit,
) {
    val candidate = session.current ?: return
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard(title = "第 " + (session.index + 1) + " 题 · " + candidate.errorTypeLabel) {
            Text(text = candidate.stemSummary.ifBlank { "（题干为空，请以原题图片为准）" })
            if (candidate.kpointNames.isNotEmpty()) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    candidate.kpointNames.forEach { name -> KpointTag(text = name) }
                }
            }
            candidate.recurrenceBadgeOrNull()?.let { badge -> RecurrenceBadge(text = badge) }
            Text(
                text = "已复习 " + candidate.reviewCount + " 次 · 下次到期由间隔重复自动排期",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard(title = "你当时写的") {
            if (candidate.studentSteps.isEmpty()) {
                Text(
                    text = "这道题当时没有填写解题过程，只能自己再写一遍。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                candidate.studentSteps.forEachIndexed { index, step ->
                    Text(text = (index + 1).toString() + ". " + step, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onToggleSolution) {
                Icon(Icons.Filled.Visibility, contentDescription = null, modifier = Modifier.size(16.dp))
                Text(text = if (solutionVisible) " 收起标准解答" else " 看标准解答（本地）")
            }
            TextButton(onClick = onSkip) { Text(text = "跳过这题") }
        }

        if (solutionVisible) {
            SectionCard(title = "标准解答") {
                if (candidate.solutionSteps.isEmpty()) {
                    Text(text = "这道题没有保存步骤，只有答案。", style = MaterialTheme.typography.bodyMedium)
                } else {
                    candidate.solutionSteps.forEachIndexed { index, step ->
                        Text(text = "第 " + (index + 1) + " 步 · " + step, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (candidate.finalAnswer.isNotBlank()) {
                    Text(
                        text = "答案：" + candidate.finalAnswer,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

        SectionCard(title = "这次重做，你想通了吗") {
            Text(
                text = "自己勾选，不需要输入任何内容；这一动作只写一条本机记录，不消耗 token。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { onSelfResult(RecallSelfResult.GOT_IT) },
                    enabled = !session.submitting,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                ) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text(text = " 做对了")
                }
                OutlinedButton(
                    onClick = { onSelfResult(RecallSelfResult.STILL_LOST) },
                    enabled = !session.submitting,
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text(text = " 还是不会")
                }
            }
            uiState.lastFeedback?.let { feedback ->
                Text(
                    text = feedback.nextReviewHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        AiCheckCard(
            enabled = uiState.aiCheckEnabled,
            state = uiState.checkState,
            onToggle = onToggleAiCheck,
            onRun = onRunAiCheck,
        )
    }
}

/** 可选 AI 校验卡（默认关闭）。 */
@Composable
private fun AiCheckCard(
    enabled: Boolean,
    state: ReviewCheckState,
    onToggle: (Boolean) -> Unit,
    onRun: () -> Unit,
) {
    SectionCard(title = "可选：AI 校验") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "开启 AI 校验（默认关闭）",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
        Text(
            text = "开启后会发起一次模型调用；关闭时复习链路完全不联网。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (enabled) {
            OutlinedButton(onClick = onRun, enabled = state !is ReviewCheckState.Running) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp))
                Text(text = if (state is ReviewCheckState.Running) " 校验中…" else " 让 AI 独立重解一遍")
            }
        }
        when (state) {
            is ReviewCheckState.Off -> Unit
            is ReviewCheckState.Running ->
                Text(
                    text = "正在请求模型…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            is ReviewCheckState.Done -> {
                Text(
                    text = if (state.cached) "命中缓存（0 token）" else "模型独立重解结果：",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.method.isNotBlank()) {
                    Text(text = "方法：" + state.method, style = MaterialTheme.typography.bodyMedium)
                }
                if (state.answer.isNotBlank()) {
                    Text(
                        text = "答案：" + state.answer,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(
                    text = "内容由 AI 生成，可能存在错误，不替代老师",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            is ReviewCheckState.Failed ->
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
        }
    }
}

/** 结算页。 */
@Composable
private fun SessionSummary(
    session: ReviewSession,
    onReload: () -> Unit,
    onBack: () -> Unit,
) {
    SectionCard(title = "本轮完成") {
        Text(text = session.summaryText, style = MaterialTheme.typography.bodyMedium)
        session.results.forEach { item ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = item.label,
                    style = MaterialTheme.typography.labelMedium,
                    color =
                        if (item.selfResult == RecallSelfResult.GOT_IT) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                )
                Text(
                    text = "  " + item.stemSummary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = item.dueText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onReload) { Text(text = "再来一轮") }
            OutlinedButton(onClick = onBack) { Text(text = "回到错题本") }
        }
    }
}

/** 空态。 */
@Composable
private fun EmptyBlock(message: String) {
    SectionCard(title = "今天没有到期的错题") {
        Text(
            text = message.ifBlank { "按间隔重复的节奏，明天再来一轮效果最好。" },
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = "复习排期由「做对了 / 还是不会」两档自评驱动：想通的题目间隔会逐渐拉长，没想通的明天会再出现。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 错误态：给出重试入口，不静默失败。 */
@Composable
private fun ErrorBlock(
    message: String,
    onRetry: () -> Unit,
) {
    SectionCard(title = "读取失败") {
        Text(text = message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        OutlinedButton(onClick = onRetry) { Text(text = "重试") }
    }
}

/** 统一卡片容器（与解题页视觉一致，但不跨包依赖）。 */
@Composable
internal fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            content()
        }
    }
}

/** 复发徽标文案（首次为 null）。 */
internal fun ReviewCandidate.recurrenceBadgeOrNull(): String? = recurrenceBadgeText(recurrenceIndex)
