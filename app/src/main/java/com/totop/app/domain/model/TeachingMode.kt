package com.totop.app.domain.model

/**
 * 讲解模式（0.2.0 起为两档）。
 *
 * 为什么把原「提示引导」与「纯引导」合并：
 * 真机反馈显示，原 GUIDED 会先渲染整份标准步骤再挂三级提示，
 * 观感与「直接讲解」几乎没差别——学生点开就看到答案，提示形同虚设；
 * 而 SOCRATIC 又不给任何可操作的抓手，只堆问题。
 * 两档的差别落在「给不给最终解答」这件事上，中间地带对学生没有实际价值。
 *
 * 合并后的语义：
 * - [DIRECT] 直接讲解：完整步骤 + 最终答案，一次给全，适合已经想通只核对写法；
 * - [GUIDED] 提示引导：**首屏只给方向与一个引导性问题**，不给步骤、不给答案；
 *   想继续就逐级要提示，想通了自己点「看全解」——答案必须由学生主动索取。
 *
 * 兼容性：旧数据里存的 `SOCRATIC` 会被 [fromWire] 归到 [GUIDED]，
 * 不会出现「设置里存的模式读不出来」或历史错题丢失模式信息的情况。
 */
enum class TeachingMode(val wire: String, val label: String) {
    DIRECT("DIRECT", "直接讲解"),
    GUIDED("GUIDED", "提示引导"),
    ;

    companion object {
        /** 默认模式：提示引导（不提前给答案）。 */
        val DEFAULT: TeachingMode = GUIDED

        /** 旧版「纯引导」的 wire 值，仅用于向后兼容读取，不再对外暴露。 */
        private const val LEGACY_SOCRATIC: String = "SOCRATIC"

        /** 按 wire 值解析，未知值与已废弃的 SOCRATIC 都回退为 [DEFAULT]。 */
        fun fromWire(raw: String?): TeachingMode {
            val token = raw?.trim()?.uppercase().orEmpty()
            return values().firstOrNull { it.wire == token } ?: DEFAULT
        }

        /**
         * 按 wire 值解析，未知值返回 null。
         *
         * 已废弃的 SOCRATIC 视同 GUIDED 返回，理由见类注释：
         * 它表达的是「不提前给答案」，与现在的 GUIDED 完全一致。
         */
        fun fromWireOrNull(raw: String?): TeachingMode? {
            val token = raw?.trim()?.uppercase().orEmpty()
            if (token == LEGACY_SOCRATIC) return GUIDED
            return values().firstOrNull { it.wire == token }
        }

        /** 是否为已废弃的模式值（用于数据迁移与统计口径说明）。 */
        fun isLegacy(raw: String?): Boolean = raw?.trim()?.uppercase() == LEGACY_SOCRATIC
    }
}
