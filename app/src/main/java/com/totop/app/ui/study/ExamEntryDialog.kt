package com.totop.app.ui.study

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.totop.app.domain.model.ExamDefaults
import com.totop.app.domain.model.ExamKind
import com.totop.app.domain.model.ExamRecord
import com.totop.app.domain.model.ExamScore
import com.totop.app.domain.model.Subjects
import com.totop.app.ui.theme.Tokens

/** 一行科目草稿。 */
private data class RowState(
    val subject: String,
    val selected: Boolean,
    val score: String,
    val fullScore: String,
    val classRank: String,
    val gradeRank: String,
)

/**
 * 成绩录入 / 编辑弹窗。
 *
 * 形态是「横向表格 + 科目多选」：一次月考六科、每科四个数字，
 * 但排名默认收起，多数时候只需要填六个分数，一屏就能录完。
 *
 * 排名展开后单独成一张表，而不是五列挤一行——360dp 窄屏上五列会看不清。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExamEntryDialog(
    initial: ExamRecord?,
    subjects: List<String>,
    defaultFullScoreOf: (String) -> Double,
    defaultClassSize: Int?,
    defaultGradeSize: Int?,
    onDismiss: () -> Unit,
    onSave: (ExamRecord) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    val today = remember { todayLocalEpochDay() }
    val pool = remember(subjects) { subjects.ifEmpty { Subjects.ORDER.take(6) } }
    val existing = initial?.scores.orEmpty()

    var dayText by remember { mutableStateOf(formatFullDay(initial?.occurredOn ?: today)) }
    var kind by remember { mutableStateOf(initial?.kind ?: ExamKind.MINOR) }
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var classSizeText by remember { mutableStateOf((initial?.classSize ?: defaultClassSize)?.toString().orEmpty()) }
    var gradeSizeText by remember { mutableStateOf((initial?.gradeSize ?: defaultGradeSize)?.toString().orEmpty()) }
    var classRankText by remember { mutableStateOf(initial?.classRank?.toString().orEmpty()) }
    var gradeRankText by remember { mutableStateOf(initial?.gradeRank?.toString().orEmpty()) }
    var ranksExpanded by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    var rows by remember {
        mutableStateOf(
            pool.map { subject ->
                val score = existing.firstOrNull { it.subject == subject }
                RowState(
                    subject = subject,
                    selected = score != null,
                    score = score?.score?.toInputText().orEmpty(),
                    fullScore = (score?.fullScore ?: defaultFullScoreOf(subject)).toInputText(),
                    classRank = score?.classRank?.toString().orEmpty(),
                    gradeRank = score?.gradeRank?.toString().orEmpty(),
                )
            },
        )
    }

    val selectedRows = rows.filter { it.selected }

    fun submit() {
        val day = parseFullDay(dayText)
        if (day == null) {
            localError = "日期格式应为 2026-10-15"
            return
        }
        val picked = rows.filter { it.selected }
        if (picked.isEmpty()) {
            localError = "先勾选这次考了哪几科"
            return
        }
        val scores = ArrayList<ExamScore>(picked.size)
        picked.forEach { row ->
            val value = row.score.toDoubleOrNull()
            if (value == null) {
                localError = Subjects.label(row.subject) + " 还没填得分"
                return
            }
            val full = row.fullScore.toDoubleOrNull() ?: defaultFullScoreOf(row.subject)
            scores.add(
                ExamScore(
                    subject = row.subject,
                    score = value,
                    fullScore = full,
                    gradeRank = row.gradeRank.toIntOrNull(),
                    classRank = row.classRank.toIntOrNull(),
                ),
            )
        }
        val multi = picked.size > 1
        val record =
            ExamRecord(
                id = initial?.id ?: ("exam-" + System.currentTimeMillis()),
                occurredOn = day,
                kind = kind,
                name = name.trim().takeIf { it.isNotEmpty() },
                gradeSize = gradeSizeText.toIntOrNull(),
                classSize = classSizeText.toIntOrNull(),
                gradeRank = if (multi) gradeRankText.toIntOrNull() else null,
                classRank = if (multi) classRankText.toIntOrNull() else null,
                scores = scores,
                createdAtUtc = initial?.createdAtUtc ?: 0L,
            )
        val errors = ExamDefaults.validate(record)
        if (errors.isNotEmpty()) {
            localError = errors.first()
            return
        }
        onSave(record)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 20.dp),
            shape = RoundedCornerShape(Tokens.RadiusLarge),
            color = Tokens.Background,
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (initial == null) "录入成绩" else "修改成绩",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    if (initial != null && onDelete != null) {
                        TextButton(onClick = { confirmDelete = true }) {
                            Text(text = "删除", color = Tokens.Error)
                        }
                    }
                }

                Column(
                    modifier =
                        Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Column(modifier = Modifier.weight(1f)) {
                            FieldLabel("日期")
                            CellInput(
                                value = dayText,
                                onValueChange = {
                                    dayText = it
                                    localError = null
                                },
                                placeholder = "2026-10-15",
                                numeric = false,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Column {
                            FieldLabel("类型")
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                ExamKind.entries.forEach { candidate ->
                                    FilterChip(
                                        selected = kind == candidate,
                                        onClick = { kind = candidate },
                                        label = { Text(text = candidate.label) },
                                    )
                                }
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        QuickDay(text = "今天") { dayText = formatFullDay(today) }
                        QuickDay(text = "昨天") { dayText = formatFullDay(today - 1) }
                    }

                    Column {
                        FieldLabel("名称（可不填）")
                        CellInput(
                            value = name,
                            onValueChange = { if (it.length <= 16) name = it },
                            placeholder = "10月月考",
                            numeric = false,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    FieldLabel("本次考的科目")
                    // 科目最多十科，用 FlowRow 自动换行；用固定 Row 会横向溢出把后几科挤出屏幕
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        pool.forEach { subject ->
                            val row = rows.first { it.subject == subject }
                            FilterChip(
                                selected = row.selected,
                                onClick = {
                                    rows =
                                        rows.map {
                                            if (it.subject == subject) it.copy(selected = !it.selected) else it
                                        }
                                    localError = null
                                },
                                label = { Text(text = Subjects.label(subject)) },
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.Bottom) {
                        Column(modifier = Modifier.weight(1f)) {
                            FieldLabel("班级人数")
                            CellInput(
                                value = classSizeText,
                                onValueChange = { classSizeText = it },
                                placeholder = "45",
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            FieldLabel("年级人数")
                            CellInput(
                                value = gradeSizeText,
                                onValueChange = { gradeSizeText = it },
                                placeholder = "480",
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }

                    if (selectedRows.isEmpty()) {
                        Text(
                            text = "先勾选这次考了哪几科。",
                            style = MaterialTheme.typography.bodySmall,
                            color = Tokens.OnSurfaceVariant,
                        )
                    } else {
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            TableHead("科目", 0.9f)
                            TableHead("得分", 1f)
                            TableHead("满分", 1f)
                        }
                        selectedRows.forEach { row ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = Subjects.label(row.subject),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Tokens.OnSurface,
                                    modifier = Modifier.weight(0.9f),
                                )
                                CellInput(
                                    value = row.score,
                                    onValueChange = { input ->
                                        rows = rows.patch(row.subject) { it.copy(score = input) }
                                        localError = null
                                    },
                                    placeholder = "112",
                                    modifier = Modifier.weight(1f).padding(end = 6.dp),
                                )
                                CellInput(
                                    value = row.fullScore,
                                    onValueChange = {
                                            input ->
                                        rows = rows.patch(row.subject) { it.copy(fullScore = input) }
                                    },
                                    placeholder = "150",
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }

                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { ranksExpanded = !ranksExpanded }
                                    .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = (if (ranksExpanded) "▾ " else "▸ ") + "排名（选填）",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Tokens.Primary,
                            )
                        }

                        if (ranksExpanded) {
                            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                TableHead("科目", 0.9f)
                                TableHead("班级排名", 1f)
                                TableHead("年级排名", 1f)
                            }
                            selectedRows.forEach { row ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = Subjects.label(row.subject),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Tokens.OnSurface,
                                        modifier = Modifier.weight(0.9f),
                                    )
                                    CellInput(
                                        value = row.classRank,
                                        onValueChange = {
                                                input ->
                                            rows = rows.patch(row.subject) { it.copy(classRank = input) }
                                        },
                                        placeholder = "3",
                                        modifier = Modifier.weight(1f).padding(end = 6.dp),
                                    )
                                    CellInput(
                                        value = row.gradeRank,
                                        onValueChange = {
                                                input ->
                                            rows = rows.patch(row.subject) { it.copy(gradeRank = input) }
                                        },
                                        placeholder = "18",
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                            if (selectedRows.size > 1) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = "总分",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        color = Tokens.OnSurface,
                                        modifier = Modifier.weight(0.9f),
                                    )
                                    CellInput(
                                        value = classRankText,
                                        onValueChange = { classRankText = it },
                                        placeholder = "8",
                                        modifier = Modifier.weight(1f).padding(end = 6.dp),
                                    )
                                    CellInput(
                                        value = gradeRankText,
                                        onValueChange = { gradeRankText = it },
                                        placeholder = "56",
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }
                    }

                    localError?.let { message ->
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = Tokens.Error,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text(text = "取消") }
                    Button(onClick = { submit() }, modifier = Modifier.weight(1f)) { Text(text = "保存") }
                }
            }
        }
    }

    if (confirmDelete && initial != null && onDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(text = "删除这次考试？") },
            text = {
                Text(
                    text = "将同时删除这条考试记录下的 " + existing.size + " 条科目成绩，删除后无法恢复。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDelete()
                    },
                ) { Text(text = "删除", color = Tokens.Error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(text = "取消") }
            },
        )
    }
}

/** 小标题。 */
@Composable
private fun FieldLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = Tokens.OnSurfaceVariant,
        modifier = Modifier.padding(bottom = 2.dp),
    )
}

