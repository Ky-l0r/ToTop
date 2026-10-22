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
     * @param subject 题目科目代码。为空时不做科目过滤（兼容旧调用）；
     *   给定时**只在该科目内挑候选**——这一点是必须的：
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
        val scoped =
            if (subject.isBlank()) {
                points
            } else {
                points.filter { it.subject.equals(subject, ignoreCase = true) }
            }
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
 * - 3 级：给前两步「做什么」。
 *
 * 关键约束：三级提示**永远不含 finalAnswer**，与 [GuidePolicy] 共同保证
 * 「GUIDED 模式严禁提前渲染最终答案」。
 */
object HintBuilder {
    /** 上限三级。 */
    const val MAX_LEVEL: Int = 3

    /** 没有方法名时的兜底方向（不涉及任何具体算式）。 */
    private const val FALLBACK_METHOD = "先把题目给了什么、要求什么逐条写下来"

    /**
     * 生成三级提示。
     *
     * 0.2.0 的调整（对应真机反馈「提示用小字呈现，不易读，提示应该为主体强调」）：
     * 提示文本改成**独立成句、可直接读出来的中文**，不再是「想一想：配方法」这种半截话；
     * 界面侧由 MessageList 用正文级字号与高对比呈现，不再塞进小字注脚。
     *
     * 每级都遵守一条硬约束：**不含最终答案**。
     *
     * @param method 解题方法名（可空）
     * @param stepContents 标准步骤的「做什么」文本
     */
    fun build(
        method: String,
        stepContents: List<String>,
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
        val first = stepContents.getOrNull(0)?.trim().orEmpty()
        if (first.isNotEmpty()) {
            hints.add(HintLevel(level = 2, text = "接下来你要做的是：" + first))
        }
        // 第 3 级必须是「展开顺序」，只有一步时它和第 2 级重复，因此直接不生成
        val twoSteps = stepContents.take(2).map { it.trim() }.filter { it.isNotEmpty() }
        if (twoSteps.size >= 2) {
            val numbered = twoSteps.mapIndexed { index, text -> (index + 1).toString() + ". " + text }
            hints.add(
                HintLevel(
                    level = 3,
                    text =
                        "把这两步按顺序做一遍，结果自己算出来（答案我不给，算完可以对一下）：\n" +
                            numbered.joinToString("\n"),
                ),
            )
        }
        return hints
    }

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
