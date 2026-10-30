package com.totop.app.ui.mistakes

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 错题本里的科目中文名。
 *
 * 回归背景：本文件对应的 `subjectLabelOf` 曾经自带一张只覆盖六科的映射表，
 * 政治、历史、地理、技术与兜底科都被原样显示成英文代码——
 * 真机反馈里「技术」一栏显示为 `technology` 就是这么来的。
 * 现在它必须与 `Subjects.label` 完全一致，任何新增科目都不需要再改这里。
 */
class SubjectLabelTest {
    @Test
    @DisplayName("十科全部显示中文")
    fun allSubjectsHaveChineseLabels() {
        val expected =
            mapOf(
                "chinese" to "语文",
                "math" to "数学",
                "english" to "英语",
                "physics" to "物理",
                "chemistry" to "化学",
                "biology" to "生物",
                "politics" to "政治",
                "history" to "历史",
                "geography" to "地理",
                "technology" to "技术",
            )
        expected.forEach { (code, label) ->
            assertEquals(label, subjectLabelOf(code), "科目 " + code + " 应显示为 " + label)
        }
    }

    @Test
    @DisplayName("兜底科显示「未知」，空值显示「未标注学科」")
    fun fallbackLabels() {
        // 「未知」是识别失败的标记，不是「通用技术」（后者属于 technology）
        assertEquals("未知", subjectLabelOf("general"))
        assertEquals("未标注学科", subjectLabelOf(""))
        assertEquals("未标注学科", subjectLabelOf("   "))
    }

    @Test
    @DisplayName("大小写与首尾空白不影响映射")
    fun normalizesCaseAndSpaces() {
        assertEquals("技术", subjectLabelOf(" Technology "))
        assertEquals("数学", subjectLabelOf("MATH"))
    }

    @Test
    @DisplayName("识别不了的代码原样返回，便于发现问题")
    fun unknownCodeIsKept() {
        assertEquals("astronomy", subjectLabelOf("astronomy"))
    }
}