/** 表头单元格（无边框，只做对齐）。 */
@Composable
private fun androidx.compose.foundation.layout.RowScope.TableHead(
    text: String,
    weight: Float,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = Tokens.OnSurfaceVariant,
        modifier = Modifier.weight(weight),
    )
}

/** 一格输入框：自绘边框，比 OutlinedTextField 矮一半，六行才放得下。 */
@Composable
private fun CellInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    numeric: Boolean = true,
) {
    Box(
        modifier =
            modifier
                .height(38.dp)
                .background(Tokens.SurfaceVariant, RoundedCornerShape(Tokens.RadiusSmall))
                .border(1.dp, Tokens.Outline, RoundedCornerShape(Tokens.RadiusSmall))
                .padding(horizontal = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) {
            Text(
                text = placeholder,
                style = MaterialTheme.typography.bodyMedium,
                color = Tokens.OnSurfaceVariant.copy(alpha = 0.55f),
            )
        }
        BasicTextField(
            value = value,
            onValueChange = { raw ->
                val cleaned = if (numeric) filterNumber(raw) else raw
                if (cleaned.length <= 7) onValueChange(cleaned)
            },
            singleLine = true,
            textStyle = TextStyle(color = Tokens.OnSurface, fontSize = MaterialTheme.typography.bodyMedium.fontSize),
            keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 日期快捷项。 */
@Composable
private fun QuickDay(
    text: String,
    onClick: () -> Unit,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = Tokens.Primary,
        modifier =
            Modifier
                .background(Tokens.PrimaryContainer, RoundedCornerShape(50))
                .clickable(onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** 只保留数字与一个小数点。 */
private fun filterNumber(raw: String): String {
    val builder = StringBuilder(raw.length)
    var dotUsed = false
    raw.forEach { ch ->
        when {
            ch.isDigit() -> builder.append(ch)
            (ch == '.' || ch == '。') && !dotUsed -> {
                builder.append('.')
                dotUsed = true
            }
        }
    }
    return builder.toString()
}

/** 更新某一行。 */
private fun List<RowState>.patch(
    subject: String,
    transform: (RowState) -> RowState,
): List<RowState> = map { if (it.subject == subject) transform(it) else it }

/** Double -> 输入框文本：整数不带「.0」。 */
private fun Double.toInputText(): String = if (this == Math.floor(this)) this.toLong().toString() else this.toString()
