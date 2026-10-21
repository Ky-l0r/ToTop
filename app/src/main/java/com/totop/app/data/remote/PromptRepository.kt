package com.totop.app.data.remote

import com.totop.app.domain.model.AiQuestion
import com.totop.app.domain.model.AiSolutionStep
import com.totop.app.domain.model.AiSolveResultCodec
import com.totop.app.domain.model.AiStage
import com.totop.app.domain.model.AiStandardSolution
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 候选知识点（阶段 D 的候选清单元素，开发方案 6.3：id/name/chapter）。
 *
 * [subject] 不发给模型（提示词 schema 里只有 id/name/chapter），
 * 只用于客户端在阶段 A 识别出科目后把候选清单收敛到同一科目，
 * 避免一道物理题的候选里混进语文、英语的节点。
 */
data class CandidateKpoint(
    val id: String,
    val name: String,
    val chapter: String = "",
    val subject: String = "",
)

/**
 * 四个阶段的输入载体。
 *
 * 类型系统本身就是第一道防泄漏闸门：
 * 阶段 B 的输入类型里根本没有学生过程字段，从编译期杜绝「把学生过程喂给独立求解」。
 */
sealed class StageInput {
    abstract val stage: AiStage

    /**
     * 阶段 A：结构化识题。
     *
     * @param ocrText ML Kit 识别文本（可为空，此时依赖图片）
     * @param supplement 学生补充说明（例如「第(2)问不要求」）
     * @param attachImage 是否随请求发送图片
     */
    data class Identify(
        val ocrText: String,
        val supplement: String = "",
        val attachImage: Boolean = false,
    ) : StageInput() {
        override val stage: AiStage get() = AiStage.A
    }

    /** 阶段 B：独立求解，只含题目本身。 */
    data class Solve(
        val question: AiQuestion,
    ) : StageInput() {
        override val stage: AiStage get() = AiStage.B
    }

    /** 阶段 C：错因定位，必须注入学生过程与标准解答。 */
    data class LocateError(
        val question: AiQuestion,
        val studentRawText: String,
        val standardSolution: AiStandardSolution,
    ) : StageInput() {
        override val stage: AiStage get() = AiStage.C
    }

    /** 阶段 D：知识点抽取，必须注入候选清单。 */
    data class AssignKpoints(
        val question: AiQuestion,
        val standardSolution: AiStandardSolution,
        val candidates: List<CandidateKpoint>,
    ) : StageInput() {
        override val stage: AiStage get() = AiStage.D
    }
}

/** 拼装完成的提示词。 */
data class AssembledPrompt(
    val stage: AiStage,
    val promptVersion: String,
    /** system 消息：阶段模板全文（角色/输入/输出/禁止事项/Schema/示例）。 */
    val systemPrompt: String,
    /** user 消息：本次真实输入（JSON）+ 输出约束。 */
    val userPrompt: String,
    /** user 消息中注入的字段名，供 [PromptGuard] 校验阶段边界。 */
    val payloadKeys: Set<String>,
    /** 是否为「解析失败后更严格」的第二次尝试。 */
    val strict: Boolean = false,
) {
    fun asMessages(): List<ChatMessage> =
        listOf(
            ChatMessage.system(systemPrompt),
            ChatMessage.user(userPrompt),
        )
}

/**
 * 阶段边界守卫（开发方案 6.3：阶段 B 禁止给学生过程、阶段 C 必须注入学生过程、
 * 阶段 D 必须注入候选知识点清单）。
 *
 * 这是纯函数校验，既在 [PromptRepository] 拼装时执行，也可被单测直接断言。
 */
object PromptGuard {
    /** 阶段 B 绝对禁止出现的字段名（学生过程相关）。 */
    val STUDENT_PROCESS_KEYS: Set<String> =
        setOf(
            "studentRawText",
            "studentSteps",
            "studentAnswer",
            "studentProcess",
            "firstError",
        )

    /** 各阶段的必需要素。 */
    fun requiredKeys(stage: AiStage): Set<String> =
        when (stage) {
            AiStage.A -> setOf("ocrText")
            AiStage.B -> setOf("question")
            AiStage.C -> setOf("question", "standardSolution", "studentRawText")
            AiStage.D -> setOf("question", "standardSolution", "candidateKpoints")
        }

