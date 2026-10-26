package com.totop.app.ui.reviewmaterial

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Biotech
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Eco
import androidx.compose.material.icons.filled.HistoryEdu
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.totop.app.ui.theme.Tokens

/**
 * 考前针对性复习资料。
 *
 * 流程：选科目 → AI 依据最近错题与还需要多练的知识点生成 →
 * 预览 → 导出 A4 PDF → 打开 / 分享 / 打印。
 *
 * 失败一定有可读原因与重试；生成中显示进度，不留白屏。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ReviewMaterialScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ReviewMaterialViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val pendingIntent by viewModel.pendingIntent.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // 打开 / 分享：ViewModel 只产出 Intent，真正的 startActivity 必须在有 Context 的这一层做。
    LaunchedEffect(pendingIntent) {
        val intent = pendingIntent ?: return@LaunchedEffect
        runCatching { context.startActivity(intent) }
        viewModel.consumePendingIntent()
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("考前复习资料") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(Tokens.Background)
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(12.dp))

            // 科目选择改成三列的图标卡片网格。
            //
            // 旧实现是十个 FilterChip 一字排开（FlowRow 换行），问题是：
            // 1. chip 长短不一，换行后参差不齐，看着像一排标签而不是选项；
            // 2. 十科全是同一种灰色胶囊，选中的那一科只多一圈边框，扫一眼找不到；
            // 3. 每个 chip 太小，手指点下去常常点到隔壁。
            // 现在每科一张卡片：图标 + 科目名，选中整块变主色浅底并描边，
            // 三列等宽，字号一致，眼睛能按位置记住「数学在左上」。
            Text(
                text = "要复习哪一科？",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = Tokens.OnSurface,
            )
            Spacer(Modifier.height(10.dp))
            state.subjects.chunked(SUBJECT_COLUMNS).forEach { rowSubjects ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    rowSubjects.forEach { subject ->
                        SubjectTile(
                            label = subject,
                            selected = subject == state.selectedSubject,
                            onClick = { viewModel.selectSubject(subject) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    // 最后一行不满三列时补空位，避免剩下的卡片被拉伸成宽窄不一
                    repeat(SUBJECT_COLUMNS - rowSubjects.size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = viewModel::generate,
                enabled = state.materialState !is ReviewMaterialState.Generating,
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                Text(
                    when (state.materialState) {
                        is ReviewMaterialState.Generating -> "正在整理…"
                        is ReviewMaterialState.Success -> "重新生成一份"
                        else -> "生成复习资料"
                    },
                )
            }

            Spacer(Modifier.height(16.dp))

            when (val materialState = state.materialState) {
                is ReviewMaterialState.Idle -> {
                    HintCard(
                        "会根据你最近的错题和还需要多练的知识点来整理，",
                        "不需要联网翻题库，也不用你手动挑题。",
                    )
                }

                is ReviewMaterialState.Generating -> {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(10.dp))
                        Text(
                            text = "正在依据你的错题整理资料…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Tokens.OnSurfaceVariant,
                        )
                    }
                }

                is ReviewMaterialState.Failed -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Tokens.ErrorContainer),
                        shape = RoundedCornerShape(Tokens.RadiusMedium),
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text(
                                text = materialState.message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Tokens.Error,
                            )
                            if (materialState.retryable) {
                                Spacer(Modifier.height(6.dp))
                                OutlinedButton(onClick = viewModel::generate) { Text("重试") }
                            }
                        }
                    }
                }

                is ReviewMaterialState.Success -> {
                    MaterialContent(
                        material = materialState.material,
                        exporting = state.exporting,
                        exported = state.exportedFile,
                        onExportPdf = viewModel::exportPdf,
                        onOpen = viewModel::openExported,
                        onShare = viewModel::shareExported,
                    )
                }
            }

            state.message?.let { message ->
                Spacer(Modifier.height(12.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = Tokens.OnSurfaceVariant,
                )
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

/** 科目卡片网格的列数：三列在常见手机宽度下每格约 100dp，点得准也放得下字。 */
private const val SUBJECT_COLUMNS: Int = 3

/**
 * 一张科目卡片：图标 + 科目名，选中态用主色浅底 + 主色描边。
 *
 * 图标按科目语义挑选（数学用计算符号、语文用书本、英语用字母等），
 * 目的不是好看，是让十张卡片**形状不同**——纯文字十宫格扫起来很吃力。
 */
@Composable
private fun SubjectTile(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val background = if (selected) Tokens.PrimaryContainer else Tokens.Surface
    val border = if (selected) Tokens.Primary else Tokens.Outline
    val contentColor = if (selected) Tokens.Primary else Tokens.OnSurface
    Column(
        modifier =
            modifier
                .clip(RoundedCornerShape(Tokens.RadiusLarge))
                .background(background)
                .border(1.dp, border, RoundedCornerShape(Tokens.RadiusLarge))
                .clickable(onClick = onClick)
                .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = subjectIconOf(label),
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = contentColor,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        )
    }
}

