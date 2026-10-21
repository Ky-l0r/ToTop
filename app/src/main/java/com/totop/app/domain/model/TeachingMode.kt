package com.totop.app.domain.model

/**
 * 讲解模式三档，枚举值与开发方案 FR-2 表格一致。
 *
 * - DIRECT：直接讲解，返回完整步骤与答案；
 * - GUIDED：提示引导（推荐默认），不返回最终答案，按三级提示逐级解锁；
 * - SOCRATIC：纯引导，只返回提问与方向，不返回答案与算式。
 */
enum class TeachingMode(val wire: String, val label: String) {
    DIRECT("DIRECT", "直接讲解"),
    GUIDED("GUIDED", "提示引导"),
    SOCRATIC("SOCRATIC", "纯引导"),
    ;

    companion object {
        /** 默认模式：提示引导。 */
        val DEFAULT: TeachingMode = GUIDED

        /** 按 wire 值解析，未知值回退为 [DEFAULT]。 */
        fun fromWire(raw: String?): TeachingMode {
            val token = raw?.trim()?.uppercase().orEmpty()
            return values().firstOrNull { it.wire == token } ?: DEFAULT
        }

        /** 按 wire 值解析，未知值返回 null。 */
        fun fromWireOrNull(raw: String?): TeachingMode? {
            val token = raw?.trim()?.uppercase().orEmpty()
            return values().firstOrNull { it.wire == token }
        }
    }
}
