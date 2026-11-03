package com.totop.app.ui.reviewmaterial

import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 历史复习资料的摘要（列表用）。
 *
 * 只带展示需要的字段：列表上要能一眼分清「哪一科、什么时候生成的、多长」，
 * 不需要把整份正文读进内存。
 */
data class ReviewMaterialSummary(
    val subjectLabel: String,
    /** 生成时间（UTC 毫秒），同时是文件主键。 */
    val createdAtUtc: Long,
    /** 本地时区的可读时间，例如「11 月 3 日 23:40」。 */
    val dateLabel: String,
    val charCount: Int,
    val mistakesUsed: Int,
    val sectionCount: Int,
)

/**
 * 复习资料的本地存档。
 *
 * 为什么不建 Room 表：
 * 1. 它是**生成出来的文档**，不是需要关联查询的事实数据——没有外键、没有聚合、没有跨表统计，
 *    加表的唯一收益是「列表查询更快」，而这里最多留 30 份、每份几 KB，读目录就够了；
 * 2. 加表要同时动四处：升 version、写 Migration、提交 schema JSON、补迁移测试，
 *    任何一处出错都会让老用户启动即崩，收益与风险不成比例；
 * 3. 与 PDF 的落点一致（都是文件），「这份资料还在不在」只有一处判断。
 *
 * 代价（必须记住）：这些资料不进数据库备份，也不参与掌握度统计；
 * 清理应用数据会一并消失。这个取舍写在 docs/偏离记录.md 里。
 *
 * 存储形态：`filesDir/review_materials/<createdAtUtc>.json`，一份一个文件，
 * 写盘走临时文件再改名，避免中途被杀留下半个 JSON。
 */
