package com.totop.app.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.totop.app.domain.usecase.port.DataExporter
import com.totop.app.domain.usecase.port.ExportArtifact
import com.totop.app.domain.usecase.port.ExportFormat
import com.totop.app.domain.usecase.port.ExportWriteResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 数据导出写盘实现（开发方案 FR-10，遵守 Android 11+ 分区存储）。
 *
 * 三条写盘路径，按优先级选择：
 * 1. **用户通过 SAF 选了位置**（`ACTION_CREATE_DOCUMENT` 返回的 Uri）：
 *    直接 `contentResolver.openOutputStream(uri, "wt")` 覆盖写入，全版本通用，
 *    不需要任何存储权限；
 * 2. **未指定位置且 App 专属目录可用**：写入 `filesDir/exports/`，
 *    再用 FileProvider 暴露 `content://` Uri 交给分享/另存；
 * 3. **分享场景**：调用方拿到 [ExportWriteResult.Success.location] 后自行拉起 `ACTION_SEND`。
 *
 * 刻意 **不使用** 已废弃的 `WRITE_EXTERNAL_STORAGE` 与 `getExternalStorageDirectory()`：
 * 这两者在 Android 11+ 会失败，且会让应用被应用商店判定为未适配分区存储。
 * 导出内容全部是本机数据，**不经过任何网络**（开发方案 C2）。
 */
@Singleton
class ExportFileWriter
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : DataExporter {
        private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO

        override fun supportedFormats(): Set<ExportFormat> = ExportFormat.entries.toSet()

        override suspend fun write(
            artifact: ExportArtifact,
            destinationUri: String?,
        ): ExportWriteResult =
            withContext(ioDispatcher) {
                if (artifact.content.isEmpty()) {
                    return@withContext ExportWriteResult.Failure("导出内容为空，请先添加错题。")
                }
                val bytes = artifact.content.toByteArray(Charsets.UTF_8)
                if (destinationUri != null && destinationUri.isNotBlank()) {
                    writeToUri(Uri.parse(destinationUri), bytes)
                } else {
                    writeToAppStorage(artifact, bytes)
                }
            }

        /**
         * SAF 目标：用户选择的文件位置，全版本可用，无需存储权限。
         *
         * 注意这里写成**块体函数**而不是 `= try { ... }`：
         * 函数内部有 `?: return ...` 的提前返回，Kotlin 禁止在表达式体函数中使用 return。
         */
        private fun writeToUri(
            uri: Uri,
            bytes: ByteArray,
        ): ExportWriteResult {
            return try {
                val stream = context.contentResolver.openOutputStream(uri, "wt")
                if (stream == null) {
                    ExportWriteResult.Failure("无法写入所选位置，请换一个目录重试。")
                } else {
                    stream.use { out ->
                        out.write(bytes)
                        out.flush()
                    }
                    ExportWriteResult.Success(location = uri.toString(), sizeBytes = bytes.size.toLong())
                }
            } catch (error: Exception) {
                ExportWriteResult.Failure(
                    userMessage = "导出失败，请检查所选目录是否可写。",
                    detail = error.message,
                )
            }
        }

        /** 未指定 SAF 位置：写 App 专属 exports 目录，返回 FileProvider 的 content:// Uri。 */
        private fun writeToAppStorage(
            artifact: ExportArtifact,
            bytes: ByteArray,
        ): ExportWriteResult {
            return try {
                val dir = File(context.filesDir, EXPORT_DIR)
                if (!dir.exists() && !dir.mkdirs()) {
                    ExportWriteResult.Failure("无法创建导出目录，请检查存储空间。")
                } else {
                    val target = uniqueFile(dir, artifact.suggestedFileName)
                    FileOutputStream(target).use { out ->
                        out.write(bytes)
                        out.flush()
                    }
                    ExportWriteResult.Success(location = authorityUriOf(target), sizeBytes = bytes.size.toLong())
                }
            } catch (error: Exception) {
                ExportWriteResult.Failure(userMessage = "导出失败，请检查存储空间是否充足。", detail = error.message)
            }
        }

        /** 同名文件不覆盖：追加 `-2`、`-3` 后缀，避免用户误以为导出失败。 */
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

        /** 用 FileProvider 把 App 私有文件变成可分享的 content:// Uri。 */
        fun authorityUriOf(file: File): String =
            runCatching {
                FileProvider.getUriForFile(context, context.packageName + FILE_PROVIDER_SUFFIX, file).toString()
            }.getOrElse { file.absolutePath }

        /**
         * 把导出文件另存到系统「下载」目录（MediaStore，Android 10+）。
         *
         * 这是「不申请存储权限也能落到用户可见位置」的官方做法：
         * Android 10 及以上走 MediaStore.Downloads；Android 9 及以下返回失败，
         * 由调用方退回 SAF 让用户自己选位置。
         */
        suspend fun exportToDownloads(artifact: ExportArtifact): ExportWriteResult =
            withContext(ioDispatcher) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    return@withContext ExportWriteResult.Failure(
                        "当前系统版本需要手动选择保存位置，请使用「另存为」按钮。",
                    )
                }
                val bytes = artifact.content.toByteArray(Charsets.UTF_8)
                val values =
                    ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, artifact.suggestedFileName)
                        put(MediaStore.Downloads.MIME_TYPE, artifact.mimeType)
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ToTop")
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                val resolver = context.contentResolver
                val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val uri =
                    try {
                        resolver.insert(collection, values)
                    } catch (
                        @Suppress("SwallowedException") error: Exception,
                    ) {
                        // MediaStore 不可用（部分定制系统会禁用 Downloads 集合），
                        // 由调用方回退到 SAF 另存为，此处只需返回失败
                        null
                    } ?: return@withContext ExportWriteResult.Failure(
                        "无法写入系统下载目录，请改用「另存为」选择位置。",
                    )
                try {
                    resolver.openOutputStream(uri)?.use { out ->
                        out.write(bytes)
                        out.flush()
                    }
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                    ExportWriteResult.Success(location = uri.toString(), sizeBytes = bytes.size.toLong())
                } catch (error: Exception) {
                    runCatching { resolver.delete(uri, null, null) }
                    ExportWriteResult.Failure(userMessage = "写入下载目录失败，请改用「另存为」。", detail = error.message)
                }
            }

        companion object {
            /** 导出目录（相对 filesDir），与 res/xml/file_paths.xml 的 exports 一致。 */
            const val EXPORT_DIR: String = "exports"

            /** FileProvider authority 后缀，与 AndroidManifest 的 ${applicationId}.fileprovider 一致。 */
            const val FILE_PROVIDER_SUFFIX: String = ".fileprovider"
        }
    }
