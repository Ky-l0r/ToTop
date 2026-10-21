package com.totop.app.domain.model

import com.totop.app.util.NormalizedRect

/** 一页图片的角色。 */
enum class PageRole(val wire: String, val label: String) {
    /** 主图：题干所在页。 */
    MAIN("main", "题目主图"),

    /** 续页：同一道题的后续部分（例如解答选项、图形、第二问）。 */
    CONTINUATION("continuation", "续页"),
}

/**
 * 拍题中的一页（对应一道题的第 i 张照片）。
 *
 * @param imagePath 压缩后的正式图片路径（不是 cacheDir 临时文件）
 * @param cropRect 框选区域（归一化坐标）；null 表示未框选、使用整图
 * @param role 本页角色，决定提交给模型时如何叙述
 * @param sha256 图片内容哈希，用于缓存键
 */
data class QuestionPage(
    val imagePath: String,
    val cropRect: NormalizedRect? = null,
    val role: PageRole = PageRole.MAIN,
    val sha256: String = "",
    /** 列表缩略图路径；为 null 时 UI 回退到加载原图。 */
    val thumbnailPath: String? = null,
) {
    /** 是否做了框选。 */
    val hasCrop: Boolean get() = cropRect != null && cropRect.isEmpty.not()
}

/**
 * 一次拍题的完整草稿：同一道题可以有多页。
 *
 * 顺序即页序：`pages[0]` 是第 1 页。UI 的缩略图排序直接改这个列表的顺序。
 *
 * 为什么用「列表 + 顺序」而不是「主图 + 附加图」：
 * 有些题先给条件（第 1 页）、再给图形（第 2 页），主次并不固定，
 * 顺序信息比角色更重要。role 只用于给模型补充说明，不参与排序决策。
 */
data class QuestionDraft(
    val pages: List<QuestionPage> = emptyList(),
) {
    val isEmpty: Boolean get() = pages.isEmpty()

    val pageCount: Int get() = pages.size

    /** 是否多页（用于界面显示「共 N 页」）。 */
    val isMultiPage: Boolean get() = pages.size > 1

    /** 追加一页到最后。 */
    fun add(page: QuestionPage): QuestionDraft = copy(pages = pages + page)

    /** 删除指定下标，越界时原样返回。 */
    fun removeAt(index: Int): QuestionDraft {
        if (index !in pages.indices) return this
        return copy(pages = pages.filterIndexed { i, _ -> i != index })
    }

    /** 从 from 移到 to（用于拖拽排序）。 */
    fun move(from: Int, to: Int): QuestionDraft {
        if (from !in pages.indices || to !in pages.indices || from == to) return this
        val mutable = pages.toMutableList()
        val item = mutable.removeAt(from)
        mutable.add(to, item)
        return copy(pages = mutable)
    }

    /** 替换某页（例如重新框选后覆盖）。 */
    fun replaceAt(index: Int, page: QuestionPage): QuestionDraft {
        if (index !in pages.indices) return this
        return copy(pages = pages.toMutableList().also { it[index] = page })
    }

    companion object {
        val EMPTY: QuestionDraft = QuestionDraft()
    }
}
