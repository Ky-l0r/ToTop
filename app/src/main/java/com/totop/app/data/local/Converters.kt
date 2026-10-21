@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.totop.app.data.local

import androidx.room.TypeConverter
import com.totop.app.domain.model.MasterySnapshot
import com.totop.app.domain.model.SolutionStep
import com.totop.app.domain.model.StandardSolution
import com.totop.app.domain.model.StudentStep
import com.totop.app.domain.model.StudentStepStatus
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Room 列的类型转换器（开发方案 5.1 中 *Json 列）。
 *
 * 约定：
 * 1. 所有复杂类型的值以 TEXT 存储，列名保持文档中的 *Json 命名；
 * 2. 数组类列（knownJson / optionsJson / pitfallJson / studentStepsJson / historyJson）
 *    一律存「裸 JSON 数组」，便于直接用 SQL 或导出脚本读取；
 * 3. JSON 编解码只做机械转换，不做业务兜底：结构损坏时抛 [SerializationException]，
 *    由调用方决定如何处理，避免把损坏数据悄悄变成默认值；
 * 4. 空列表编码为 `[]` 而不是 null，保证列非空；
 * 5. 转换器是纯 Kotlin，不依赖 Android API，可在 JVM 单测覆盖。
 */
class Converters {
    // ---------- 通用字符串数组（question.knownJson / optionsJson、knowledge_point.pitfallJson） ----------

    @TypeConverter
    fun fromStringList(value: List<String>?): String = json.encodeToString(value.orEmpty())

    @TypeConverter
    fun toStringList(raw: String?): List<String> =
        if (raw.isNullOrBlank()) emptyList() else json.decodeFromString<List<String>>(raw)

    // ---------- 学生步骤（mistake_record.studentStepsJson） ----------

    @TypeConverter
    fun fromStudentSteps(steps: List<StudentStep>?): String =
        json.encodeToString(steps.orEmpty().map { PersistStudentStep(it.idx, it.content, it.status.wire) })

    @TypeConverter
    fun toStudentSteps(raw: String?): List<StudentStep> {
        if (raw.isNullOrBlank()) return emptyList()
        return json.decodeFromString<List<PersistStudentStep>>(raw).map {
            StudentStep(idx = it.idx, content = it.content, status = StudentStepStatus.fromWire(it.status))
        }
    }

    // ---------- 标准解答（mistake_record.correctPathJson） ----------

    @TypeConverter
    fun fromStandardSolution(solution: StandardSolution?): String {
        val value = solution ?: StandardSolution()
        return json.encodeToString(
            PersistSolution(
                steps = value.steps.map { PersistSolutionStep(it.idx, it.content, it.why) },
                finalAnswer = value.finalAnswer,
                method = value.method,
            ),
        )
    }

    @TypeConverter
    fun toStandardSolution(raw: String?): StandardSolution {
        if (raw.isNullOrBlank()) return StandardSolution()
        val value = json.decodeFromString<PersistSolution>(raw)
        return StandardSolution(
            steps = value.steps.map { SolutionStep(idx = it.idx, content = it.content, why = it.why) },
            finalAnswer = value.finalAnswer,
            method = value.method,
        )
    }

    // ---------- 掌握度历史快照（mastery.historyJson，最多 90 条） ----------

    @TypeConverter
    fun fromMasteryHistory(history: List<MasterySnapshot>?): String =
        json.encodeToString(history.orEmpty().map { PersistSnapshot(it.dateEpochDayUtc, it.score) })

    @TypeConverter
    fun toMasteryHistory(raw: String?): List<MasterySnapshot> {
        if (raw.isNullOrBlank()) return emptyList()
        return json.decodeFromString<List<PersistSnapshot>>(raw).mapNotNull {
            // 越界快照说明数据被外部改写或旧版本口径不同，丢弃该条而不是让构造函数抛异常
            runCatching { MasterySnapshot(dateEpochDayUtc = it.day, score = it.score) }.getOrNull()
        }
    }

    /** Room 与 kotlinx.serialization 共用的 JSON 配置：[Json] 实例无状态，可安全共享。 */
    private companion object {
        val json: Json =
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
                explicitNulls = false
            }
    }

    // ---------- 持久化 DTO（只在 data 层使用，不对外暴露） ----------

    @Serializable
    internal data class PersistStudentStep(val idx: Int, val content: String, val status: String)

    @Serializable
    internal data class PersistSolutionStep(val idx: Int, val content: String, val why: String)

    @Serializable
    internal data class PersistSolution(
        val steps: List<PersistSolutionStep> = emptyList(),
        val finalAnswer: String = "",
        val method: String = "",
    )

    @Serializable
    internal data class PersistSnapshot(val day: Long, val score: Double)
}
