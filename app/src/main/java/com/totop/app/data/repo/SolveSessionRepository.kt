package com.totop.app.data.repo

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 会话里的一条消息。
 *
 * @param id 本地唯一 ID
 * @param fromStudent true = 学生说的，false = AI 说的
 * @param text 正文。AI 消息是纯文本，学生消息是用户输入的原话
 * @param pages 这条消息附带的题目图片路径（顺序即页序）；AI 消息通常为空
 * @param createdAtUtc 创建时间（UTC 毫秒）
 * @param errorText 非空表示这条 AI 回复失败了，展示重试入口
 * @param kind 消息类型，决定气泡的渲染方式
 */
@Serializable
data class ChatTurn(
    val id: String,
    val fromStudent: Boolean,
    val text: String = "",
    val pages: List<String> = emptyList(),
    val createdAtUtc: Long = 0L,
    val errorText: String? = null,
    val kind: String = KIND_CHAT,
    /**
     * 结构化解题结果的 JSON 快照（仅 [KIND_SOLUTION] 有值）。
     *
     * 为什么存字符串而不是直接存结构体：
     * 仓储层属于 data，展示模型属于 ui，data 不能反向依赖 ui（分层硬约束）。
     * 由 ui 层负责序列化与反序列化，仓储只当不透明的一段文本搬运。
     *
     * 为什么必须存：历史会话重新打开时，若只有纯文本正文，
     * 这条气泡就退化成「普通 AI 消息」——追问上下文拿不到原题与原解，
     * 用户打字追问会被当成新题重解一遍。这正是老版本的表现。
     */
    val solutionJson: String? = null,
) {
    /** 是否是一条「解题报告」消息（由四阶段结构化结果渲染）。 */
    val isSolution: Boolean get() = kind == KIND_SOLUTION

    companion object {
        /** 普通对话消息。 */
        const val KIND_CHAT: String = "chat"

        /** 结构化解题结果消息。 */
        const val KIND_SOLUTION: String = "solution"
    }
}

/**
 * 一次完整的问答会话（一道题 + 基于它的所有追问）。
 *
 * 为什么要把「一道题」当成一个会话：
 * 用户的心智模型就是「一个问题一个记录」。把同一道题的追问散落在全局时间线里，
 * 回顾时根本找不到「我当时问的是哪道题」。
 */
@Serializable
data class SolveSession(
    val id: String,
    /** 列表标题：取第一句学生输入或题干首行。 */
    val title: String = "",
    /** 副标题：科目 + 轮次数。 */
    val subtitle: String = "",
    val turns: List<ChatTurn> = emptyList(),
    val createdAtUtc: Long = 0L,
    val updatedAtUtc: Long = 0L,
    /** 学生是否已手动「结束」这个会话（结束后可以放心开新对话）。 */
    val archived: Boolean = false,
) {
    /** 轮次（一问一答算一轮）。 */
    val roundCount: Int get() = turns.count { it.fromStudent }

    /** 最后一条消息的时间，列表排序用。 */
    val lastActivityUtc: Long get() = maxOf(updatedAtUtc, createdAtUtc)

    /** 列表展示的标题：为空时给一个兜底。 */
    val displayTitle: String get() = title.ifBlank { "未命名对话" }
}

/**
 * 会话的本地仓储（多对话管理）。
 *
 * 为什么用文件而不是 Room 表：
 * 1. **零迁移**：新增表要升 DB version + Migration + MigrationTest，
 *    而会话是「阅读记录」性质，不参与任何跨表 JOIN / 聚合统计；
 * 2. 一条会话整体读写（打开就是整段历史），不像错题本那样需要分页检索；
 * 3. 会话体量可控：单条会话几十条消息，全部加载进内存也就几十 KB。
 *
 * 数据完整性：
 * - 原子写（先写 .tmp 再 rename），进程被杀不会留下半个 JSON；
 * - 读失败返回空列表而不是抛异常，界面显示「暂无对话」而不是崩溃；
 * - 单条会话损坏时跳过它继续解析其余，避免一条坏数据毁掉全部历史。
 */
