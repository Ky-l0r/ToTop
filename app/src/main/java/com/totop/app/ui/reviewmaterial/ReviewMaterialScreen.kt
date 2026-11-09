package com.totop.app.ui.reviewmaterial

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Biotech
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Eco
import androidx.compose.material.icons.filled.HistoryEdu
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.totop.app.ui.theme.Tokens

/**
 * 考前针对性复习资料（列表 / 生成页）。
 *
 * 拆页后的职责：选科目 → 生成 → 管理历史。正文与 PDF 导出在详情页
 * （[ReviewMaterialDetailScreen]）——正文一次七八千字，以前塞在历史列表下方，
 * 学生每进来一次都要滑很久；现在点历史即进详情，列表保持一屏。
 *
 * 失败一定有可读原因与重试；生成中显示进度，不留白屏。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewMaterialScreen(
    onBack: () -> Unit,
    onOpenDetail: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ReviewMaterialViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // 生成成功后的一次性跳转：事件由 Channel 发出，旋转重建不会重放
    LaunchedEffect(viewModel) {
        viewModel.openDetail.collect { createdAtUtc -> onOpenDetail(createdAtUtc) }
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

            // 以往的复习资料：点一条进详情页看正文与导出，列表只留摘要
            HistorySection(
                items = state.history,
                loading = state.historyLoading,
                onOpen = onOpenDetail,
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

                // 正文在详情页渲染；这里只保留按钮文案「重新生成一份」的状态语义
                is ReviewMaterialState.Success -> Unit
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
 * 上次整理的那份就在同一个位置，点一下直接进详情，不用再找入口。
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
