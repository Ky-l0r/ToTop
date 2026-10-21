package com.totop.app.domain.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/** 知识点受控 ID 约定单测：KP_<SUBJECT>_<四位序号>。 */
class KnowledgeIdConventionTest {
    @Nested
    @DisplayName("格式化")
    inner class Format {
        @Test
        fun `按约定生成四位左补零`() {
            assertEquals("KP_MATH_0312", KnowledgeIdConvention.format("MATH", 312))
            assertEquals("KP_MATH_0001", KnowledgeIdConvention.format("math", 1))
            assertEquals("KP_PHYSICS_9999", KnowledgeIdConvention.format(" physics ", 9999))
            assertEquals("KP_MATH_0000", KnowledgeIdConvention.format("MATH", 0))
        }

        @Test
        fun `非法科目与越界序号抛异常`() {
            assertThrows(IllegalArgumentException::class.java) { KnowledgeIdConvention.format("M", 1) }
            assertThrows(IllegalArgumentException::class.java) { KnowledgeIdConvention.format("MATH1", 1) }
            assertThrows(IllegalArgumentException::class.java) { KnowledgeIdConvention.format("MATH", 10000) }
            assertThrows(IllegalArgumentException::class.java) { KnowledgeIdConvention.format("MATH", -1) }
        }
    }

    @Nested
    @DisplayName("校验与解析")
    inner class ValidateParse {
        @Test
        fun `合法 ID 通过校验`() {
            assertTrue(KnowledgeIdConvention.isValid("KP_MATH_0312"))
            assertTrue(KnowledgeIdConvention.isValid("KP_CHEMISTRY_0007"))
        }

        @Test
        fun `不合规写法被拒绝`() {
            listOf(
                null,
                "",
                "KP_MATH_312", // 序号不足四位
                "KP_MATH_03120", // 序号超四位
                "kp_math_0312", // 大小写
                "KP-MATH-0312", // 分隔符
                "MATH_0312", // 缺前缀
                "KP__0312", // 缺科目
                "KP_MATH_03a2", // 非数字
                "导数与单调性", // 自由文本
            ).forEach { id -> assertFalse(KnowledgeIdConvention.isValid(id), "应拒绝：$id") }
        }

        @Test
        fun `解析科目与序号`() {
            assertEquals("MATH", KnowledgeIdConvention.subjectOf("KP_MATH_0312"))
            assertEquals(312, KnowledgeIdConvention.serialOf("KP_MATH_0312"))
            assertNull(KnowledgeIdConvention.subjectOf("bad"))
            assertNull(KnowledgeIdConvention.serialOf("bad"))
        }

        @Test
        fun `内置科目判定`() {
            assertTrue(KnowledgeIdConvention.isKnownSubject("KP_MATH_0312"))
            assertFalse(KnowledgeIdConvention.isKnownSubject("KP_UNKNOWNSUBJECT_0001"))
        }

        @Test
        fun `知识点的受控标记随 ID 变化`() {
            val ok = KnowledgePoint(id = "KP_MATH_0001", name = "导数与单调性", subject = "math")
            val bad = KnowledgePoint(id = "导数与单调性", name = "导数与单调性", subject = "math")
            assertTrue(ok.hasControlledId)
            assertFalse(bad.hasControlledId)
        }
    }
}
