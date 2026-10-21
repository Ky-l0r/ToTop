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
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.totop.app.domain.model.QuestionPage
import com.totop.app.ml.rememberAlbumPicker
import com.totop.app.ml.rememberCameraPermissionRequester
import com.totop.app.ui.capture.CaptureScreen
import com.totop.app.ui.capture.PagePreviewScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 拍题页的容器：在「对话页 / 全屏相机 / 照片框选」三个界面之间切换。
 *
 * 为什么要单独一个容器：
 * 相机是**全屏沉浸**的，对话页有输入框与底部导航；
 * 两者不能同时存在，否则相机会被输入框切掉一块，取景框比例失真。
 * 因此用同一份 ViewModel 状态驱动三个互斥的界面，退出相机回到对话页时数据仍在。
 */
@Composable
fun SolveRoute(
    onOpenKnowledgePoint: (String) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: com.totop.app.ui.chat.SolveViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val draftPages by viewModel.draftPages.collectAsStateWithLifecycle()
    val activePage by viewModel.activePage.collectAsStateWithLifecycle()
    val processing by viewModel.processing.collectAsStateWithLifecycle()
    val networkStatus by viewModel.networkStatus.collectAsStateWithLifecycle()
    val mode by viewModel.mode.collectAsStateWithLifecycle()

    // 三个互斥界面：对话 / 相机 / 单页框选预览
    var cameraOpen by remember { mutableStateOf(false) }
    var previewOpen by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    val pickAlbum =
        rememberAlbumPicker(
            onPicked = { uri -> viewModel.importFromGallery(uri) },
            onCancelled = { message = "已取消相册选择" },
        )
    val requestPermission =
        rememberCameraPermissionRequester(
            onResult = { granted ->
                if (granted) {
                    cameraOpen = true
                } else {
                    message = "没有相机权限，可以改用「相册」导入"
                }
            },
        )

    LaunchedEffect(Unit) { viewModel.bootstrap() }

    val thumbnails = rememberBitmaps(draftPages.pages)
    val composerPages =
        draftPages.pages.mapIndexed { index, page ->
            ComposerPage(thumbnail = thumbnails[index], cropped = page.cropRect != null)
        }

    if (cameraOpen) {
        CaptureScreen(
            pageCount = draftPages.pageCount,
            onCaptured = { path ->
                viewModel.importFromCameraFile(path)
                cameraOpen = false
            },
            onPickAlbum = { uri ->
                viewModel.importFromGallery(uri)
                cameraOpen = false
            },
            onClose = { cameraOpen = false },
            modifier = modifier,
        )
        return
    }

    if (previewOpen && draftPages.pages.isNotEmpty()) {
        val bitmap = thumbnails.getOrNull(activePage)
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
                // 图片还在压缩/落盘时不让发送：此时 pages 可能还没进草稿，
                // 直接发会变成「没有图片的解题」，用户会以为拍的照片丢了。
                if (processing) {
                    message = "图片还在处理，请稍等一下"
                } else {
                    previewOpen = false
                    viewModel.send()
                }
            },
            onRetake = {
                viewModel.removePage(activePage)
                cameraOpen = true
            },
            onSaveOffline = {
                viewModel.saveOffline()
                previewOpen = false
                message = "已存到「离线记题」，联网后再解"
            },
            onCancel = { previewOpen = false },
            modifier = modifier,
        )
        return
    }

    SolveConversationScreen(
        uiState = uiState,
        draft = draft,
        pages = composerPages,
        pageCount = draftPages.pageCount,
        processing = processing,
        networkOnline = networkStatus != com.totop.app.ui.chat.NetworkStatus.OFFLINE,
        mode = mode,
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
        onAskAgain = viewModel::askAgain,
        onSetMode = viewModel::setMode,
        onOpenKnowledgePoint = onOpenKnowledgePoint,
        message = message,
        onDismissMessage = { message = null },
        modifier = modifier,
    )
}

/**
 * 把各页图片解码成缩略图位图。
 *
 * 用 remember 缓存并只在页数/路径变化时重解码：
 * 每帧重新解码会让滚动直接掉帧，这是「切换页面卡顿」的常见来源之一。
 */
@Composable
private fun rememberBitmaps(pages: List<QuestionPage>): List<Bitmap?> {
    val context = LocalContext.current
    var bitmaps by remember(pages.map { it.imagePath to it.thumbnailPath }) {
        mutableStateOf<List<Bitmap?>>(List(pages.size) { null })
    }
    LaunchedEffect(pages.map { it.imagePath to it.thumbnailPath }) {
        bitmaps =
            withContext(Dispatchers.IO) {
                pages.map { page -> decodeThumbnail(page.thumbnailPath ?: page.imagePath) }
            }
    }
    return bitmaps
}

/** 以较低采样率解码，只为缩略图显示，不占大内存。 */
private fun decodeThumbnail(path: String): Bitmap? =
    runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 256) sample *= 2
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()
