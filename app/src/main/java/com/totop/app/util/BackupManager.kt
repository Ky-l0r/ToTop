package com.totop.app.util

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Build
import androidx.documentfile.provider.DocumentFile
import com.totop.app.data.local.AppDatabase
import com.totop.app.data.local.dao.SettingsDao
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 全量本地备份与恢复（开发方案 FR-10.4：换机迁移）。
 *
 * 打包内容（zip，与 [BackupLayout] 的规范一致）：
 * - `manifest.json`：格式版本、时间、应用版本、数据库版本、条目数量；
 * - `db/totop.db`：数据库快照（**先 checkpoint + 关闭写入再拷**，保证一致性）；
 * - `settings.json`：设置表（只含非敏感项；API Key 在 Android Keystore，**不备份、不迁移**）；
 * - `images/<sha256>.jpg` 与 `images/thumbs/...`：题目原图与缩略图。
 *
 * 安全与合规：
 * - 备份包只写入用户通过 SAF 选择的位置或 App 私有目录，**不经过任何网络**；
 * - 恢复时校验 [BackupLayout.isSafeEntryName] 防 zip slip（`../`）、
 *   校验清单版本与数据库版本（[BackupLayout.checkCompatibility]）后才覆盖；
 * - 恢复前先把现有数据整体挪到 `backup_before_restore/`，失败可回滚，绝不静默丢数据。
 *
 * 不引入任何第三方压缩库：java.util.zip 是 JDK/Android 内置能力。
 */