    /** 阶段 B 的输入不得包含任何学生过程字段。 */
    fun forbiddenKeys(stage: AiStage): Set<String> = if (stage == AiStage.B) STUDENT_PROCESS_KEYS else emptySet()

    /**
     * 校验一个阶段的注入字段集合。
     *
     * @return 违规描述清单；为空表示合规
     */
    fun violations(
        stage: AiStage,
        payloadKeys: Set<String>,
    ): List<String> {
        val problems = mutableListOf<String>()
        val missing = requiredKeys(stage) - payloadKeys
        if (missing.isNotEmpty()) {
            problems.add("阶段 " + stage.wire + " 缺少必需输入：" + missing.sorted().joinToString("、"))
        }
        val forbidden = forbiddenKeys(stage) intersect payloadKeys
        if (forbidden.isNotEmpty()) {
            problems.add("阶段 " + stage.wire + " 禁止注入学生过程字段：" + forbidden.sorted().joinToString("、"))
        }
        return problems
    }

    /** 缺字段直接抛异常（拼装期即失败，不留到运行期）。 */
    fun requireValid(
        stage: AiStage,
        payloadKeys: Set<String>,
    ) {
        val problems = violations(stage, payloadKeys)
        require(problems.isEmpty()) { problems.joinToString("；") }
    }
}

/**
 * 提示词仓库：从 assets/prompts/ 读取四阶段模板与 promptVersion，并按阶段拼装。
 *
 * 约束（开发方案 6.3、12.1 第 3 条）：
 * - 提示词文本一律外置 assets，禁止硬编码进 Kotlin；
 * - 阶段 B 只注入题目，绝不注入学生过程；
 * - 阶段 C 必须注入学生过程与标准解答；
 * - 阶段 D 必须注入候选知识点清单；
 * - 解析失败后的第二次尝试使用 [AssembledPrompt.strict] 更严格提示词（FR-1.10）。
 */
