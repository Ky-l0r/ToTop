package com.totop.app.ui.mistakes

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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

        // 筛选条件改为**一行一项**的下拉，不再横向滚动叠放。
        //
        // 真机反馈的原话是「最好让用户一个一个条件进行筛选，不然我点一下知识点
        // 就会出来一堆东西」。横向滚动 + 四个 chip 挤在一起的问题是：
        // 手指点哪个都像点到了一半，下拉菜单又没有分组，一打开就是一长串。
        // 现在每个条件独占一行（标签在左、当前值在右），点开的菜单也只在
        // 当前条件下展开——一眼就能看出「我现在在筛什么」。
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            FilterRow(
                label = "科目",
                valueLabel = filter.subject?.let { subjectLabelOf(it) } ?: "全部科目",
                active = filter.subject != null,
                options = subjectOptions.map { it to subjectLabelOf(it) },
                onSelect = onSubjectChange,
            )
            FilterRow(
                label = "知识点",
                valueLabel =
                    filter.kpointId?.let { id -> kpointOptions.firstOrNull { it.id == id }?.name ?: id }
                        ?: if (filter.subject == null) "全部知识点" else "本科目的全部知识点",
                active = filter.kpointId != null,
                options = kpointOptions.map { it.id to it.label() },
                onSelect = onKpointChange,
                // 知识点候选已按科目收敛，这里说明一下，避免用户以为漏了内容
                hint =
                    if (filter.subject == null) {
                        "先选科目可以缩小范围"
                    } else {
                        "只显示「" + subjectLabelOf(filter.subject) + "」的知识点"
                    },
            )
            FilterRow(
                label = "错因",
                valueLabel = filter.errorType?.label ?: "全部错因",
                active = filter.errorType != null,
                options = ErrorType.FIXED.map { it.code to it.label },
                onSelect = { code -> onErrorTypeChange(code?.let { ErrorType.ofCode(it) }) },
            )
            FilterRow(
                label = "时间",
                valueLabel = filter.timeWindow.label,
                active = filter.timeWindow != TimeWindow.ALL,
                options = TimeWindow.entries.map { it.name to it.label },
                onSelect = { name ->
                    onTimeWindowChange(TimeWindow.entries.firstOrNull { it.name == name } ?: TimeWindow.ALL)
                },
            )
        }

        ActiveFilterChips(filter = filter, onClear = onClear, onKeywordCleared = { onKeywordChange("") })
    }
}

/**
 * 一行筛选器：左侧固定标签，右侧当前值 + 下拉箭头，点整行展开菜单。
 *
 * @param options (值, 展示名) 列表；菜单第一项固定为「全部」，对应值 null
 * @param hint 可选的一行说明，用于解释「这里的候选为什么减少了」
 */
@Composable
private fun FilterRow(
    label: String,
    valueLabel: String,
    active: Boolean,
    options: List<Pair<String, String>>,
    onSelect: (String?) -> Unit,
    hint: String? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                    .clickable { expanded = true }
                    .padding(horizontal = 10.dp, vertical = 9.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(56.dp),
                )
                Text(
                    text = valueLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color =
                        if (active) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = Icons.Filled.ArrowDropDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            if (hint != null && active.not()) {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 56.dp, top = 1.dp),
                )
            }
        }
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
                // 删除收进三点菜单。
                //
                // 这是真机反馈明确指出的问题：以前「删除这道错题」是一个悬在
                // 卡片左上方的独立浮层，既不像按钮也不属于任何一组操作，
                // 还容易被误触（删错题是不可撤销的）。
                // 现在它是三点菜单里的一项，需要两步才能删掉，误触概率降到很低。
                Box {
                    IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(28.dp)) {
                        Icon(
                            imageVector = Icons.Filled.MoreVert,
                            contentDescription = "更多操作",
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text(text = "打开回顾") },
                            onClick = {
                                menuExpanded = false
                                onClick()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(text = "删除这道错题", color = MaterialTheme.colorScheme.error) },
                            onClick = {
                                menuExpanded = false
                                onDelete()
                            },
                        )
                    }
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