@Singleton
class SolveSessionRepository
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO

        private val json =
            Json {
                ignoreUnknownKeys = true
                prettyPrint = false
                encodeDefaults = true
            }

        private val dir: File
            get() = File(context.filesDir, DIR)

        private val indexFile: File
            get() = File(dir, INDEX_FILE)

        /** 会话列表（按最近活动倒序）。损坏的会话被跳过。 */
        suspend fun all(): List<SolveSession> =
            withContext(ioDispatcher) {
                val ids =
                    runCatching {
                        val raw = indexFile.takeIf { it.exists() }?.readText().orEmpty()
                        if (raw.isBlank()) emptyList() else json.decodeFromString(IdList.serializer(), raw).ids
                    }.getOrDefault(emptyList())
                ids.mapNotNull { id -> readSession(id) }
                    .sortedByDescending { it.lastActivityUtc }
            }

        /** 读取一条会话；不存在或损坏返回 null。 */
        suspend fun byId(id: String): SolveSession? = withContext(ioDispatcher) { readSession(id) }

        /**
         * 新建会话并落盘。
         *
         * @param title 初始标题（通常是题干首行）
         */
        suspend fun create(title: String = ""): SolveSession =
            withContext(ioDispatcher) {
                val now = System.currentTimeMillis()
                val session =
                    SolveSession(
                        id = UUID.randomUUID().toString(),
                        title = title.trim().take(MAX_TITLE),
                        createdAtUtc = now,
                        updatedAtUtc = now,
                    )
                writeSession(session)
                session
            }

        /**
         * 整体覆盖保存一条会话。
         *
         * 刻意用「整体覆盖」而不是「增量追加」：会话是纯展示数据，
         * 覆盖写永远得到一致状态，不会出现追加写一半导致消息错位。
         */
        suspend fun save(session: SolveSession) {
            withContext(ioDispatcher) {
                val updated = session.copy(updatedAtUtc = System.currentTimeMillis())
                writeSession(updated)
            }
        }

        /** 重命名。 */
        suspend fun rename(
            id: String,
            title: String,
        ) {
            withContext(ioDispatcher) {
                val current = readSession(id) ?: return@withContext
                writeSession(current.copy(title = title.trim().take(MAX_TITLE)))
            }
        }

        /** 归档（学生手动结束对话）。 */
        suspend fun setArchived(
            id: String,
            archived: Boolean,
        ) {
            withContext(ioDispatcher) {
                val current = readSession(id) ?: return@withContext
                writeSession(current.copy(archived = archived))
            }
        }

        /** 删除一条会话及其索引项。 */
        suspend fun delete(id: String) {
            withContext(ioDispatcher) {
                runCatching { File(dir, fileNameOf(id)).delete() }
                writeIndex(readIndex().filterNot { it == id })
            }
        }

        /** 清空全部会话（设置页「清空对话历史」）。 */
        suspend fun deleteAll() {
            withContext(ioDispatcher) {
                readIndex().forEach { id -> runCatching { File(dir, fileNameOf(id)).delete() } }
                writeIndex(emptyList())
            }
        }

        /** 修剪：只保留最近 [keep] 条，其余连同数据文件一起删除。 */
        suspend fun trimTo(keep: Int) {
            withContext(ioDispatcher) {
                if (keep <= 0) return@withContext
                val sorted = all()
                if (sorted.size <= keep) return@withContext
                sorted.drop(keep).forEach { session ->
                    runCatching { File(dir, fileNameOf(session.id)).delete() }
                }
                writeIndex(sorted.take(keep).map { it.id })
            }
        }

        // ---------- 文件读写 ----------

        private fun readSession(id: String): SolveSession? {
            if (id.isBlank()) return null
            val file = File(dir, fileNameOf(id))
            if (!file.exists()) return null
            return runCatching {
                json.decodeFromString(SolveSession.serializer(), file.readText())
            }.getOrNull()
        }

        private fun writeSession(session: SolveSession) {
            ensureDir() ?: return
            val file = File(dir, fileNameOf(session.id))
            atomicWrite(file, json.encodeToString(SolveSession.serializer(), session))
            val ids = readIndex()
            // 新建的会话放到最前，保证「最近活动」与索引顺序大体一致
            val next = (listOf(session.id) + ids.filterNot { it == session.id })
            writeIndex(next)
        }

        private fun readIndex(): List<String> {
            val raw = runCatching { indexFile.takeIf { it.exists() }?.readText().orEmpty() }.getOrDefault("")
            if (raw.isBlank()) return emptyList()
            return runCatching { json.decodeFromString(IdList.serializer(), raw).ids }.getOrDefault(emptyList())
        }

        private fun writeIndex(ids: List<String>) {
            ensureDir() ?: return
            atomicWrite(indexFile, json.encodeToString(IdList.serializer(), IdList(ids.distinct())))
        }

        private fun ensureDir(): File? {
            val target = dir
            if (target.exists()) return target
            return if (target.mkdirs()) target else null
        }

        /** 原子写：先写 .tmp 再 rename，避免进程被杀留下半个文件。 */
        private fun atomicWrite(
            target: File,
            content: String,
        ) {
            val parent = target.parentFile ?: return
            val tmp = File(parent, target.name + ".tmp")
            runCatching {
                tmp.writeText(content)
                if (target.exists()) target.delete()
                tmp.renameTo(target)
            }
        }

        /** 会话文件名：ID 只允许安全字符，防止路径穿越。 */
        private fun fileNameOf(id: String): String {
            val safe = id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
            return (safe.ifBlank { "invalid" }) + ".json"
        }

        /** 索引文件结构。 */
        @Serializable
        private data class IdList(val ids: List<String> = emptyList())

        companion object {
            /** 存放目录（相对 filesDir）。 */
            const val DIR: String = "sessions"

            /** 索引文件名。 */
            const val INDEX_FILE: String = "index.json"

            /** 标题最大字符数。 */
            const val MAX_TITLE: Int = 40

            /** 默认保留的会话条数上限（超出后删除最旧的）。 */
            const val DEFAULT_KEEP: Int = 100
        }
    }
