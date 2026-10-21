package com.totop.app.ui.knowledge

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.ReportProblem
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.totop.app.R
import com.totop.app.domain.model.MasteryBand
import com.totop.app.ui.mistakes.KpointTag

/**
 * 知识点详情（FR-3.3）。
 *
 * 六块内容（自上而下）：
 * 1. 定义与适用范围；
 * 2. 模板化解题思路；
 * 3. 高频易错点清单；
 * 4. 前置依赖知识点（可点击跳转）；
 * 5. 该知识点下的错题列表；
 * 6. 巩固练习入口。
 *
 * 数据全部来自内置知识点树与本机记录，除「巩固练习」外不联网。
 *
 * @param kpointId 受控知识点 ID（KP_<SUBJECT>_<四位序号>）
 * @param onBack 返回
 * @param onOpenKnowledgePoint 点击前置依赖时跳转另一个知识点
 * @param onPractice 进入巩固练习（AI 出题，需配置 Key）
 * @param onOpenMistake 打开一道错题
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KnowledgePointDetailScreen(
    kpointId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenKnowledgePoint: (String) -> Unit = {},
    onPractice: (String) -> Unit = {},
    onOpenMistake: (String) -> Unit = {},
    viewModel: KpointDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(kpointId) { viewModel.bootstrap(kpointId) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.nav_kpoint_detail)) },
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
        if (uiState.loading) {
            Row(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }
            return@Scaffold
        }

        uiState.errorMessage?.let { message ->
            Column(
                modifier = Modifier.fillMaxSize().padding(innerPadding).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = "知识点 ID：" + kpointId,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = onBack) { Text(text = "返回") }
            }
            return@Scaffold
        }

        val point = uiState.point ?: return@Scaffold
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HeaderCard(uiState = uiState)

            DetailCard(
                title = "定义与适用范围",
                icon = { Icon(Icons.Filled.Book, contentDescription = null, modifier = Modifier.size(16.dp)) },
            ) {
                Text(
                    text = point.definition.ifBlank { "内置数据未填写定义，可在教材中对照本章节的定义。" },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            DetailCard(title = "模板化解题思路") {
                Text(
                    text = point.commonApproach.ifBlank { "内置数据未填写通用思路，先按标准解答的步骤顺序梳理。" },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            DetailCard(
                title = "高频易错点",
                icon = {
                    Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.size(16.dp))
                },
            ) {
                if (point.pitfalls.isEmpty()) {
                    Text(
                        text = "这个知识点暂时没有整理好的易错点。做题时留意单位、符号与适用条件。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    point.pitfalls.forEachIndexed { index, pitfall ->
                        Row(verticalAlignment = Alignment.Top) {
                            Text(
                                text = (index + 1).toString() + ".",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                            Text(
                                text = " " + pitfall,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }

            DetailCard(
                title = "前置依赖知识点",
                icon = {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                },
            ) {
                if (uiState.prerequisites.isEmpty()) {
                    Text(
                        text = "这个知识点没有前置依赖，可以直接开始。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = "先补这些再看本页，效果更好（点击可跳转）：",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    uiState.prerequisites.forEach { prerequisite ->
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onOpenKnowledgePoint(prerequisite.id) }
                                    .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = prerequisite.name,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = prerequisite.chapter,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            DetailCard(
                title = "这个知识点的错题（" + uiState.mistakes.size + "）",
                icon = {
                    Icon(Icons.Filled.ReportProblem, contentDescription = null, modifier = Modifier.size(16.dp))
                },
            ) {
                if (uiState.mistakes.isEmpty()) {
                    Text(
                        text = "还没有归到这个知识点的错题。做错题时 AI 会按受控知识点树归档。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    uiState.mistakes.forEach { mistake ->
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onOpenMistake(mistake.mistakeId) }
                                    .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = mistake.stemSummary.ifBlank { "（题干为空）" },
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            mistake.recurrenceBadge?.let { badge ->
                                com.totop.app.ui.mistakes.RecurrenceBadge(text = badge)
                            }
                            Text(
                                text = "  " + mistake.errorTypeLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            DetailCard(
                title = "巩固练习",
                icon = {
                    Icon(
                        Icons.AutoMirrored.Filled.HelpOutline,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                },
            ) {
                Text(
                    text =
                        "练习会围绕这个知识点生成变式题，生成后模型会独立重做一遍，" +
                            "答案一致才入库，不一致的题不会给你做。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "已通过校验的练习题：" + uiState.verifiedExerciseCount + " 道",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onPractice(point.id) },
                        enabled = uiState.canPractice,
                    ) {
                        Text(text = if (uiState.verifiedExerciseCount > 0) "开始练习" else "生成练习并开始")
                    }
                    TextButton(onClick = onBack) { Text(text = "稍后再说") }
                }
                Text(
                    text = "AI 出题会消耗 token；可在设置页关闭「本次生成练习题」。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(
                text = "知识点来自内置受控树（ID " + point.id + "），AI 只能引用、不能自造。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 头部：名称 + 章节 + 掌握度色阶。 */
@Composable
private fun HeaderCard(uiState: KpointDetailUiState) {
    val point = uiState.point ?: return
    val band = KnowledgeCharts.bandOfScore(uiState.score ?: 0.0)
    val tone = if (uiState.score == null) MasteryColors.Untouched else MasteryColors.of(band)
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(tone.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = point.name,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            uiState.parentName?.let { parent ->
                KpointTag(text = parent)
            }
            if (point.chapter.isNotBlank()) {
                Text(
                    text = "  " + point.chapter,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text =
                "掌握度：" + uiState.scoreText +
                    when (band) {
                        MasteryBand.RED -> "（红：优先补）"
                        MasteryBand.YELLOW -> "（黄：还要练）"
                        MasteryBand.GREEN -> "（绿：稳）"
                    },
            style = MaterialTheme.typography.bodyMedium,
            color = tone,
        )
        Text(
            text = "掌握度只看复习自评与错题频次，不做机器打分。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 统一内容卡片。 */
@Composable
private fun DetailCard(
    title: String,
    modifier: Modifier = Modifier,
    icon: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Box(modifier = Modifier.padding(end = 6.dp)) { icon() }
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
        content()
    }
}
