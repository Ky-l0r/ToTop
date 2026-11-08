package com.totop.app.ui.practice

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
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.totop.app.ui.common.SkeletonList
import com.totop.app.ui.theme.Tokens

/**
 * 变式刷题页（0.7.0）。
 *
 * 交互顺序刻意固定成「先作答 → 看答案 → 自评」：
 * 答案默认不显示，学生写不写都行，但要点一下「看答案」才出现，
 * 这样「先自己动手」这件事至少有一步摩擦，不会被一眼扫到结论。
 */
@Composable
fun PracticeScreen(
    kpointId: String,
    kpointName: String,
    onBack: () -> Unit,
    viewModel: PracticeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // 幂等在 ViewModel 里：同一知识点重复进入不会重复出题（出题是要花钱的）。
    androidx.compose.runtime.LaunchedEffect(kpointId, kpointName) {
        viewModel.start(kpointId, kpointName)
    }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(
                text = if (state.kpointName.isBlank()) "练几道" else state.kpointName,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            if (state.total > 0) {
                Text(
                    text = state.progressLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = Tokens.OnSurfaceVariant,
                )
            }
        }

        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when {
                state.loading -> SkeletonList(rows = 4)
                state.finished -> ResultCard(state = state, onRetry = viewModel::retry, onBack = onBack)
                state.stem.isBlank() ->
                    Text(
                        text = state.message ?: "还没有题目。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Tokens.OnSurfaceVariant,
                    )
                else -> QuestionCard(state = state, viewModel = viewModel)
            }
            if (state.message != null && !state.loading && state.stem.isNotBlank()) {
                Text(
                    text = state.message.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = Tokens.OnSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun QuestionCard(
    state: PracticeUiState,
    viewModel: PracticeViewModel,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Tokens.SurfaceVariant)
                .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(text = state.stem, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = "先自己写，写完再看答案。",
            style = MaterialTheme.typography.labelSmall,
            color = Tokens.OnSurfaceVariant,
        )
        OutlinedTextField(
            value = state.studentAnswer,
            onValueChange = viewModel::updateStudentAnswer,
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            label = { Text(text = "你的答案（可以不写）") },
        )
        if (!state.revealed) {
            Button(onClick = viewModel::reveal, enabled = state.canReveal) { Text(text = "看答案") }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(text = "答案", style = MaterialTheme.typography.labelMedium, color = Tokens.Primary)
                Text(text = state.answer, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(4.dp))
                Text(text = "和你写的一样吗？", style = MaterialTheme.typography.labelSmall, color = Tokens.OnSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { viewModel.rate(matched = true) }) { Text(text = "对上了") }
                    TextButton(onClick = { viewModel.rate(matched = false) }) { Text(text = "没对上") }
                }
            }
        }
    }
}

@Composable
private fun ResultCard(
    state: PracticeUiState,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Tokens.PrimaryContainer)
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = if (state.passed) "过关" else "还差一点",
            style = MaterialTheme.typography.titleMedium,
            color = if (state.passed) Tokens.Success else Tokens.OnSurface,
        )
        Text(
            text = "这一轮 " + state.total + " 题里对上了 " + state.matched + " 题。",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = if (state.passed) "已把这个知识点下最近一道错题标成「理解」。" else "再来一轮，把没对上的那种问法练熟。",
            style = MaterialTheme.typography.labelSmall,
            color = Tokens.OnSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onRetry) { Text(text = "再出一轮") }
            TextButton(onClick = onBack) { Text(text = "回到知识点") }
        }
    }
}
