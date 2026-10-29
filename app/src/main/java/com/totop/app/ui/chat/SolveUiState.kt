package com.totop.app.ui.chat

import com.totop.app.data.remote.SolveEvent
import com.totop.app.data.repo.ChatTurn
import com.totop.app.data.repo.SolveSession
import com.totop.app.domain.model.AiQuestion
import com.totop.app.domain.model.AiSolvedContent
import com.totop.app.domain.model.AiStage
import com.totop.app.domain.model.ChoiceAnswerGuard
import com.totop.app.domain.model.ErrorType
import com.totop.app.domain.model.FirstError
import com.totop.app.domain.model.Question
import com.totop.app.domain.model.StandardSolution
import com.totop.app.domain.model.StudentStep
import com.totop.app.domain.model.StudentStepStatus
import com.totop.app.domain.model.Subjects
import com.totop.app.domain.model.SummaryCard
import com.totop.app.domain.model.TeachingMode
import com.totop.app.util.NormalizedRect

/** 顶部网络状态（FR-1.9：请求前先判断网络，不通时明确提示而不是空转）。 */
enum class NetworkStatus(val label: String) {
    ONLINE("网络正常"),
    OFFLINE("无网络连接"),
    UNKNOWN("网络状态未知"),
}

/**
 * 解题页的输入阶段状态机。
 *
 * 与旧版的差别：旧版把「会话内容」也塞进这个状态机（Idle/Ready/Solving/Solved/Failed），
 * 导致「切换页面回来内容丢失」「发送后不能继续追问」两个问题无法根治——
 * 因为状态机每次发送都从 Solved 回到 Ready，历史被覆盖掉了。
 *
 * 现在这个状态机**只描述输入这一侧**（拍照 → 写思路 → 发送 → 等待），
 * 会话内容由 [ConversationMessage] 列表承载并可持久化。
 */
enum class ComposerPhase {
    /** 还没有任何图片，也没有正在进行的解题。 */
    EMPTY,

    /** 已经拍了图，等用户写思路并发送。 */
    READY,

    /** 正在解题（四阶段进行中）。 */
    SOLVING,
}

/** 消息列表里的一条（UI 直接渲染）。 */
data class ConversationMessage(
    val id: String,
    val fromStudent: Boolean,
    val text: String,
    /** 学生消息附带的图片路径（页序）。 */
    val pages: List<String> = emptyList(),
    /** 结构化解题结果；非 null 时渲染成解题气泡。 */
    val solution: SolutionPayload? = null,
    /** 非空表示这条 AI 回复失败，展示原因与重试。 */
    val errorText: String? = null,
    /** 是否还在流式生成中。 */
    val streaming: Boolean = false,
    /** 生成失败时的阶段（用于提示是哪一步出的问题）。 */
    val failedStage: AiStage? = null,
    /** 失败是否可重试。 */
    val retryable: Boolean = false,
    /**
     * 追问里发生了答案订正、并已写回错题本时的回执文案。
     *
     * 单独一个字段而不是拼进 [text]：正文要原样用于下一轮追问的上下文，
     * 混进「已同步错题本」这类系统提示会污染模型的输入。
     */
    val correctionNote: String? = null,
    val createdAtUtc: Long = 0L,
)

/**
 * 解题结果的展示快照。
 *
 * 刻意做成独立的不可变数据类，而不是直接复用 [AiSolvedContent]：
 * 1. [AiSolvedContent] 含 Question / StandardSolution 等完整领域对象，
 *    存进会话文件时字段太多且多数与展示无关；
 * 2. 把「展示要什么」显式列出来，避免以后往里加字段时不小心把内部结构泄漏到存储格式。
 */
