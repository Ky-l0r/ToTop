package com.totop.app.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** 备份包的命名、清单与安全规则的纯函数测试（开发方案 FR-10.4）。 */
class BackupLayoutTest {
    private val base = 1_735_689_600_000L // 2025-01-01T00:00:00Z

    @Test
    @DisplayName("清单可序列化并原样解析回来")
    fun manifestRoundTrip() {
        val manifest =
            BackupLayout.Manifest(
                formatVersion = BackupLayout.FORMAT_VERSION,
                createdAtUtc = base,
                appVersion = "0.1.0",
                dbVersion = 1,
                imageCount = 12,
                settingsCount = 4,
                deviceModel = "Pixel \"测试\" 机型",
            )
        val json = manifest.toJsonString()
        val parsed = BackupLayout.parseManifest(json)
        assertNotNull(parsed)
        assertEquals(manifest.createdAtUtc, parsed!!.createdAtUtc)
        assertEquals(12, parsed.imageCount)
        assertEquals(4, parsed.settingsCount)
        assertEquals("Pixel \"测试\" 机型", parsed.deviceModel, "引号必须被正确转义")
        assertEquals(BackupLayout.FORMAT_VERSION, parsed.formatVersion)
    }

    @Test
    @DisplayName("损坏或字段缺失的清单返回 null，不抛异常")
    fun manifestInvalid() {
        assertNull(BackupLayout.parseManifest("not json"))
        assertNull(BackupLayout.parseManifest("{}"))
        assertNull(BackupLayout.parseManifest("""{"formatVersion":1}"""))
    }

    @Test
    @DisplayName("兼容性判定：版本过新与数据库版本不一致都拒绝恢复")
    fun compatibility() {
        val ok = BackupLayout.Manifest(1, base, "0.1.0", 1, 0, 0)
        assertTrue(BackupLayout.checkCompatibility(ok, 1).canRestore)

        val tooNew = ok.copy(formatVersion = 99)
        val tooNewResult = BackupLayout.checkCompatibility(tooNew, 1)
        assertFalse(tooNewResult.canRestore)
        assertTrue(tooNewResult is BackupLayout.Compatibility.TooNew)

        val dbMismatch = ok.copy(dbVersion = 7)
        val dbResult = BackupLayout.checkCompatibility(dbMismatch, 1)
        assertFalse(dbResult.canRestore)
        assertTrue(dbResult is BackupLayout.Compatibility.DbVersionMismatch)
        assertTrue(dbResult.userMessage.isNotBlank())
    }

    @Test
    @DisplayName("zip slip 防护：绝对路径、.. 与盘符一律拒绝")
    fun zipSlip() {
        assertTrue(BackupLayout.isSafeEntryName("manifest.json"))
        assertTrue(BackupLayout.isSafeEntryName("db/totop.db"))
        assertTrue(BackupLayout.isSafeEntryName("images/abc.jpg"))
        assertTrue(BackupLayout.isSafeEntryName("settings.json"))

        assertFalse(BackupLayout.isSafeEntryName("/etc/passwd"))
        assertFalse(BackupLayout.isSafeEntryName("../totop.db"))
        assertFalse(BackupLayout.isSafeEntryName("images/../../evil.jpg"))
        assertFalse(BackupLayout.isSafeEntryName("C:/Windows/system32"))
        assertFalse(BackupLayout.isSafeEntryName(""))
        assertFalse(BackupLayout.isSafeEntryName("unknown.txt"))
    }

    @Test
    @DisplayName("建议文件名含 UTC 日与时分，且以 .zip 结尾")
    fun fileName() {
        val name = BackupLayout.fileNameOf(base)
        assertTrue(name.startsWith(BackupLayout.FILE_PREFIX))
        assertTrue(name.endsWith(BackupLayout.SUFFIX))
        assertTrue(name.contains("d" + Math.floorDiv(base, 86_400_000L)))
        assertEquals(name, BackupLayout.fileNameOf(base), "同一时刻文件名必须稳定")
        assertFalse(name.contains("/"))
    }

    @Test
    @DisplayName("条目路径常量与备份包布局一致")
    fun constants() {
        assertEquals("manifest.json", BackupLayout.MANIFEST)
        assertEquals("db/", BackupLayout.DB_PREFIX)
        assertEquals("images/", BackupLayout.IMAGES_PREFIX)
        assertEquals("settings.json", BackupLayout.SETTINGS)
        assertEquals(1, BackupLayout.FORMAT_VERSION)
        assertEquals("totop.db", BackupLayout.baseNameOf("db/totop.db"))
    }

    @Test
    @DisplayName("设置表 JSON 往返一致，键排序稳定")
    fun settingsJson() {
        val entries = mapOf("b" to "2", "a" to "1", "c" to "含引号\"的值")
        val json = settingsToJson(entries)
        assertTrue(json.startsWith("{\"a\""), "键必须排序，保证可复现")
        assertEquals(entries, settingsFromJson(json))
        assertEquals("{}", settingsToJson(emptyMap()))
        assertEquals(emptyMap<String, String>(), settingsFromJson("坏数据"))
    }

    @Test
    @DisplayName("图片条目统计与排序")
    fun imageEntries() {
        val names = listOf("images/b.jpg", "manifest.json", "images/a.jpg", "images/thumbs/c.jpg", "images/")
        assertEquals(3, countImageEntries(names))
        assertEquals(listOf("images/a.jpg", "images/b.jpg", "images/thumbs/c.jpg"), sortedImageEntries(names))
        assertEquals(3, manifestImageCount("""{"imageCount":3}"""))
        assertEquals(0, manifestImageCount("not json"))
    }
}
