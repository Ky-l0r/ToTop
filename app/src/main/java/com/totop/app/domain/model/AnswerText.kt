package com.totop.app.domain.model

/**
 * 答案文本的比对与拼接（纯函数，可单测）。
 *
 * 为什么单独拿出来：这两件事都不依赖数据库，但要同时被「老师给答案」「追问补小问」
 * 两条写入路径使用；留在仓储里会让它多出两个公开方法（detekt 的 TooManyFunctions
 * 阈值是 40），也让这两条最容易出错的规则藏在一堆 SQL 之间。
 */
object AnswerText {
    /**
     * 归一化后再比较：只吸收排版差异，不做语义判断。
     *
     * 刻意不做的三件事：不去掉字母大小写以外的内容（`2x` 与 `2X` 视为同一答案，
     * 但 `2x` 与 `2x²` 不视为同一）、不解析数学等价（`1/2` 与 `0.5` 判为不同）、
     * 不忽略单位与措辞。宁可多标一次「存疑」，也不要替学生判断两个答案等价。
     */
    fun normalize(raw: String): String =
        raw
            .replace(" ", "")
            .replace("\u00A0", "")
            .replace("，", ",")
            .replace("；", ";")
            .replace("。", "")
            .trim()
            .lowercase()

    /** 两个答案是否一致（按 [normalize] 的规则）。 */
    fun sameAs(
        left: String,
        right: String,
    ): Boolean = normalize(left) == normalize(right)

    /**
     * 把新补的小问答案并进最终答案：空值直接用，非空用「；」续在后面。
     *
     * 追加而不是覆盖：订正改的是同一问的结论，补小问补的是之前没有的另外几问。
     */
    fun merge(
        existing: String,
        added: List<SolutionPart>,
    ): String {
        if (added.isEmpty()) return existing
        val suffix = added.joinToString("；") { it.label + "：" + it.answer }
        return if (existing.isBlank()) suffix else existing.trimEnd('；', ';') + "；" + suffix
    }
}