@kotlinx.serialization.Serializable
data class SolutionPayload(
    val questionId: String,
    val subjectCode: String,
    val stem: String,
    val known: List<String> = emptyList(),
    val ask: String = "",
    /**
     * 选择题的选项原文（含 A/B/C/D 标记）。
     *
     * 之前这里没有这个字段：题目卡只渲染了题干与「已知 / 求」，
     * 选项整段丢失。后果不只是"看着不完整"——多选题的答案被模型只写了一个字母时，
     * 用户手上没有选项可以对照，根本无从判断到底漏了哪几个。
     */
    val options: List<String> = emptyList(),
    /**
     * 阶段 A 判定的题型（见 [ChoiceAnswerGuard]）。
     *
     * 有了它，才谈得上「按题型限制可选数量」：
     * 旧版只能靠题干里的「多选」字样猜，猜错就放行了一个数量不对的答案。
     */
    val choiceType: String = ChoiceAnswerGuard.UNKNOWN,
    val steps: List<SolutionStepView> = emptyList(),
    val finalAnswer: String = "",
    val method: String = "",
    val firstError: FirstErrorView? = null,
    val kpoints: List<KpointView> = emptyList(),
    val summary: SummaryView? = null,
    /** 引导模式下已解锁的提示级别（0 表示尚未解锁）。 */
    val unlockedHintLevel: Int = 0,
    /** 引导模式下学生是否已选择「我已想通，看全解」。 */
    val fullSolutionRevealed: Boolean = false,
    /** 三级提示；步骤不足时可能只有 1-2 级。 */
    val hints: List<HintLevel> = emptyList(),
    /** 落库后的错题 ID；未入库时为 null。 */
    val mistakeId: String? = null,
    /** 落库失败原因（非致命，仅提示）。 */
    val persistMessage: String? = null,
) {
    /** 科目中文名。 */
    val subjectLabel: String get() = Subjects.label(subjectCode)

    /** 是否定位到了错因。 */
    val hasError: Boolean get() = firstError != null

    /**
     * 答案数量与题型不符时的说明；一致或题型未知时返回 null。
     *
     * 判定统一走 [ChoiceAnswerGuard]：管线里「要不要重答」与界面上「要不要提示」
     * 用的是同一套规则，不会出现「后台认为没问题、界面却在报警」的分叉。
     */
    fun choiceViolation(): String? =
        ChoiceAnswerGuard.violation(
            options = options,
            finalAnswer = finalAnswer,
            choiceType = choiceType,
        )
}

/** 解答步骤的展示行。 */
@kotlinx.serialization.Serializable
data class SolutionStepView(
    val idx: Int,
    val content: String,
    val why: String = "",
)

/** 首个错误的展示行。 */
@kotlinx.serialization.Serializable
data class FirstErrorView(
    val stepIdx: Int,
    val typeLabel: String,
    val explain: String,
    val correctThinking: String = "",
    /**
     * 出错步骤所属的小问编号（1 起）。
     *
     * 0.3.x 之前没有这个字段，提示起点只能拿 [stepIdx]（学生步骤序号）去猜标准步骤的位置，
     * 学生跳步时会把提示指到上一问。旧会话文件里没有该字段，默认为 null 且自动退回旧行为。
     */
    val subQuestion: Int? = null,
)

/** 解答步骤按小问分组后的一段。 */
internal data class StepSection(
    /** 小问标题；整道题没有小问时为空串（不显示标题）。 */
    val title: String,
    val steps: List<SolutionStepView>,
    /** 该段对应的小问编号；整题不分问时为 null。 */
    val number: Int? = null,
)

/**
 * 把步骤链按「(1)」「(2)」这类小问标记切段（纯逻辑，可单测）。
 *
 * 识别规则刻意保守：
 * - 只认行首的「(数字)」「（数字）」「第数字问」；
 * - 未命中时整份步骤只返回一段且 title 为空，界面与旧版完全一致；
 * - 只有当前段已有内容时才允许开新段，避免「由(1)得」这类引用被误当成新小问。
 *
 * 放在 ui/chat 而不是 ui/solve：展示（分组卡片）与引导（提示从小问起）都要用，
 * 两者共用同一份切段规则，避免「界面按小问分组、提示却仍从第 (1) 问起」的错位。
 */
internal object StepSections {
    /** 取步骤开头的小问编号；没有则返回 null。 */
    internal fun markerOf(content: String): Int? {
        val text = content.trimStart()
        if (text.isEmpty()) return null
        // 形态一：(2) 或 （2）
        val first = text.first()
        if (first == '(' || first == '（') {
            val digits = text.drop(1).takeWhile { it in '0'..'9' }
            if (digits.isNotEmpty()) {
                val rest = text.drop(1 + digits.length).trimStart()
                if (rest.firstOrNull() == ')' || rest.firstOrNull() == '）') return digits.toIntOrNull()
            }
            return null
        }
        // 形态二：第2问
        if (text.startsWith("第")) {
            val digits = text.drop(1).takeWhile { it in '0'..'9' }
            if (digits.isNotEmpty() && text.drop(1 + digits.length).startsWith("问")) {
                return digits.toIntOrNull()
            }
        }
        return null
    }

