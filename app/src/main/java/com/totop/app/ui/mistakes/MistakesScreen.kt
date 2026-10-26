package com.totop.app.ui.mistakes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.totop.app.R

/**
 * 错题本（FR-4，M3）。
 *
 * 页面结构（自上而下）：
 * 1. 顶部栏：标题 + 「复习」入口；
 * 2. 计数行：共 N 道 / 已加载 M 道；
 * 3. 筛选栏：搜索 + 科目 / 知识点 / 错因 / 时间；
 * 4. [LazyColumn] 分页列表：滚到底部自动加载下一页，行内展示缩略图与「第 N 次错」复发徽标；
 * 5. 空态 / 错误态：都给出可执行的下一步，不出现空白页。
 *
 * 性能约束（方案 7.1）：无毛玻璃、无大面积动画、无共享元素转场；
 * 列表用 key = mistakeId，滚动位置在筛选变化时才重置。
 *
 * @param onOpenReview 进入复习流程（FR-4.2）
 * @param onOpenMistake 打开一道错题的**回顾页**（只读回看，不进入复习流程）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MistakesScreen(
    onOpenReview: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenMistake: (mistakeId: String) -> Unit = {},
    // 从功能中心进入后需要返回上一层；默认空实现保持既有调用方不变
    onBack: () -> Unit = {},
    viewModel: MistakesViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    val nowUtcMillis = remember(uiState.items.size) { System.currentTimeMillis() }

    LaunchedEffect(Unit) { viewModel.bootstrap() }

    // 滚动到末尾时追加下一页；derivedStateOf 保证只在真正越界时触发一次重组
    val shouldLoadMore by remember {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val totalItems = listState.layoutInfo.totalItemsCount
            totalItems > 0 && lastVisible >= totalItems - 3
        }
    }
    LaunchedEffect(shouldLoadMore, uiState.items.size, uiState.total) {
        if (shouldLoadMore) viewModel.loadMore()
    }

    LaunchedEffect(uiState.errorMessage) {
        val message = uiState.errorMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.nav_mistakes)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(onClick = onOpenReview) { Text(text = "复习") }
                },
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { innerPadding ->
        // 计数行与筛选栏**放进同一个 LazyColumn 里**，跟着列表一起滚走。
        //
        // 旧实现把它们固定在顶部：搜索框 + 四行筛选 + 条件回显常驻占掉半屏，
        // 手机上真正能看错题的区域只剩一小条——真机反馈的
        // 「下拉后筛选还是固定在上面，可查看空间变小」说的就是这件事。
        // 滚走之后想再筛，往回拨一下即可；筛选条件本身不会丢。
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "count-row") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = uiState.countText,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    if (uiState.loading) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    }
                }
            }

            item(key = "filter-bar") {
                MistakeFilterBar(
                    filter = uiState.filter,
                    subjectOptions = uiState.subjectOptions,
                    kpointOptions = uiState.kpointOptions,
                    onKeywordChange = viewModel::setKeyword,
                    onSearchSubmit = viewModel::submitSearch,
                    onSubjectChange = viewModel::setSubject,
                    onKpointChange = viewModel::setKpoint,
                    onErrorTypeChange = viewModel::setErrorType,
                    onTimeWindowChange = viewModel::setTimeWindow,
                    onClear = viewModel::clearFilter,
                )
            }

            when {
                uiState.loading && uiState.items.isEmpty() ->
                    item(key = "loading") {
                        LoadingBlock()
                    }

                uiState.isEmpty ->
                    item(key = "empty") {
                        EmptyBlock(
                            message = uiState.emptyMessage,
                            filterActive = uiState.filter.isActive,
                            onClear = viewModel::clearFilter,
                            onRetry = viewModel::reload,
                        )
                    }

                else -> {
                    items(items = uiState.items, key = { it.mistakeId }) { card ->
                        MistakeCard(
                            card = card,
                            nowUtcMillis = nowUtcMillis,
                            onClick = { onOpenMistake(card.mistakeId) },
                            onDelete = { viewModel.deleteMistake(card.mistakeId) },
                        )
                    }
                    if (uiState.loadingMore) {
                        item(key = "loading-more") {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                )
                            }
                        }
                    }
                    if (!uiState.hasMore && uiState.items.isNotEmpty()) {
                        item(key = "list-end") {
                            Text(
                                text = "已经到底了 · 共 " + uiState.total + " 道错题",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 加载骨架：静态灰块，不做闪烁动画（方案 7.1）。 */
@Composable
private fun LoadingBlock() {
    // 放在 LazyColumn 的 item 里，不能再用 fillMaxSize：
    // item 的高度是无界测量，fillMaxSize 会让它撑不住并触发测量异常。
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        repeat(4) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().height(72.dp),
            ) {}
        }
        Text(
            text = "正在读取本机错题…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 空态：区分「没有错题」与「筛选没命中」。 */
@Composable
private fun EmptyBlock(
    message: String,
    filterActive: Boolean,
    onClear: () -> Unit,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Filled.SearchOff,
            contentDescription = null,
            modifier = Modifier.size(36.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (filterActive) {
                Button(onClick = onClear) { Text(text = "清除筛选") }
            }
            OutlinedButton(onClick = onRetry) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Text(text = " 重新读取")
            }
        }
        Text(
            text = "错题只保存在本机，不会上传到任何服务器。",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
