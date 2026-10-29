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
 * 首页的「最近常问」条目。
 *
 * 与 [WeakKpointItem] 并列展示：一个是做错过的，一个是写作业时想不起来的，
 * 两者都是「该回头看一眼」的地方，但成因不同，混在一起会看不出该补什么。
 */
data class ForgottenItem(
    val kpointId: String,
    val name: String,
    /** 累计问过几次。 */
    val askCount: Int,
)

/**
 * 首页的一个「学习概览」指标块。
 *
 * 为什么做成数据而不是在界面里写死四个格子：
 * 数值、单位、说明文字、是否已完成都会随数据变化（例如「今天已完成复习」），
 * 把这些拼装留在 ViewModel 里，界面只负责画，测起来也只看这个列表。
 */
data class HomeMetric(
    val key: String,
    val label: String,
    val value: String,
    /** 一行补充说明，例如「比上周少 2 题」。 */
    val caption: String = "",
    /** 0..1 的完成度；为 null 时不画进度条。 */
    val progress: Float? = null,
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
    /** 最近 7 天新增的错题数（首页概览用）。 */
    val weekMistakeCount: Int = 0,
    /** 已掌握的知识点数（掌握度 >= 70）。 */
    val masteredCount: Int = 0,
    /** 今日复习完成度 0..1；没有待复习时按 1 处理。 */
    val todayProgress: Float = 0f,
    /** 首页每日一句（含 emoji，来自 assets/copy/home_lines.json）。 */
    val dailyLine: String = "",
    /** 一句人话总结，例如「最近 7 天你在这 3 个知识点上错得较多」。 */
    val overviewHint: String = "",
    /** 需要多练的知识点列表（最多 3 条）。 */
    val weakKpoints: List<WeakKpointItem> = emptyList(),
    /** 写作业时常问、说明容易忘的知识点（最多 3 条）。 */
    val forgottenKpoints: List<ForgottenItem> = emptyList(),
    val loading: Boolean = true,
    /** 加载失败时展示可读原因，绝不留白。 */
    val error: String? = null,
) {
    /** 顶部指标块：都用图标 + 数字 + 说明表达，避免首页只剩几行字。 */
    val metrics: List<HomeMetric>
        get() =
            listOf(
                HomeMetric(
                    key = "due",
                    label = "今天待复习",
                    value = dueReviewCount.toString(),
                    caption = if (dueReviewCount == 0) "今天的复习已经清空" else "做完就少一题",
                    progress = todayProgress,
                ),
                HomeMetric(
                    key = "week",
                    label = "近七天错题",
                    value = weekMistakeCount.toString(),
                    caption = "这一周新进来的题",
                ),
                HomeMetric(
                    key = "weak",
                    label = "还要多练",
                    value = weakCount.toString(),
                    caption = "反复出错的知识点",
                ),
                HomeMetric(
                    key = "mastered",
                    label = "已掌握",
                    value = masteredCount.toString(),
                    caption = "掌握度 70 以上",
                ),
            )

    /** 是否是完全的新用户（没有任何学习记录）。 */
    val isBlank: Boolean get() = !loading && mistakeCount == 0 && masteredCount == 0 && dueReviewCount == 0
}
