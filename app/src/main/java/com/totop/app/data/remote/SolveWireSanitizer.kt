package com.totop.app.data.remote

import com.totop.app.domain.model.ErrorTypeMapper
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 把模型输出的「形状偏差」修回契约要求的形状。
 *
 * 为什么需要它（实测 DeepSeek Flash 这类小模型）：
 * 1. `finalAnswer` 写成数字（`24` 而不是 `"24"`）；
 * 2. `firstError` 直接写成一整句话，而不是 `{stepIdx,type,explain}` 对象；
 * 3. `confidence` 写成 `"0.9"` 或 `"90%"`；
 * 4. `steps` / `studentSteps` / `kpoints` 里混进字符串元素。
 *
 * 这些偏差**语义清楚、方向唯一**，与其重试一次（还可能再错一次、再付一次钱），
 * 不如就地按类型纠正。修不了的（例如 `studentSteps` 是数字、`steps` 是对象而非数组）
 * 保持原样，交给 [AiGateway.validateStageJson] 判失败并触发严格重试——
 * 那里才是「重试」该发生的地方。
 *
 * 特别地，**数组元素写成一整句话**（`"steps":["配方得 (x-1)^2","最小值是 0"]`）
 * 一律按内容转成 `{idx, content}`，不丢弃：内容本来就清楚，丢掉它会让 `steps`
 * 变空、整次解答判失败，代价远大于这一步缺 `why`。整段字符串（不是数组）
 * 只按换行切，不按标点切——按逗号切等于替模型重新分步，那才是「猜」。
 *
 * 只做类型与容器形状的纠正，**不补内容、不猜结论**：
 * - 缺字段照样缺，由契约校验报缺失；
 * - 字符串化的 `firstError` 只会被放进 `explain`，类型仍由 [ErrorTypeMapper] 按原文归一。
 */
object SolveWireSanitizer {
    /** 整份契约（四阶段合并结果）的形状纠正。 */
    fun sanitizeContract(input: JsonObject): JsonObject =
        buildJsonObject {
            put("question", sanitizeQuestion(input["question"]))
            put("standardSolution", sanitizeSolution(input["standardSolution"]))
            put("studentSteps", sanitizeStudentSteps(input["studentSteps"]))
            put("firstError", sanitizeFirstError(input["firstError"]))
            put("knowledgePoints", sanitizeKpoints(input["knowledgePoints"]))
            put("summaryCard", sanitizeSummaryCard(input["summaryCard"]))
            put("aiGenerated", input["aiGenerated"] ?: JsonPrimitive(true))
        }

    /** 阶段 A 的识题结果：主要用于把选项、已知条件里的数字纠正成字符串。 */
    fun sanitizeQuestion(element: JsonElement?): JsonObject {
        val source = element as? JsonObject ?: return buildJsonObject { }
        return buildJsonObject {
            textOf(source["subject"])?.let { put("subject", it) }
            textOf(source["grade"])?.let { put("grade", it) }
            textOf(source["stem"])?.let { put("stem", it) }
            put("known", textArrayOf(source["known"]))
            textOf(source["ask"])?.let { put("ask", it) }
            put("options", textArrayOf(source["options"]))
            put("hasFigure", boolOf(source["hasFigure"]) ?: false)
            textOf(source["choiceType"])?.let { put("choiceType", it) }
            textOf(source["handwriting"])?.let { put("handwriting", it) }
            textOf(source["handwritingText"])?.let { put("handwritingText", it) }
            (source["progress"] as? JsonArray)?.let { put("progress", it) }
            put("warnings", textArrayOf(source["warnings"]))
        }
    }

    /** 阶段 B 的解答：补 `steps` 容器与元素形状，保留答案与方法原文。 */
    fun sanitizeSolution(element: JsonElement?): JsonObject {
        val source = element as? JsonObject ?: return buildJsonObject { }
        return buildJsonObject {
            put("steps", sanitizeSteps(source["steps"]))
            textOf(source["finalAnswer"])?.let { put("finalAnswer", it) }
            textOf(source["method"])?.let { put("method", it) }
            textOf(source["check"])?.let { put("check", it) }
        }
    }

