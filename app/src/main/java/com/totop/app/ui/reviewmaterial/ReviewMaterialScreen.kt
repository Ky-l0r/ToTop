package com.totop.app.ui.reviewmaterial

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Eco
import androidx.compose.material.icons.filled.HistoryEdu
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
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

            // 以往的复习资料：退出页面再进来，上次生成的那份仍然在这里。
            HistorySection(
                items = state.history,
                loading = state.historyLoading,
                onOpen = viewModel::openHistory,
                onDelete = viewModel::deleteHistory,
            )

            Spacer(Modifier.height(16.dp))

            when (val materialState = state.materialState) {
                is ReviewMaterialState.Idle -> {
                    HintCard(
                        "会先把你错过的题归类归因，再往下归纳知识点与下手方法，",
                        "并补齐这一科的高频易错点、答题模板与考前建议；导出自动压到 " +
                            ReviewMaterialPdfWriter.MAX_PAGES + " 页以内，双面打印最多 " +
                            (ReviewMaterialPdfWriter.MAX_PAGES / 2) + " 张纸。",
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
 * 以往的复习资料。
 *
 * 为什么单独一块而不是塞进「历史记录」页：学生的动作是「考前冲刺时打开这一页」，
 * 上次整理的那份就在同一个位置，点一下直接预览、再导出，不用再找入口。
 */
@Composable
private fun HistorySection(
    items: List<ReviewMaterialSummary>,
    loading: Boolean,
    onOpen: (Long) -> Unit,
    onDelete: (Long) -> Unit,
) {
    // 删除前二次确认：资料本身能重新生成，但删错了会以为自己丢过东西
    var pendingDelete by remember { mutableStateOf<ReviewMaterialSummary?>(null) }

    Column(
        modifier =
            Modifier.fillMaxWidth()
                .background(Tokens.SurfaceVariant, RoundedCornerShape(Tokens.RadiusMedium))
                .padding(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "以往的复习资料",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = Tokens.OnSurface,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = if (items.isEmpty()) "还没有" else items.size.toString() + " 份",
                style = MaterialTheme.typography.labelSmall,
                color = Tokens.OnSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))

        when {
            loading && items.isEmpty() -> {
                Text(
                    text = "正在读取…",
                    style = MaterialTheme.typography.bodySmall,
                    color = Tokens.OnSurfaceVariant,
                )
            }

            items.isEmpty() -> {
                Text(
                    text = "生成一份之后会自动存在这里，退出再进也还在，方便回头对照以前的错题。",
                    style = MaterialTheme.typography.bodySmall,
                    color = Tokens.OnSurfaceVariant,
                )
            }

            else -> {
                items.forEach { item ->
                    HistoryRow(
                        item = item,
                        onOpen = { onOpen(item.createdAtUtc) },
                        onDelete = { pendingDelete = item },
                    )
                }
            }
        }
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除这份资料？") },
            text = {
                Text(
                    target.subjectLabel + " · " + target.dateLabel + " 的这份复习资料会从本机删掉，" +
                        "删除后只能重新生成。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(target.createdAtUtc)
                        pendingDelete = null
                    },
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

/** 一条历史记录：科目 + 时间 + 篇幅，右侧删除。 */
@Composable
private fun HistoryRow(
    item: ReviewMaterialSummary,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    val meta =
        buildString {
            append("约 ").append(item.charCount).append(" 字 · ").append(item.sectionCount).append(" 章")
            if (item.mistakesUsed > 0) append(" · ").append(item.mistakesUsed).append(" 道错题")
        }
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(Tokens.RadiusSmall))
                .clickable(onClick = onOpen)
                .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = item.subjectLabel + " · " + item.dateLabel,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = Tokens.OnSurface,
            )
            Text(
                text = meta,
                style = MaterialTheme.typography.labelSmall,
                color = Tokens.OnSurfaceVariant,
            )
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = "删除这份资料",
                tint = Tokens.OnSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

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
            fontWeight = FontWeight.Bold,
            color = Tokens.Primary,
        )
        Spacer(Modifier.height(3.dp))
        // 总述块：左侧一条主色竖线 + 主色文字，把「这份资料怎么用」和正文分开
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Box(
                modifier =
                    Modifier
                        .padding(end = 9.dp)
                        .width(2.5.dp)
                        .fillMaxHeight()
                        .background(Tokens.Primary, RoundedCornerShape(2.dp)),
            )
            ReviewRichText(
                material.summary,
                MaterialTheme.typography.bodyLarge,
                Tokens.Primary,
                Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = materialSourceNote(material),
            style = MaterialTheme.typography.labelSmall,
            color = Tokens.OnSurfaceVariant,
        )

        material.sections.forEachIndexed { index, section ->
            SectionBlock(ReviewText.sectionHeading(index + 1, section.title)) {
                section.blocks.forEach { block -> BlockContent(block) }
                section.subsections.forEachIndexed { subIndex, subsection ->
                    SubSectionBlock(ReviewText.subsectionHeading(subIndex + 1, subsection.title)) {
                        subsection.blocks.forEach { block -> BlockContent(block) }
                    }
                }
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
                "PDF 按 A4 排版，自动压到 " + ReviewMaterialPdfWriter.MAX_PAGES + " 页以内" +
                    "（双面打印最多 " + ReviewMaterialPdfWriter.MAX_PAGES / 2 + " 张），" +
                    "保存到手机的「下载/ToTop」文件夹；也可以点「分享 / 打印」直接发给打印机 App。",
            style = MaterialTheme.typography.labelSmall,
            color = Tokens.OnSurfaceVariant,
        )
    }
}

/**
 * 卷首说明：资料从哪来、多长。与 PDF 上那行小字保持同一口径。
 *
 * 「共 N 道错题 / 纳入 M 道」分开写：只报 M 会让人以为错题本里只有这么几道。
 */
private fun materialSourceNote(material: ReviewMaterial): String {
    val origin =
        when {
            material.mistakesUsed <= 0 -> "本科目暂无错题记录，按通用考点整理"
            material.mistakesAvailable > material.mistakesUsed ->
                "取材：本科目共 " + material.mistakesAvailable + " 道错题，纳入最近 " + material.mistakesUsed + " 道"
            else -> "取材：本科目 " + material.mistakesUsed + " 道错题"
        }
    return origin + " · 全文约 " + material.charCount + " 字 · " + material.sections.size + " 章"
}

/** 一个内容块的界面渲染，规则与 PDF 一致：段落、要点、编号步骤、例题。 */
@Composable
private fun BlockContent(block: ReviewBlock) {
    when (block.style) {
        // 段落的多行本来就是同一段（提示词里写的是「paragraph 的 lines 会被连成一段」）。
        // 旧实现每行各起一段，一句完整的话被切成两三块，屏幕上读起来又碎又长。
        ReviewBlockStyle.PARAGRAPH -> {
            val segments = remember(block) { ReviewText.paragraphSegments(block.lines) }
            segments.forEachIndexed { index, segment ->
                if (index > 0) Spacer(Modifier.height(PARAGRAPH_GAP))
                ParagraphLine(segment)
            }
        }

        ReviewBlockStyle.BULLETS ->
            block.lines.forEach { line ->
                BulletLine(line)
                Spacer(Modifier.height(ITEM_GAP))
            }

        ReviewBlockStyle.NUMBERED ->
            block.lines.forEachIndexed { index, line ->
                NumberedLine(index + 1, line)
                Spacer(Modifier.height(ITEM_GAP))
            }

        ReviewBlockStyle.EXAMPLE -> ExampleBlock(block.lines)
    }
    Spacer(Modifier.height(BLOCK_GAP))
}

/** 一条无序要点：主色小方块 + 悬挂缩进的正文。 */
@Composable
private fun BulletLine(line: String) {
    Row(Modifier.fillMaxWidth()) {
        Box(
            modifier =
                Modifier
                    .padding(top = BULLET_TOP_PADDING, end = 8.dp)
                    .size(4.dp)
                    .background(Tokens.Primary, RoundedCornerShape(2.dp)),
        )
        TaggedLine(line, Modifier.weight(1f))
    }
}

/** 一条编号步骤：主色序号 + 悬挂缩进的正文。 */
@Composable
private fun NumberedLine(
    number: Int,
    line: String,
) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            text = number.toString() + ".",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            color = Tokens.Primary,
            modifier = Modifier.padding(end = 8.dp),
        )
        TaggedLine(line, Modifier.weight(1f))
    }
}

