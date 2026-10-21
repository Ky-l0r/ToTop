package com.totop.app.domain.model

/**
 * 题目领域模型，对应开发方案 5.1 `question` 表。
 *
 * 所有时间戳均为 UTC 毫秒 Long，展示层再转本地时区（开发方案 5.2）。
 */
data class Question(
    /** 本地主键。 */
    val id: String,
    /** 学科，例如 math / physics。 */
    val subject: String,
    /** 学段，例如「高一」。 */
    val grade: String,
    /** 题干纯文本（OCR 或手输）。 */
    val stemText: String,
    /** 已知条件列表。 */
    val known: List<String> = emptyList(),
    /** 所求。 */
    val ask: String = "",
    /** 选项（选择题）。 */
    val options: List<String> = emptyList(),
    /** 是否含图（决定是否必须走视觉模型）。 */
    val hasFigure: Boolean = false,
    /** 原图本地路径；无图为 null。 */
    val imagePath: String? = null,
    /** 原图 sha256，唯一，作为缓存与去重锚点。 */
    val imageSha256: String? = null,
    /** 来源。 */
    val sourceType: QuestionSourceType = QuestionSourceType.MANUAL,
    /** 创建时间（UTC 毫秒）。 */
    val createdAtUtc: Long,
) {
    val isMultipleChoice: Boolean get() = options.isNotEmpty()

    val isVisualQuestion: Boolean get() = hasFigure || imagePath != null
}
