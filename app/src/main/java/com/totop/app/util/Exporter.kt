package com.totop.app.util

import android.content.Context
import androidx.documentfile.provider.DocumentFile
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
 * Markdown / CSV / Anki 导出器的统一入口（开发方案 FR-10.1 / FR-10.2 / FR-10.3）。
 *
 * 三个导出器共享同一套「渲染 -> 写盘」流程：
 * - 渲染由领域层的 [com.totop.app.domain.usecase.ExportDataUseCase] 完成（纯逻辑）；
 * - 写盘由 [ExportFileWriter] 完成（SAF / FileProvider / MediaStore），
 *   **它是 `DataExporter` 接口在本工程的唯一实现**（Hilt 绑定见 [ExportModule]）；
 * - 本类不再实现 `DataExporter`，只提供面向具体格式的便捷方法与目录管理，
 *   避免同一个接口出现两个实现导致注入歧义。
 *
 * 「Anki 退化为 CSV」这类产品决策固定在一处（FR-10.3）。
 */
@Singleton
class Exporter
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val writer: ExportFileWriter,
    ) {
        private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO

        /** 支持的格式（当前四种全支持）。 */
        fun supportedFormats(): Set<ExportFormat> = writer.supportedFormats()

        /** 通用写盘：委托给唯一的 [DataExporter] 实现 [ExportFileWriter]。 */
        suspend fun write(
            artifact: ExportArtifact,
            destinationUri: String? = null,
        ): ExportWriteResult = writer.write(artifact, destinationUri)

        /** Markdown 错题本（公式保持 Unicode/LaTeX 原文，AI 生成内容带标识）。 */
        suspend fun writeMarkdown(
            artifact: ExportArtifact,
            destinationUri: String? = null,
        ): ExportWriteResult = requireFormat(artifact, ExportFormat.MARKDOWN) ?: writer.write(artifact, destinationUri)

        /** CSV 明细（错题明细 / 知识点统计 / 周报数据 三个视图）。 */
        suspend fun writeCsv(
            artifact: ExportArtifact,
            destinationUri: String? = null,
        ): ExportWriteResult = requireFormat(artifact, ExportFormat.CSV) ?: writer.write(artifact, destinationUri)

        /**
         * Anki 卡片导出（FR-10.3）。
         *
         * 产品决策：**不生成 .apkg**。apkg 是一个 SQLite 集合 + 媒体包的 zip 结构，
         * 生成它需要引入 Anki 的集合格式实现（第三方 SDK 或大段私有格式代码），
         * 与「不得新增第三方 SDK、包体 30MB 门禁」冲突。
         * 因此统一导出 Anki 可直接导入的 **TSV/CSV 文本**（首行带 `#separator:tab` 等指令），
         * 用户在 Anki 里「导入 -> 文本文件」即可得到卡片。
         */
        suspend fun writeAnki(
            artifact: ExportArtifact,
            destinationUri: String? = null,
        ): ExportWriteResult = requireFormat(artifact, ExportFormat.ANKI) ?: writer.write(artifact, destinationUri)

        /** 全量备份 JSON（结构化数据；图片由 [BackupManager] 单独打包）。 */
        suspend fun writeBackupJson(
            artifact: ExportArtifact,
            destinationUri: String? = null,
        ): ExportWriteResult =
            requireFormat(artifact, ExportFormat.BACKUP_JSON) ?: writer.write(artifact, destinationUri)

        /** 另存到系统下载目录（Android 10+）。 */
        suspend fun saveToDownloads(artifact: ExportArtifact): ExportWriteResult = writer.exportToDownloads(artifact)

        /**
         * 把 SAF 选中的**目录** Uri 转成具体文件 Uri（部分 ROM 的 `ACTION_OPEN_DOCUMENT_TREE`
         * 返回的是目录，需要在这里落成文件）。
         *
         * @return 可写入的文件 Uri；目录不可写时返回 null
         */
        suspend fun resolveFileUriInTree(
            treeUri: String,
            fileName: String,
        ): String? =
            withContext(ioDispatcher) {
                runCatching {
                    val tree =
                        DocumentFile.fromTreeUri(
                            context,
                            android.net.Uri.parse(treeUri),
                        ) ?: return@runCatching null
                    val existing = tree.findFile(fileName)
                    val file = existing ?: tree.createFile(mimeTypeOf(fileName), fileName) ?: return@runCatching null
                    file.uri.toString()
                }.getOrNull()
            }

        /** 列出已有导出文件（设置页「已有导出」列表，避免重复导出时找不到）。 */
        suspend fun existingExports(): List<File> =
            withContext(ioDispatcher) {
                val dir = File(context.filesDir, ExportFileWriter.EXPORT_DIR)
                if (!dir.exists()) return@withContext emptyList()
                dir.listFiles()
                    ?.filter { it.isFile }
                    ?.sortedByDescending { it.lastModified() }
                    ?: emptyList()
            }

        /** 删除一个导出文件。 */
        suspend fun delete(file: File): Boolean =
            withContext(ioDispatcher) {
                runCatching { !file.exists() || file.delete() }.getOrDefault(false)
            }

        /** 写一段文本到 App 导出目录（备份包等二进制场景使用）。 */
        suspend fun writeBytesToExports(
            fileName: String,
            bytes: ByteArray,
        ): File? =
            withContext(ioDispatcher) {
                runCatching {
                    val dir = File(context.filesDir, ExportFileWriter.EXPORT_DIR)
                    if (!dir.exists() && !dir.mkdirs()) return@runCatching null
                    val target = File(dir, fileName)
                    FileOutputStream(target).use { out ->
                        out.write(bytes)
                        out.flush()
                    }
                    target
                }.getOrNull()
            }

        private fun requireFormat(
            artifact: ExportArtifact,
            expected: ExportFormat,
        ): ExportWriteResult? =
            if (artifact.format != expected) {
                ExportWriteResult.Failure(
                    userMessage = "导出格式不匹配：期望 " + expected.label + "，实际为 " + artifact.format.label + "。",
                )
            } else {
                null
            }

        private fun mimeTypeOf(fileName: String): String =
            when (fileName.substringAfterLast('.', "").lowercase()) {
                "md" -> "text/markdown"
                "csv" -> "text/csv"
                "txt" -> "text/plain"
                "json" -> "application/json"
                "zip" -> "application/zip"
                else -> "application/octet-stream"
            }
    }
