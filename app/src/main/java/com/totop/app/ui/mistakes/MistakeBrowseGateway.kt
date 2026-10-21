package com.totop.app.ui.mistakes

import android.database.Cursor
import com.totop.app.data.local.AppDatabase
import com.totop.app.domain.model.ErrorType
import com.totop.app.domain.usecase.port.DAY_MILLIS
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 错题本查询端口（分页 + 筛选 + 搜索）。
 *
 * 为什么要单独抽一层：
 * 错题本需要「科目 / 知识点 / 错因 / 时间 / 关键词」五个条件同时生效的**分页**查询，
 * 而 data 层现有的 DAO 只提供按错因、按知识点的单条件分页。
 * 本任务的改动范围限定在 ui/ 四个包，不得修改 data 层，因此在 ui 层新增一个**适配层**：
 * 上层（ViewModel）只看到 [MistakeCardUi] 与 [Page]，SQL 只出现在本文件的实现里，
 * 与「ViewModel 不直接接触 SQL / Retrofit」的约束不冲突。
 *
 * 查询一律参数化绑定（禁止字符串拼接用户输入），关键词按 LIKE 字面量转义。
 */
interface MistakeBrowseGateway {
    /** 一页结果。 */
    data class Page(
        val items: List<MistakeCardUi>,
        /** 满足筛选条件的总条数，用于「共 N 道」与是否还有下一页。 */
        val total: Int,
        val page: Int,
        val pageSize: Int,
    ) {
        /** 是否还有下一页。 */
        val hasMore: Boolean get() = (page + 1) * pageSize < total
    }

    /**
     * 分页查询错题。
     *
     * @param filter 筛选条件
     * @param page 从 0 开始
     * @param pageSize 每页条数（1..100）
     * @param nowUtc 用于计算时间窗口下界
     */
    suspend fun page(
        filter: MistakeFilter,
        page: Int,
        pageSize: Int,
        nowUtc: Long,
    ): Page

    /** 全部科目代码（去重，用于科目筛选下拉）。 */
    suspend fun subjects(): List<String>

    /** 某知识点下最近一道错题的题干（巩固练习的参考原型题）。 */
    suspend fun latestStemOfKpoint(kpointId: String): String?
}