    fun group(steps: List<SolutionStepView>): List<StepSection> {
        if (steps.isEmpty()) return emptyList()
        val sections = mutableListOf<StepSection>()
        val current = mutableListOf<SolutionStepView>()
        var currentNumber: Int? = null
        var title = ""

        fun flush() {
            if (current.isNotEmpty()) {
                sections.add(StepSection(title = title, steps = current.toList(), number = currentNumber))
                current.clear()
            }
        }
        steps.forEach { step ->
            val marker = markerOf(step.content)
            if (marker != null && marker != currentNumber && current.isNotEmpty()) {
                flush()
                currentNumber = marker
                title = "第 " + marker + " 问"
            } else if (marker != null && currentNumber == null) {
                currentNumber = marker
                title = "第 " + marker + " 问"
            }
            current.add(step)
        }
        flush()
        return sections
    }

    /**
     * 卡点所在小问的**首个步骤下标**（0 起），提示从这一步开始给。
     *
     * 两级判定：
     * 1. 有 [subQuestion] 时直接按小问号找段起点。这是阶段 C 契约里的权威信息，
     *    不再借学生步骤序号推位置，因此学生跳步、压缩步骤都不会把提示指错问；
     * 2. 没有 [subQuestion]（旧会话、整题不分问、模型给不出）或标准步骤里找不到该问时，
     *    退回按 [stepIdx] 推算的旧行为：把学生步序号当作位置去找所属段。
     *
     * @param stepIdx [FirstErrorView.stepIdx]，1 起的学生步骤序号。
     * @param subQuestion [FirstErrorView.subQuestion]，1 起的小问号。
     * @return 该步所在小问的起始下标；没有小问标记、越界或两者都为空时返回 0，
     *   退化为「从第一步给提示」。
     */
    fun startIndexOf(
        steps: List<SolutionStepView>,
        stepIdx: Int?,
        subQuestion: Int? = null,
    ): Int {
        if (steps.isEmpty()) return 0
        val sections = group(steps)
        if (subQuestion != null) {
            var cursor = 0
            sections.forEach { section ->
                if (section.number == subQuestion) return cursor
                cursor += section.steps.size
            }
            // 找不到该小问说明模型给的问号与标准步骤对不上（阶段 B 没标小问、或模型猜了一个），
            // 这时不猜「大概在附近」，落到下面的序号推算，最差也是旧行为。
        }
        if (stepIdx == null || stepIdx < 1 || stepIdx > steps.size) return 0
        // 越界说明这个序号与标准步骤对不上（stepIdx 来自学生步骤），
        // 此时不猜「大概在最后一问」——猜错会把提示指到不相干的小问，
        // 退化成整题一段反而更安全。
        val position = stepIdx - 1
        var cursor = 0
        sections.forEach { section ->
            val endExclusive = cursor + section.steps.size
            if (position < endExclusive) {
                // 该段没有小问标记时仍然回到 0：整道题是一段，谈不上「从第几问起」
                return if (section.title.isBlank()) 0 else cursor
            }
            cursor = endExclusive
        }
        return 0
    }
}

/** 知识点的展示行。 */
@kotlinx.serialization.Serializable
data class KpointView(
    val id: String,
    val name: String,
    val usage: String = "",
)

/** 摘要卡展示行。 */
@kotlinx.serialization.Serializable
data class SummaryView(
    val title: String,
    val bullets: List<String> = emptyList(),
)

/** 单阶段进度。 */
data class StageProgress(
    val stage: AiStage,
    val status: StageStatus,
    /** 命中本地缓存时内部标记（界面不再展示「命中缓存 0 token」，属于内部实现细节）。 */
    val cached: Boolean = false,
    /** 首个增量到达耗时（毫秒），内部性能观测用，不展示。 */
    val firstTokenMs: Long? = null,
    val summary: String = "",
) {
    /** 阶段名（中文）。 */
    val label: String get() = stage.label

    /** 阶段字母（A/B/C/D）。 */
    val letter: String get() = stage.wire
}