    /** 解答步骤：字符串元素按内容补成一步，`idx` 按出现顺序补。 */
    fun sanitizeSteps(element: JsonElement?): JsonArray {
        // JsonArrayBuilder 不暴露 size，手工数：补出来的 idx 只保证从 1 起递增，
        // 不参与统计，够用且不会被模型原有的跳号带歪。
        var nextIndex = 0
        return buildJsonArray {
            stepItemsOf(element).forEach { item ->
                when (item) {
                    is JsonObject -> {
                        // 模型已经给了 idx 就照用（它可能跳号，但那是它的语义）
                        add(
                            buildJsonObject {
                                intOf(item["idx"])?.let { put("idx", it) }
                                textOf(item["content"])?.let { put("content", it) }
                                textOf(item["why"])?.let { put("why", it) }
                            },
                        )
                        nextIndex++
                    }
                    // 模型把一步写成一句话：内容就是这一步，直接补成对象
                    is JsonPrimitive ->
                        textOf(item)?.takeIf { it.isNotBlank() }?.let { text ->
                            nextIndex++
                            add(
                                buildJsonObject {
                                    put("idx", nextIndex)
                                    put("content", text)
                                },
                            )
                        }
                    else -> Unit
                }
            }
        }
    }

    /**
     * 学生过程：数组、单个对象、整段字符串都收。
     *
     * 字符串元素同样转成一步——学生过程本来就是学生的原话，
     * 丢掉这句等于把他的思路删掉一段，比保留更糟。
     */
    fun sanitizeStudentSteps(element: JsonElement?): JsonArray {
        var nextIndex = 0
        return buildJsonArray {
            stepItemsOf(element).forEach { item ->
                when (item) {
                    is JsonObject -> {
                        add(
                            buildJsonObject {
                                intOf(item["idx"])?.let { put("idx", it) }
                                textOf(item["content"])?.let { put("content", it) }
                                textOf(item["status"])?.let { put("status", it) }
                            },
                        )
                        nextIndex++
                    }
                    is JsonPrimitive ->
                        textOf(item)?.takeIf { it.isNotBlank() }?.let { text ->
                            nextIndex++
                            add(
                                buildJsonObject {
                                    put("idx", nextIndex)
                                    put("content", text)
                                },
                            )
                        }
                    else -> Unit
                }
            }
        }
    }

    /**
     * 首个错误：契约要求对象或 null。
     *
     * 模型写成一句话时（`"firstError": "第二步符号写反了"`），
     * 把整句放进 `explain`、类型留空，让 [ErrorTypeMapper] 按原文归一——
     * 若这句话恰好就是七类之一（例如「计算失误」），仍会被识别成对应类别。
     */
    fun sanitizeFirstError(element: JsonElement?): JsonElement {
        if (element == null || element is JsonNull) return JsonNull
        val source =
            when (element) {
                is JsonObject -> element
                is JsonArray -> element.firstOrNull() as? JsonObject
                // 纯量：整句当 explain
                is JsonPrimitive -> {
                    val text = textOf(element)?.takeIf { it.isNotBlank() }
                    if (text == null) return JsonNull
                    val looksLikeType = ErrorTypeMapper.mapToTypeOrNull(text) != null
                    return buildJsonObject {
                        put("explain", text)
                        if (looksLikeType) put("type", text)
                    }
                }
                else -> null
            }
                ?: return JsonNull
        return buildJsonObject {
            intOf(source["stepIdx"])?.let { put("stepIdx", it) }
            intOf(source["subQuestion"])?.let { put("subQuestion", it) }
            textOf(source["type"])?.let { put("type", it) }
            textOf(source["explain"])?.let { put("explain", it) }
            textOf(source["correctThinking"])?.let { put("correctThinking", it) }
        }.takeIf { it.isNotEmpty() } ?: JsonNull
    }

