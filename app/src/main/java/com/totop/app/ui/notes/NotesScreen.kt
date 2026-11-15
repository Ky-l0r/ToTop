package com.totop.app.ui.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.totop.app.domain.model.StudyNote
import com.totop.app.ui.components.MarkdownPlain
import com.totop.app.ui.theme.Tokens

/**
 * 卡点笔记：学生自己记下的重点与卡点。
 *
 * 为什么单开一页而不是塞进知识点详情：笔记可能一条知识点都没命中（陪伴页的回答常常
 * 只是「这个概念是什么意思」），挂在节点上的列表看不到它们——学生记完就再也找不到了。
 * 这里按分类列全部，不依赖归属。
 *
 * 两条克制：不做搜索与筛选（几十条的量，分节已经够用）；正文最多 6 行，
 * 想读全文回陪伴页看原话——这一页的职责是「想起自己记过什么」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: NotesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("卡点笔记") },
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
                is NotesUiState.Empty -> EmptyNotes()
                is NotesUiState.Ready ->
                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        item { NotesHint() }
                        current.sections.forEach { section ->
                            item(key = "header-" + section.kind.wire) {
                                SectionHeader(
                                    title = section.kind.label,
                                    hint = section.kind.hint,
                                    count = section.notes.size,
                                )
                            }
                            items(section.notes, key = { it.id }) { note ->
                                NoteCard(note = note, onDelete = { viewModel.delete(note.id) })
                            }
                        }
                    }
            }
        }
    }
}

@Composable
private fun NotesHint() {
    Text(
        text =
            "这些是你在陪伴作业里记下来的原话，没有改写。" +
                "「重点」是结论与公式，「卡点」是还没想通的地方；生成复习资料时会各挑几条放进去。",
        style = MaterialTheme.typography.bodySmall,
        color = Tokens.OnSurfaceVariant,
    )
}

/** 分节标题：分类名 + 条数 + 一句话说明。 */
@Composable
private fun SectionHeader(
    title: String,
    hint: String,
    count: Int,
) {
    Column(modifier = Modifier.padding(top = 12.dp, bottom = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = Tokens.OnSurface,
            )
            Text(
                text = "  " + count + " 条",
                style = MaterialTheme.typography.labelSmall,
                color = Tokens.OnSurfaceVariant,
            )
        }
        Text(
            text = hint,
            style = MaterialTheme.typography.labelSmall,
            color = Tokens.OnSurfaceVariant,
        )
    }
}

@Composable
private fun NoteCard(
    note: StudyNote,
    onDelete: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Tokens.Surface)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = MarkdownPlain.normalize(note.title),
                style = MaterialTheme.typography.labelLarge,
                color = Tokens.OnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = MarkdownPlain.normalize(note.body),
                style = MaterialTheme.typography.bodyMedium,
                color = Tokens.OnSurfaceVariant,
                maxLines = BODY_PREVIEW_LINES,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = note.source.label + if (note.hasKpoint) "" else " · 未挂知识点",
                style = MaterialTheme.typography.labelSmall,
                color = Tokens.OnSurfaceVariant,
            )
        }
        Spacer(Modifier.size(8.dp))
        IconButton(
            onClick = onDelete,
            modifier = Modifier.size(32.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = "删除这条笔记",
                tint = Tokens.OnSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun EmptyNotes() {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "还没有笔记",
            style = MaterialTheme.typography.titleMedium,
            color = Tokens.OnSurface,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "在陪伴作业里问完一句，觉得有用就点「记重点」；没太想通就点「记卡点」。",
            style = MaterialTheme.typography.bodyMedium,
            color = Tokens.OnSurfaceVariant,
        )
    }
}

/** 卡片正文预览行数：再多这一页就变成笔记合订本了。 */
private const val BODY_PREVIEW_LINES: Int = 6
