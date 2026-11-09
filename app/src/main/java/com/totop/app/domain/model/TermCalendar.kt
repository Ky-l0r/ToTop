package com.totop.app.domain.model

/**
 * 学期（学情页唯一的时间维度）。
 *
 * 边界按国内校历取整月：秋季 8/1 至次年 1/31，春季 2/1 至 7/31。
 * 各省开学日相差半个月，误差只落在学期最初几天，不会造成统计错乱；
 * 确需精确时由学生在界面修改本学期开学日（只改当前学期，历史不动）。
 *
 * 纯 Kotlin 实现，日期换算用「民用历 <-> 日序号」整数算法，
 * 不引入 java.time（minSdk 24 上不可用），也因此可在 JVM 单测中直接验证。
 */
data class Term(
    /** 稳定键，例如 2026S / 2025A。 */
    val key: String,
    /** 展示标签，例如「2026 春」。 */
    val label: String,
    /** 起始日（含），UTC 日序号。 */
    val fromDay: Long,
    /** 结束日（不含），UTC 日序号。 */
    val toDayExclusive: Long,
) {
    /** 该日是否落在本学期内。 */
    fun contains(day: Long): Boolean = day >= fromDay && day < toDayExclusive
}

/** 学期起止推算。 */
object TermCalendar {
    /** 春季起始月。 */
    const val SPRING_START_MONTH: Int = 2

    /** 秋季起始月。 */
    const val AUTUMN_START_MONTH: Int = 8

    /** 春季标签后缀。 */
    private const val SPRING_SUFFIX: String = "S"

    /** 秋季标签后缀。 */
    private const val AUTUMN_SUFFIX: String = "A"

    /**
     * 给定日所属的学期。
     *
     * @param overrideStart 学生修改过的本学期开学日（UTC 日序号），
     *   仅当它落在默认学期区间内且不晚于当天时才生效，避免把整条时间轴挪位。
     */
    fun ofDay(
        day: Long,
        overrideStart: Long? = null,
    ): Term {
        val (year, month, _) = civilOf(day)
        val term =
            when {
                month >= AUTUMN_START_MONTH ->
                    autumn(year)

                month == 1 ->
                    autumn(year - 1)

                else ->
                    spring(year)
            }
        if (overrideStart == null || overrideStart > day) return term
        if (overrideStart < term.fromDay) return term
        return term.copy(fromDay = overrideStart)
    }

    /** 当前学期 + 之前若干个学期，按时间倒序。 */
    fun recent(
        todayDay: Long,
        overrideStart: Long? = null,
        count: Int = 4,
    ): List<Term> {
        if (count <= 0) return emptyList()
        val result = ArrayList<Term>(count)
        var cursor = ofDay(todayDay, overrideStart)
        result.add(cursor)
        while (result.size < count) {
            cursor = ofDay(cursor.fromDay - 1)
            result.add(cursor)
        }
        return result
    }

    /** 按学期键取学期（下拉项回填用）；不认识时返回 null。 */
    fun byKey(
        key: String,
        todayDay: Long,
        overrideStart: Long? = null,
        count: Int = 8,
    ): Term? = recent(todayDay, overrideStart, count).firstOrNull { it.key == key }

    /** 秋季学期。 */
    private fun autumn(year: Int): Term =
        Term(
            key = year.toString() + AUTUMN_SUFFIX,
            label = year.toString() + " 秋",
            fromDay = daysFromCivil(year, AUTUMN_START_MONTH, 1),
            toDayExclusive = daysFromCivil(year + 1, SPRING_START_MONTH, 1),
        )

    /** 春季学期。 */
    private fun spring(year: Int): Term =
        Term(
            key = year.toString() + SPRING_SUFFIX,
            label = year.toString() + " 春",
            fromDay = daysFromCivil(year, SPRING_START_MONTH, 1),
            toDayExclusive = daysFromCivil(year, AUTUMN_START_MONTH, 1),
        )

    /**
     * 民用历 -> 日序号（Howard Hinnant 的 days_from_civil）。
     *
     * 公式里的常数是该算法的固定系数，抽成常量后反而无法与原始推导对照，
     * 因此就地放行 MagicNumber。
     */
    @Suppress("MagicNumber")
    fun daysFromCivil(
        year: Int,
        month: Int,
        day: Int,
    ): Long {
        var y = year.toLong()
        y -= if (month <= 2) 1 else 0
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val mp = (month + if (month > 2) -3 else 9).toLong()
        val doy = (153 * mp + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097 + doe - 719468
    }

    /** 日序号 -> 民用历（年, 月, 日）。常数说明同 [daysFromCivil]。 */
    @Suppress("MagicNumber")
    fun civilOf(epochDay: Long): Triple<Int, Int, Int> {
        val z = epochDay + 719468
        val era = (if (z >= 0) z else z - 146096) / 146097
        val doe = z - era * 146097
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        val y = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
        val m = (mp + if (mp < 10) 3 else -9).toInt()
        val year = (y + if (m <= 2) 1 else 0).toInt()
        return Triple(year, m, d)
    }
}
