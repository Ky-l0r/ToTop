package com.totop.app.ui.chat

import com.totop.app.data.remote.SolveEvent
import com.totop.app.data.repo.ChatTurn
import com.totop.app.data.repo.SolveSession
import com.totop.app.domain.model.AiQuestion
import com.totop.app.domain.model.AiSolvedContent
import com.totop.app.domain.model.AiStage
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
data class SolutionPayload(
    val questionId: String,
    val subjectCode: String,
    val stem: String,
    val known: List<String> = emptyList(),
    val ask: String = "",
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
}

/** 解答步骤的展示行。 */
data class SolutionStepView(
    val idx: Int,
    val content: String,
    val why: String = "",
)

/** 首个错误的展示行。 */
data class FirstErrorView(
    val stepIdx: Int,
    val typeLabel: String,
    val explain: String,
    val correctThinking: String = "",
)

/** 知识点的展示行。 */
data class KpointView(
    val id: String,
    val name: String,
    val usage: String = "",
)

/** 摘要卡展示行。 */
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
        val hints =
            com.totop.app.ui.chat.HintBuilder.build(
                method = content.standardSolution.method,
                stepContents = content.standardSolution.steps.map { it.content },
            )
        return SolutionPayload(
            questionId = content.question.id,
            subjectCode = content.question.subject,
            stem = content.question.stemText,
            known = content.question.known,
            ask = content.question.ask,
            steps =
                content.standardSolution.steps.map { step ->
                    SolutionStepView(idx = step.idx, content = step.content, why = step.why)
                },
            finalAnswer = content.standardSolution.finalAnswer,
            method = content.standardSolution.method,
            firstError =
                content.firstError?.let { error ->
                    FirstErrorView(
                        stepIdx = error.stepIdx,
                        typeLabel = error.type.label,
                        explain = error.explain,
                        correctThinking = error.correctThinking,
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
     * 解题气泡的正文由 [SolutionPayload] 决定，这里把正文压成一句话，
     * 保证即使将来展示格式变了，历史里也还留着一份可读的文字。
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
        )

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

    /** 从会话记录还原展示消息（不含解题结构，历史里的解题气泡按纯文本渲染）。 */
    fun toMessages(session: SolveSession): List<ConversationMessage> =
        session.turns.map { turn ->
            ConversationMessage(
                id = turn.id,
                fromStudent = turn.fromStudent,
                text = turn.text,
                pages = turn.pages,
                errorText = turn.errorText,
                createdAtUtc = turn.createdAtUtc,
            )
        }
}
