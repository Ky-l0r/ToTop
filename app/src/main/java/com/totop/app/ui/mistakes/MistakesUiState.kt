package com.totop.app.ui.mistakes

import com.totop.app.domain.model.ErrorType
import com.totop.app.domain.usecase.port.DAY_MILLIS

/**
 * 时间窗口筛选（FR-4.1「按时间筛选」）。
 *
 * 统一以 UTC 毫秒计算下界：数据层所有时间列都是 UTC 毫秒（文档 5.2），
 * 展示层需要本地日期时再转换，筛选逻辑本身与时区无关。
 */
enum class TimeWindow(val label: String, val days: Int?) {
    ALL("全部时间", null),
    WEEK("近 7 天", 7),
    MONTH("近 30 天", 30),
    QUARTER("近 90 天", 90),
    ;

    /** 该窗口的 UTC 下界（含）；[ALL] 返回 null 表示不加时间条件。 */
    fun fromUtcOrNull(nowUtc: Long): Long? = days?.let { nowUtc - it.toLong() * DAY_MILLIS }
}

/**
 * 错题本筛选条件（FR-4.1：科目 / 知识点 / 错因 / 时间 + 搜索）。
 *
 * 纯数据 + 纯函数，可直接单测；SQL 片段由 [MistakeBrowseGateway] 依据本对象生成。
 */
data class MistakeFilter(
    val subject: String? = null,
    val kpointId: String? = null,
    val errorType: ErrorType? = null,
    val timeWindow: TimeWindow = TimeWindow.ALL,
    val keyword: String = "",
) {
    /** 有效的搜索词（去首尾空白后非空才参与查询）。 */
    val normalizedKeyword: String get() = keyword.trim()

    /** 是否有任何筛选处于生效状态（决定界面是否显示「清除筛选」）。 */
    val isActive: Boolean
        get() =
            subject != null || kpointId != null || errorType != null ||
                timeWindow != TimeWindow.ALL || normalizedKeyword.isNotEmpty()

    /** 生效条件数量，用于界面上的筛选按钮徽标。 */
    val activeCount: Int
        get() =
            listOf(
                subject != null,
                kpointId != null,
                errorType != null,
                timeWindow != TimeWindow.ALL,
                normalizedKeyword.isNotEmpty(),
            ).count { it }

    fun withSubject(value: String?): MistakeFilter = copy(subject = value)

    fun withKpoint(value: String?): MistakeFilter = copy(kpointId = value)

    fun withErrorType(value: ErrorType?): MistakeFilter = copy(errorType = value)

    fun withTimeWindow(value: TimeWindow): MistakeFilter = copy(timeWindow = value)

    fun withKeyword(value: String): MistakeFilter = copy(keyword = value)

    /** 清空筛选，保留无（用于「清除筛选」按钮）。 */
    fun cleared(): MistakeFilter = MistakeFilter()

    companion object {
        /**
         * 把用户输入转义成 SQL LIKE 的字面量。
         *
         * 反斜杠先转义，再转义 % 与 _，配合查询里的 `ESCAPE '\\'`，
         * 保证搜索 `50%` 这类内容时不会变成通配符查询。
         */
        fun escapeLike(raw: String): String =
            raw
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_")
    }
}

/** 知识点下拉项（受控 ID + 名称）。 */
data class KpointOption(val id: String, val name: String) {
    /** 下拉展示文本：章节不参与过滤，只帮助定位。 */
    fun label(): String = name
}

/**
 * 错题列表的一行（投影结果，不是完整领域对象）。
 *
 * 列表只需要「题干摘要 + 错因 + 知识点 + 复发次数 + 缩略图」，
 * 因此查询阶段就做投影，避免把整份标准解答与 AI 内容读进内存。
 */
data class MistakeCardUi(
    val mistakeId: String,
    val questionId: String,
    val stemText: String,
    val subject: String,
    val errorTypeLabel: String,
    val errorTypeCode: String,
    val kpointNames: List<String>,
    val recurrenceIndex: Int,
    val createdAtUtc: Long,
    /** 主图绝对路径；无图为 null。 */
    val imagePath: String?,
    /** 列表用缩略图绝对路径；缩略图缺失时回退主图。 */
    val thumbnailPath: String?,
    val finalAnswer: String,
    val hasLocatedErrorStep: Boolean,
) {
    /** 复发徽标：首次不显示，第 2 次起显示「第 N 次错」（FR-4.5）。 */
    val recurrenceBadge: String? get() = recurrenceBadgeText(recurrenceIndex)

    /** 题干摘要：列表只显示前 60 个字，避免长题干把卡片撑爆。 */
    val stemSummary: String get() = summarize(stemText, STEM_SUMMARY_LIMIT)

    /** 科目中文名。 */
    val subjectLabel: String get() = subjectLabelOf(subject)

    /** 知识点标签（最多三个，其余折叠成一个「+N」标签）。 */
    val kpointTags: List<String>
        get() {
            if (kpointNames.size <= KPOINT_TAG_LIMIT) return kpointNames
            val shown = kpointNames.take(KPOINT_TAG_LIMIT)
            val rest = kpointNames.size - KPOINT_TAG_LIMIT
            return shown + listOf("+" + rest)
        }

    companion object {
        /** 题干摘要字数上限。 */
        const val STEM_SUMMARY_LIMIT: Int = 60

        /** 知识点标签展示上限。 */
        const val KPOINT_TAG_LIMIT: Int = 3
    }
}

/** 复发文案：首次为 null，其余为「第 N 次错」。 */
fun recurrenceBadgeText(recurrenceIndex: Int): String? =
    if (recurrenceIndex <= 1) null else "第 " + recurrenceIndex + " 次错"

/** 题干摘要：按字符截断并加省略号，压缩连续空白但不改动公式符号。 */
fun summarize(
    text: String,
    limit: Int,
): String {
    val normalized = text.replace(Regex("[ \\t]{2,}"), " ").trim()
    if (limit <= 0) return ""
    if (normalized.length <= limit) return normalized
    return normalized.take(limit).trimEnd() + "…"
}

/**
 * 学科代码转中文。
 *
 * 必须直接委托 [com.totop.app.domain.model.Subjects]，不能在这里维护第二份映射表：
 * 本文件原先自带一张只覆盖六科的 when 表，缺政治、历史、地理、技术与兜底科，
 * 于是「技术」在错题本里被原样显示成英文代码 `technology`。
 * 科目一旦新增，两处映射必然再次分叉，因此这里只保留「空值」这一个例外。
 */
fun subjectLabelOf(subject: String): String {
    val token = subject.trim()
    if (token.isEmpty()) return "未标注学科"
    return com.totop.app.domain.model.Subjects.label(token)
}