@Singleton
class BackupManager
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val database: AppDatabase,
        private val settingsDao: SettingsDao,
    ) {
        private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO

        /**
         * 生成备份包并写到 [destinationUri]（SAF），或默认写到 App 导出目录。
         *
         * @param destinationUri `ACTION_CREATE_DOCUMENT` 返回的 Uri；为 null 时写到 filesDir/exports
         */
        suspend fun backup(
            destinationUri: String? = null,
            nowUtc: Long = System.currentTimeMillis(),
            appVersion: String = "",
            deviceModel: String = Build.MODEL ?: "",
            listener: BackupProgressListener? = null,
        ): BackupOutcome =
            withContext(ioDispatcher) {
                val dbFile = databaseFile()
                if (!dbFile.exists()) {
                    return@withContext BackupOutcome.Failure("数据库文件不存在，无法备份。请先正常使用一次应用再试。")
                }
                val images = imageFiles()
                val settings = runCatching { settingsDao.all() }.getOrDefault(emptyList())
                val settingsJson = settingsToJson(settings.associate { it.key to it.value })
                val manifest =
                    BackupLayout.Manifest(
                        formatVersion = BackupLayout.FORMAT_VERSION,
                        createdAtUtc = nowUtc,
                        appVersion = appVersion,
                        dbVersion = AppDatabase.VERSION,
                        imageCount = images.size,
                        settingsCount = settings.size,
                        deviceModel = deviceModel,
                    )

                val workDir =
                    File(context.cacheDir, BACKUP_TEMP_DIR).apply {
                        if (exists()) deleteRecursively()
                        mkdirs()
                    }
                val staged = File(workDir, "totop.db")
                val stagedOk = runCatching { stageDatabase(dbFile, staged) }.getOrDefault(false)
                if (!stagedOk) {
                    workDir.deleteRecursively()
                    return@withContext BackupOutcome.Failure("数据库快照失败，请重试；若反复失败请先清理应用缓存。")
                }

                val totalSteps = images.size + 3
                val written =
                    if (destinationUri.isNullOrBlank()) {
                        writeZipToAppStorage(manifest, staged, settingsJson, images) { progress ->
                            listener?.onProgress("已备份 " + progress + " 张图片", progress, totalSteps)
                        }
                    } else {
                        writeZipToUri(Uri.parse(destinationUri), manifest, staged, settingsJson, images) { progress ->
                            listener?.onProgress("正在备份第 " + progress + " 个文件", progress, totalSteps)
                        }
                    }
                workDir.deleteRecursively()
                when (written) {
                    is ZipResult.Failure -> BackupOutcome.Failure(written.userMessage, written.detail)
                    is ZipResult.Success ->
                        BackupOutcome.Success(
                            uri = written.location,
                            bytes = written.bytes,
                            imageCount = images.size,
                            manifest = manifest,
                        )
                }
            }

        /**
         * 从备份包恢复。
         *
         * @param sourceUri 用户通过 `ACTION_OPEN_DOCUMENT` 选择的备份包
         */
        suspend fun restore(
            sourceUri: String,
            nowUtc: Long = System.currentTimeMillis(),
            listener: BackupProgressListener? = null,
        ): RestoreOutcome =
            withContext(ioDispatcher) {
                val uri = Uri.parse(sourceUri)
                val manifestJson =
                    readEntry(uri, BackupLayout.MANIFEST)
                        ?: return@withContext RestoreOutcome.Failure("这不是有效的 To Top 备份包（缺少 manifest.json）。")
                val manifest =
                    BackupLayout.parseManifest(manifestJson)
                        ?: return@withContext RestoreOutcome.Failure("备份清单损坏，无法恢复。")
                when (val compatibility = BackupLayout.checkCompatibility(manifest, AppDatabase.VERSION)) {
                    is BackupLayout.Compatibility.Ok -> Unit
                    else -> return@withContext RestoreOutcome.Failure(compatibility.userMessage)
                }

                // 校验所有条目名称后再落盘，防止 zip slip 覆盖到应用私有目录之外
                val entries =
                    listEntryNames(uri)
                        ?: return@withContext RestoreOutcome.Failure("备份包无法读取，请确认文件完整。")
                val unsafe = entries.firstOrNull { !BackupLayout.isSafeEntryName(it) }
                if (unsafe != null) {
                    return@withContext RestoreOutcome.Failure("备份包包含非法路径（" + unsafe + "），已拒绝恢复。")
                }

                val rollbackDir =
                    File(context.filesDir, ROLLBACK_DIR).apply {
                        if (exists()) deleteRecursively()
                        mkdirs()
                    }
                val dbFile = databaseFile()
                val backupCopy = File(rollbackDir, dbFile.name)
                if (dbFile.exists() && !dbFile.renameTo(backupCopy)) {
                    // 重命名失败时退化为复制 + 删除
                    runCatching {
                        dbFile.copyTo(backupCopy, overwrite = true)
                        dbFile.delete()
                    }
                }

                val imageDir = File(context.filesDir, IMAGE_DIR)
                var restoredImages = 0
                val restored =
                    runCatching {
                        ZipInputStream(context.contentResolver.openInputStream(uri)?.buffered()).use { zip ->
                            var entry: ZipEntry? = zip.nextEntry
                            var index = 0
                            while (entry != null) {
                                val name = entry.name
                                if (!entry.isDirectory && BackupLayout.isSafeEntryName(name)) {
                                    when {
                                        name == BackupLayout.DB_PREFIX + dbFile.name -> {
                                            FileOutputStream(dbFile).use { out -> zip.copyTo(out) }
                                        }

                                        name == BackupLayout.SETTINGS -> {
                                            val text = zip.readBytes().toString(Charsets.UTF_8)
                                            settingsFromJson(text).forEach { (key, value) ->
                                                settingsDao.upsert(
                                                    com.totop.app.data.local.entity.SettingsEntity(
                                                        key = key,
                                                        value = value,
                                                        updatedAt = nowUtc,
                                                    ),
                                                )
                                            }
                                        }

                                        name.startsWith(BackupLayout.IMAGES_PREFIX) -> {
                                            // 外层的 !entry.isDirectory 已过滤掉 images/、images/thumbs/ 这类目录条目
                                            val target = File(imageDir, name.removePrefix(BackupLayout.IMAGES_PREFIX))
                                            target.parentFile?.mkdirs()
                                            FileOutputStream(target).use { out -> zip.copyTo(out) }
                                            restoredImages++
                                        }
                                    }
                                    index++
                                    listener?.onProgress("正在恢复第 " + index + " 个文件", index, entries.size)
                                }
                                zip.closeEntry()
                                entry = zip.nextEntry
                            }
                        }
                        true
                    }.getOrDefault(false)

                if (!restored) {
                    // 回滚：把原数据库放回去，避免用户数据被半途破坏
                    runCatching {
                        if (backupCopy.exists()) {
                            if (dbFile.exists()) dbFile.delete()
                            backupCopy.renameTo(dbFile)
                        }
                        rollbackDir.deleteRecursively()
                    }
                    return@withContext RestoreOutcome.Failure("恢复失败，已自动回滚到恢复前的数据。请检查备份文件是否完整。")
                }

                rollbackDir.deleteRecursively()
                RestoreOutcome.Success(
                    restoredImages = restoredImages,
                    manifest = manifest,
                    note = "已恢复 " + restoredImages + " 张图片与设置。请重启应用以加载新数据。",
                )
            }

        /** 当前数据库文件。 */
        private fun databaseFile(): File {
            val name = database.openHelper.databaseName ?: AppDatabase.NAME
            return context.getDatabasePath(name)
        }

        /**
         * 数据库一致性快照。
         *
         * 直接用文件拷贝会读到 WAL 未落盘的内容，因此先执行 `PRAGMA wal_checkpoint(TRUNCATE)`，
         * 把 WAL 合并回主库，再做字节拷贝；Room 的写入仍可能并发，所以这一步在 IO 线程串行执行。
         */
        private fun stageDatabase(
            source: File,
            target: File,
        ): Boolean {
            runCatching {
                database.openHelper.writableDatabase
                SQLiteDatabase.openDatabase(
                    source.absolutePath,
                    null,
                    SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
                ).use { db ->
                    db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { cursor ->
                        if (cursor.moveToFirst()) Unit
                    }
                }
            }
            return runCatching {
                source.copyTo(target, overwrite = true)
                target.length() > 0L
            }.getOrDefault(false)
        }

        /** 所有图片文件（主图 + 缩略图），按路径排序保证备份可复现。 */
        private fun imageFiles(): List<File> {
            val dir = File(context.filesDir, IMAGE_DIR)
            if (!dir.exists()) return emptyList()
            return dir.walkTopDown()
                .filter { it.isFile && it.extension.lowercase() == "jpg" }
                .sortedBy { it.absolutePath }
                .toList()
        }

        private sealed class ZipResult {
            data class Success(val location: String, val bytes: Long) : ZipResult()

            data class Failure(val userMessage: String, val detail: String? = null) : ZipResult()
        }

        private fun writeZipToAppStorage(
            manifest: BackupLayout.Manifest,
            dbSnapshot: File,
            settingsJson: String,
            images: List<File>,
            onProgress: (Int) -> Unit,
        ): ZipResult =
            runCatching {
                val dir = File(context.filesDir, ExportFileWriter.EXPORT_DIR).apply { mkdirs() }
                val target = File(dir, BackupLayout.fileNameOf(manifest.createdAtUtc))
                FileOutputStream(target).use { out ->
                    ZipOutputStream(out.buffered()).use { zip ->
                        writeZipEntries(zip, manifest, dbSnapshot, settingsJson, images, onProgress)
                    }
                }
                val uri =
                    runCatching {
                        androidx.core.content.FileProvider.getUriForFile(
                            context,
                            context.packageName + ExportFileWriter.FILE_PROVIDER_SUFFIX,
                            target,
                        ).toString()
                    }.getOrElse { target.absolutePath }
                ZipResult.Success(uri, target.length())
            }.getOrElse { ZipResult.Failure("备份失败，请检查存储空间。", it.message) }

        private fun writeZipToUri(
            uri: Uri,
            manifest: BackupLayout.Manifest,
            dbSnapshot: File,
            settingsJson: String,
            images: List<File>,
            onProgress: (Int) -> Unit,
        ): ZipResult =
            runCatching {
                val out =
                    context.contentResolver.openOutputStream(uri, "wt")
                        ?: return@runCatching ZipResult.Failure("无法写入所选位置，请换一个目录。")
                out.use { stream ->
                    ZipOutputStream(stream.buffered()).use { zip ->
                        writeZipEntries(zip, manifest, dbSnapshot, settingsJson, images, onProgress)
                    }
                }
                ZipResult.Success(uri.toString(), 0L)
            }.getOrElse { ZipResult.Failure("备份失败，请检查所选目录是否可写。", it.message) }

        private fun writeZipEntries(
            zip: ZipOutputStream,
            manifest: BackupLayout.Manifest,
            dbSnapshot: File,
            settingsJson: String,
            images: List<File>,
            onProgress: (Int) -> Unit,
        ) {
            zip.putNextEntry(ZipEntry(BackupLayout.MANIFEST))
            zip.write(manifest.toJsonString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            zip.putNextEntry(ZipEntry(BackupLayout.DB_PREFIX + dbSnapshot.name))
            FileInputStream(dbSnapshot).use { it.copyTo(zip) }
            zip.closeEntry()

            zip.putNextEntry(ZipEntry(BackupLayout.SETTINGS))
            zip.write(settingsJson.toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            val imageDir = File(context.filesDir, IMAGE_DIR)
            images.forEachIndexed { index, file ->
                val relative = file.absolutePath.removePrefix(imageDir.absolutePath).trimStart(File.separatorChar)
                zip.putNextEntry(ZipEntry(BackupLayout.IMAGES_PREFIX + relative.replace(File.separatorChar, '/')))
                FileInputStream(file).use { it.copyTo(zip) }
                zip.closeEntry()
                onProgress(index + 1)
            }
        }

        /** 只读某个条目的文本内容。 */
        private fun readEntry(
            uri: Uri,
            entryName: String,
        ): String? =
            runCatching {
                ZipInputStream(context.contentResolver.openInputStream(uri)?.buffered()).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        if (entry.name == entryName) return@use zip.readBytes().toString(Charsets.UTF_8)
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                    null
                }
            }.getOrNull()

        /** 列出全部条目名。 */
        private fun listEntryNames(uri: Uri): List<String>? =
            runCatching {
                ZipInputStream(context.contentResolver.openInputStream(uri)?.buffered()).use { zip ->
                    val names = ArrayList<String>()
                    var entry = zip.nextEntry
                    while (entry != null) {
                        names += entry.name
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                    names
                }
            }.getOrNull()

        companion object {
            /** 主图目录（相对 filesDir，与 ImagePreprocess 保持一致）。 */
            const val IMAGE_DIR: String = "images"

            /** 备份临时目录（相对 cacheDir）。 */
            const val BACKUP_TEMP_DIR: String = "backup_temp"

            /** 恢复前的回滚目录（相对 filesDir）。 */
            const val ROLLBACK_DIR: String = "backup_before_restore"

            /** 把 SAF 选中的目录转成该目录下的文件 Uri（部分 ROM 的 OPEN_DOCUMENT_TREE 返回目录）。 */
            suspend fun resolveFileInTree(
                context: Context,
                treeUri: String,
                fileName: String,
            ): String? =
                runCatching {
                    val tree = DocumentFile.fromTreeUri(context, Uri.parse(treeUri)) ?: return@runCatching null
                    val file =
                        tree.findFile(
                            fileName,
                        ) ?: tree.createFile("application/zip", fileName) ?: return@runCatching null
                    file.uri.toString()
                }.getOrNull()
        }
    }
