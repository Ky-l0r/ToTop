package com.totop.app.domain.model

/**
 * 知识点受控 ID 约定：`KP_<SUBJECT>_<四位序号>`，例如 `KP_MATH_0312`。
 *
 * 约束来源：开发方案 FR-1.7 / 3.2 阶段 D「必须映射内置知识点树受控 ID，禁止自由造词」。
 * 该对象是纯 Kotlin 实现，不依赖 Android API，可在 JVM 单测中直接使用。
 *
 * 规则：
 * 1. 前缀固定为 `KP`，全大写；
 * 2. 科目段为 2-12 位大写字母（MATH / PHYSICS / CHEMISTRY ...）；
 * 3. 序号段为固定四位十进制数字（0000-9999），不足左补零。
 */
object KnowledgeIdConvention {
    /** 受控 ID 前缀。 */
    const val PREFIX: String = "KP"

    /** 序号段固定位数。 */
    const val SERIAL_DIGITS: Int = 4

    /** 序号下界。 */
    const val MIN_SERIAL: Int = 0

    /** 序号上界（四位数字）。 */
    const val MAX_SERIAL: Int = 9999

    /** 目前内置知识点树覆盖的科目段（可扩充，但必须保持为大写字母）。 */
    val KNOWN_SUBJECTS: List<String> =
        listOf(
            "MATH",
            "PHYSICS",
            "CHEMISTRY",
            "BIOLOGY",
            "CHINESE",
            "ENGLISH",
        )

    private val SUBJECT_TOKEN = Regex("^[A-Z]{2,12}$")
    private val FULL_ID = Regex("^KP_([A-Z]{2,12})_([0-9]{4})$")

    /**
     * 由科目段与序号生成受控 ID。
     *
     * @throws IllegalArgumentException 科目段非法或序号越界。
     */
    fun format(
        subject: String,
        serial: Int,
    ): String {
        val token = subject.trim().uppercase()
        require(SUBJECT_TOKEN.matches(token)) {
            "科目段必须为 2-12 位大写字母，实际为：$subject"
        }
        require(serial in MIN_SERIAL..MAX_SERIAL) {
            "知识点序号必须位于 $MIN_SERIAL..$MAX_SERIAL，实际为：$serial"
        }
        return PREFIX + "_" + token + "_" + serial.toString().padStart(SERIAL_DIGITS, '0')
    }

    /** 是否为合法的受控 ID。null / 空串 / 大小写不合规均返回 false。 */
    fun isValid(id: String?): Boolean = id != null && FULL_ID.matches(id)

    /** 解析科目段；非法 ID 返回 null。 */
    fun subjectOf(id: String): String? = FULL_ID.find(id)?.groupValues?.getOrNull(1)

    /** 解析序号；非法 ID 返回 null。 */
    fun serialOf(id: String): Int? = FULL_ID.find(id)?.groupValues?.getOrNull(2)?.toIntOrNull()

    /** 是否属于内置科目段；非法 ID 返回 false。 */
    fun isKnownSubject(id: String): Boolean = subjectOf(id)?.let { it in KNOWN_SUBJECTS } == true
}
