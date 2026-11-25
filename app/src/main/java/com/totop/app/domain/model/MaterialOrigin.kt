package com.totop.app.domain.model

/**
 * 一条资料的来源类别（0.9，落库到 `learned_material.origin`）。
 *
 * 为什么要单独一个字段而不是复用 `sourceLabel`：后者是**学生自己填的中文标注**
 * （「作业帮截图」「老师发的解析」），可以随时改；而「这条是导入的标准答案，还是自己收集的解析」
 * 是产品口径，决定资料库这一页显示什么。把可见文案当枚举用，改一次文案就会静默失效。
 */
enum class MaterialOrigin(
    val wire: String,
    val label: String,
) {
    /** 自己收集：拍题页看完解答点「存进资料库」。 */
    COLLECTED("collected", "自己收集"),

    /** 导入的标准答案：拍题页抽屉「导入标准答案」读进来的那一份。 */
    IMPORTED("import", "导入的标准答案"),
    ;

    companion object {
        fun fromWire(raw: String?): MaterialOrigin = entries.firstOrNull { it.wire == raw?.trim() } ?: COLLECTED
    }
}
