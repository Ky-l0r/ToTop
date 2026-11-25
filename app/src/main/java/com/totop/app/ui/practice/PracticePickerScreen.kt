package com.totop.app.ui.practice

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.totop.app.ui.common.SubjectTabs
import com.totop.app.ui.theme.Tokens

/**
 * 巩固练习的选择页（0.9）：科目 → 知识点 → 题型，两步可退回。
 *
 * 之所以把「来源（按知识点 / 按最近错题）」也放在这一页：它决定的只是出题时给不给样本题干，
 * 对学生的意义是「练同类题还是练我错过的那道」，属于选范围的一部分，不该藏到下一页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PracticePickerScreen(
    onStart: (kpointId: String, kpointName: String, questionType: String, useHistory: Boolean) -> Unit,
    onBack: () -> Unit,
    viewModel: PracticePickerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.bootstrap() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "巩固练习") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "1 选科目",
                style = MaterialTheme.typography.labelLarge,
                color = Tokens.OnSurfaceVariant,
            )
            if (state.subjects.isNotEmpty()) {
                SubjectTabs(
                    available = state.subjects.toSet(),
                    allowed = state.subjects.toSet(),
                    selected = state.subject,
                    onSelect = viewModel::selectSubject,
                )
            }

            Text(
                text = "2 选知识点",
                style = MaterialTheme.typography.labelLarge,
                color = Tokens.OnSurfaceVariant,
            )
            if (state.message.isNotBlank()) {
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = Tokens.OnSurfaceVariant,
                )
            }
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(state.items) { item ->
                    val selected = item.id == state.selectedId
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.selectItem(item.id, item.name) }
                                .padding(vertical = 8.dp),
                    ) {
                        Text(
                            text = item.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (selected) Tokens.Primary else Tokens.OnSurface,
                        )
                        Text(
                            text = item.chapter,
                            style = MaterialTheme.typography.labelSmall,
                            color = Tokens.OnSurfaceVariant,
                        )
                    }
                }
            }

            Text(
                text = "3 选题型与来源",
                style = MaterialTheme.typography.labelLarge,
                color = Tokens.OnSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("" to "不限", "选择" to "选择", "填空" to "填空", "解答" to "解答").forEach { (value, label) ->
                    Chip(
                        text = label,
                        selected = state.questionType == value,
                        onClick = { viewModel.setQuestionType(value) },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(
                    text = "按知识点",
                    selected = !state.useHistory,
                    onClick = { viewModel.setUseHistory(false) },
                )
                Chip(
                    text = "按最近错题",
                    selected = state.useHistory,
                    onClick = { viewModel.setUseHistory(true) },
                )
            }

            Button(
                onClick = {
                    onStart(state.selectedId, state.selectedName, state.questionType, state.useHistory)
                },
                enabled = state.canStart,
                modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp),
            ) {
                Text(text = if (state.selectedName.isBlank()) "先选一个知识点" else "练「" + state.selectedName + "」")
            }
        }
    }
}

/** 细胶囊：选中用主色底，未选中留白底，与全局筛选胶囊同一套观感。 */
@Composable
private fun Chip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = if (selected) Tokens.Primary else Tokens.OnSurfaceVariant,
        modifier =
            Modifier
                .background(
                    if (selected) Tokens.PrimaryContainer else Tokens.SurfaceVariant,
                    RoundedCornerShape(50),
                )
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}
