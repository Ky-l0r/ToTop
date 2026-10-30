package com.totop.app.ui.chat

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.totop.app.data.remote.CandidateKpoint
import com.totop.app.domain.model.KnowledgePoint
import com.totop.app.domain.model.TeachingMode

/** 网络状态探测（只读系统能力，不发起任何请求）。 */
object NetworkProbe {
    /** 读取当前网络状态；无法判定时返回 [NetworkStatus.UNKNOWN]。 */
    fun status(context: Context): NetworkStatus {
        val manager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return NetworkStatus.UNKNOWN
        val network = runCatching { manager.activeNetwork }.getOrNull() ?: return NetworkStatus.OFFLINE
        val capabilities =
            runCatching { manager.getNetworkCapabilities(network) }.getOrNull()
                ?: return NetworkStatus.OFFLINE
        val reachable = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        return if (reachable) NetworkStatus.ONLINE else NetworkStatus.OFFLINE
    }
}

/**
 * 阶段 D 的候选知识点筛选（纯函数，可单测）。
 *
 * 为什么需要筛选：知识点树二级节点有 146 个，全量塞进阶段 D 的提示词约 4K token，
 * 与「成本控制」直接冲突。策略：
 * 1. 题干里**出现名字**的知识点优先（命中越长越具体，排越前）；
 * 2. 命中项的同章节兄弟节点次之（同一章常一起考）；
 * 3. 仍不足时按章节与 orderIndex 补足，保证 AI 永远有足够候选可选；
 * 4. 上限 [MAX_CANDIDATES]，且候选 ID 全部来自内置知识点树（AI 不得自造 ID）。
 */
object KpointCandidates {
    /** 候选清单上限：约 60 条，控制在 1.5K token 以内。 */
    const val MAX_CANDIDATES: Int = 60

    /** 参与匹配的文本长度上限，避免超长题干拖慢匹配。 */
    private const val MAX_HAYSTACK_LENGTH: Int = 4000

    /**
     * 筛选候选。
     *
     * @param subject 题目科目代码。为空时按**每个科目各挑一份**再拼接（见下文）；
     *   给定时只在该科目内挑候选——这一点是必须的：
     *   知识点树扩到十科共 700+ 节点后，若不分科目，一道物理题的候选里会混进
     *   语文、英语的节点，模型很可能给出跨科目错误归属。
     */
    fun pick(
        points: List<KnowledgePoint>,
        haystack: String,
        subject: String = "",
        limit: Int = MAX_CANDIDATES,
    ): List<CandidateKpoint> {
        if (points.isEmpty() || limit <= 0) return emptyList()
        val text = haystack.take(MAX_HAYSTACK_LENGTH)
        // 科目未知时不能「跨十科取前 60 条」。
        //
        // 旧实现的直接后果是化学、生物这类排在后面的科目候选极少：
        // 600 多个节点按章节顺序截断 60 条，基本全是语文与数学，
        // 阶段 A 识别出 chemistry 之后再按科目过滤就只剩个位数，
        // 表现为「化学题连续几道都匹配不到知识点」。
        //
        // 现在每个科目各挑一份：清单只在客户端使用，真正进提示词的
        // 是按科目过滤后的那一份（见 AiGateway 阶段 D），因此条数变多不增加 token。
        if (subject.isBlank()) {
            val subjects = points.map { it.subject }.distinct()
            if (subjects.size <= 1) return pickForSubject(points, text, limit)
            return subjects.flatMap { code -> pickForSubject(points, text, limit, code) }
        }
        return pickForSubject(points, text, limit, subject)
    }

