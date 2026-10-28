package com.totop.app.ui.mistakes

import android.database.Cursor
import com.totop.app.data.local.AppDatabase
import com.totop.app.domain.model.ErrorType
import com.totop.app.domain.model.StudentStepStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 错题详情的一行完整快照（用于「回顾我错在哪里」页面）。
 *
 * 为什么单独一个投影而不是复用领域对象：
 * 详情页要同时展示 question（题干、原图）与 mistake_record（成因、标准解答、学生步骤），
 * 两者分属不同表；用一次 JOIN 取回，避免详情页发三四次查询、再在界面里拼。
 *
 * 所有文本字段都有兜底空串，界面渲染不需要再判 null。
 */
data class MistakeDetailUi(
    val mistakeId: String,
    val questionId: String,
    val subject: String,
    val grade: String,
    val stemText: String,
    val known: List<String>,
    val ask: String,
    val options: List<String>,
    /** 全部页面图片，顺序即页序；旧记录只有第 1 页。 */
    val imagePaths: List<String>,
    val createdAtUtc: Long,
    val errorTypeCode: String,
    val errorExplain: String,
    val firstErrorStepIdx: Int?,
    val finalAnswer: String,
    val method: String,
    val steps: List<MistakeStepUi>,
    val studentSteps: List<StudentStepUi>,
    val kpointNames: List<String>,
    val recurrenceIndex: Int,
    val mode: String,
) {
    /** 科目中文名。 */
    val subjectLabel: String get() = subjectLabelOf(subject)

    /** 错因中文名（未知代码原样展示，不做猜测）。 */
    val errorTypeLabel: String get() = ErrorType.ofCode(errorTypeCode)?.label ?: errorTypeCode

    /** 是否定位到具体出错步骤。 */
    val hasLocatedError: Boolean get() = firstErrorStepIdx != null

    /** 学生是否写过解题过程。 */
    val hasStudentWork: Boolean get() = studentSteps.isNotEmpty()
}

/** 标准解答的一步。 */
data class MistakeStepUi(
    val idx: Int,
    val content: String,
    val why: String,
)

/** 学生自己写的一步及其与标准步骤的对齐状态。 */
data class StudentStepUi(
    val idx: Int,
    val content: String,
    val status: StudentStepStatus,
) {
    /** 状态中文名（用于列表左侧的小标签）。 */
    val statusLabel: String
        get() =
            when (status) {
                StudentStepStatus.CORRECT -> "对"
                StudentStepStatus.WRONG -> "错"
                StudentStepStatus.MISSING -> "缺"
            }
}

/** 错题详情的读取端口。 */
interface MistakeDetailGateway {
    /** 读取一道错题的完整详情；不存在返回 null。 */
    suspend fun detail(mistakeId: String): MistakeDetailUi?
}

/**
 * Room 实现。
 *
 * 查询一次 JOIN 取全：mistake_record × question，知识点名称用子查询聚合。
 * 标准解答与学生步骤在库里是 **JSON 序列化后的文本列**（见 Converters），
 * 因此这里把解析交给纯函数 [MistakeDetailCodec]，保证解析逻辑可单测、
 * 且单条数据损坏不会让整页崩掉（坏字段降级为空列表，页面照常打开）。
 */
