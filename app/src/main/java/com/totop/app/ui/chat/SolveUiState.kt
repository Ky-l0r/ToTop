package com.totop.app.ui.chat

import com.totop.app.data.remote.SolveEvent
import com.totop.app.data.remote.TokenUsage
import com.totop.app.domain.model.AiSolvedContent
import com.totop.app.domain.model.AiStage
import com.totop.app.domain.model.ErrorType
import com.totop.app.domain.model.StudentStepStatus
import com.totop.app.domain.model.TeachingMode
import com.totop.app.util.NormalizedRect

/** 顶部网络状态（FR-1.9：请求前先判断网络，不通时明确提示而不是空转）。 */
enum class NetworkStatus(val label: String) {
    ONLINE("网络正常"),
    OFFLINE("无网络连接"),
    UNKNOWN("网络状态未知"),
}

/**
 * 解题页的主状态机。
 *
 * 状态迁移（单向，禁止跳级）：
 * <pre>
 *   Idle ──import/camera/text──> Ready ──send──> Solving ──完成──> Solved
 *     ^                            ^               |  \            |
 *     |                            |            失败|   \取消        |重新提问
 *     |                            |               v    v          v
 *     +────────reset───────────────+────────── Failed / Cancelled  Ready
 * </pre>
 *
 * - [Idle]：空态，等待用户导入题目；
 * - [Ready]：题目已就绪（OCR/手输完成），可发送；
 * - [Solving]：四阶段调用进行中，[stages] 记录各阶段进度，[streamText] 承载增量文本；
 * - [Solved]：拿到完整结构化结果，进入分块渲染；
 * - [Failed] / [Cancelled]：保留题目与已有增量，允许重试或重新提问。
 */
sealed class SolveUiState {
    /** 空态：尚未导入任何题目。 */
    data object Idle : SolveUiState()

    /** 题目已就绪，等待发送。 */
    data class Ready(val question: PreparedQuestion) : SolveUiState()

    /** 调用进行中。 */
    data class Solving(
        val question: PreparedQuestion,
        val stages: List<StageProgress>,
        val streamText: String,
        val startedAtUtc: Long,
    ) : SolveUiState()

    /** 已完成，可渲染全部结果分块。 */
    data class Solved(
        val question: PreparedQuestion,
        val content: AiSolvedContent,
        val warnings: List<String>,
        val usage: TokenUsage,
        /** 引导模式下已解锁的提示级别（0 表示尚未解锁任何提示）。 */
        val unlockedHintLevel: Int,
        /** 引导模式下学生是否已选择「我已想通，看全解」。 */
        val fullSolutionRevealed: Boolean,
        /** 三级提示；步骤不足时可能只有 1-2 级。 */
        val hints: List<HintLevel> = emptyList(),
        /** 落库后的错题 ID；未入库（例如未提供学生过程）时为 null。 */
        val mistakeId: String? = null,
        /** 落库失败原因（非致命，仅提示）。 */
        val persistMessage: String? = null,
    ) : SolveUiState()

    /** 失败：可重试（可重试错误）或需改设置（不可重试错误）。 */
    data class Failed(
        val question: PreparedQuestion?,
        val stage: AiStage,
        val message: String,
        val retryable: Boolean,
        val technicalDetail: String? = null,
        val warnings: List<String> = emptyList(),
    ) : SolveUiState()

    /** 用户主动停止，或页面离开导致中断。 */
    data class Cancelled(
        val question: PreparedQuestion,
        val message: String = "已停止本次生成",
        val hasPartialResult: Boolean = false,
    ) : SolveUiState()
}

/** 单阶段进度。 */
data class StageProgress(
    val stage: AiStage,
    val status: StageStatus,
    /** 命中本地缓存时展示「命中缓存，0 token」。 */
    val cached: Boolean = false,
    /** 首个增量到达耗时（毫秒），用于「首字 < 3s」的自检展示。 */
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
 * @param imagePath 预处理后的主图路径（filesDir/images/<sha256>.jpg）
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
 *   纯文字输入时为空列表。
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
 * 规则来自 FR-2：
 * - DIRECT：直接展示全部步骤与最终答案；
 * - GUIDED：展示标准步骤，但**严禁提前渲染 finalAnswer**；需逐级解锁三级提示，
 *   或点击「我已想通，看全解」后才展示答案；
 * - SOCRATIC：只渲染提问与方向，不展示步骤内容与答案。
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
            TeachingMode.SOCRATIC -> false
        }

    /** 该模式下是否展示标准步骤的正文。 */
    fun showsSolutionSteps(mode: TeachingMode): Boolean =
        when (mode) {
            TeachingMode.DIRECT -> true
            TeachingMode.GUIDED -> true
            TeachingMode.SOCRATIC -> false
        }

    /** 该模式下是否展示三级提示卡。 */
    fun showsHints(mode: TeachingMode): Boolean = mode != TeachingMode.SOCRATIC

    /** 该模式下是否展示「我已想通，看全解」入口。 */
    fun showsRevealAction(
        mode: TeachingMode,
        revealed: Boolean,
    ): Boolean = mode == TeachingMode.GUIDED && !revealed

    /** SOCRATIC 模式下是否只展示提问与方向（此时隐藏错因对比与摘要卡细节）。 */
    fun showsGuidingOnly(mode: TeachingMode): Boolean = mode == TeachingMode.SOCRATIC
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