/**
 * 段落里带「易错 / 注意 / 必背」的行：先给一个浅底色小标签，再写正文。
 *
 * 这些词是模型自己标出来的重点。当作普通两个字印在句首时，学生在纸上根本抓不住；
 * 加一个色块标签，扫一眼就知道这一条是「会翻车的地方」。
 */
@Composable
private fun ParagraphLine(line: String) {
    val tag = ReviewText.tagOf(line)
    if (tag == null) {
        ReviewRichText(line, MaterialTheme.typography.bodyLarge, Tokens.OnSurface)
        return
    }
    val body = line.trimStart().removePrefix(tag).trimStart('：', ':', ' ')
    Row(Modifier.fillMaxWidth()) {
        TagChip(tag)
        ReviewRichText(body, MaterialTheme.typography.bodyLarge, Tokens.OnSurface, Modifier.weight(1f))
    }
}

/** 带标签的行（要点 / 编号步骤共用）：标签在上文占一个色块，正文照常折行。 */
@Composable
private fun TaggedLine(
    line: String,
    modifier: Modifier = Modifier,
) {
    val tag = ReviewText.tagOf(line)
    if (tag == null) {
        ReviewRichText(line, MaterialTheme.typography.bodyLarge, Tokens.OnSurface, modifier)
        return
    }
    val body = line.trimStart().removePrefix(tag).trimStart('：', ':', ' ')
    Row(modifier) {
        TagChip(tag)
        ReviewRichText(body, MaterialTheme.typography.bodyLarge, Tokens.OnSurface, Modifier.weight(1f))
    }
}

