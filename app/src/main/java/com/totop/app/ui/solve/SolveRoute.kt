package com.totop.app.ui.solve

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.totop.app.domain.model.QuestionPage
import com.totop.app.ml.rememberAlbumPicker
import com.totop.app.ml.rememberCameraPermissionRequester
import com.totop.app.ui.capture.CaptureScreen
import com.totop.app.ui.capture.PREVIEW_LONG_SIDE
import com.totop.app.ui.capture.PagePreviewScreen
import com.totop.app.ui.chat.NetworkStatus
import com.totop.app.ui.chat.SolveViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 拍题页的容器：在「对话页 / 全屏相机 / 照片框选」三个界面之间切换。
 *
 * 为什么要单独一个容器：
 * 相机是**全屏沉浸**的，对话页有输入框与底部导航；
 * 两者不能同时存在，否则相机会被输入框切掉一块，取景框比例失真。
 * 因此用同一份 ViewModel 状态驱动三个互斥的界面，退出相机回到对话页时数据仍在。
 *
 * 流程顺序（这是本次修复的重点）：
 *
 *     拍照/相册 -> [自动进入框选页] -> 确认 -> 回到对话页 -> 写思路 -> 发送
 *
 * 旧流程在拍完照后直接回到对话页，甚至直接发送，跳过了「框选」和「写思路」两步，
 * 用户看到的就是「拍完了却没让我框，也没让我说想法」。
 */
@Composable
fun SolveRoute(
    onOpenKnowledgePoint: (String) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SolveViewModel = hiltViewModel(),
) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val draftPages by viewModel.draftPages.collectAsStateWithLifecycle()
    val activePage by viewModel.activePage.collectAsStateWithLifecycle()
    val processing by viewModel.processing.collectAsStateWithLifecycle()
    val phase by viewModel.phase.collectAsStateWithLifecycle()
    val networkStatus by viewModel.networkStatus.collectAsStateWithLifecycle()
    val mode by viewModel.mode.collectAsStateWithLifecycle()
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val currentSessionId by viewModel.sessionId.collectAsStateWithLifecycle()
    val toast by viewModel.toast.collectAsStateWithLifecycle()

    // 三个互斥界面：对话 / 相机 / 单页框选预览
    var cameraOpen by remember { mutableStateOf(false) }
    var previewOpen by remember { mutableStateOf(false) }

    /** 是否在等「图片处理完就自动打开框选页」。 */
    var pendingPreview by remember { mutableStateOf(false) }

    val pickAlbum =
        rememberAlbumPicker(
            onPicked = { uri ->
                viewModel.importFromGallery(uri)
                // 相册导入后同样自动进入框选页：用户选了图，下一步就该框题
                pendingPreview = true
            },
            onCancelled = { viewModel.showToast("已取消相册选择") },
        )
    val requestPermission =
        rememberCameraPermissionRequester(
            onResult = { granted ->
                if (granted) {
                    cameraOpen = true
                } else {
                    viewModel.showToast("没有相机权限，可以改用「相册」导入")
                }
            },
        )

    LaunchedEffect(Unit) { viewModel.bootstrap() }

    // 图片处理完成（页数增加）且用户正在等框选时，自动打开框选页。
    //
    // 用 pageCount 而不是 processing 做判断：processing 在写盘前就置回 false，
    // 那时 pages 还没进草稿，直接打开预览会看到「正在打开照片…」的空页面。
    LaunchedEffect(draftPages.pageCount, pendingPreview, processing) {
        if (pendingPreview && draftPages.pageCount > 0 && !processing) {
            pendingPreview = false
            viewModel.selectPage(draftPages.pageCount - 1)
            previewOpen = true
        }
    }

    val bitmaps = rememberPreviewBitmaps(draftPages.pages)
    val composerPages =
        draftPages.pages.mapIndexed { index, page ->
            ComposerPage(thumbnail = bitmaps.getOrNull(index), cropped = page.cropRect != null)
        }

    if (cameraOpen) {
        CaptureScreen(
            pageCount = draftPages.pageCount,
            onCaptured = { path ->
                viewModel.importFromCameraFile(path)
                cameraOpen = false
                // 拍完立即进入框选页，而不是回到对话页等下一次操作
                pendingPreview = true
            },
            onPickAlbum = { uri ->
                viewModel.importFromGallery(uri)
                cameraOpen = false
                pendingPreview = true
            },
            onClose = { cameraOpen = false },
            modifier = modifier,
        )
        return
    }

    if (previewOpen && draftPages.pages.isNotEmpty()) {
        val bitmap = bitmaps.getOrNull(activePage)
        PagePreviewScreen(
            draft = draftPages,
            currentIndex = activePage.coerceIn(0, (draftPages.pageCount - 1).coerceAtLeast(0)),
            bitmap = bitmap,
            loading = processing,
            onCropChanged = { rect -> viewModel.setCrop(activePage, rect) },
            onResetCrop = { viewModel.setCrop(activePage, null) },
            onAddPage = { cameraOpen = true },
            onDeletePage = { index -> viewModel.removePage(index) },
            onSelectPage = { index -> viewModel.selectPage(index) },
            onMovePage = { from, to -> viewModel.movePage(from, to) },
            onConfirm = {
                // 确认只做一件事：回到对话页让用户写思路。
                // 不直接发送——是否发送、思路写没写，由用户自己决定。
                previewOpen = false
                viewModel.runOcrIfNeeded()
            },
            onRetake = {
                viewModel.removePage(activePage)
                if (draftPages.pageCount <= 1) previewOpen = false
                cameraOpen = true
            },
            onSaveOffline = {
                viewModel.saveOffline()
                previewOpen = false
            },
            onCancel = { previewOpen = false },
            modifier = modifier,
        )
        return
    }

    SolveConversationScreen(
        messages = messages,
        draft = draft,
        pages = composerPages,
        pageCount = draftPages.pageCount,
        processing = processing,
        phase = phase,
        networkOnline = networkStatus != NetworkStatus.OFFLINE,
        mode = mode,
        sessions = sessions,
        currentSessionId = currentSessionId,
        onDraftChange = { text -> viewModel.updateStudentProcess(text) },
        onSend = { viewModel.send() },
        onStop = viewModel::stop,
        onOpenCamera = { requestPermission {} },
        onOpenAlbum = pickAlbum,
        onRemovePage = { index -> viewModel.removePage(index) },
        onOpenPage = { index ->
            viewModel.selectPage(index)
            previewOpen = true
        },
        onOpenSettings = onOpenSettings,
        onRetry = viewModel::retry,
        onStartNewSession = viewModel::startNewSession,
        onOpenSession = viewModel::openSession,
        onDeleteSession = viewModel::deleteSession,
        onSetMode = viewModel::setMode,
        onOpenKnowledgePoint = onOpenKnowledgePoint,
        onUnlockHint = viewModel::unlockNextHint,
        onRevealSolution = viewModel::revealFullSolution,
        message = toast,
        onDismissMessage = viewModel::dismissToast,
        modifier = modifier,
    )
}

