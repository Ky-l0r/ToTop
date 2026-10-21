package com.totop.app.ui.reviewmaterial

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.totop.app.ui.theme.Tokens

/**
 * 考前针对性复习资料。
 *
 * 流程：选科目 + 考试日期 → AI 依据最近错题与还需要多练的知识点生成 →
 * 预览 → 导出/打印 A4。
 *
 * 失败一定有可读原因与重试；生成中显示进度，不留白屏。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewMaterialScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ReviewMaterialViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

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
            modifier = Modifier
                .fillMaxSize()
                .background(Tokens.Background)
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(12.dp))

            // 科目选择
            Text(
                text = "要复习哪一科？",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = Tokens.OnSurface,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.subjects.forEach { subject ->
                    FilterChip(
                        selected = subject == state.selectedSubject,
                        onClick = { viewModel.selectSubject(subject) },
                        label = { Text(subject) },
                    )
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
                        CircularProgressIndicator(modifier = Modifier.height(20.dp))
                        Spacer(Modifier.width(10.dp))
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
                        onExportPdf = viewModel::exportPdf,
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

@Composable
private fun HintCard(
    line1: String,
    line2: String,
) {
    Column(
        modifier = Modifier.fillMaxWidth()
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
    onExportPdf: () -> Unit,
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onExportPdf, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.PictureAsPdf, contentDescription = null)
                Text("  导出 A4 PDF")
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "PDF 按 A4 排版，可直接打印或分享到打印机 App。",
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
