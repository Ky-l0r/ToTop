package com.totop.app.ui.library

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.totop.app.domain.model.KpointMethod
import com.totop.app.domain.model.LearnedMaterial
import com.totop.app.domain.model.Subjects
import com.totop.app.ui.components.SurfaceCard
import com.totop.app.ui.theme.Tokens
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 资料库：学生攒下来的解析。
 *
 * 两条克制：一是不做搜索/筛选（条目本来就少，加了只是负担）；
 * 二是每条都显示来源与时间——这份东西可能来自作业帮，可能是错的，
 * 界面必须一直提醒「这不是你自己做的」。
 *
 * 0.6.2 起卡片可点开：详情里能看**当初那张截图**与完整提取内容（方法、步骤、结论、易错）。
 * 此前列表只显示标题与一句摘要，学生「存了但看不到存的是什么」，等于只能删。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var opened by remember { mutableStateOf<LearnedMaterial?>(null) }
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
                        if (current.methods.isNotEmpty()) {
                            item { SectionTitle("按知识点归纳的通用解法", current.methods.size) }
                            items(current.methods, key = { "method-" + it.id }) { method ->
                                MethodCard(item = method, onDelete = { viewModel.deleteMethod(method.id) })
                            }
                        }
                        if (current.items.isNotEmpty()) {
                            item {
                                SectionTitle(
                                    title = if (current.methods.isEmpty()) "我收的解析" else "我收的解析（原文）",
                                    count = current.items.size,
                                )
                            }
                            items(current.items, key = { it.id }) { item ->
                                MaterialCard(
                                    item = item,
                                    onOpen = { opened = item },
                                    onDelete = { viewModel.delete(item.id) },
                                )
                            }
                        }
                    }
            }
        }
    }

    opened?.let { item ->
        MaterialDetailDialog(item = item, onDismiss = { opened = null })
    }
}

@Composable
private fun LibraryHint() {
    Text(
        text =
            "上面那些「通用解法」是自动攒的：每次解题会把方法和步骤按知识点归纳成一张卡，" +
                "同类题做得越多，卡片越接近这类题的标准路径，解题时会挑两张给 AI 参考。" +
                "下面是你从别的渠道存进来的解析原文，点开能看截图与当时提取的内容；" +
                "它们没有经过校验，AI 只用来对齐思路，不照抄结论。",
        style = MaterialTheme.typography.bodySmall,
        color = Tokens.OnSurfaceVariant,
    )
}

/** 分组标题：带条数，避免学生以为「只有这几条」。 */
@Composable
private fun SectionTitle(
    title: String,
    count: Int,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = Tokens.OnSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = count.toString() + " 条",
            style = MaterialTheme.typography.labelSmall,
            color = Tokens.OnSurfaceVariant,
        )
    }
}

/**
 * 一张通用解法卡。
 *
 * 只展示骨架（前几步 + 适用条件 + 被验证次数），不展开全文：
 * 卡片是用来「确认这个知识点我有没有一条靠谱的路」的，不是用来读解析的。
 */
@Composable
private fun MethodCard(
    item: KpointMethod,
    onDelete: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Tokens.Surface)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = item.kpointName + " · " + item.name,
                style = MaterialTheme.typography.labelMedium,
                color = Tokens.Primary,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = item.sampleCount.toString() + " 道同类题",
                style = MaterialTheme.typography.labelSmall,
                color = Tokens.OnSurfaceVariant,
            )
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "删除这张解法卡",
                    tint = Tokens.OnSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        if (item.applicable.isNotBlank()) {
            Text(
                text = "适用：" + item.applicable,
                style = MaterialTheme.typography.bodySmall,
                color = Tokens.OnSurfaceVariant,
            )
        }
        item.keySteps.take(METHOD_PREVIEW_STEPS).forEachIndexed { index, step ->
            Text(
                text = (index + 1).toString() + ". " + step,
                style = MaterialTheme.typography.bodySmall,
                color = Tokens.OnSurface,
                maxLines = METHOD_PREVIEW_LINES,
            )
        }
        if (item.keySteps.size > METHOD_PREVIEW_STEPS) {
            Text(
                text = "另有 " + (item.keySteps.size - METHOD_PREVIEW_STEPS) + " 步",
                style = MaterialTheme.typography.labelSmall,
                color = Tokens.OnSurfaceVariant,
            )
        }
    }
}