/** 科目中文名 -> 图标。认不出的科目退回通用书本图标，不至于空一块。 */
private fun subjectIconOf(label: String): androidx.compose.ui.graphics.vector.ImageVector =
    when (label) {
        "语文" -> Icons.AutoMirrored.Filled.MenuBook
        "数学" -> Icons.Filled.Calculate
        "英语" -> Icons.Filled.Translate
        "物理" -> Icons.Filled.Science
        "化学" -> Icons.Filled.Biotech
        "生物" -> Icons.Filled.Eco
        "政治" -> Icons.Filled.AccountBalance
        "历史" -> Icons.Filled.HistoryEdu
        "地理" -> Icons.Filled.Public
        "技术" -> Icons.Filled.Computer
        else -> Icons.Filled.Book
    }

@Composable
private fun HintCard(
    line1: String,
    line2: String,
) {
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .background(Tokens.SurfaceVariant, RoundedCornerShape(Tokens.RadiusMedium))
                .padding(16.dp),
    ) {
        Text(line1, style = MaterialTheme.typography.bodyMedium, color = Tokens.OnSurface)
        Text(line2, style = MaterialTheme.typography.bodySmall, color = Tokens.OnSurfaceVariant)
    }
}

@Composable
private fun MaterialContent(
    material: ReviewMaterial,
    exporting: Boolean,
    exported: ExportArtifactInfo?,
    onExportPdf: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit,
) {
    Column {
        Text(
            text = material.subjectLabel + " · " + material.examDate,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = Tokens.OnSurface,
        )
        Spacer(Modifier.height(4.dp))
        Text(material.summary, style = MaterialTheme.typography.bodyMedium, color = Tokens.OnSurfaceVariant)

        SectionBlock("还需要多练的知识点") {
            material.weakKpoints.forEach { kp ->
                Text(
                    text = "· " + kp.name + "（错过 " + kp.mistakeCount + " 次）",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Tokens.OnSurface,
                )
                if (kp.reminder.isNotBlank()) {
                    Text(
                        text = "　" + kp.reminder,
                        style = MaterialTheme.typography.bodySmall,
                        color = Tokens.OnSurfaceVariant,
                    )
                }
            }
        }

        SectionBlock("典型错题") {
            material.typicalMistakes.forEachIndexed { index, item ->
                Text(
                    text = (index + 1).toString() + ". " + item.stem,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Tokens.OnSurface,
                )
                Text(
                    text = "错在哪：" + item.errorSummary,
                    style = MaterialTheme.typography.bodySmall,
                    color = Tokens.OnSurfaceVariant,
                )
                Text(
                    text = "正确思路：" + item.correctIdea,
                    style = MaterialTheme.typography.bodySmall,
                    color = Tokens.OnSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
            }
        }

        SectionBlock("同类练习") {
            material.exercises.forEachIndexed { index, item ->
                Text(
                    text = (index + 1).toString() + ". " + item.stem,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Tokens.OnSurface,
                )
                Text(
                    text = "答案：" + item.answer,
                    style = MaterialTheme.typography.bodySmall,
                    color = Tokens.OnSurfaceVariant,
                )
                Text(
                    text = "解析：" + item.analysis,
                    style = MaterialTheme.typography.bodySmall,
                    color = Tokens.OnSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
            }
        }

        SectionBlock("考前建议") {
            material.tips.forEach { tip ->
                Text("· " + tip, style = MaterialTheme.typography.bodyMedium, color = Tokens.OnSurface)
            }
        }

        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onExportPdf,
            enabled = !exporting,
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
            Icon(Icons.Filled.PictureAsPdf, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(if (exporting) "  正在生成 PDF…" else "  导出 A4 PDF")
        }

        // 导出成功后才出现「打开 / 分享」。这是修「找不到文件」的关键交互：
        // 旧版只打一行文件名，用户没有任何可点的出口。
        if (exported != null) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOpen, modifier = Modifier.weight(1f)) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Text("  打开")
                }
                OutlinedButton(onClick = onShare, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text("  分享 / 打印")
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text =
                "PDF 按 A4 排版，保存到手机的「下载/ToTop」文件夹；" +
                    "也可以点「分享 / 打印」直接发给打印机 App。",
            style = MaterialTheme.typography.labelSmall,
            color = Tokens.OnSurfaceVariant,
        )
    }
}

@Composable
private fun SectionBlock(
    title: String,
    content: @Composable () -> Unit,
) {
    Spacer(Modifier.height(16.dp))
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = Tokens.Primary,
    )
    Spacer(Modifier.height(6.dp))
    content()
}