@Composable
private fun TagChip(tag: String) {
    Text(
        text = tag,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = Tokens.Warning,
        modifier =
            Modifier
                .padding(end = 6.dp, top = TAG_TOP_PADDING)
                .background(Tokens.WarningContainer, RoundedCornerShape(3.dp))
                .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/**
 * 例题：题干 → 选项（一行一个）→ 思路 → 答案 → 详解。
 *
 * 左侧一条赭石竖线把整道题圈在一起；答案与详解排在虚线之后，
 * 并且答案用成功色、详解用灰色小字——先自己做，做完再往下看。
 */
@Composable
private fun ExampleBlock(lines: List<String>) {
    if (lines.isEmpty()) return
    val answer = lines.firstOrNull { ReviewText.isAnswerLine(it) }?.substringAfter('：', "").orEmpty().trim()
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(
            modifier =
                Modifier
                    .padding(end = 10.dp)
                    .width(2.5.dp)
                    .fillMaxHeight()
                    .background(Tokens.Warning, RoundedCornerShape(2.dp)),
        )
        Column(Modifier.weight(1f)) {
            Row(Modifier.fillMaxWidth()) {
                Text(
                    text = "例：",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = Tokens.Warning,
                )
                ReviewRichText(
                    lines.first(),
                    MaterialTheme.typography.bodyLarge,
                    Tokens.OnSurface,
                    Modifier.weight(1f),
                )
            }
            lines.drop(1).forEach { raw ->
                val line = raw.trim()
                if (line.isEmpty()) return@forEach
                when {
                    ReviewText.isOptionLine(line) -> OptionRow(line)
                    ReviewText.isHintLine(line) ->
                        ReviewRichText(
                            ReviewText.stripAnswerLeak(line, answer),
                            MaterialTheme.typography.bodyLarge,
                            Tokens.Reasoning,
                            Modifier.padding(top = 4.dp),
                        )

                    ReviewText.isAnswerLine(line) -> {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 6.dp),
                            thickness = 0.7.dp,
                            color = Tokens.Outline,
                        )
                        ReviewRichText(
                            line,
                            MaterialTheme.typography.bodySmall,
                            Tokens.Success,
                            Modifier.fillMaxWidth(),
                            bold = true,
                        )
                    }

                    ReviewText.isDetailLine(line) ->
                        ReviewRichText(
                            line,
                            MaterialTheme.typography.bodySmall,
                            Tokens.OnSurfaceVariant,
                            Modifier.padding(top = 3.dp),
                        )

                    else ->
                        ReviewRichText(
                            line,
                            MaterialTheme.typography.bodyLarge,
                            Tokens.OnSurface,
                            Modifier.padding(top = 3.dp),
                        )
                }
                Spacer(Modifier.height(2.dp))
            }
        }
    }
}