/** 阶段状态。 */
enum class StageStatus { PENDING, RUNNING, DONE }

/**
 * 拍下的一页题目。
 *
 * @param imagePath 预处理后的主图路径（filesDir/images/&lt;sha256&gt;.jpg）
 * @param sha256 图片内容哈希，参与缓存键
 * @param cropRect 框选区域（归一化 0..1）；null 表示未框选、用整图
 * @param thumbnailPath 列表缩略图路径，可能为 null（缩略图失败不影响主流程）
 */
data class PreparedPage(
    val imagePath: String,
    val sha256: String,
    val cropRect: NormalizedRect? = null,
    val thumbnailPath: String? = null,
) {
    /** 是否做过框选。 */
    val hasCrop: Boolean get() = cropRect != null && cropRect.isEmpty.not()
}

/**
 * 已就绪的题目（OCR 或手输的结果，尚未发起调用）。
 *
 * @param stemText 题干文本（OCR 归一化或手输）；用视觉模型时可以为空
 * @param studentRawText 学生手写的解题过程
 * @param pages 拍下的页面列表。**顺序即页序**，第 1 页在首位。
 * @param avgConfidence OCR 平均置信度，低于阈值时自动改走视觉档
 */
data class PreparedQuestion(
    val stemText: String,
    val studentRawText: String = "",
    val pages: List<PreparedPage> = emptyList(),
    val avgConfidence: Double = 0.0,
    val hasFigure: Boolean = false,
    val isHandwritten: Boolean = false,
    val sourceLabel: String = "手输",
) {
    /** 第 1 页图片路径（兼容既有只关心单图的逻辑）。 */
    val imagePath: String? get() = pages.firstOrNull()?.imagePath

    /** 第 1 页图片哈希（缓存键用第一页，多页时其余页不参与缓存键）。 */
    val imageSha256: String? get() = pages.firstOrNull()?.sha256

    /** 页数。 */
    val pageCount: Int get() = pages.size

    /** 是否多页题目。 */
    val isMultiPage: Boolean get() = pages.size > 1

    /** 是否可以发起调用：题干非空，或至少有一张图。 */
    val canSolve: Boolean get() = stemText.isNotBlank() || pages.isNotEmpty()

    /** 题干字符数（用于界面提示「已识别 N 字」）。 */
    val stemLength: Int get() = stemText.length
}

/**
 * 引导模式的展示策略（纯函数，可单测）。
 *
 * 规则（0.2.0 合并为两档后）：
 * - DIRECT：直接展示全部步骤与最终答案；
 * - GUIDED：**不展示步骤与答案**，只给方向与引导性问题；需逐级解锁三级提示，
 *   或点击「我已想通，看全解」后才展示完整解答。
 *
 * 关键不变式（有单测守着）：GUIDED 且未揭示时，
 * [showsSolutionSteps] 与 [showsFinalAnswer] 必须同时为 false。
 * 任何一处遗漏都会让学生绕过「自己先想」这一步，等于把引导模式退化成直接讲解。
 */
object GuidePolicy {
    /** 该模式下是否展示完整答案。 */
    fun showsFinalAnswer(
        mode: TeachingMode,
        revealed: Boolean,
    ): Boolean =
        when (mode) {
            TeachingMode.DIRECT -> true
            TeachingMode.GUIDED -> revealed
        }

    /**
     * 该模式下是否展示标准步骤的正文。
     *
     * GUIDED 从 0.2.0 起改为 **false**：
     * 旧实现在引导模式下照样渲染整份标准步骤，学生一进来就看到完整解答，
     * 「提示引导」与「直接讲解」在观感上完全没有区别——这正是真机反馈指出的问题。
     * 现在引导模式首屏只给方向与引导问题，步骤要学生主动点「看全解」才出现。
     */
    fun showsSolutionSteps(mode: TeachingMode): Boolean = mode == TeachingMode.DIRECT

    /** 该模式下是否展示三级提示卡。 */
    fun showsHints(mode: TeachingMode): Boolean = mode == TeachingMode.GUIDED