class ReviewMaterialStore(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val dir: File = File(context.filesDir, DIR_NAME)

    /** 保存一份资料；同一时间戳覆盖旧文件（重新生成不会重复堆叠）。 */
    suspend fun save(material: ReviewMaterial): Boolean =
        withContext(ioDispatcher) {
            runCatching {
                ensureDir()
                val target = File(dir, material.createdAtUtc.toString() + SUFFIX)
                val temp = File(dir, target.name + TEMP_SUFFIX)
                temp.writeText(encode(material), Charsets.UTF_8)
                if (target.exists()) target.delete()
                // renameTo 在个别存储上会失败，失败时退化为复制，保证不留临时文件
                if (!temp.renameTo(target)) {
                    temp.copyTo(target, overwrite = true)
                    temp.delete()
                }
                trimToLimit()
                true
            }.getOrDefault(false)
        }

    /** 全部历史，按时间倒序（最新在前）。坏文件直接跳过，不让一份损坏拖垮列表。 */
    suspend fun list(): List<ReviewMaterialSummary> =
        withContext(ioDispatcher) {
            runCatching {
                files().mapNotNull { file -> readSummary(file) }
            }.getOrDefault(emptyList())
        }

    /** 按时间戳读回一份完整资料。 */
    suspend fun load(createdAtUtc: Long): ReviewMaterial? =
        withContext(ioDispatcher) {
            val file = File(dir, createdAtUtc.toString() + SUFFIX)
            if (!file.exists()) return@withContext null
            runCatching { decode(file.readText(Charsets.UTF_8)) }.getOrNull()
        }

    /** 删除一份；文件不存在也算成功（重复点删除不该报错）。 */
    suspend fun delete(createdAtUtc: Long): Boolean =
        withContext(ioDispatcher) {
            val file = File(dir, createdAtUtc.toString() + SUFFIX)
            runCatching { !file.exists() || file.delete() }.getOrDefault(false)
        }

    private fun ensureDir() {
        if (!dir.exists()) dir.mkdirs()
    }

    /** 按文件名（即时间戳）倒序取全部存档。 */
    private fun files(): List<File> =
        dir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(SUFFIX) }
            .orEmpty()
            .sortedByDescending { it.nameWithoutExtension.toLongOrNull() ?: 0L }

    /** 只保留最近 [MAX_FILES] 份，避免长期使用后无限增长。 */
    private fun trimToLimit() {
        files().drop(MAX_FILES).forEach { file -> runCatching { file.delete() } }
    }

    private fun readSummary(file: File): ReviewMaterialSummary? {
        val material = runCatching { decode(file.readText(Charsets.UTF_8)) }.getOrNull() ?: return null
        return ReviewMaterialSummary(
            subjectLabel = material.subjectLabel,
            createdAtUtc = material.createdAtUtc,
            dateLabel = dateLabel(material.createdAtUtc),
            charCount = material.charCount,
            mistakesUsed = material.mistakesUsed,
            sectionCount = material.sections.size,
        )
    }

    private fun dateLabel(createdAtUtc: Long): String =
        if (createdAtUtc <= 0L) {
            ""
        } else {
            SimpleDateFormat("M 月 d 日 HH:mm", Locale.CHINA).format(Date(createdAtUtc))
        }

    /** 序列化成自包含的 JSON：字段名与模型一致，方便以后排查与外部解析。 */
    private fun encode(material: ReviewMaterial): String {
        val root = JSONObject()
        root.put("v", FORMAT_VERSION)
        root.put("subjectLabel", material.subjectLabel)
        root.put("examDate", material.examDate)
        root.put("summary", material.summary)
        root.put("mistakesUsed", material.mistakesUsed)
        root.put("createdAtUtc", material.createdAtUtc)
        val sections = JSONArray()
        material.sections.forEach { section ->
            val sectionJson = JSONObject()
            sectionJson.put("title", section.title)
            val blocks = JSONArray()
            section.blocks.forEach { block ->
                val blockJson = JSONObject()
                blockJson.put("style", block.style.name)
                blockJson.put("lines", JSONArray(block.lines))
                blocks.put(blockJson)
            }
            sectionJson.put("blocks", blocks)
            sections.put(sectionJson)
        }
        root.put("sections", sections)
        return root.toString()
    }

    /** 反序列化；缺字段或格式不对时抛异常，由调用方决定跳过还是报错。 */
    private fun decode(raw: String): ReviewMaterial {
        val root = JSONObject(raw)
        val sectionsJson = root.optJSONArray("sections") ?: JSONArray()
        val sections = ArrayList<ReviewSection>(sectionsJson.length())
        for (sectionIndex in 0 until sectionsJson.length()) {
            val sectionJson = sectionsJson.optJSONObject(sectionIndex) ?: continue
            val title = sectionJson.optString("title").trim()
            if (title.isEmpty()) continue
            val blocksJson = sectionJson.optJSONArray("blocks") ?: JSONArray()
            val blocks = ArrayList<ReviewBlock>(blocksJson.length())
            for (blockIndex in 0 until blocksJson.length()) {
                val blockJson = blocksJson.optJSONObject(blockIndex) ?: continue
                val linesJson = blockJson.optJSONArray("lines") ?: continue
                val lines = ArrayList<String>(linesJson.length())
                for (lineIndex in 0 until linesJson.length()) {
                    val line = linesJson.optString(lineIndex)
                    if (line.isNotBlank()) lines.add(line)
                }
                if (lines.isEmpty()) continue
                blocks.add(
                    ReviewBlock(
                        style = ReviewBlockStyle.parse(blockJson.optString("style")),
                        lines = lines,
                    ),
                )
            }
            if (blocks.isEmpty()) continue
            sections.add(ReviewSection(title = title, blocks = blocks))
        }
        require(sections.isNotEmpty()) { "存档里没有可用章节" }
        return ReviewMaterial(
            subjectLabel = root.optString("subjectLabel"),
            examDate = root.optString("examDate", "考前复习"),
            summary = root.optString("summary"),
            sections = sections,
            mistakesUsed = root.optInt("mistakesUsed", 0),
            createdAtUtc = root.optLong("createdAtUtc", 0L),
        )
    }

    private companion object {
        /** 存档目录名。 */
        const val DIR_NAME: String = "review_materials"

        /** 文件后缀。 */
        const val SUFFIX: String = ".json"

        /** 写入中的临时后缀。 */
        const val TEMP_SUFFIX: String = ".tmp"

        /** 最多保留几份历史：再多也没人翻，还会占空间。 */
        const val MAX_FILES: Int = 30

        /** 存档格式版本，将来结构变化时用于兼容判断。 */
        const val FORMAT_VERSION: Int = 1
    }
}