/** 一个选项占一行：字母用主色加粗，正文悬挂缩进。 */
@Composable
private fun OptionRow(line: String) {
    val letter = ReviewText.optionLetter(line).orEmpty()
    val body = line.replaceFirst(Regex("^[（(]?[A-Da-dＡ-Ｄ][)）.、．:：]\\s*"), "").trim()
    Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
        Text(
            text = if (letter.isEmpty()) "" else letter + ".",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            color = Tokens.Primary,
            modifier = Modifier.width(22.dp),
        )
        ReviewRichText(body, MaterialTheme.typography.bodyLarge, Tokens.OnSurface, Modifier.weight(1f))
    }
}

/**
 * 章节标题：主色序号 + 墨色标题 + 一条淡线。
 *
 * 序号与标题分色，是为了让「现在是第几章」比「这一章叫什么」更快被眼睛抓到——
 * 打印后翻页找章节靠的就是这一列序号。
 */
@Composable
private fun SectionBlock(
    title: String,
    content: @Composable () -> Unit,
) {
    Spacer(Modifier.height(SECTION_GAP))
    val number = title.substringBefore('、', "")
    val rest = if (number.isEmpty()) title else title.substringAfter('、', title)
    val styled =
        remember(title) {
            buildAnnotatedString {
                if (number.isNotEmpty()) {
                    withStyle(SpanStyle(color = Tokens.Primary, fontWeight = FontWeight.Bold)) {
                        append(number)
                        append("、")
                    }
                }
                append(rest)
            }
        }
    Text(
        text = styled,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = Tokens.OnSurface,
    )
    Spacer(Modifier.height(5.dp))
    HorizontalDivider(thickness = 0.8.dp, color = Tokens.Outline)
    Spacer(Modifier.height(7.dp))
    content()
}

/**
 * 小节标题：主色「（一）」+ 墨色标题，比章节标题小一号、不带分割线。
 *
 * 与 PDF 保持同一套层级：章节有分割线，小节只有序号换色——
 * 学生在屏幕上记住的层级，打印出来还是那一套。
 */
@Composable
private fun SubSectionBlock(
    title: String,
    content: @Composable () -> Unit,
) {
    Spacer(Modifier.height(SUB_SECTION_GAP))
    val close = title.indexOf('）')
    val hasLabel = title.startsWith("（") && close > 0
    val label = if (hasLabel) title.substring(0, close + 1) else ""
    val rest = if (hasLabel) title.substring(close + 1) else title
    val styled =
        remember(title) {
            buildAnnotatedString {
                if (label.isNotEmpty()) {
                    withStyle(SpanStyle(color = Tokens.Primary, fontWeight = FontWeight.Bold)) {
                        append(label)
                    }
                }
                append(rest)
            }
        }
    Text(
        text = styled,
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.Bold,
        color = Tokens.OnSurface,
    )
    Spacer(Modifier.height(3.dp))
    content()
}

// ---------- 版式间距 ----------
//
// 这份资料一次要读七八千字，间距不是审美问题而是「能不能读完」的问题：
// 章节之间要留够，让眼睛知道翻篇了；同一条列表里的两条要点要贴紧，
// 否则四到八条要点会被排成八个互不相干的段落。

/** 章节之间的留白。 */
private val SECTION_GAP = 16.dp

/** 小节之间的留白：介于章节与内容块之间。 */
private val SUB_SECTION_GAP = 11.dp

/** 内容块之间的留白：比条目间距大一级。 */
private val BLOCK_GAP = 8.dp

/** 同一条列表里两条要点之间。 */
private val ITEM_GAP = 5.dp

/** 段落内部拆出的两个自然段之间。 */
private val PARAGRAPH_GAP = 7.dp

/** 要点小方块的下沉量：落在首行字的中腰，而不是贴在字顶。 */
private val BULLET_TOP_PADDING = 10.dp

/** 「易错 / 注意」标签的下沉量。 */
private val TAG_TOP_PADDING = 2.dp
