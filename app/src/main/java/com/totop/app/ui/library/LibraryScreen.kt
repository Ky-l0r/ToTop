package com.totop.app.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.totop.app.domain.model.LearnedMaterial
import com.totop.app.domain.model.Subjects
import com.totop.app.ui.theme.Tokens
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 资料库：学生攒下来的解析。
 *
 * 两条克制：一是不做搜索/筛选（条目本来就少，加了只是负担）；
 * 二是每条都显示来源与时间——这份东西可能来自作业帮，可能是错的，
 * 界面必须一直提醒「这不是你自己做的」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("资料库") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (val current = state) {
                is LibraryUiState.Empty -> EmptyLibrary()
                is LibraryUiState.Ready ->
                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        item { LibraryHint() }
                        items(current.items, key = { it.id }) { item ->
                            MaterialCard(item = item, onDelete = { viewModel.delete(item.id) })
                        }
                    }
            }
        }
    }
}

@Composable
private fun LibraryHint() {
    Text(
        text =
            "这些是你从别的渠道存进来的解析。解同类题时会挑最多 3 条给 AI 参考，" +
                "它们没有经过校验，AI 只用来对齐思路，不照抄结论。",
        style = MaterialTheme.typography.bodySmall,
        color = Tokens.OnSurfaceVariant,
    )
}

@Composable
private fun EmptyLibrary() {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "资料库还是空的",
            style = MaterialTheme.typography.titleMedium,
            color = Tokens.OnSurface,
        )
        Text(
            text = "拍一道题、看完解答之后，在解答卡底部点「存进资料库」，就会留在这里。",
            style = MaterialTheme.typography.bodySmall,
            color = Tokens.OnSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun MaterialCard(
    item: LearnedMaterial,
    onDelete: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Tokens.Surface)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = Subjects.label(item.subject) + " · " + item.kpointName,
                style = MaterialTheme.typography.labelMedium,
                color = Tokens.Primary,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "删除这条资料",
                    tint = Tokens.OnSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        if (item.title.isNotBlank()) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyMedium,
                color = Tokens.OnSurface,
            )
        }
        if (item.summary.isNotBlank()) {
            Text(
                text = item.summary,
                style = MaterialTheme.typography.bodySmall,
                color = Tokens.OnSurfaceVariant,
            )
        }
        Text(
            text = "来源：" + item.sourceLabel + " · " + formatTime(item.createdAtUtc),
            style = MaterialTheme.typography.labelSmall,
            color = Tokens.OnSurfaceVariant,
        )
    }
}

/** 时间：只到分钟，年份不同才带上年份——资料库不是账本，不需要秒。 */
private fun formatTime(atUtc: Long): String {
    val now = Date()
    val target = Date(atUtc)
    val sameYear =
        SimpleDateFormat("yyyy", Locale.CHINA).format(now) ==
            SimpleDateFormat("yyyy", Locale.CHINA).format(target)
    val pattern = if (sameYear) "M月d日 HH:mm" else "yyyy年M月d日"
    return SimpleDateFormat(pattern, Locale.CHINA).format(target)
}
