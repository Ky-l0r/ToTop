package com.totop.app.work

import com.totop.app.domain.model.Sm2Scheduler
import com.totop.app.domain.model.Sm2State

/**
 * 复习提醒排期（开发方案 FR-4.4：简化 SM-2 排期 + 本地通知）。
 *
 * 这一层只做**纯计算**：把「一批待复习项」按到期时间排序、按每日提醒上限裁剪，
 * 得到「今天该提醒哪些」。真正的状态推进由 [Sm2Scheduler] 负责，
 * WorkManager 只负责按时间唤醒（[ReviewReminderWorker]）。
 *
 * 之所以把排期从 Worker 里抽出来，是因为 Worker 依赖 android.content.Context 与
 * WorkManager 运行时，在 JVM 单测里跑不动；纯函数部分必须 100% 可单测。
 */
object ReviewSchedule {
    /** 每日提醒最多包含多少道题（超过会让通知变成骚扰）。 */
    const val DEFAULT_DAILY_LIMIT: Int = 5

    /** 一条待复习项。 */
    data class ReviewItem(
        val mistakeId: String,
        val kpointId: String,
        /** 下次到期时间（UTC 毫秒）。 */
        val dueAtUtc: Long,
        /** 已复习次数。 */
        val repetition: Int = 0,
        /** 当前间隔（天）。 */
        val intervalDays: Int = 0,
    ) {
        init {
            require(mistakeId.isNotBlank()) { "mistakeId 不得为空" }
        }
    }

    /**
     * 计算今天要提醒的条目。
     *
     * 规则（顺序即优先级）：
     * 1. 只保留 [nowUtc] 已到期的条目；
     * 2. 按到期时间升序（越早到期越先提醒），到期时间相同再按间隔天数降序
     *    （间隔越长说明掌握得越久没复习，遗忘风险更高）；
     * 3. 最多取 [limit] 条。
     *
     * @return 提醒列表；为空表示今天没有需要提醒的题
     */
    fun dueToday(
        items: List<ReviewItem>,
        nowUtc: Long,
        limit: Int = DEFAULT_DAILY_LIMIT,
    ): List<ReviewItem> {
        if (limit <= 0) return emptyList()
        return items
            .asSequence()
            .filter { it.dueAtUtc <= nowUtc }
            .sortedWith(compareBy({ it.dueAtUtc }, { -it.intervalDays }))
            .take(limit)
            .toList()
    }

    /**
     * 下一次提醒时间：取未到期条目中最早的一个；全部已到期或没有条目时，
     * 返回 [nowUtc] + [defaultIntervalMillis]（避免 Worker 立刻反复唤醒）。
     */
    fun nextReminderAt(
        items: List<ReviewItem>,
        nowUtc: Long,
        defaultIntervalMillis: Long = Sm2Scheduler.DAY_MS,
    ): Long {
        val earliestFuture =
            items.asSequence()
                .map { it.dueAtUtc }
                .filter { it > nowUtc }
                .minOrNull()
        return earliestFuture ?: (nowUtc + defaultIntervalMillis.coerceAtLeast(1L))
    }

    /**
     * 到期条数统计（通知文案「今天有 N 道题该复习了」）。
     */
    fun dueCount(
        items: List<ReviewItem>,
        nowUtc: Long,
    ): Int = items.count { it.dueAtUtc <= nowUtc }

    /**
     * 把一条复习记录还原成 SM-2 状态（用于从 review_log 重建排期）。
     *
     * 从零开始按时间顺序重放所有复习，而不是只在库里存 dueAt：
     * 这样即使通知被系统清理、Worker 被杀死，排期也能确定性地重建。
     *
     * @param reviewedAtUtcList 该错题的复习时间点（升序）
     * @param gotItList 与时间点一一对应的自评结果（true = 做对了）
     */
    fun replay(
        reviewedAtUtcList: List<Long>,
        gotItList: List<Boolean>,
    ): Sm2State {
        require(reviewedAtUtcList.size == gotItList.size) { "复习时间与自评结果数量必须一致" }
        var state = Sm2State()
        for (index in reviewedAtUtcList.indices) {
            val quality = if (gotItList[index]) Sm2Scheduler.QUALITY_GOT_IT else Sm2Scheduler.QUALITY_STILL_LOST
            state = Sm2Scheduler.nextByQuality(state, quality, reviewedAtUtcList[index])
        }
        return state
    }

    /**
     * 通知文案（纯函数，便于单测，不在 Worker 里拼字符串）。
     *
     * @param count 到期条数
     */
    fun notificationText(count: Int): String =
        when {
            count <= 0 -> "今天没有需要复习的题，去学点新的吧。"
            count == 1 -> "今天有 1 道错题该复习了，花 2 分钟巩固一下。"
            else -> "今天有 " + count + " 道错题该复习了，趁热打铁效果最好。"
        }
}