@Singleton
class RoomMistakeDetailGateway
    @Inject
    constructor(
        private val database: AppDatabase,
    ) : MistakeDetailGateway {
        private var dispatcher: CoroutineDispatcher = Dispatchers.IO

        internal fun withDispatcher(dispatcher: CoroutineDispatcher): RoomMistakeDetailGateway {
            this.dispatcher = dispatcher
            return this
        }

        override suspend fun detail(mistakeId: String): MistakeDetailUi? =
            withContext(dispatcher) {
                database.query(SQL, arrayOf(mistakeId)).use { cursor ->
                    if (!cursor.moveToFirst()) return@withContext null
                    cursor.toDetail()
                }
            }

        /**
         * 多页图片：优先用题目里存的全页列表，旧记录是空数组时回退到第 1 页。
         *
         * 不做数据回填——旧记录只有一张图，回填也变不出第 2 页，
         * 读取侧兜一下比写一次迁移更省事，也不会动到用户已有数据。
         */
        private fun decodeImagePaths(
            rawJson: String?,
            firstPage: String?,
        ): List<String> {
            val pages = MistakeDetailCodec.decodeStringList(rawJson)
            return when {
                pages.isNotEmpty() -> pages
                firstPage.isNullOrBlank() -> emptyList()
                else -> listOf(firstPage)
            }
        }

        private fun Cursor.toDetail(): MistakeDetailUi {
            val correctSolutionJson = getStringOrNull(COL_CORRECT_PATH).orEmpty()
            val studentStepsJson = getStringOrNull(COL_STUDENT_STEPS).orEmpty()
            val correct = MistakeDetailCodec.decodeSolution(correctSolutionJson)
            val student = MistakeDetailCodec.decodeStudentSteps(studentStepsJson)
            return MistakeDetailUi(
                mistakeId = getStringOrNull(COL_MISTAKE_ID).orEmpty(),
                questionId = getStringOrNull(COL_QUESTION_ID).orEmpty(),
                subject = getStringOrNull(COL_SUBJECT).orEmpty(),
                grade = getStringOrNull(COL_GRADE).orEmpty(),
                stemText = getStringOrNull(COL_STEM).orEmpty(),
                known = MistakeDetailCodec.decodeStringList(getStringOrNull(COL_KNOWN)),
                ask = getStringOrNull(COL_ASK).orEmpty(),
                options = MistakeDetailCodec.decodeStringList(getStringOrNull(COL_OPTIONS)),
                imagePaths = decodeImagePaths(getStringOrNull(COL_IMAGE_PATHS), getStringOrNull(COL_IMAGE_PATH)),
                createdAtUtc = getLong(COL_CREATED_AT),
                errorTypeCode = getStringOrNull(COL_ERROR_TYPE).orEmpty(),
                errorExplain = getStringOrNull(COL_ERROR_EXPLAIN).orEmpty(),
                firstErrorStepIdx = getIntOrNull(COL_FIRST_ERROR_STEP),
                finalAnswer = getStringOrNull(COL_FINAL_ANSWER).orEmpty(),
                method = correct.method,
                steps = correct.steps,
                studentSteps = student,
                kpointNames =
                    getStringOrNull(COL_KPOINT_NAMES)
                        ?.split(SEPARATOR)
                        ?.map { it.trim() }
                        ?.filter { it.isNotEmpty() }
                        .orEmpty(),
                recurrenceIndex = getInt(COL_RECURRENCE),
                mode = getStringOrNull(COL_MODE).orEmpty(),
            )
        }

        private fun Cursor.getIntOrNull(index: Int): Int? = if (isNull(index)) null else getInt(index)

        private companion object {
            const val SEPARATOR: String = "|"

            const val COL_MISTAKE_ID = 0
            const val COL_QUESTION_ID = 1
            const val COL_SUBJECT = 2
            const val COL_GRADE = 3
            const val COL_STEM = 4
            const val COL_KNOWN = 5
            const val COL_ASK = 6
            const val COL_OPTIONS = 7
            const val COL_IMAGE_PATH = 8
            const val COL_CREATED_AT = 9
            const val COL_ERROR_TYPE = 10
            const val COL_ERROR_EXPLAIN = 11
            const val COL_FIRST_ERROR_STEP = 12
            const val COL_FINAL_ANSWER = 13
            const val COL_CORRECT_PATH = 14
            const val COL_STUDENT_STEPS = 15
            const val COL_KPOINT_NAMES = 16
            const val COL_RECURRENCE = 17
            const val COL_MODE = 18
            const val COL_IMAGE_PATHS = 19

            const val SQL =
                "SELECT mr.id AS mistakeId, mr.questionId AS questionId, " +
                    "q.subject AS subject, q.grade AS grade, q.stemText AS stemText, " +
                    "q.knownJson AS knownJson, q.ask AS ask, q.optionsJson AS optionsJson, " +
                    "q.imagePath AS imagePath, mr.createdAt AS createdAt, " +
                    "mr.errorType AS errorType, mr.errorExplain AS errorExplain, " +
                    "mr.firstErrorStepIdx AS firstErrorStepIdx, mr.finalAnswer AS finalAnswer, " +
                    "mr.correctPathJson AS correctPathJson, mr.studentStepsJson AS studentStepsJson, " +
                    "(SELECT GROUP_CONCAT(kp.name, '|') FROM mistake_kpoint AS mk " +
                    "INNER JOIN knowledge_point AS kp ON kp.id = mk.kpointId " +
                    "WHERE mk.mistakeId = mr.id) AS kpointNames, " +
                    "mr.recurrenceIndex AS recurrenceIndex, mr.mode AS mode, " +
                    "q.imagePathsJson AS imagePathsJson " +
                    "FROM mistake_record AS mr INNER JOIN question AS q ON q.id = mr.questionId " +
                    "WHERE mr.id = ? LIMIT 1"
        }
    }
