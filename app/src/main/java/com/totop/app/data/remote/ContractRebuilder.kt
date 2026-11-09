package com.totop.app.data.remote

import com.totop.app.domain.model.AiFirstError
import com.totop.app.domain.model.AiKnowledgePoint
import com.totop.app.domain.model.AiQuestion
import com.totop.app.domain.model.AiSolutionStep
import com.totop.app.domain.model.AiSolveResult
import com.totop.app.domain.model.AiStandardSolution
import com.totop.app.domain.model.AiStudentStep
import com.totop.app.domain.model.AiSummaryCard
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 把四阶段的原始 JSON **按字段**重建成 [AiSolveResult]，绕开严格反序列化。
 *
 * 为什么需要它（这是一类反复出现、靠改提示词解决不了的问题）：
 *
 * 阶段 A/B/C/D 全部跑完、token 已经花掉之后，`AiSolveResultCodec.decode` 仍可能整份失败：
 * 只要任何一处类型与契约不一致（某个 `confidence` 是 `NaN`、某个步骤数组里套了数组、
 * 某个字段写成了字符串），kotlinx.serialization 就抛异常，调用方只能判「结构不完整」，
 * 学生等了四个阶段的解答直接作废，还得再点一次重试——而重试大概率再错一次。
 *
 * 形状偏差是模型侧的固有属性，重试与提示词都改不掉它（见 docs/偏离记录.md 第 38 章）。
 * 正确的落点是**解析层吸收**：字段能取到就取，取不到就给默认值，实在没有内容才判失败。
 * 本类就是这条落点，只在严格解码失败或结构缺失时被调用，正常路径不经过它。
 *
 * 边界（不做的事）：
 * - 不补内容：没有的文字不会凭空生成，只把模型自己写的字搬到正确的字段里；
 * - 不猜结论：`finalAnswer` 缺失就留空，不从步骤里「推导」一个答案；
 * - 不伪造 ID：知识点没有受控 ID 一律丢弃，避免污染掌握度统计。
 */
internal object ContractRebuilder {
    /** 重建结果与过程中的降级说明（写进 warnings，界面上可见）。 */
    data class Rebuilt(
        val result: AiSolveResult,
        val notes: List<String> = emptyList(),
    )

    /**
     * 重建整份契约。
     *
     * @param question 阶段 A 已解析好的题目（本来就不是从 JSON 反序列化来的，直接用）
     * @param solution 阶段 B 的原始对象
     * @param stageC 阶段 C 的原始对象
     * @param stageD 阶段 D 的原始对象
     */
    fun rebuild(
        question: AiQuestion,
        solution: JsonObject,
        stageC: JsonObject,
        stageD: JsonObject,
    ): Rebuilt {
        val notes = mutableListOf<String>()
        val standardSolution = rebuildSolution(solution, notes)
        return Rebuilt(
            result =
                AiSolveResult(
                    question = question,
                    standardSolution = standardSolution,
                    studentSteps = rebuildStudentSteps(stageC),
                    firstError = rebuildFirstError(stageC),
                    knowledgePoints = rebuildKpoints(stageD),
                    summaryCard = rebuildSummaryCard(stageC),
                    aiGenerated = true,
                ),
            notes = notes,
        )
    }

    /** 标准解答：先按契约取，取不到步骤就把解答里出现过的文字按行重建。 */
    private fun rebuildSolution(
        solution: JsonObject,
        notes: MutableList<String>,
    ): AiStandardSolution {
        val sanitized = SolveWireSanitizer.sanitizeSolution(solution)
        val steps = stepsOf(sanitized["steps"] as? JsonArray)
        if (steps.isNotEmpty()) {
            return AiStandardSolution(
                steps = steps,
                finalAnswer = sanitized.stringOrNull("finalAnswer"),
                method = sanitized.stringOrNull("method"),
                check = sanitized.stringOrNull("check"),
            )
        }
        // 步骤形状彻底认不出来（数组套数组、对象当数组、键名全自造）：
        // 内容仍然是模型写的，按原文重建比整份丢弃划算得多。
        val recovered = recoverSteps(solution)
        if (recovered.isNotEmpty()) {
            notes.add("解答步骤的结构不规范，已按模型原文重新分步（内容未改动）")
        }
        return AiStandardSolution(
            steps = recovered,
            finalAnswer = sanitized.stringOrNull("finalAnswer"),
            method = sanitized.stringOrNull("method"),
            check = sanitized.stringOrNull("check"),
        )
    }