    /**
     * 引导模式下是否展示「我已想通，看全解」入口。
     *
     * 只要还没揭示就展示：这是学生主动索取答案的唯一入口，
     * 藏起来会让「想通了想核对」的学生无路可走。
     */
    fun showsRevealAction(
        mode: TeachingMode,
        revealed: Boolean,
    ): Boolean = mode == TeachingMode.GUIDED && !revealed

    /**
     * 该模式是否属于「引导式」（需要学生先自己想）。
     *
     * 这里只描述模式本身的性质，不再表示「只显示提问」。
     */
    fun isGuided(mode: TeachingMode): Boolean = mode == TeachingMode.GUIDED

    /** 引导模式下，学生还没揭示全解时是否应隐藏步骤与答案（答案拦截的唯一判定入口）。 */
    fun withholdsSolution(
        mode: TeachingMode,
        revealed: Boolean,
    ): Boolean = isGuided(mode) && !revealed
}

/**
 * 三级提示模型（用 HINT 阶段的结构化输出承载）。
 *
 * 提示不来自独立的 AI 阶段，而是从阶段 B 的解答结构按步骤裁剪生成：
 * 1 级 = 只给方向（方法名），2 级 = 给下一步该做什么，3 级 = 给到具体步骤的前半句。
 * 之所以这样设计，是因为四阶段提示词契约里没有单独的 hint 阶段，
 * 而 GUIDED 模式又必须在「不解锁全解」的前提下给梯子。
 */
@kotlinx.serialization.Serializable
data class HintLevel(
    val level: Int,
    val text: String,
) {
    init {
        require(level in 1..3) { "提示级别必须为 1..3，实际为：" + level }
    }

    /** 级别标题。 */
    val title: String
        get() =
            when (level) {
                1 -> "第 1 级：给方向"
                2 -> "第 2 级：给下一步"
                else -> "第 3 级：给到关键式子"
            }

    /** 级别徽标（用于提示卡左上角的小圆标，不用长句占位）。 */
    val badge: String get() = level.toString()

    /**
     * 该级别一句话在做什么。
     *
     * 与 [title] 的区别：[title] 是给提示卡当小标题的完整句，
     * 这里是可以放在徽标旁、扫一眼就懂的短语。
     */
    val purpose: String
        get() =
            when (level) {
                1 -> "先想方向"
                2 -> "看关键一步"
                else -> "就差这一步"
            }
}

/** 学生步骤的对齐视图行（correct / wrong / missing 三态）。 */
data class AlignedStudentStep(
    val idx: Int,
    val content: String,
    val status: StudentStepStatus,
    /** 是否为 AI 定位到的首个错误步骤。 */
    val isFirstError: Boolean,
    /** 错因标签，仅首个错误步骤有值。 */
    val errorLabel: String? = null,
)

/** 错因枚举的界面行。 */
data class ErrorTypeOption(val type: ErrorType, val selected: Boolean)

/** 计算属性工具：把 [SolveEvent] 的结果映射为阶段进度列表。 */
internal object StageProgressMapper {
    /** 初始四阶段进度（全部 PENDING）。 */
    fun initial(): List<StageProgress> =
        AiStage.values().map { stage ->
            StageProgress(stage = stage, status = StageStatus.PENDING)
        }

    /** 标记某阶段开始。 */
    fun start(
        list: List<StageProgress>,
        stage: AiStage,
        cached: Boolean,
    ): List<StageProgress> =
        list.map { item ->
            when {
                item.stage == stage -> item.copy(status = StageStatus.RUNNING, cached = cached)
                else -> item
            }
        }

    /** 记录首字耗时。 */
    fun firstToken(
        list: List<StageProgress>,
        stage: AiStage,
        elapsedMs: Long,
    ): List<StageProgress> = list.map { item -> if (item.stage == stage) item.copy(firstTokenMs = elapsedMs) else item }

    /** 标记某阶段完成。 */
    fun complete(
        list: List<StageProgress>,
        stage: AiStage,
        summary: String,
    ): List<StageProgress> =
        list.map { item ->
            when {
                item.stage == stage -> item.copy(status = StageStatus.DONE, summary = summary)
                else -> item
            }
        }
}

/**
 * 领域结果 ↔ 展示快照的转换（纯函数，可单测）。
 *
 * 抽成独立对象的好处：落库与会话持久化两条路径共用同一份转换，
 * 不会出现「界面上有错因、存下来却没有」这类两边不一致的问题。
 */
