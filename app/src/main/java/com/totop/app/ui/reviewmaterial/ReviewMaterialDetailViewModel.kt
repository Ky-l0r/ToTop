package com.totop.app.ui.reviewmaterial

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 详情页状态：读一份历史资料 + 导出 PDF + 打开 / 分享。 */
data class ReviewMaterialDetailUiState(
    val loading: Boolean = true,
    val material: ReviewMaterial? = null,
    /** 读取失败原因（资料被删 / 文件损坏 / 时间戳非法）。 */
    val error: String? = null,
    val exporting: Boolean = false,
    /** 最近一次导出的文件信息；非空时界面显示「打开 / 分享」入口。 */
    val exportedFile: ExportArtifactInfo? = null,
    /** 操作反馈，一次性提示。 */
    val message: String? = null,
)

/**
 * 复习资料详情页 ViewModel。
 *
 * 从列表页拆出来的另一半职责：正文渲染 + PDF 导出。
 * 列表页只负责「生成与选哪份」，这里只负责「看与带走」——
 * 导出状态、分享 Intent 放在详情页，列表页旋转重建时不会背上这些一次性状态。
 */
@HiltViewModel
class ReviewMaterialDetailViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val store: ReviewMaterialStore,
        private val pdfWriter: ReviewMaterialPdfWriter,
        private val sink: ExportSink,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(ReviewMaterialDetailUiState())
        val uiState: StateFlow<ReviewMaterialDetailUiState> = _uiState.asStateFlow()

        /** 待分享的 Intent；界面消费后调用 [consumePendingIntent]。 */
        private val _pendingIntent = MutableStateFlow<Intent?>(null)
        val pendingIntent: StateFlow<Intent?> = _pendingIntent.asStateFlow()

        /** 已加载过的时间戳：同一个 ID 不重复读盘（旋转重建会再次回调 load）。 */
        private var loadedAt: Long? = null

        /** 按时间戳读一份资料；失败给出可读原因，绝不静默，也绝不触发生成。 */
        fun load(createdAtUtc: Long) {
            if (loadedAt == createdAtUtc) return
            loadedAt = createdAtUtc
            _uiState.value = ReviewMaterialDetailUiState(loading = true)
            viewModelScope.launch {
                if (createdAtUtc <= 0L) {
                    _uiState.value =
                        ReviewMaterialDetailUiState(loading = false, error = "这份资料的链接不完整，请回到列表重新打开。")
                    return@launch
                }
                val material = store.load(createdAtUtc)
                _uiState.value =
                    if (material == null) {
                        ReviewMaterialDetailUiState(
                            loading = false,
                            error = "这份资料已经读取不到了，可能已被删除；可以回列表重新生成一份。",
                        )
                    } else {
                        ReviewMaterialDetailUiState(loading = false, material = material)
                    }
            }
        }

        /**
         * 导出 A4 PDF。
         *
         * 三步：渲染到临时文件 → 发布到下载目录 → 记住可分享 Uri。
         * 任何一步失败都把具体原因写进 message（例如磁盘不可写、空间不足），
         * 而不是笼统提示「导出失败」。
         */
        fun exportPdf() {
            val material = _uiState.value.material ?: return
            if (_uiState.value.exporting) return
            _uiState.value = _uiState.value.copy(exporting = true, message = "正在生成 PDF…")
            viewModelScope.launch {
                when (val result = pdfWriter.write(context, material)) {
                    is PdfWriteResult.Success -> {
                        val displayName =
                            ReviewMaterialPdfWriter.FILE_STEM + System.currentTimeMillis() + ".pdf"
                        when (val published = sink.publish(result.file, PDF_MIME, displayName)) {
                            is PublishResult.Success ->
                                _uiState.value =
                                    _uiState.value.copy(
                                        exporting = false,
                                        exportedFile = published.info,
                                        message =
                                            if (published.info.visibleInDownloads) {
                                                "已保存到「下载/ToTop/" + published.info.displayName + "」，共 " +
                                                    result.pageCount + " 页（双面打印 " +
                                                    (result.pageCount + 1) / 2 + " 张），可直接打开或分享。"
                                            } else {
                                                "已生成 " + published.info.displayName + "（" + result.pageCount +
                                                    " 页），可点下方按钮打开或分享。"
                                            },
                                    )
                            is PublishResult.Failure ->
                                _uiState.value =
                                    _uiState.value.copy(
                                        exporting = false,
                                        message = "PDF 已生成但保存失败：" + published.reason + "（可改用分享按钮）",
                                    )
                        }
                    }
                    is PdfWriteResult.Failure ->
                        _uiState.value =
                            _uiState.value.copy(exporting = false, message = "导出失败：" + result.reason)
                }
            }
        }

        /** 打开刚导出的 PDF。 */
        fun openExported() {
            val info = _uiState.value.exportedFile ?: return
            val intent = sink.openIntent(info, PDF_MIME)
            if (intent == null) {
                _uiState.value = _uiState.value.copy(message = "本机没有能打开 PDF 的应用，可以用「分享/打印」发到别处。")
            } else {
                _pendingIntent.value = intent
            }
        }

        /** 分享或打印刚导出的 PDF。 */
        fun shareExported() {
            val info = _uiState.value.exportedFile ?: return
            val intent = sink.shareIntent(info, PDF_MIME)
            if (intent == null) {
                _uiState.value = _uiState.value.copy(message = "没有可用的分享入口，请检查文件是否还在。")
            } else {
                _pendingIntent.value = intent
            }
        }

        /** 界面已消费 Intent。 */
        fun consumePendingIntent() {
            _pendingIntent.value = null
        }

        private companion object {
            /** PDF 的 MIME 类型。 */
            const val PDF_MIME: String = "application/pdf"
        }
    }