/**
 * 把各页图片解码成**框选用的高清预览图**。
 *
 * 这里刻意不用缩略图（长边 320）：
 * 框选是对着屏幕用手指画一个框，缩略图铺满全屏后会糊成一片，
 * 用户根本看不清要框哪一行——这正是「框题时图片太糊」的成因。
 * 改用长边 1280 的预览图，与上传图同分辨率，做到所见即所得。
 *
 * 用 remember 缓存并只在页数/路径变化时重解码：
 * 每帧重新解码会让滚动直接掉帧，这是「切换页面卡顿」的常见来源之一。
 */
@Composable
private fun rememberPreviewBitmaps(pages: List<QuestionPage>): List<Bitmap?> {
    val keys = pages.map { it.imagePath }
    var bitmaps by remember(keys) {
        mutableStateOf<List<Bitmap?>>(List(pages.size) { null })
    }
    LaunchedEffect(keys) {
        bitmaps =
            withContext(Dispatchers.IO) {
                pages.map { page -> decodePreview(page.imagePath) }
            }
    }
    return bitmaps
}

/** 按长边 1280 解码，兼顾清晰度与内存。 */
private fun decodePreview(path: String): Bitmap? =
    runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        var sample = 1
        val longSide = maxOf(bounds.outWidth, bounds.outHeight)
        while (longSide / (sample * 2) >= PREVIEW_LONG_SIDE) sample *= 2
        val options =
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        BitmapFactory.decodeFile(path, options)
    }.getOrNull()
