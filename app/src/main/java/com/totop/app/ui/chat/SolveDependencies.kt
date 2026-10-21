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

    /** 筛选候选。 */
    fun pick(
        points: List<KnowledgePoint>,
        haystack: String,
        limit: Int = MAX_CANDIDATES,
    ): List<CandidateKpoint> {
        if (points.isEmpty() || limit <= 0) return emptyList()
        val text = haystack.take(MAX_HAYSTACK_LENGTH)
        val leaves = points.filter { it.parentId != null }
        val pool = leaves.ifEmpty { points }

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

        return ordered.map { CandidateKpoint(id = it.id, name = it.name, chapter = it.chapter) }
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

    /**
     * 生成三级提示。
     *
     * @param method 解题方法名（可空）
     * @param stepContents 标准步骤的「做什么」文本
     */
    fun build(
        method: String,
        stepContents: List<String>,
    ): List<HintLevel> {
        val hints = ArrayList<HintLevel>(MAX_LEVEL)
        val methodText = method.trim().ifEmpty { "先把题目条件与所求逐条列出来" }
        hints.add(HintLevel(level = 1, text = "想一想：" + methodText))
        val first = stepContents.getOrNull(0)?.trim().orEmpty()
        if (first.isNotEmpty()) {
            hints.add(HintLevel(level = 2, text = "下一步该做的是：" + first))
        }
        // 第 3 级必须是「展开顺序」，只有一步时它和第 2 级重复，因此直接不生成
        val twoSteps = stepContents.take(2).map { it.trim() }.filter { it.isNotEmpty() }
        if (twoSteps.size >= 2) {
            val numbered = twoSteps.mapIndexed { index, text -> (index + 1).toString() + ". " + text }
            hints.add(HintLevel(level = 3, text = "按这个顺序展开：\n" + numbered.joinToString("\n")))
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
            TeachingMode.SOCRATIC -> "纯引导"
        }

    /** 模式一句话说明。 */
    fun descriptionOf(mode: TeachingMode): String =
        when (mode) {
            TeachingMode.DIRECT -> "给出完整步骤与最终答案，适合已经想通只想核对写法"
            TeachingMode.GUIDED -> "先给三级提示逐级解锁，想通后自己点开全解，答案不会提前出现"
            TeachingMode.SOCRATIC -> "只给提问与方向，不展示步骤与答案，适合逼自己想"
        }
}
