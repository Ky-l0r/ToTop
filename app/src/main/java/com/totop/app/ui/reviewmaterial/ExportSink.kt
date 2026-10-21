package com.totop.app.ui.reviewmaterial

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * 导出产物及其可分享入口。
 *
 * @param file 实际落盘的文件
 * @param contentUri 交给系统分享/打印的 content:// Uri（FileProvider 或 MediaStore）
 * @param visibleInDownloads 是否已落到用户可见的「下载」目录
 * @param displayName 给用户看的文件名
 */
data class ExportArtifactInfo(
    val file: File?,
    val contentUri: String,
    val visibleInDownloads: Boolean,
    val displayName: String,
)

/**
 * 导出文件的落盘与打开。
 *
 * **为什么需要这个类**（真机反馈「导出后，在手机上找不到文件」的直接成因）：
 * 旧实现把 PDF 写进 `cacheDir/review_material/`，只在界面上打了一行文件名。
 * cacheDir 是应用私有的临时目录：文件管理器看不见、文件管理器也进不去、
 * 系统清理时还会被删掉。用户点了「导出」却什么都拿不到，等于功能不存在。
 *
 * 现在的策略（按可靠性排序，逐级降级，绝不静默失败）：
 * 1. **写入系统「下载」目录**（MediaStore.Downloads，Android 10+）——文件管理器里直接可见；
 * 2. Android 9 及以下写应用外部私有目录并用 FileProvider 暴露；
 * 3. 任何一步失败都返回失败原因，由界面提示「改用分享」，而不是假装成功。
 *
 * 另外提供 [shareIntent] / [openIntent]：无论落到哪里，用户总能通过分享面板
 * 把文件发给微信、邮箱或打印机 App，这条路径在所有 Android 版本上都成立。
 */
