package com.totop.app.domain.model

/**
 * 「学生学哪几科」的口径（纯函数，可单测）。
 *
 * 为什么单独抽出来：
 * 这个集合会被错题本、知识点、复习资料、首页统计多处读取，各处自己判空必然出现
 * 「一页显示全部、另一页只显示两科」的分叉。所有读取方都走这里，只留一份规则。
 *
 * 规则：
 * 1. 存的是逗号分隔的科目代码，顺序无关；
 * 2. 空值 = 全选（升级上来的老用户不该因为没选过科目就什么都看不到）；
 * 3. 非法代码直接丢弃；丢光了同样回落到全选，绝不返回空集合——
 *    空集合会让所有列表空白，而用户无法从界面判断是自己选错了还是数据没了；
 * 4. `general`（未知科目）**不属于可选科目**：它不是一门课，是识别失败的标记。
 *    它可以出现在「全部」列表里，但永远不会出现在科目页签或多选列表里。
 */
object SubjectScope {
    /** 可选的科目：即 [Subjects.ORDER]，十科，不含 `general`。 */
    val PICKABLE: List<String> = Subjects.ORDER

    /** 全选。 */
    val ALL: Set<String> = PICKABLE.toSet()

    /** 解析存储值；空值或全是非法代码时返回 [ALL]。 */
    fun parse(raw: String?): Set<String> {
        if (raw.isNullOrBlank()) return ALL
        val codes =
            raw.split(',')
                .map { it.trim() }
                .filter { PICKABLE.contains(it) }
                .toSet()
        return codes.ifEmpty { ALL }
    }

    /** 序列化成存储值，按 [PICKABLE] 的顺序，便于人工排查。 */
    fun format(codes: Set<String>): String = PICKABLE.filter { codes.contains(it) }.joinToString(",")

    /**
     * 某条记录的科目是否该被展示。
     *
     * `general`（未知）始终可见：它出现在「全部」里是对的，学生要知道有这么一道题；
     * 但它不会被算进任何科目的统计（统计只遍历 [PICKABLE]）。
     */
    fun isVisible(
        code: String,
        allowed: Set<String>,
    ): Boolean = code == Subjects.GENERAL || allowed.contains(code)
}