class PromptRepository(
    private val assetReader: AssetReader,
    private val json: Json = AiSolveResultCodec.json,
) {
    /** 资源目录前缀。 */
    private val root = "prompts"

    private var cachedVersion: PromptVersionFile? = null
    private val templateCache = HashMap<AiStage, String>()

    /** 提示词版本（参与缓存键）。 */
    fun promptVersion(): String = versionFile().promptVersion

    /** 各阶段对应的资源文件名。 */
    fun stageFiles(): Map<AiStage, String> {
        val stages = versionFile().stages
        return AiStage.values().mapNotNull { stage ->
            stages[stage.wire]?.let { stage to it }
        }.toMap()
    }

    /** 读取某阶段模板全文。 */
    fun template(stage: AiStage): String =
        templateCache.getOrPut(stage) {
            val file =
                stageFiles()[stage]
                    ?: error("assets/prompts 未配置阶段 " + stage.wire + " 的模板文件")
            assetReader.read(root + "/" + file)
        }

    /** 按阶段拼装提示词。 */
    fun assemble(
        input: StageInput,
        strict: Boolean = false,
        requestId: String = "",
    ): AssembledPrompt {
        val payload = payloadOf(input)
        val keys = payload.keys
        PromptGuard.requireValid(input.stage, keys)
        val builder = StringBuilder()
        if (requestId.isNotBlank()) {
            builder.append("client_request_id: ").append(requestId).append('\n')
        }
        builder.append("以下是本次调用的输入（JSON）：\n")
        builder.append(json.encodeToString(JsonObject.serializer(), payload))
        builder.append("\n\n")
        builder.append(outputInstruction(input.stage, strict))
        return AssembledPrompt(
            stage = input.stage,
            promptVersion = promptVersion(),
            systemPrompt = template(input.stage),
            userPrompt = builder.toString(),
            payloadKeys = keys,
            strict = strict,
        )
    }

    /** 构造阶段输入 JSON。 */
    fun payloadOf(input: StageInput): JsonObject =
        when (input) {
            is StageInput.Identify ->
                buildJsonObject {
                    put("ocrText", input.ocrText)
                    put("supplement", input.supplement)
                    put("attachImage", input.attachImage)
                }

            is StageInput.Solve ->
                buildJsonObject {
                    put("question", questionJson(input.question))
                }

            is StageInput.LocateError ->
                buildJsonObject {
                    put("question", questionJson(input.question))
                    put("standardSolution", solutionJson(input.standardSolution))
                    put("studentRawText", input.studentRawText)
                }

            is StageInput.AssignKpoints ->
                buildJsonObject {
                    put("question", questionJson(input.question))
                    put("standardSolution", solutionJson(input.standardSolution))
                    put(
                        "candidateKpoints",
                        buildJsonArray {
                            input.candidates.forEach { candidate ->
                                add(
                                    buildJsonObject {
                                        put("id", candidate.id)
                                        put("name", candidate.name)
                                        put("chapter", candidate.chapter)
                                    },
                                )
                            }
                        },
                    )
                }
        }

    /** 输出约束文案；strict 为 true 时追加「只输出 JSON、补齐缺字段」的硬约束。 */
    fun outputInstruction(
        stage: AiStage,
        strict: Boolean,
    ): String {
        val base =
            when (stage) {
                AiStage.A -> "请只输出阶段 A 的 JSON 对象（字段见 system 中的 Schema），不要输出任何解释文字。"
                AiStage.B -> "请只输出阶段 B 的 JSON 对象（steps/finalAnswer/method/check/confidence），不要提及学生，不要输出解释文字。"
                AiStage.C -> "请只输出阶段 C 的 JSON 对象（studentSteps/firstError/summaryCard），type 只能取七个固定错因之一。"
                AiStage.D -> "请只输出阶段 D 的 JSON 对象（kpoints/primaryId/unmatched），id 必须来自 candidateKpoints。"
            }
        if (!strict) return base
        return base + "\n" +
            "注意：上一次输出无法被解析。本次务必严格满足：\n" +
            "1. 整个回复只包含一个 JSON 对象，前后不得有任何文字、Markdown 代码围栏或注释；\n" +
            "2. 不要出现省略号、占位符或未闭合的数组；\n" +
            "3. 所有 Schema 中 required 的字段必须出现，字符串不得为空；\n" +
            "4. 数字字段只写数字，不要加引号或单位。"
    }

    private fun questionJson(question: AiQuestion): JsonObject =
        buildJsonObject {
            put("subject", question.subject ?: "math")
            put("grade", question.grade ?: "未知")
            put("stem", question.stem.orEmpty())
            put("known", buildJsonArray { question.known.forEach { add(JsonPrimitive(it)) } })
            put("ask", question.ask.orEmpty())
            put("options", buildJsonArray { question.options.forEach { add(JsonPrimitive(it)) } })
            put("hasFigure", question.hasFigure)
        }

    private fun solutionJson(solution: AiStandardSolution): JsonObject =
        buildJsonObject {
            put(
                "steps",
                buildJsonArray {
                    solution.steps.forEach { step -> add(stepJson(step)) }
                },
            )
            put("finalAnswer", solution.finalAnswer.orEmpty())
            put("method", solution.method.orEmpty())
        }

    private fun stepJson(step: AiSolutionStep): JsonObject =
        buildJsonObject {
            put("idx", step.idx ?: 0)
            put("content", step.content.orEmpty())
            put("why", step.why.orEmpty())
        }

    private fun versionFile(): PromptVersionFile =
        cachedVersion ?: run {
            val raw = assetReader.read(root + "/prompt_version.json")
            val parsed = json.decodeFromString(PromptVersionFile.serializer(), raw)
            cachedVersion = parsed
            parsed
        }

    /** 清空内部缓存（提示词版本升级或测试复用实例时）。 */
    fun invalidate() {
        cachedVersion = null
        templateCache.clear()
    }

    companion object {
        /** assets/prompts/prompt_version.json 的结构。 */
        @Serializable
        data class PromptVersionFile(
            val promptVersion: String = "1.0.0",
            val releasedAt: String = "",
            val description: String = "",
            val stages: Map<String, String> = emptyMap(),
            val rules: List<String> = emptyList(),
        )
    }
}

/** 资源读取抽象：Android 用 [AndroidAssetReader]，单测用内存映射。 */
fun interface AssetReader {
    fun read(path: String): String

    companion object {
        /** 内存实现：单测与预览使用。 */
        fun of(files: Map<String, String>): AssetReader =
            AssetReader { path ->
                files[path] ?: error("测试资源不存在：" + path)
            }
    }
}
