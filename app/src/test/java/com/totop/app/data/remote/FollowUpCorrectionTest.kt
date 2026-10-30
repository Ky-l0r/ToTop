package com.totop.app.data.remote

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 追问订正标记的解析。
 *
 * 三段行为必须同时成立：
 * 1. 标记行从给学生看的正文里消失（不能出现「[[订正]]最终答案：C」这种东西）；
 * 2. 新答案被正确取出；
 * 3. 模型没给标记时不产生任何「订正」——宁可不同步，也不能猜一个答案写进错题本。
 */
class FollowUpCorrectionTest {
    @Test
    @DisplayName("带标记：正文干净，答案取到")
    fun parseWithMarker() {
        val raw =
            "哦，是我理解偏了：试管口朝上会把冷凝水倒流炸裂。\n" +
                "所以这道题能选的只有 C。\n" +
                "[[订正]]最终答案：C；理由：A 项装置错误"
        val parsed = FollowUpCorrection.parse(raw)
        assertEquals("C", parsed.correctedAnswer)
        assertFalse(parsed.text.contains("[[订正]]"))
        assertFalse(parsed.text.contains("最终答案"))
        assertEquals("哦，是我理解偏了：试管口朝上会把冷凝水倒流炸裂。\n所以这道题能选的只有 C。", parsed.text)
    }

    @Test
    @DisplayName("标记的答案带选项内容、用半角冒号也能解析")
    fun parseFlexibleFormats() {
        assertEquals("C. 氯化钠固体", FollowUpCorrection.parse("分析完了\n[[订正]]答案: C. 氯化钠固体").correctedAnswer)
        assertEquals("B、D", FollowUpCorrection.parse("[[订正]]最终答案：B、D").correctedAnswer)
    }

    @Test
    @DisplayName("核对标记：结论没变也能被客户端识别，正文同样干净")
    fun parseCheckMarker() {
        val raw = "我把条件代回去重算了一遍，结果还是 C。\n[[核对]]最终答案：C"
        val parsed = FollowUpCorrection.parse(raw)
        assertEquals("C", parsed.correctedAnswer)
        assertTrue(parsed.verifiedOnly, "只带核对标记时 verifiedOnly 必须为 true")
        assertFalse(parsed.text.contains("核对"))
        assertEquals("我把条件代回去重算了一遍，结果还是 C。", parsed.text)
    }

    @Test
    @DisplayName("订正标记优先于核对标记被识别")
    fun correctionWinsOverCheck() {
        val raw = "重算后是 B。\n[[核对]]最终答案：B\n[[订正]]最终答案：B；理由：选项看错了"
        val parsed = FollowUpCorrection.parse(raw)
        assertEquals("B", parsed.correctedAnswer)
        assertFalse(parsed.verifiedOnly, "带订正标记时按订正处理")
        assertFalse(parsed.text.contains("[["))
    }

    @Test
    @DisplayName("没有标记：原文不动，不产生订正")
    fun parseWithoutMarker() {
        val raw = "这道题的第一步是把速度分解。"
        val parsed = FollowUpCorrection.parse(raw)
        assertEquals(raw, parsed.text)
        assertNull(parsed.correctedAnswer)
    }

    @Test
    @DisplayName("标记里没有答案：不产生订正，但标记行仍然被剔除")
    fun parseMarkerWithoutAnswer() {
        val parsed = FollowUpCorrection.parse("说明如下。\n[[订正]]")
        assertNull(parsed.correctedAnswer)
        assertEquals("说明如下。", parsed.text)
    }
}
