package com.totop.app.ui.reviewmaterial

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.totop.app.ui.theme.Tokens

/*
 * 复习资料正文的共享渲染层。
 *
 * 从单页时代拆出来：列表页与详情页都要按同一套版式画正文，
 * 留一份实现才能保证「屏幕上看到的」和「导出 PDF 的」在拆页后仍是同一套层级。
 * 渲染规则与 [ReviewMaterialPdfWriter] 保持一致：段落、要点、编号步骤、例题。
 */

/** 一份资料的完整正文 + 导出操作区。列表页已不渲染正文，详情页独用。 */
@Composable
fun MaterialContent(
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