class ExportSink(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /**
     * 把一个已经写好的临时文件发布到用户可见位置。
     *
     * @param source 已写好的文件（通常在 cacheDir）
     * @param mimeType MIME 类型
     * @param displayName 目标文件名（含扩展名）
     */
    suspend fun publish(
        source: File,
        mimeType: String,
        displayName: String,
    ): PublishResult =
        withContext(ioDispatcher) {
            if (!source.exists() || source.length() == 0L) {
                return@withContext PublishResult.Failure("导出文件为空，请重试一次")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val viaMediaStore = publishToDownloads(source, mimeType, displayName)
                if (viaMediaStore != null) return@withContext viaMediaStore
            }
            // Android 9 及以下，或 MediaStore 被定制系统禁用：写应用外部私有目录，
            // 再用 FileProvider 暴露 content:// 交给分享面板。
            publishToExternalFiles(source, displayName)
        }

    /** MediaStore.Downloads：Android 10+ 无需任何存储权限即可落到用户可见位置。 */
    private fun publishToDownloads(
        source: File,
        mimeType: String,
        displayName: String,
    ): PublishResult? =
        runCatching {
            val resolver = context.contentResolver
            val values =
                ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                    put(MediaStore.Downloads.MIME_TYPE, mimeType)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = resolver.insert(collection, values) ?: return null
            resolver.openOutputStream(uri)?.use { out ->
                source.inputStream().use { input -> input.copyTo(out) }
                out.flush()
            } ?: return null
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            PublishResult.Success(
                info =
                    ExportArtifactInfo(
                        file = null,
                        contentUri = uri.toString(),
                        visibleInDownloads = true,
                        displayName = displayName,
                    ),
            )
        }.getOrNull()

    /** 应用外部私有目录 + FileProvider（Android 9 及以下的兜底）。 */
    private fun publishToExternalFiles(
        source: File,
        displayName: String,
    ): PublishResult =
        runCatching {
            val base = context.getExternalFilesDir(EXPORT_DIR) ?: File(context.filesDir, EXPORT_DIR)
            if (!base.exists() && !base.mkdirs()) {
                return PublishResult.Failure("无法创建导出目录，请检查存储空间")
            }
            val target = uniqueFile(base, displayName)
            FileOutputStream(target).use { out ->
                source.inputStream().use { input -> input.copyTo(out) }
                out.flush()
            }
            val uri =
                runCatching {
                    FileProvider.getUriForFile(context, context.packageName + FILE_PROVIDER_SUFFIX, target)
                }.getOrNull()
            if (uri == null) {
                // 连 FileProvider 都拿不到：至少把真实路径告诉用户，让他自己去找
                PublishResult.Success(
                    info =
                        ExportArtifactInfo(
                            file = target,
                            contentUri = target.absolutePath,
                            visibleInDownloads = false,
                            displayName = target.name,
                        ),
                )
            } else {
                PublishResult.Success(
                    info =
                        ExportArtifactInfo(
                            file = target,
                            contentUri = uri.toString(),
                            visibleInDownloads = false,
                            displayName = target.name,
                        ),
                )
            }
        }.getOrElse { PublishResult.Failure(it.message ?: "导出失败") }

    /** 分享意图：把文件发给其它 App（微信、邮箱、打印机）。 */
    fun shareIntent(info: ExportArtifactInfo, mimeType: String): Intent? {
        val uri = parseUri(info.contentUri) ?: return null
        val intent =
            Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        return Intent.createChooser(intent, "分享复习资料")
    }

    /** 打开意图：用系统 PDF 阅读器或图片查看器直接打开。 */
    fun openIntent(info: ExportArtifactInfo, mimeType: String): Intent? {
        val uri = parseUri(info.contentUri) ?: return null
        val intent =
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        return runCatching {
            if (intent.resolveActivity(context.packageManager) == null) null else intent
        }.getOrNull()
    }

    /**
     * 打印意图。
     *
     * 刻意**不直接接系统 PrintManager**：
     * 那需要自己实现 PrintDocumentAdapter 把 PDF 一页页渲染过去，
     * 代码量大且各家 ROM 的打印服务行为不一致。
     * 改用「分享给打印 App」这条路径：
     * 用户在分享面板里选择已安装的打印服务（系统打印、HP、Epson 等），
     * 这些 App 自己会处理 A4 排版，兼容性反而更好。
     */
    fun printIntent(info: ExportArtifactInfo, mimeType: String): Intent? =
        shareIntent(info, mimeType)

    /** 把字符串 Uri 解析成可用 Uri；file:// 与绝对路径都转成 FileProvider Uri。 */
    private fun parseUri(raw: String): Uri? {
        if (raw.isBlank()) return null
        if (raw.startsWith("content://")) return Uri.parse(raw)
        val file = File(raw)
        if (!file.exists()) return null
        return runCatching {
            FileProvider.getUriForFile(context, context.packageName + FILE_PROVIDER_SUFFIX, file)
        }.getOrElse { Uri.fromFile(file) }
    }

    /** 同名文件不覆盖：追加 -2、-3 后缀。 */
    private fun uniqueFile(
        dir: File,
        fileName: String,
    ): File {
        val base = fileName.substringBeforeLast('.', fileName)
        val extension = fileName.substringAfterLast('.', "")
        var candidate = File(dir, fileName)
        var index = 2
        while (candidate.exists() && index < 1000) {
            val name = if (extension.isEmpty()) base + "-" + index else base + "-" + index + "." + extension
            candidate = File(dir, name)
            index++
        }
        return candidate
    }

    companion object {
        /** 用户可见目录下的子文件夹名。 */
        const val FOLDER: String = "ToTop"

        /** 应用内导出子目录。 */
        const val EXPORT_DIR: String = "exports"

        /** FileProvider authority 后缀。 */
        const val FILE_PROVIDER_SUFFIX: String = ".fileprovider"
    }
}

/** 发布结果。 */
sealed class PublishResult {
    data class Success(val info: ExportArtifactInfo) : PublishResult()

    data class Failure(val reason: String) : PublishResult()
}
