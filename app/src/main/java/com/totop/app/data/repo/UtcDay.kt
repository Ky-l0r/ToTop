package com.totop.app.data.repo

import com.totop.app.util.DAY_MILLIS

/**
 * UTC 日序号换算（开发方案 5.2：daily_stats.date 与 mastery 快照的 dateEpochDayUtc）。
 *
 * 纯函数，与时区无关：日序号 = floorDiv(utcMillis, 86400000)。
 * 展示层需要本地日期时再在 ui 层做时区转换，数据层不引入 java.time。
 */
object UtcDay {
    /** 一天的毫秒数，与 util 层保持同一常量。 */
    const val MILLIS_PER_DAY: Long = DAY_MILLIS

    /** UTC 毫秒 -> UTC 日序号。 */
    fun of(utcMillis: Long): Long = Math.floorDiv(utcMillis, MILLIS_PER_DAY)

    /** UTC 日序号 -> 该日 00:00:00 UTC 的毫秒时间戳。 */
    fun startOfDayMillis(dateEpochDayUtc: Long): Long = dateEpochDayUtc * MILLIS_PER_DAY

    /** 某日的半开区间 [start, end)，供 DAO 的 >= from AND < to 查询使用。 */
    fun dayRange(dateEpochDayUtc: Long): LongRange =
        startOfDayMillis(dateEpochDayUtc) until startOfDayMillis(dateEpochDayUtc + 1)

    /** 最近 N 天（含今天）的日序号区间，升序。 */
    fun lastDays(
        nowUtcMillis: Long,
        days: Int,
    ): List<Long> {
        val today = of(nowUtcMillis)
        val bounded = days.coerceAtLeast(1)
        return (0 until bounded).map { today - (bounded - 1) + it }
    }
}
