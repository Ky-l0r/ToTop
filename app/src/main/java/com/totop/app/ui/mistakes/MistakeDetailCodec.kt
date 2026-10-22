@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.totop.app.ui.mistakes

import com.totop.app.domain.model.StudentStepStatus
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 错题详情里 JSON 列的解析（纯函数，可 JVM 单测）。
 *
 * 为什么要有这一层：
 * 库里 `correctSolution` / `studentSteps` / `known` / `options` 都是 TEXT 列，
 * 内容是 kotlinx.serialization 编码后的 JSON。详情页直接 `Json.decodeFromString`
 * 会遇到两个现实问题：
 * 1. **单条脏数据会让整页打不开**：只要一条错题的 JSON 被截断，
 *    详情页就抛异常，用户看到的是「加载失败」而不是「这一项的解答没存下来」；
 * 2. 旧版本写入的数据字段可能对不上（例如早期没有 `why`），
 *    严格解析会整体失败。
 *
 * 因此这里统一做「尽力解析」：能解析就解析，解析不出来就返回空结构，
 * 由界面用「解答暂时缺失」这类文案明确告知，绝不静默显示空白。
 */
object MistakeDetailCodec {
    /** 与 [com.totop.app.data.local.Converters] 使用同一份 JSON 配置，保证读写口径一致。 */
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }

    /** 解析字符串数组列；失败返回空列表。 */
    fun decodeStringList(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<String>>(raw) }.getOrDefault(emptyList())
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    /** 解析标准解答列；失败返回空结构（steps 为空、答案为空）。 */
    fun decodeSolution(raw: String?): DecodedSolution {
        if (raw.isNullOrBlank()) return DecodedSolution()
        val parsed =
            runCatching { json.decodeFromString<PersistSolution>(raw) }.getOrNull()
                ?: return DecodedSolution()
        return DecodedSolution(
            steps =
                parsed.steps.mapIndexed { index, step ->
                    MistakeStepUi(
                        idx = step.idx.takeIf { it > 0 } ?: (index + 1),
                        content = step.content.trim(),
                        why = step.why.trim(),
                    )
                }.filter { it.content.isNotEmpty() },
            finalAnswer = parsed.finalAnswer.trim(),
            method = parsed.method.trim(),
        )
    }

    /** 解析学生步骤列；失败返回空列表。 */
    fun decodeStudentSteps(raw: String?): List<StudentStepUi> {
        if (raw.isNullOrBlank()) return emptyList()
        val parsed =
            runCatching { json.decodeFromString<List<PersistStudentStep>>(raw) }.getOrNull()
                ?: return emptyList()
        return parsed.mapIndexed { index, step ->
            StudentStepUi(
                idx = step.idx.takeIf { it > 0 } ?: (index + 1),
                content = step.content.trim(),
                status = StudentStepStatus.fromWire(step.status),
            )
        }.filter { it.content.isNotEmpty() }
    }

    /** 解码后的标准解答。 */
    data class DecodedSolution(
        val steps: List<MistakeStepUi> = emptyList(),
        val finalAnswer: String = "",
        val method: String = "",
    )

    @Serializable
    private data class PersistSolutionStep(
        val idx: Int = 0,
        val content: String = "",
        val why: String = "",
    )

    @Serializable
    private data class PersistSolution(
        val steps: List<PersistSolutionStep> = emptyList(),
        val finalAnswer: String = "",
        val method: String = "",
    )

    @Serializable
    private data class PersistStudentStep(
        val idx: Int = 0,
        val content: String = "",
        val status: String = "",
    )
}
