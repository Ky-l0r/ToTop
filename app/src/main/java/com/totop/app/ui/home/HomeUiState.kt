package com.totop.app.ui.home

/** 首页需要展示的一个「还需要多练的知识点」。 */
data class WeakKpointItem(
    val id: String,
    val name: String,
    /** 已转成中文的科目标签，例如「数学」。 */
    val subjectLabel: String,
    /** 该知识点累计错过几次。 */
    val mistakeCount: Int,
)

/**
 * 首页状态。
 *
 * 所有字段都已经「翻译成人话」：
 * 掌握度 -> 学得怎么样 / 还要多练；
 * 排期任务 -> 待复习。
 */
data class HomeUiState(
    val greeting: String = "今天想解决哪道题？",
    val subtitle: String = "先把最近的错题过一遍，再开始新的一题。",
    /** 错题本里的错题总数。 */
    val mistakeCount: Int = 0,
    /** 需要多练的知识点个数。 */
    val weakCount: Int = 0,
    /** 今天待复习的条目数。 */
    val dueReviewCount: Int = 0,
    /** 一句人话总结，例如「最近 7 天你在这 3 个知识点上错得较多」。 */
    val overviewHint: String = "",
    /** 需要多练的知识点列表（最多 3 条）。 */
    val weakKpoints: List<WeakKpointItem> = emptyList(),
    val loading: Boolean = true,
    /** 加载失败时展示可读原因，绝不留白。 */
    val error: String? = null,
)
