package com.totop.app.data.repo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 离线记题模型单测。
 *
 * 只测纯计算逻辑（标题/副标题推断），不测文件 IO：
 * 文件读写在 [OfflineNoteRepository] 里依赖 Android Context，
 * 属于 JVM 单测覆盖不到的边界，需要仪器测试。
 */
class OfflineNoteTest {
    @Test
    @DisplayName("有题目文本时，标题取第一行非空内容")
    fun titleFromStem() {
        val note =
            OfflineNote(
                id = "1",
                stemText = "\n\n下列有关物质结构与性质的说法不正确的是\nA. SiC 结构",
            )
        assertEquals("下列有关物质结构与性质的说法不正确的是", note.title)
    }

    @Test
    @DisplayName("没有题目文本时，标题说明拍了几页")
    fun titleFromPages() {
        val note = OfflineNote(id = "1", stemText = "", imagePaths = listOf("a.jpg", "b.jpg"))
        assertEquals("拍了 2 页", note.title)
    }

    @Test
    @DisplayName("完全没有内容时也有可读标题，不留空白")
    fun titleFallback() {
        val note = OfflineNote(id = "1", stemText = "   ", imagePaths = emptyList())
        assertEquals("空白记录", note.title)
    }

    @Test
    @DisplayName("副标题如实反映页数与是否写了思路")
    fun subtitle() {
        val withProcess =
            OfflineNote(id = "1", studentProcess = "我先算了导数", imagePaths = listOf("a.jpg"))
        assertEquals("1 页图片 · 已写思路", withProcess.subtitle)
        val withoutProcess = OfflineNote(id = "2", studentProcess = "", imagePaths = listOf("a.jpg", "b.jpg"))
        assertEquals("2 页图片 · 没写思路", withoutProcess.subtitle)
    }

    @Test
    @DisplayName("超长题干标题被截断，不会撑破列表")
    fun longTitleTruncated() {
        val note = OfflineNote(id = "1", stemText = "很长的题目".repeat(20))
        assertTrue(note.title.length <= 28)
    }

    @Test
    @DisplayName("默认状态是未解，避免刚存下就被当成已处理")
    fun defaultUnsolved() {
        val note = OfflineNote(id = "1")
        assertFalse(note.solved)
        assertEquals(0L, note.createdAtUtc)
    }
}
