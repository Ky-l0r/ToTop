package com.totop.app.data.remote

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 重归纳结果的反序列化契约。
 *
 * 这条链路是「锦上添花」的：解析不出东西就放弃本次重写，卡片停在合并态。
 * 因此这里断言的重点不是「能解析出完美的骨架」，而是**坏输入一律返回 null**，
 * 绝不抛到主链路上去打断一次正常的解题。
 */
@DisplayName("方法卡重写解析")
class KpointMethodCodecTest {
    @Test
    @DisplayName("标准 JSON 能解析出三段")
    fun `正常输出`() {
        val raw =
            """{"steps":["确认表达式","代入并化简"],"applicable":"求某点函数值时","pitfalls":["忘记化简"]}"""
        val skeleton = KpointMethodCodec.parse(raw)
        assertEquals(listOf("确认表达式", "代入并化简"), skeleton?.steps)
        assertEquals("求某点函数值时", skeleton?.applicable)
        assertEquals(listOf("忘记化简"), skeleton?.pitfalls)
    }

    @Test
    @DisplayName("容忍代码围栏与前后解释")
    fun `围栏与解释`() {
        val raw =
            "好的，整理如下：\n```json\n{\"steps\":[\"确认表达式\"],\"applicable\":\"\",\"pitfalls\":[]}\n```\n以上。"
        val skeleton = KpointMethodCodec.parse(raw)
        assertEquals(listOf("确认表达式"), skeleton?.steps)
        assertTrue(skeleton?.pitfalls?.isEmpty() == true)
    }

    @Test
    @DisplayName("缺字段或缺步骤不崩，交给上层决定要不要写库")
    fun `缺字段`() {
        val skeleton = KpointMethodCodec.parse("""{"applicable":"求最值时"}""")
        assertEquals(emptyList<String>(), skeleton?.steps)
        assertEquals("求最值时", skeleton?.applicable)
    }

    @Test
    @DisplayName("不是 JSON 时返回 null，不抛异常")
    fun `坏输入`() {
        assertNull(KpointMethodCodec.parse(""))
        assertNull(KpointMethodCodec.parse("这题我不会"))
        assertNull(KpointMethodCodec.parse("{"))
    }
}
