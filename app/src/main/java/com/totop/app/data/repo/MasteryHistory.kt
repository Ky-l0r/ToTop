package com.totop.app.data.repo

import com.totop.app.domain.model.MasterySnapshot

/**
 * 掌握度历史裁剪（开发方案 5.2：mastery.historyJson 保留最近 90 天快照）。
 *
 * 纯函数，不依赖 Room 与 Android，可在 JVM 单测直接覆盖。
 */
object MasteryHistory {
    /** 默认保留天数。 */
    const val KEEP_DAYS: Int = 90

    /**
     * 裁剪历史快照。
     *
     * 规则：
     * 1. 同一 UTC 日只保留最后一条（后写入的覆盖先写入的）；
     * 2. 按日期升序排列；
     * 3. 只保留最后 [keepDays] 条。
     */
    fun trim(
        history: List<MasterySnapshot>,
        keepDays: Int = KEEP_DAYS,
    ): List<MasterySnapshot> {
        if (history.isEmpty()) return emptyList()
        val boundedKeep = keepDays.coerceAtLeast(1)
        return history
            .groupBy { it.dateEpochDayUtc }
            .map { (_, list) -> list.last() }
            .sortedBy { it.dateEpochDayUtc }
            .takeLast(boundedKeep)
    }
}
