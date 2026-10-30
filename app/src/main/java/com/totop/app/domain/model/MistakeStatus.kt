package com.totop.app.domain.model

/**
 * 易错题的四种状态（口径见 docs/需求定稿-0.6.0.md 第四节，命名按用户 2026-10-31 的定稿）。
 *
 * 为什么状态不只有「错」：
 * 学生抱回来的东西本来就有四种——根本没思路、自己做了但不确定、确实做错、
 * 以及看完之后想通了。只留「错」会逼着学生把「不会」和「待校对」也当成错题记，
 * 统计里就只剩一个数字，看不出该补什么。
 *
 * 排序即界面顺序：不会 → 待校对 → 错误 → 理解。
 * 最后一个是终态：自评看懂并能自己重做，等同于「过关」，会参与掌握度（见 0.6.0 第七节）。
 *
 * wire 值是持久化与筛选参数的唯一来源，改文案不影响历史数据。
 */
enum class MistakeStatus(
    val wire: String,
    val label: String,
    /** 一句话解释，用在筛选条的说明与详情页。 */
    val hint: String,
) {
    /** 拍了但没思路，或明确说不会。没有错因，因为没有可对齐的学生步骤。 */
    UNKNOWN("unknown", "不会", "拍了但没思路，或明说不会"),

    /** 自己做过，答案还没核对。多来自「核对答案」与手动记题。 */
    PENDING_CHECK("pending_check", "待校对", "自己做过，答案还没核对"),

    /** 有学生过程且定位到首个错误步骤。历史记录全部落在这里。 */
    WRONG("wrong", "错误", "定位到了第一个错误步骤"),

    /** 自评已看懂、能自己重做，等同于「过关」。 */
    UNDERSTOOD("understood", "理解", "看懂了，能自己重做"),

    ;

    companion object {
        /** 历史数据的默认值：0.6.0 之前的记录都是「做错」。 */
        val DEFAULT: MistakeStatus = WRONG

        /** 按 wire 解析；认不出时返回 null（由调用方决定回退策略）。 */
        fun ofWire(raw: String?): MistakeStatus? =
            entries.firstOrNull { it.wire.equals(raw?.trim(), ignoreCase = true) }

        /**
         * 读取侧的回退：认不出就当「错误」。
         *
         * 回退到 [WRONG] 而不是 null，是因为列表与统计都不接受「无状态」的记录：
         * 一条读不出状态的旧数据如果被当成异常的 null，会在列表里消失，
         * 学生看到的是「错题少了」而不是「有一条读不出来」。
         */
        fun fromWireOrWrong(raw: String?): MistakeStatus = ofWire(raw) ?: DEFAULT
    }
}
