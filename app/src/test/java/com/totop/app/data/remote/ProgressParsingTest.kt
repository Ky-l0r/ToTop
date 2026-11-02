package com.totop.app.data.remote

import com.totop.app.domain.model.QuestionProgressState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 阶段 A「逐问进度」的解析容错。
 *
 * 这条链上最容易出事的不是正常输出，而是模型少写字段、把 no 写成字符串、
 * 或者老缓存里根本没有这个数组——任何一种都不该让整次解答失败，
 * 也不该把「不知道」猜成「没写」。
 */
@DisplayName("逐问进度解析")
class ProgressParsingTest {
    private fun obj(raw: String): JsonObject = Json.parseToJsonElement(raw) as JsonObject

    @Test
    @DisplayName("正常数组按顺序还原")
    fun `正常解析`() {
        val progress =
            obj("""{"progress":[{"no":1,"state":"done"},{"no":2,"state":"stuck"},{"no":3,"state":"missing"}]}""")
                .progressOr("progress")
        assertEquals(3, progress.size)
        assertEquals(1, progress[0].no)
        assertEquals(QuestionProgressState.DONE, progress[0].state)
        assertEquals(QuestionProgressState.STUCK, progress[1].state)
        assertEquals("(3) 没写", progress[2].label)
    }

    @Test
    @DisplayName("没有该字段时返回空列表")
    fun `字段缺失`() {
        assertTrue(obj("""{"stem":"x"}""").progressOr("progress").isEmpty())
    }

    @Test
    @DisplayName("字段不是数组时返回空列表，不抛异常")
    fun `类型不对`() {
        assertTrue(obj("""{"progress":"done"}""").progressOr("progress").isEmpty())
    }

    @Test
    @DisplayName("认不出的 state 丢弃这一条，不猜成没写")
    fun `未知状态丢弃`() {
        val progress =
            obj("""{"progress":[{"no":1,"state":"half"},{"no":2,"state":"done"}]}""")
                .progressOr("progress")
        assertEquals(1, progress.size)
        assertEquals(2, progress[0].no)
    }

    @Test
    @DisplayName("no 缺失时按整题处理")
    fun `无小问编号`() {
        val progress = obj("""{"progress":[{"state":"done"}]}""").progressOr("progress")
        assertEquals(1, progress.size)
        assertEquals(null, progress[0].no)
        assertEquals("写到", progress[0].label)
    }

    @Test
    @DisplayName("handwriting 只有 partial/full 才算看到手写")
    fun `手写判定`() {
        val none = com.totop.app.domain.model.AiQuestion(handwriting = "none", handwritingText = "")
        val partial = com.totop.app.domain.model.AiQuestion(handwriting = "partial", handwritingText = "(1) 2x=4")
        assertTrue(!none.hasHandwriting)
        assertTrue(partial.hasHandwriting)
    }
}
