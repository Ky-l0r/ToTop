package com.totop.app.ml

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 增强档位的取用规则。
 *
 * 重点是「存了坏值不能把增强关掉」：偏好存在 SharedPreferences 里，
 * 版本回退或手改文件都可能留下未知 key，回落默认比起「悄悄关掉处理」更安全。
 */
@DisplayName("拍照增强档位")
class ScanLevelTest {
    @Test
    @DisplayName("未知 key 回落标准档")
    fun `未知回落`() {
        assertEquals(ScanLevel.STANDARD, ScanLevel.fromKey(null))
        assertEquals(ScanLevel.STANDARD, ScanLevel.fromKey(""))
        assertEquals(ScanLevel.STANDARD, ScanLevel.fromKey("ultra"))
    }

    @Test
    @DisplayName("每个档位都能按 key 还原")
    fun `往返一致`() {
        ScanLevel.entries.forEach { level ->
            assertEquals(level, ScanLevel.fromKey(level.key))
        }
    }

    @Test
    @DisplayName("关闭档不参与处理")
    fun `关闭不处理`() {
        assertFalse(ScanLevel.OFF.enabled)
        assertTrue(ScanLevel.entries.filter { it != ScanLevel.OFF }.all { it.enabled })
    }

    @Test
    @DisplayName("增益随档位递增")
    fun `增益递增`() {
        val gains = listOf(ScanLevel.WEAK, ScanLevel.STANDARD, ScanLevel.STRONG).map { it.gain }
        assertEquals(gains.sorted(), gains)
    }
}