object SolutionMapper {
    /** 领域聚合结果 -> 展示快照。 */
    fun fromContent(
        content: AiSolvedContent,
        mode: TeachingMode,
        mistakeId: String? = null,
    ): SolutionPayload {
        val steps =
            content.standardSolution.steps.map { step ->
                SolutionStepView(idx = step.idx, content = step.content, why = step.why)
            }
        // 提示从学生卡住的那一问开始给。
        //
        // 一道 (1)(2)(3) 的大题，标准步骤有十几步；若永远从第 1 问开始给提示，
        // 学生说「我不会第 (3) 问」时拿到的却是「先配方」——那不是他的卡点。
        // firstError 里的 subQuestion 说明卡在第几问（1 起，与标准步骤的 (1)(2) 同编号），
        // 直接据此定位小问起点；缺该字段时退回按 stepIdx 推算，整道题没有小问标记时返回 0。
        val hintFrom =
            StepSections.startIndexOf(
                steps = steps,
                stepIdx = content.firstError?.stepIdx,
                subQuestion = content.firstError?.subQuestion,
            )
        val hints =
            com.totop.app.ui.chat.HintBuilder.build(
                method = content.standardSolution.method,
                stepContents = steps.drop(hintFrom).map { it.content },
            )
        return SolutionPayload(
            questionId = content.question.id,
            subjectCode = content.question.subject,
            stem = content.question.stemText,
            known = content.question.known,
            ask = content.question.ask,
            options = content.question.options,
            choiceType = content.choiceType,
            steps = steps,
            finalAnswer = content.standardSolution.finalAnswer,
            method = content.standardSolution.method,
            firstError =
                content.firstError?.let { error ->
                    FirstErrorView(
                        stepIdx = error.stepIdx,
                        typeLabel = error.type.label,
                        explain = error.explain,
                        correctThinking = error.correctThinking,
                        subQuestion = error.subQuestion,
                    )
                },
            kpoints =
                content.kpointAssignments.map { kp ->
                    KpointView(id = kp.kpointId, name = kp.name, usage = kp.usage)
                },
            summary =
                content.summaryCard?.let { card ->
                    SummaryView(title = card.title, bullets = card.bullets)
                },
            unlockedHintLevel = 0,
            fullSolutionRevealed = mode == TeachingMode.DIRECT,
            hints = hints,
            mistakeId = mistakeId,
        )
    }

    /** 展示快照 -> 领域聚合（仅用于追问上下文与重新落库，不含完整 Question 全字段）。 */
    fun toContent(payload: SolutionPayload): AiSolvedContent =
        AiSolvedContent(
            question =
                Question(
                    id = payload.questionId,
                    subject = payload.subjectCode,
                    grade = "",
                    stemText = payload.stem,
                    known = payload.known,
                    ask = payload.ask,
                    options = payload.options,
                    createdAtUtc = System.currentTimeMillis(),
                ),
            standardSolution =
                StandardSolution(
                    steps =
                        payload.steps.map {
                            com.totop.app.domain.model.SolutionStep(idx = it.idx, content = it.content, why = it.why)
                        },
                    finalAnswer = payload.finalAnswer,
                    method = payload.method,
                ),
            studentSteps = emptyList<StudentStep>(),
            firstError =
                payload.firstError?.let { error ->
                    FirstError(
                        stepIdx = error.stepIdx,
                        subQuestion = error.subQuestion,
                        type = ErrorType.ofLabel(error.typeLabel) ?: ErrorType.FALLBACK,
                        explain = error.explain,
                        correctThinking = error.correctThinking,
                    )
                },
            kpointAssignments =
                payload.kpoints.map {
                    com.totop.app.domain.model.KpointAssignment(
                        kpointId = it.id,
                        name = it.name,
                        confidence = 1.0,
                        usage = it.usage,
                    )
                },
            summaryCard = payload.summary?.let { SummaryCard(title = it.title, bullets = it.bullets) },
            aiGenerated = true,
            choiceType = payload.choiceType,
        )

