package com.totop.app.data.repo

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 离线记题的一条记录。
 *
 * @param id 本地唯一 ID
 * @param stemText 用户手写的题目文本（可以为空，图片才是主体）
 * @param studentProcess 用户手写的解题思路
 * @param imagePaths 本机图片路径，顺序即页序
 * @param createdAtUtc 创建时间（UTC 毫秒），列表按它倒序
 * @param solved 是否已经在联网后解过（解过就置 true，列表里折叠）
 */
@Serializable
data class OfflineNote(
    val id: String,
    val stemText: String = "",
    val studentProcess: String = "",
    val imagePaths: List<String> = emptyList(),
    val createdAtUtc: Long = 0L,
    val solved: Boolean = false,
) {
    /** 列表标题：优先用题目文本，没有就用页数描述。 */
    val title: String
        get() =
            stemText.lineSequence().firstOrNull { it.isNotBlank() }?.take(28)
                ?: if (imagePaths.isEmpty()) "空白记录" else "拍了 " + imagePaths.size + " 页"

    /** 列表副标题：说明有几页、有没有写思路。 */
    val subtitle: String
        get() {
            val parts = mutableListOf<String>()
            if (imagePaths.isNotEmpty()) parts.add(imagePaths.size.toString() + " 页图片")
            parts.add(if (studentProcess.isBlank()) "没写思路" else "已写思路")
            return parts.joinToString(" · ")
        }
}

/**
 * 离线记题的本地仓储。
 *
 * 为什么用文件而不是 Room 表：
 * 1. **零迁移**。新增表需要升 DB version + 写 Migration + 补 MigrationTest，
 *    而这条功能的数据是「临时草稿」性质，用文件更合适，也彻底排除迁移风险；
 * 2. 离线记题不参与任何跨表查询（不需要 JOIN、分页、聚合），
 *    文件一次性读入内存即可，简单且够用；
 * 3. 直接序列化成 JSON，日后要迁到表里也只是搬家，不会丢数据。
 *
 * 原子性：写入先写临时文件再 rename，避免进程被杀时留下半个 JSON。
 */
@Singleton
class OfflineNoteRepository
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        /** IO 调度器：构造参数默认值不会被 Hilt 识别，因此在类内固定为 IO。 */
        private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO

        private val json =
            Json {
                ignoreUnknownKeys = true
                prettyPrint = false
            }

        private val file: File
            get() = File(File(context.filesDir, DIR), FILE_NAME)

        /** 读取全部记录，按时间倒序。文件不存在或损坏时返回空列表而不是抛异常。 */
        suspend fun all(): List<OfflineNote> =
            withContext(ioDispatcher) {
                val raw =
                    runCatching { file.takeIf { it.exists() }?.readText() }.getOrNull()
                        ?: return@withContext emptyList()
                if (raw.isBlank()) return@withContext emptyList()
                runCatching { json.decodeFromString(ListSerializer(OfflineNote.serializer()), raw) }
                    .getOrDefault(emptyList())
                    .sortedByDescending { it.createdAtUtc }
            }

        /** 新增一条记录，返回落盘后的记录。 */
        suspend fun save(
            stemText: String,
            studentProcess: String,
            imagePaths: List<String>,
        ): OfflineNote =
            withContext(ioDispatcher) {
                val note =
                    OfflineNote(
                        id = UUID.randomUUID().toString(),
                        stemText = stemText,
                        studentProcess = studentProcess,
                        // 只保留真实存在的文件，避免之后点开是空图
                        imagePaths = imagePaths.filter { path -> File(path).exists() },
                        createdAtUtc = System.currentTimeMillis(),
                    )
                persist(all() + note)
                note
            }

        /** 删除一条。 */
        suspend fun delete(id: String) {
            withContext(ioDispatcher) {
                persist(all().filterNot { it.id == id })
            }
        }

        /** 标记为已解。 */
        suspend fun markSolved(id: String) {
            withContext(ioDispatcher) {
                persist(all().map { if (it.id == id) it.copy(solved = true) else it })
            }
        }

        /** 原子写：先写 .tmp 再 rename。 */
        private fun persist(notes: List<OfflineNote>) {
            val target = file
            val dir = target.parentFile ?: return
            if (!dir.exists() && !dir.mkdirs()) return
            val tmp = File(dir, FILE_NAME + ".tmp")
            runCatching {
                tmp.writeText(json.encodeToString(ListSerializer(OfflineNote.serializer()), notes))
                if (target.exists()) target.delete()
                tmp.renameTo(target)
            }
        }

        companion object {
            /** 存放目录（相对 filesDir）。 */
            const val DIR: String = "offline"

            /** 数据文件名。 */
            const val FILE_NAME: String = "notes.json"
        }
    }