    /** 知识点：只保留带受控 ID 的对象，其它形状丢弃（宁可少一条，也不造一个假知识点）。 */
    fun sanitizeKpoints(element: JsonElement?): JsonArray =
        buildJsonArray {
            (element as? JsonArray).orEmpty().forEach { item ->
                val kpoint = item as? JsonObject ?: return@forEach
                val id = textOf(kpoint["id"])?.trim().orEmpty()
                if (id.isEmpty()) return@forEach
                add(
                    buildJsonObject {
                        put("id", id)
                        textOf(kpoint["name"])?.let { put("name", it) }
                        doubleOf(kpoint["confidence"])?.let { put("confidence", it) }
                        textOf(kpoint["usage"])?.let { put("usage", it) }
                    },
                )
            }
        }

    /** 摘要卡：契约要求对象；写成一句话时放进 `title`，要点留空。 */
    fun sanitizeSummaryCard(element: JsonElement?): JsonElement {
        if (element == null || element is JsonNull) return JsonNull
        if (element is JsonPrimitive) {
            val text = textOf(element)?.takeIf { it.isNotBlank() } ?: return JsonNull
            return buildJsonObject { put("title", text) }
        }
        val source = element as? JsonObject ?: return JsonNull
        return buildJsonObject {
            textOf(source["title"])?.let { put("title", it) }
            put("bullets", textArrayOf(source["bullets"]))
        }
    }

    /** 取文本：字符串、数字、布尔都算文本；对象与数组不算。 */
    fun textOf(element: JsonElement?): String? = (element as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    /** 取整数：兼容 `1`、`"1"`、`"1.0"`。 */
    private fun intOf(element: JsonElement?): Int? {
        val text = textOf(element)?.trim() ?: return null
        return text.toIntOrNull() ?: text.toDoubleOrNull()?.toInt()
    }

    /** 取小数：兼容 `0.9`、`"0.9"`、`"90%"`。 */
    private fun doubleOf(element: JsonElement?): Double? {
        val raw = textOf(element)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val percent = raw.endsWith("%")
        val number = raw.removeSuffix("%").trim().toDoubleOrNull() ?: return null
        return if (percent) number / 100.0 else number
    }

    private fun boolOf(element: JsonElement?): Boolean? =
        when (val text = textOf(element)?.trim()?.lowercase()) {
            "true", "yes", "1" -> true
            "false", "no", "0" -> false
            else -> null
        }

    /** 任意元素转字符串数组：单值包成一条，数字转文本，null 丢弃。 */
    private fun textArrayOf(element: JsonElement?): JsonArray =
        buildJsonArray {
            when (element) {
                is JsonArray ->
                    element.forEach { item ->
                        textOf(item)?.takeIf { it.isNotBlank() }?.let { add(JsonPrimitive(it)) }
                    }
                is JsonPrimitive ->
                    textOf(element)?.takeIf { it.isNotBlank() }?.let { add(JsonPrimitive(it)) }
                else -> Unit
            }
        }

    /**
     * 步骤来源：数组、单个对象（漏了方括号）、整段字符串。
     *
     * 整段字符串按换行切；只有一行时就是一步——不按标点切，
     * 因为那是替模型重新分步，属于「猜内容」。
     *
     * 解答与学生过程共用这一条规则：两者都是「一组原话」，
     * 形状偏差也一模一样，没有理由写成两份。
     */
    private fun stepItemsOf(element: JsonElement?): List<JsonElement> =
        when (element) {
            is JsonArray -> element.toList()
            is JsonObject -> listOf(element)
            is JsonPrimitive -> textStepsOf(textOf(element).orEmpty())
            else -> emptyList()
        }

    private fun textStepsOf(text: String): List<JsonElement> =
        text.split('\n', '\r')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { JsonPrimitive(it) }
}