    /** 单科目筛选：命中项优先，其次同章节兄弟，最后按章节顺序补足。 */
    private fun pickForSubject(
        points: List<KnowledgePoint>,
        text: String,
        limit: Int,
        subject: String = "",
    ): List<CandidateKpoint> {
        val scoped =
            if (subject.isBlank()) points else points.filter { it.subject.equals(subject, ignoreCase = true) }
        // 该科目还没有知识点树时不要把别的科目塞进来，直接返回空，
        // 由上层跳过阶段 D 并给出「暂无该科目知识点树」的提示。
        if (scoped.isEmpty()) return emptyList()
        val leaves = scoped.filter { it.parentId != null }
        val pool = leaves.ifEmpty { scoped }

        val matched =
            pool
                .filter { it.name.isNotBlank() && text.contains(it.name) }
                .sortedByDescending { it.name.length }
        val matchedIds = matched.map { it.id }.toSet()

        val matchedChapters = matched.map { it.chapter }.filter { it.isNotEmpty() }.toSet()
        val siblings = pool.filter { it.id !in matchedIds && it.chapter.isNotEmpty() && it.chapter in matchedChapters }

        val ordered = ArrayList<KnowledgePoint>(limit)
        val seen = HashSet<String>()
        (matched + siblings.sortedBy { it.orderIndex }).forEach { point ->
            if (ordered.size < limit && seen.add(point.id)) ordered.add(point)
        }
        pool.sortedWith(compareBy({ it.chapter }, { it.orderIndex })).forEach { point ->
            if (ordered.size < limit && seen.add(point.id)) ordered.add(point)
        }

        return ordered.map { CandidateKpoint(id = it.id, name = it.name, chapter = it.chapter, subject = it.subject) }
    }
}

/**
 * 引导模式的提示生成（纯函数，可单测）。
 *
 * 阶段 B 的解答步骤已含「做什么 / 为什么」，按以下规则裁剪成三级提示：
 * - 1 级：只给方法名（`method`），不给任何算式；
 * - 2 级：给第一步「做什么」；
 * - 3 级：**接着**第二步给到第三步，不重复第 1 级与第 2 级已经说过的内容。
 *
 * 关键约束：三级提示**永远不含 finalAnswer**，与 [GuidePolicy] 共同保证
 * 「GUIDED 模式严禁提前渲染最终答案」。
 *
 * 0.5.1 的三处修正（对应真机反馈「建议重复前两步 / 直接给答案 / 答案不全」）：
 * 1. **不再重复**：旧 3 级把第 1、2 步整段再列一遍，而 2 级刚说过第 1 步，
 *    学生点开看到的是一模一样的两段话；现在 3 级从第 2 步接续；
 * 2. **不再泄题**：旧 3 级写着「答案我不给」，但其中的步骤原文本身就含结论
 *    （例如「故阴极为 SiHCl₃ + 3e⁻ → Si + 3Cl⁻」）。现在先用 finalAnswer 兜底擦除，
 *    再把含结论的最后一步整段排除；
 * 3. **不再半截**：旧实现只取前两步，四步以上的题提示完仍不知道怎么往下走；
 *    现在 2+3 级合起来至少覆盖前三步，剩下的一步明确交给学生自己算。
 */
object HintBuilder {
    /** 上限三级。 */
    const val MAX_LEVEL: Int = 3

    /** 没有方法名时的兜底方向（不涉及任何具体算式）。 */
    private const val FALLBACK_METHOD = "先把题目给了什么、要求什么逐条写下来"

    /** 提示里最多给到第几步：再往后基本就是收尾与答案，给了等于给答案。 */
    private const val MAX_STEP_HINTS: Int = 3

    /** 单步提示的最大长度：超过就截断，避免一张提示卡占满一屏。 */
    private const val MAX_STEP_CHARS: Int = 160

    /**
     * 生成三级提示。
     *
     * @param method 解题方法名（可空）
     * @param stepContents 标准步骤的「做什么」文本
     * @param finalAnswer 最终答案；给定时用于擦除步骤里泄露的结论（可空）
     */
    fun build(
        method: String,
        stepContents: List<String>,
        finalAnswer: String = "",
    ): List<HintLevel> {
        val hints = ArrayList<HintLevel>(MAX_LEVEL)
        val methodText = method.trim().ifEmpty { FALLBACK_METHOD }
        hints.add(
            HintLevel(
                level = 1,
                text =
                    "这道题的主线是「" + methodText + "」。先别动笔算，" +
                        "想一想题目给了哪些条件、要你求什么，再往下看下一步。",
            ),
        )
        val answer = finalAnswer.trim()
        val steps = usableSteps(stepContents = stepContents, answer = answer)
        val first = steps.getOrNull(0)
        if (first != null) {
            hints.add(HintLevel(level = 2, text = "第一步这么走：" + first))
        }
        // 第 3 级从第 2 步接续：只给还没说过的部分，且最多给到第 3 步
        val rest = steps.drop(1).take(MAX_STEP_HINTS - 1)
        if (rest.isNotEmpty()) {
            val numbered = rest.mapIndexed { index, text -> (index + 2).toString() + ". " + text }
            hints.add(
                HintLevel(
                    level = 3,
                    text =
                        (if (rest.size == 1) "接着走这一步" else "接着往下走这" + rest.size + "步") +
                            "（从这儿开始自己算，答案我不给）：\n" +
                            numbered.joinToString("\n"),
                ),
            )
        }
        return hints
    }

