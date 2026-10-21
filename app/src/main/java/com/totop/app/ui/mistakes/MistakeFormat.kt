package com.totop.app.ui.mistakes

import com.totop.app.domain.model.ErrorType
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.util.Date
import java.util.Locale

/**
 * 错题本界面的展示辅助（纯函数，可单测）。
 *
 * 时间列在数据层是 UTC 毫秒（文档 5.2），这里统一转本地时区后再格式化，
 * 保证「今天 / 昨天 / 3 天前」与学生手机上的日历一致。
 */
object MistakeFormat {
    /** 本地日期格式（年-月-日）。 */
    private const val DATE_PATTERN = "yyyy-MM-dd"

    /**
     * 相对日期文案。
     *
     * 同一天显示「今天 14:05」，前一日显示「昨天 09:30」，其余显示「2025-03-04」。
     * 用本地时区的「日序号」比较，避免跨时区把今天算成昨天。
     */
    fun relativeDayLabel(
        utcMillis: Long,
        nowUtcMillis: Long,
    ): String {
        val zone = ZoneId.systemDefault()
        val day = localEpochDay(utcMillis, zone)
        val today = localEpochDay(nowUtcMillis, zone)
        val diff = today - day
        return when {
            diff == 0L -> "今天"
            diff == 1L -> "昨天"
            diff in 2L..6L -> diff.toString() + " 天前"
            else -> formatDate(utcMillis)
        }
    }

    /** 具体时间文案：今天 / 昨天带时刻，更早只显示日期。 */
    fun dayTimeLabel(
        utcMillis: Long,
        nowUtcMillis: Long,
    ): String {
        val day = relativeDayLabel(utcMillis, nowUtcMillis)
        return if (day == "今天" || day == "昨天") day + " " + formatTime(utcMillis) else day
    }

    /** yyyy-MM-dd。 */
    fun formatDate(utcMillis: Long): String = SimpleDateFormat(DATE_PATTERN, Locale.CHINA).format(Date(utcMillis))

    /** HH:mm。 */
    fun formatTime(utcMillis: Long): String = SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(utcMillis))

    /** 当地日序号（与时区相关，仅用于「相差几天」的比较）。 */
    fun localEpochDay(
        utcMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long = Instant.ofEpochMilli(utcMillis).atZone(zone).toLocalDate().toEpochDay()

    /** 错因选择项：固定七类，供筛选下拉使用。 */
    fun errorTypeOptions(): List<ErrorType> = ErrorType.FIXED

    /** 排序后的科目展示名（按中文名排序，避免依代码顺序跳动）。 */
    fun subjectLabels(subjects: List<String>): List<Pair<String, String>> =
        subjects.map { it to subjectLabelOf(it) }.sortedBy { it.second }
}
