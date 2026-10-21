package com.totop.app.domain.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * ErrorTypeMapper 单测：七类固定枚举完整性、中文名/机器码/近义词映射、
 * 未知值与脏输入一律落兜底（不抛异常）。
 */
class ErrorTypeMapperTest {
    @Nested
    @DisplayName("固定七类完整性")
    inner class FixedSeven {
        @Test
        fun `枚举数量固定为七`() {
            assertEquals(7, ErrorType.values().size)
            assertEquals(7, ErrorType.FIXED_COUNT)
        }

        @Test
        fun `中文名与产品定义完全一致且顺序固定`() {
            assertEquals(
                listOf("审题理解", "概念混淆", "公式误用", "计算失误", "方法选择不当", "步骤缺失", "书写规范"),
                ErrorType.FIXED_LABELS,
            )
        }

        @Test
        fun `兜底值必为七类之一`() {
            assertTrue(ErrorType.FALLBACK in ErrorType.values())
        }

        @Test
        fun `机器码唯一`() {
            assertEquals(7, ErrorType.values().map { it.code }.toSet().size)
        }
    }

    @Nested
    @DisplayName("合法输入映射")
    inner class ValidInputs {
        @Test
        fun `中文标准名可映射`() {
            ErrorType.FIXED_LABELS.forEach { label ->
                val mapping = ErrorTypeMapper.map(label)
                assertTrue(mapping.matched, "应命中：$label")
                assertEquals(label, mapping.errorType.label)
            }
        }

        @Test
        fun `机器码与下划线连字符写法可映射`() {
            assertEquals(ErrorType.FORMULA_MISUSE, ErrorTypeMapper.mapToType("FORMULA_MISUSE"))
            assertEquals(ErrorType.FORMULA_MISUSE, ErrorTypeMapper.mapToType("formula_misuse"))
            assertEquals(ErrorType.FORMULA_MISUSE, ErrorTypeMapper.mapToType("formula-misuse"))
        }

        @Test
        fun `近义词可映射`() {
            assertEquals(ErrorType.CALCULATION_ERROR, ErrorTypeMapper.mapToType("算错"))
            assertEquals(ErrorType.READING_COMPREHENSION, ErrorTypeMapper.mapToType("审题不清"))
            assertEquals(ErrorType.MISSING_STEPS, ErrorTypeMapper.mapToType("跳步"))
        }

        @Test
        fun `带引号加粗与序号前缀可清洗`() {
            assertEquals(ErrorType.FORMULA_MISUSE, ErrorTypeMapper.mapToType("\"**公式误用**\""))
            assertEquals(ErrorType.CALCULATION_ERROR, ErrorTypeMapper.mapToType("3. 计算失误！"))
            assertEquals("公式误用", ErrorTypeMapper.sanitize("  **公式误用**  "))
        }

        @Test
        fun `首尾空白不敏感`() {
            assertEquals(ErrorType.NOTATION_NORM, ErrorTypeMapper.mapToType("  书写规范  "))
        }
    }

    @Nested
    @DisplayName("兜底与脏输入")
    inner class Fallback {
        @Test
        fun `未知错因落入兜底且标记未命中`() {
            val mapping = ErrorTypeMapper.map("粗心大意")
            assertEquals(ErrorType.FALLBACK, mapping.errorType)
            assertFalse(mapping.matched)
            assertTrue(mapping.isFallback)
            assertEquals("粗心大意", mapping.raw)
        }

        @Test
        fun `空值与 null 落入兜底`() {
            listOf(null, "", "   ").forEach { raw ->
                val mapping = ErrorTypeMapper.map(raw)
                assertEquals(ErrorType.FALLBACK, mapping.errorType)
                assertFalse(mapping.matched)
            }
        }

        @Test
        fun `AI 自造新类型不抛异常且归入兜底`() {
            val invented = listOf("粗心", "unclassified", "NEW_TYPE_X", "其它", "???")
            invented.forEach { raw ->
                val mapping = ErrorTypeMapper.map(raw)
                assertEquals(ErrorType.FALLBACK, mapping.errorType, "输入：$raw")
            }
        }

        @Test
        fun `合法性判定区分已识别与未识别`() {
            assertTrue(ErrorTypeMapper.isValid("计算失误"))
            assertFalse(ErrorTypeMapper.isValid("不存在的类型"))
            assertNull(ErrorTypeMapper.mapToTypeOrNull("不存在的类型"))
            assertEquals(ErrorType.FALLBACK, ErrorTypeMapper.mapToType("不存在的类型"))
        }

        @Test
        fun `允许清单覆盖七类中文名与机器码`() {
            assertEquals(7, ErrorTypeMapper.allowedLabels.size)
            assertEquals(7, ErrorTypeMapper.allowedCodes.size)
            assertTrue(ErrorTypeMapper.allowedLabels.containsAll(ErrorType.FIXED_LABELS))
        }
    }
}