    /**
     * 取可用的步骤：擦掉泄露的结论、丢掉含答案的收尾步。
     *
     * 收尾步的判定很保守——只有「答案文本出现在这一步里」或「这一步是最后一步且
     * 以答案、所以、因此开头」才丢。宁可多留一步，也不能把整题提示删空。
     */
    private fun usableSteps(
        stepContents: List<String>,
        answer: String,
    ): List<String> {
        val raw =
            stepContents
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .map { if (it.length > MAX_STEP_CHARS) it.take(MAX_STEP_CHARS) + "…" else it }
        if (raw.isEmpty()) return raw
        val scrubbed = raw.map { scrub(it, answer) }.filter { it.isNotBlank() }
        // 擦得太狠时退回「原文去掉最后一步」：答案本身就是那一步的内容时，
        // 整步擦掉会得到一张空提示卡，那比泄露一点更糟。
        val usable = if (scrubbed.size >= 2 || raw.size <= 1) scrubbed else raw.dropLast(1)
        if (usable.size <= 1) return usable
        val last = usable.last()
        val leaksAnswer = looksLikeConclusion(last, answer)
        return if (leaksAnswer) usable.dropLast(1) else usable
    }

    /** 最后一步是否是「结论步」：含答案原文，或本身就是一句收尾。 */
    private fun looksLikeConclusion(
        step: String,
        answer: String,
    ): Boolean {
        if (answer.length >= 2 && step.contains(answer)) return true
        val head = step.trimStart()
        return CONCLUSION_PREFIXES.any { head.startsWith(it) }
    }

    /**
     * 把一句话里含答案的分句擦掉。
     *
     * 「H₂SO₄ 与 SO₃ 的质量分数为 20%」在中途插一句「答案不给」没有意义，
     * 正确做法是这一小句直接不出现。
     */
    private fun scrub(
        text: String,
        answer: String,
    ): String {
        val trimmed = text.trim()
        if (answer.length < 2 || !trimmed.contains(answer)) return trimmed
        val clauses = trimmed.split('，', '。', '；', ',', ';')
        val kept = clauses.filterNot { it.contains(answer) }
        val rebuilt = kept.joinToString("，").trim().trimEnd('，', '。', '；')
        return if (rebuilt.isBlank()) "" else rebuilt
    }

    /** 结论步的常见开头。命中即视为「这一步已经在给答案」。 */
    private val CONCLUSION_PREFIXES: List<String> =
        listOf("所以", "因此", "故", "最终", "综上", "答案为", "结论", "答案是")

    /** 取第 [level] 级提示；不存在时返回 null（步骤太少时只有 1-2 级）。 */
    fun levelOf(
        hints: List<HintLevel>,
        level: Int,
    ): HintLevel? = hints.firstOrNull { it.level == level }
}

/** 讲解模式的界面元信息。 */
object ModeMeta {
    /** 模式短标题。 */
    fun titleOf(mode: TeachingMode): String =
        when (mode) {
            TeachingMode.DIRECT -> "直接讲解"
            TeachingMode.GUIDED -> "提示引导"
        }

    /** 模式一句话说明。 */
    fun descriptionOf(mode: TeachingMode): String =
        when (mode) {
            TeachingMode.DIRECT -> "完整步骤与最终答案一次给全，适合已经想通只想核对写法"
            TeachingMode.GUIDED -> "先只给方向和一个问题，想不通再逐级要提示，想通了点开全解"
        }
}