    private fun stepsOf(array: JsonArray?): List<AiSolutionStep> =
        array.orEmpty().mapNotNull { element ->
            val step = element as? JsonObject ?: return@mapNotNull null
            val content = step.stringOrNull("content")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            AiSolutionStep(
                idx = step["idx"]?.let { text -> text.toString().trim('"').toIntOrNull() },
                content = content,
                why = step.stringOrNull("why"),
            )
        }

    /**
     * 从解答对象里捞出所有可读文字，按行切成步骤。
     *
     * 先只捞 `steps` 子树（那里才是步骤），捞不到再放开到整份解答——
     * 此时会多出 `method`、`check` 这类说明性文字，属于可见的重复，
     * 但比「四个阶段白跑」好。`finalAnswer` 例外：它有自己的字段，
     * 而且答案本身不是一步，放进来只会让步骤区多一行孤立的结果。
     */
    private fun recoverSteps(solution: JsonObject): List<AiSolutionStep> {
        val fromSteps = collectTexts(solution["steps"])
        val texts =
            fromSteps.ifEmpty {
                collectTexts(JsonObject(solution.filterKeys { it != "finalAnswer" }))
            }
        return texts
            .flatMap { it.split('\n', '\r') }
            .map { it.trim().trimEnd('，', ',', '。') }
            .filter { it.isNotEmpty() }
            .distinct()
            .mapIndexed { index, line -> AiSolutionStep(idx = index + 1, content = line) }
    }

    /** 递归收集一个 JSON 子树里的全部文本（跳过纯标点与空串），保持出现顺序。 */
    private fun collectTexts(element: JsonElement?): List<String> =
        when (element) {
            null, is JsonNull -> emptyList()
            is JsonPrimitive -> {
                val text = element.content.trim()
                if (text.isEmpty() || text.all { it in JSON_PUNCTUATION }) emptyList() else listOf(text)
            }
            is JsonArray -> element.flatMap { collectTexts(it) }
            is JsonObject -> element.values.flatMap { collectTexts(it) }
            else -> emptyList()
        }

    private fun rebuildStudentSteps(stageC: JsonObject): List<AiStudentStep> {
        val array = SolveWireSanitizer.sanitizeStudentSteps(stageC["studentSteps"])
        return array.mapNotNull { element ->
            val step = element as? JsonObject ?: return@mapNotNull null
            AiStudentStep(
                idx = step.stringOrNull("idx")?.toIntOrNull(),
                content = step.stringOrNull("content"),
                status = step.stringOrNull("status"),
            )
        }
    }

    private fun rebuildFirstError(stageC: JsonObject): AiFirstError? {
        val sanitized = SolveWireSanitizer.sanitizeFirstError(stageC["firstError"]) as? JsonObject ?: return null
        if (sanitized.isEmpty()) return null
        return AiFirstError(
            stepIdx = sanitized.stringOrNull("stepIdx")?.toIntOrNull(),
            subQuestion = sanitized.stringOrNull("subQuestion")?.toIntOrNull(),
            type = sanitized.stringOrNull("type"),
            explain = sanitized.stringOrNull("explain"),
            correctThinking = sanitized.stringOrNull("correctThinking"),
        )
    }

    private fun rebuildKpoints(stageD: JsonObject): List<AiKnowledgePoint> {
        val array = SolveWireSanitizer.sanitizeKpoints(stageD["kpoints"])
        return array.mapNotNull { element ->
            val kpoint = element as? JsonObject ?: return@mapNotNull null
            AiKnowledgePoint(
                id = kpoint.stringOrNull("id"),
                name = kpoint.stringOrNull("name"),
                confidence = kpoint.stringOrNull("confidence")?.toDoubleOrNull()?.takeIf { it.isFinite() },
                usage = kpoint.stringOrNull("usage"),
            )
        }
    }

    private fun rebuildSummaryCard(stageC: JsonObject): AiSummaryCard? {
        val sanitized = SolveWireSanitizer.sanitizeSummaryCard(stageC["summaryCard"]) as? JsonObject ?: return null
        if (sanitized.isEmpty()) return null
        val bullets =
            (sanitized["bullets"] as? JsonArray).orEmpty().mapNotNull { item ->
                (item as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
            }
        return AiSummaryCard(title = sanitized.stringOrNull("title"), bullets = bullets)
    }

    /** JSON 结构里不承载内容的字符，重建步骤时丢掉。 */
    private val JSON_PUNCTUATION: Set<Char> = setOf('{', '}', '[', ']', ':', ',', '"', ' ')
}