/** Room 实现：用 RoomDatabase 的参数化查询构造分页结果。 */
@Singleton
class RoomMistakeBrowseGateway
    @Inject
    constructor(
        private val database: AppDatabase,
    ) : MistakeBrowseGateway {
        private var dispatcher: CoroutineDispatcher = Dispatchers.IO

        internal fun withDispatcher(dispatcher: CoroutineDispatcher): RoomMistakeBrowseGateway {
            this.dispatcher = dispatcher
            return this
        }

        override suspend fun page(
            filter: MistakeFilter,
            page: Int,
            pageSize: Int,
            nowUtc: Long,
        ): MistakeBrowseGateway.Page =
            withContext(dispatcher) {
                val boundedSize = pageSize.coerceIn(1, MAX_PAGE_SIZE)
                val boundedPage = page.coerceAtLeast(0)
                val where = WhereBuilder().apply(filter, nowUtc)
                val total = countOf(where)
                if (total == 0) {
                    return@withContext MistakeBrowseGateway.Page(emptyList(), 0, boundedPage, boundedSize)
                }
                val args = where.args.toMutableList()
                args += boundedSize
                args += boundedPage * boundedSize
                val cursor =
                    database.query(
                        SELECT_PAGE + where.sql + ORDER_BY + " LIMIT ? OFFSET ?",
                        args.toTypedArray(),
                    )
                val items =
                    cursor.use { row ->
                        val list = ArrayList<MistakeCardUi>(row.count)
                        while (row.moveToNext()) {
                            list += row.toCard()
                        }
                        list
                    }
                MistakeBrowseGateway.Page(items = items, total = total, page = boundedPage, pageSize = boundedSize)
            }

        override suspend fun subjects(): List<String> =
            withContext(dispatcher) {
                database.query("SELECT DISTINCT subject FROM question ORDER BY subject ASC", null).use { row ->
                    val list = ArrayList<String>(row.count)
                    while (row.moveToNext()) {
                        val value = row.getStringOrNull(0)
                        if (!value.isNullOrBlank()) list += value
                    }
                    list
                }
            }

        override suspend fun latestStemOfKpoint(kpointId: String): String? =
            withContext(dispatcher) {
                val sql =
                    "SELECT q.stemText AS stemText FROM mistake_kpoint AS mk " +
                        "INNER JOIN mistake_record AS mr ON mr.id = mk.mistakeId " +
                        "INNER JOIN question AS q ON q.id = mr.questionId " +
                        "WHERE mk.kpointId = ? ORDER BY mr.createdAt DESC, mr.id ASC LIMIT 1"
                database.query(sql, arrayOf(kpointId)).use { row ->
                    if (row.moveToFirst()) row.getStringOrNull(0) else null
                }
            }

        private fun countOf(where: WhereBuilder): Int {
            val sql = COUNT_PREFIX + where.sql
            return database.query(sql, where.args.toTypedArray()).use { row ->
                if (row.moveToFirst()) row.getInt(0) else 0
            }
        }

        /**
         * WHERE 子句构造器：只产出条件片段与参数数组，绝不拼接用户输入。
         */
        private class WhereBuilder {
            val args: MutableList<Any> = ArrayList()

            val sql: String
                get() = if (conditions.isEmpty()) "" else " AND " + conditions.joinToString(" AND ")

            private val conditions: MutableList<String> = ArrayList()

            /** 依据筛选条件追加子句。 */
            fun apply(
                filter: MistakeFilter,
                nowUtc: Long,
            ): WhereBuilder {
                filter.subject?.let { subject ->
                    // 科目是 question 表的枚举字段，不参与模糊搜索
                    conditions += "q.subject = ?"
                    args += subject
                }
                filter.kpointId?.let { kpointId ->
                    conditions += "mr.id IN (SELECT mistakeId FROM mistake_kpoint WHERE kpointId = ?)"
                    args += kpointId
                }
                filter.errorType?.let { type ->
                    conditions += "mr.errorType = ?"
                    args += type.code
                }
                filter.timeWindow.fromUtcOrNull(nowUtc)?.let { fromUtc ->
                    conditions += "mr.createdAt >= ?"
                    args += fromUtc
                }
                val keyword = filter.normalizedKeyword
                if (keyword.isNotEmpty()) {
                    val pattern = "%" + MistakeFilter.escapeLike(keyword) + "%"
                    conditions += "(q.stemText LIKE ? ESCAPE '\\' OR mr.finalAnswer LIKE ? ESCAPE '\\' " +
                        "OR mr.errorExplain LIKE ? ESCAPE '\\')"
                    repeat(KEYWORD_FIELD_COUNT) { args += pattern }
                }
                return this
            }
        }

        /** Cursor 单行映射。 */
        private fun Cursor.toCard(): MistakeCardUi {
            val imagePath = getStringOrNull(COL_IMAGE_PATH)
            val errorCode = getStringOrNull(COL_ERROR_TYPE).orEmpty()
            val kpointNames =
                getStringOrNull(COL_KPOINT_NAMES)
                    ?.split(SEPARATOR)
                    ?.filter { it.isNotBlank() }
                    .orEmpty()
            return MistakeCardUi(
                mistakeId = getStringOrNull(COL_MISTAKE_ID).orEmpty(),
                questionId = getStringOrNull(COL_QUESTION_ID).orEmpty(),
                stemText = getStringOrNull(COL_STEM).orEmpty(),
                subject = getStringOrNull(COL_SUBJECT).orEmpty(),
                errorTypeLabel = ErrorType.ofCode(errorCode)?.label ?: errorCode,
                errorTypeCode = errorCode,
                kpointNames = kpointNames,
                recurrenceIndex = getInt(COL_RECURRENCE),
                createdAtUtc = getLong(COL_CREATED_AT),
                imagePath = imagePath,
                thumbnailPath = thumbnailPathOf(imagePath),
                finalAnswer = getStringOrNull(COL_FINAL_ANSWER).orEmpty(),
                hasLocatedErrorStep = !isNull(COL_FIRST_ERROR_STEP),
            )
        }

        companion object {
            /** 单页上限。 */
            const val MAX_PAGE_SIZE: Int = 100

            /** 关键词参与匹配的字段数（题干 / 答案 / 错因说明）。 */
            private const val KEYWORD_FIELD_COUNT: Int = 3

            /** 知识点名称分隔符（SQLite GROUP_CONCAT 的第二参数）。 */
            private const val SEPARATOR: String = "|"

            private const val COL_MISTAKE_ID = 0
            private const val COL_QUESTION_ID = 1
            private const val COL_ERROR_TYPE = 2
            private const val COL_RECURRENCE = 3
            private const val COL_CREATED_AT = 4
            private const val COL_FINAL_ANSWER = 5
            private const val COL_FIRST_ERROR_STEP = 6
            private const val COL_STEM = 7
            private const val COL_SUBJECT = 8
            private const val COL_IMAGE_PATH = 9
            private const val COL_KPOINT_NAMES = 10

            private const val SELECT_PAGE =
                "SELECT mr.id AS mistakeId, mr.questionId AS questionId, " +
                    "mr.errorType AS errorType, mr.recurrenceIndex AS recurrenceIndex, " +
                    "mr.createdAt AS createdAt, mr.finalAnswer AS finalAnswer, " +
                    "mr.firstErrorStepIdx AS firstErrorStepIdx, q.stemText AS stemText, " +
                    "q.subject AS subject, q.imagePath AS imagePath, " +
                    "(SELECT GROUP_CONCAT(kp.name, '|') FROM mistake_kpoint AS mk " +
                    "INNER JOIN knowledge_point AS kp ON kp.id = mk.kpointId " +
                    "WHERE mk.mistakeId = mr.id) AS kpointNames " +
                    "FROM mistake_record AS mr INNER JOIN question AS q ON q.id = mr.questionId " +
                    "WHERE 1 = 1"

            private const val COUNT_PREFIX =
                "SELECT COUNT(*) FROM mistake_record AS mr " +
                    "INNER JOIN question AS q ON q.id = mr.questionId WHERE 1 = 1"

            private const val ORDER_BY = " ORDER BY mr.createdAt DESC, mr.id ASC"

            private const val THUMBNAIL_SUFFIX: String = "_thumb.jpg"

            /**
             * 缩略图路径：列表只加载缩略图（长边 320），原图留给详情页。
             *
             * 缩略图由预处理阶段生成在 images/thumbs/<sha256>_thumb.jpg；
             * 文件缺失时返回 null，界面显示占位块而不是原图，避免列表滚动时解码大图。
             */
            fun thumbnailPathOf(imagePath: String?): String? {
                if (imagePath.isNullOrBlank()) return null
                val main = File(imagePath)
                val stem = main.name.substringBeforeLast('.')
                val candidate = File(File(main.parentFile, "thumbs"), stem + THUMBNAIL_SUFFIX)
                return if (candidate.exists()) candidate.absolutePath else null
            }

            /** 时间窗口下界（供单测直接验证语义）。 */
            fun fromUtcFor(
                window: TimeWindow,
                nowUtc: Long,
            ): Long? = window.days?.let { nowUtc - it.toLong() * DAY_MILLIS }
        }
    }

/** 安全取字符串列：列为 NULL 时返回 null 而不是空串，便于区分「无图」。 */
internal fun Cursor.getStringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)