    /** 阶段 A 的识题结果 -> 领域 Question（落库用，补上时间戳与图片信息）。 */
    fun questionOf(
        question: AiQuestion,
        id: String,
        imagePath: String?,
        imageSha256: String?,
        sourceType: com.totop.app.domain.model.QuestionSourceType,
        createdAtUtc: Long,
    ): Question =
        Question(
            id = id,
            subject = question.subject ?: Subjects.GENERAL,
            grade = question.grade ?: "未知",
            stemText = question.stem.orEmpty(),
            known = question.known,
            ask = question.ask.orEmpty(),
            options = question.options,
            hasFigure = question.hasFigure,
            imagePath = imagePath,
            imageSha256 = imageSha256,
            sourceType = sourceType,
            createdAtUtc = createdAtUtc,
        )
}

/** 会话持久化用的纯转换（可单测）。 */
object SessionTurns {
    /**
     * 展示消息 -> 持久化消息。
     *
     * 解题气泡除了正文，还会把结构化快照一并写进 [ChatTurn.solutionJson]：
     * 只留纯文本的话，重新打开历史会话时这条气泡就变成普通 AI 消息，
     * 「这题考什么」「错在哪」这些区块全部消失，追问也失去了上下文。
     *
     * 序列化失败不影响落盘：降级为只存正文，历史仍然可读。
     */
    fun toTurn(message: ConversationMessage): ChatTurn =
        ChatTurn(
            id = message.id,
            fromStudent = message.fromStudent,
            text = message.text,
            pages = message.pages,
            createdAtUtc = message.createdAtUtc,
            errorText = message.errorText,
            kind = if (message.solution != null) ChatTurn.KIND_SOLUTION else ChatTurn.KIND_CHAT,
            solutionJson = message.solution?.let { encodeSolution(it) },
        )

    /** 展示快照 -> JSON 文本；失败返回 null（历史里仍有正文可读）。 */
    fun encodeSolution(payload: SolutionPayload): String? =
        runCatching { payloadJson.encodeToString(SolutionPayload.serializer(), payload) }.getOrNull()

    /** JSON 文本 -> 展示快照；老数据或损坏数据返回 null。 */
    fun decodeSolution(json: String?): SolutionPayload? {
        if (json.isNullOrBlank()) return null
        return runCatching { payloadJson.decodeFromString(SolutionPayload.serializer(), json) }.getOrNull()
    }

    /**
     * 会话文件用的 Json 配置。
     *
     * ignoreUnknownKeys：将来给 SolutionPayload 加字段时，旧文件仍能读出来；
     * encodeDefaults：保证前后两次落盘的文件结构一致，便于排查。
     */
    private val payloadJson: kotlinx.serialization.json.Json =
        kotlinx.serialization.json.Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    /** 会话标题：优先学生说的第一句，其次题干首行。 */
    fun titleOf(
        turns: List<ConversationMessage>,
        fallbackStem: String = "",
    ): String {
        val firstStudent = turns.firstOrNull { it.fromStudent }?.text?.trim().orEmpty()
        val candidate =
            firstStudent.takeIf { it.isNotEmpty() }
                ?: fallbackStem.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        return candidate.take(TITLE_MAX_CHARS).ifBlank { "新对话" }
    }

    /** 副标题：科目 + 轮次。 */
    fun subtitleOf(
        subjectCode: String,
        rounds: Int,
    ): String {
        val label = Subjects.label(subjectCode)
        return label + " · " + rounds + " 轮对话"
    }

    /** 会话标题最多取多少个字符（列表一行放得下，且能看出是哪道题）。 */
    const val TITLE_MAX_CHARS: Int = 30

    /**
     * 从会话记录还原展示消息。
     *
     * 解题气泡会用 [ChatTurn.solutionJson] 还原完整的结构化快照，
     * 因此历史里的「这题考什么 / 错在哪 / 知识点」与当时的交互状态都在，
     * 并且继续追问时也有上下文可用。
     *
     * 老版本写下的会话没有 solutionJson，此时退回纯文本渲染（气泡内容仍然可读）。
     */
    fun toMessages(session: SolveSession): List<ConversationMessage> =
        session.turns.map { turn ->
            ConversationMessage(
                id = turn.id,
                fromStudent = turn.fromStudent,
                text = turn.text,
                pages = turn.pages,
                errorText = turn.errorText,
                createdAtUtc = turn.createdAtUtc,
                solution = if (turn.isSolution) decodeSolution(turn.solutionJson) else null,
            )
        }
}
