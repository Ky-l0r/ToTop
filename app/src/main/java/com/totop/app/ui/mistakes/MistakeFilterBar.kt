package com.totop.app.ui.mistakes

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.totop.app.domain.model.ErrorType

/**
 * 错题本筛选栏（FR-4.1）。
 *
 * 五个条件：搜索框 / 科目 / 知识点 / 错因 / 时间窗口。
 * 交互约束：
 * - 所有下拉都用 [DropdownMenu]，不做底部弹窗与大动画（方案 7.1）；
 * - 生效条件以 [AssistChip] 形式回显，可逐个移除，另提供「清除筛选」。
 */
@Composable
fun MistakeFilterBar(
    filter: MistakeFilter,
    subjectOptions: List<String>,
    kpointOptions: List<KpointOption>,
    onKeywordChange: (String) -> Unit,
    onSearchSubmit: () -> Unit,
    onSubjectChange: (String?) -> Unit,
    onKpointChange: (String?) -> Unit,
    onErrorTypeChange: (ErrorType?) -> Unit,
    onTimeWindowChange: (TimeWindow) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = filter.keyword,
            onValueChange = onKeywordChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(text = "搜索题干、答案或错因说明") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
            trailingIcon = {
                if (filter.keyword.isNotEmpty()) {
                    IconButton(onClick = { onKeywordChange("") }) {
                        Icon(Icons.Filled.Close, contentDescription = "清空搜索", modifier = Modifier.size(18.dp))
                    }
                }
            },
            keyboardOptions =
                androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = androidx.compose.ui.text.input.ImeAction.Search,
                ),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { onSearchSubmit() }),
        )

        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterPicker(
                label = "科目",
                selectedLabel = filter.subject?.let { subjectLabelOf(it) } ?: "全部",
                active = filter.subject != null,
                options = subjectOptions.map { it to subjectLabelOf(it) },
                onSelect = onSubjectChange,
            )
            FilterPicker(
                label = "知识点",
                selectedLabel =
                    filter.kpointId?.let { id -> kpointOptions.firstOrNull { it.id == id }?.name ?: id }
                        ?: "全部",
                active = filter.kpointId != null,
                options = kpointOptions.map { it.id to it.label() },
                onSelect = onKpointChange,
            )
            FilterPicker(
                label = "错因",
                selectedLabel = filter.errorType?.label ?: "全部",
                active = filter.errorType != null,
                options = ErrorType.FIXED.map { it.code to it.label },
                onSelect = { code -> onErrorTypeChange(code?.let { ErrorType.ofCode(it) }) },
            )
            FilterPicker(
                label = "时间",
                selectedLabel = filter.timeWindow.label,
                active = filter.timeWindow != TimeWindow.ALL,
                options = TimeWindow.entries.map { it.name to it.label },
                onSelect = { name ->
                    onTimeWindowChange(TimeWindow.entries.firstOrNull { it.name == name } ?: TimeWindow.ALL)
                },
            )
            if (filter.isActive) {
                TextButton(onClick = onClear) { Text(text = "清除筛选") }
            }
        }

        ActiveFilterChips(filter = filter, onClear = onClear, onKeywordCleared = { onKeywordChange("") })
    }
}

/**
 * 通用下拉筛选器：一个 FilterChip + 一个 DropdownMenu。
 *
 * @param options (值, 展示名) 列表；第一项固定为「全部」，对应值 null
 */
@Composable
private fun FilterPicker(
    label: String,
    selectedLabel: String,
    active: Boolean,
    options: List<Pair<String, String>>,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = active,
            onClick = { expanded = true },
            label = { Text(text = label + "：" + selectedLabel) },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(text = "全部") },
                onClick = {
                    expanded = false
                    onSelect(null)
                },
            )
            options.forEach { (value, text) ->
                DropdownMenuItem(
                    text = { Text(text = text) },
                    onClick = {
                        expanded = false
                        onSelect(value)
                    },
                )
            }
        }
    }
}

/** 生效条件回显。 */
@Composable
private fun ActiveFilterChips(
    filter: MistakeFilter,
    onClear: () -> Unit,
    onKeywordCleared: () -> Unit,
) {
    if (!filter.isActive) return
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "已选 " + filter.activeCount + " 个条件",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        filter.subject?.let { AssistChip(onClick = onClear, label = { Text(text = subjectLabelOf(it)) }) }
        filter.kpointId?.let { AssistChip(onClick = onClear, label = { Text(text = "知识点") }) }
        filter.errorType?.let { AssistChip(onClick = onClear, label = { Text(text = it.label) }) }
        if (filter.timeWindow != TimeWindow.ALL) {
            AssistChip(onClick = onClear, label = { Text(text = filter.timeWindow.label) })
        }
        if (filter.normalizedKeyword.isNotEmpty()) {
            AssistChip(
                onClick = onKeywordCleared,
                label = { Text(text = "\"" + filter.normalizedKeyword + "\"") },
            )
        }
    }
}

/**
 * 错题卡片（列表一行）。
 *
 * 版式（自上而下）：
 * 1. 首行：科目 · 相对日期 · 错因标签 · 复发徽标；
 * 2. 题干摘要（白色卡片，最多 60 字）；
 * 3. 知识点标签行；
 * 4. 右侧缩略图（存在时才占位，避免空图块）。
 *
 * 性能：缩略图由 Coil 异步解码并带内存/磁盘缓存，滚动时不做任何主线程 IO。
 */
@Composable
fun MistakeCard(
    card: MistakeCardUi,
    nowUtcMillis: Long,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        onClick = onClick,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = card.subjectLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "  ·  " + MistakeFormat.dayTimeLabel(card.createdAtUtc, nowUtcMillis),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box(modifier = Modifier.weight(1f))
                card.recurrenceBadge?.let { badge ->
                    RecurrenceBadge(text = badge)
                }
                IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = "更多操作",
                        modifier = Modifier.size(16.dp),
                    )
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text(text = "删除这道错题") },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        },
                    )
                }
            }

            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = card.stemSummary.ifBlank { "（题干为空，可能只保存了图片）" },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    if (card.kpointTags.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            card.kpointTags.forEach { tag -> KpointTag(text = tag) }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ErrorTypeTag(text = card.errorTypeLabel)
                        if (!card.hasLocatedErrorStep) {
                            Text(
                                text = "未定位到具体步骤",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (card.thumbnailPath != null) {
                    MistakeThumbnail(
                        path = card.thumbnailPath,
                        modifier =
                            Modifier
                                .padding(start = 10.dp)
                                .size(width = 64.dp, height = 64.dp),
                    )
                }
            }
        }
    }
}

/** 缩略图：只加载预处理阶段生成的小图，失败时显示占位图标。 */
@Composable
private fun MistakeThumbnail(
    path: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        coil.compose.AsyncImage(
            model = java.io.File(path),
            contentDescription = "错题原图缩略图",
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(1.dp),
        )
    }
}

/** 复发徽标：「第 N 次错」（FR-4.5）。 */
@Composable
fun RecurrenceBadge(
    text: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** 错因标签（固定七类的中文名）。 */
@Composable
fun ErrorTypeTag(
    text: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

/** 知识点标签。 */
@Composable
fun KpointTag(
    text: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