/** 卡片上最多直接展示几步。 */
private const val METHOD_PREVIEW_STEPS: Int = 3

/** 单步在卡片上的行数上限。 */
private const val METHOD_PREVIEW_LINES: Int = 2

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
            text =
                "拍一道题、解完之后就会在这里长出一张「通用解法」卡；" +
                    "在解答卡底部点「存进资料库」，还会把你收集的原文留在这里。",
            style = MaterialTheme.typography.bodySmall,
            color = Tokens.OnSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun MaterialCard(
    item: LearnedMaterial,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    SurfaceCard(modifier = Modifier.fillMaxWidth(), onClick = onOpen) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Thumbnail(item = item)
            Spacer(Modifier.width(Tokens.SpaceMd))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Tokens.SpaceXs),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = Subjects.label(item.subject) + " · " + item.kpointName,
                        style = MaterialTheme.typography.labelMedium,
                        color = Tokens.Primary,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "查看 ›",
                        style = MaterialTheme.typography.labelSmall,
                        color = Tokens.OnSurfaceVariant,
                    )
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
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "删除这条资料",
                    tint = Tokens.OnSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/** 缩略图：有截图就显示截图，没有就给一个书页占位，避免卡片高低不齐。 */
@Composable
private fun Thumbnail(item: LearnedMaterial) {
    val path = item.imagePath
    val exists = path != null && File(path).exists()
    Box(
        modifier =
            Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Tokens.SurfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (exists && path != null) {
            coil.compose.AsyncImage(
                model = File(path),
                contentDescription = "这条资料的截图",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.MenuBook,
                contentDescription = null,
                tint = Tokens.OnSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * 详情：截图 + 当时提取出来的全部内容。
 *
 * 用对话框而不是新页面：看资料是「核对一下这条是什么」，看完就关，
 * 不需要返回栈，也不需要再占一个路由。
 */
@Composable
private fun MaterialDetailDialog(
    item: LearnedMaterial,
    onDismiss: () -> Unit,
) {
    val imagePath = item.imagePath?.takeIf { it.isNotBlank() && File(it).exists() }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(Tokens.RadiusLarge),
            color = Tokens.Surface,
            modifier = Modifier.fillMaxWidth().heightIn(max = 620.dp),
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
            ) {
                Text(
                    text = Subjects.label(item.subject) + " · " + item.kpointName,
                    style = MaterialTheme.typography.labelMedium,
                    color = Tokens.Primary,
                    fontWeight = FontWeight.Medium,
                )
                if (item.title.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Tokens.OnSurface,
                    )
                }
                Spacer(Modifier.height(10.dp))

                if (imagePath != null) {
                    coil.compose.AsyncImage(
                        model = File(imagePath),
                        contentDescription = "这条资料的截图",
                        contentScale = ContentScale.Fit,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(max = 280.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Tokens.SurfaceVariant),
                    )
                    Spacer(Modifier.height(10.dp))
                } else {
                    Text(
                        text = "这条没有附截图（早期收录或从文字解答存下来的）。",
                        style = MaterialTheme.typography.labelSmall,
                        color = Tokens.OnSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                }

                DetailField("方法", item.method)
                DetailList("步骤", item.keySteps)
                DetailField("结论", item.answer)
                DetailList("易错", item.pitfalls)

                Spacer(Modifier.height(10.dp))
                Text(
                    text = "来源：" + item.sourceLabel + " · " + formatTime(item.createdAtUtc),
                    style = MaterialTheme.typography.labelSmall,
                    color = Tokens.OnSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "别人的解析可能有错。解题时它只作为参考，不会覆盖模型自己的推导。",
                    style = MaterialTheme.typography.labelSmall,
                    color = Tokens.OnSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("关闭") }
            }
        }
    }
}

@Composable
private fun DetailField(
    label: String,
    value: String,
) {
    if (value.isBlank()) return
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = Tokens.OnSurface,
    )
    Text(
        text = value,
        style = MaterialTheme.typography.bodySmall,
        color = Tokens.OnSurface,
        modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
    )
}

@Composable
private fun DetailList(
    label: String,
    values: List<String>,
) {
    if (values.isEmpty()) return
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = Tokens.OnSurface,
    )
    values.forEachIndexed { index, value ->
        Text(
            text = (index + 1).toString() + ". " + value,
            style = MaterialTheme.typography.bodySmall,
            color = Tokens.OnSurface,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
    Spacer(Modifier.height(8.dp))
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
