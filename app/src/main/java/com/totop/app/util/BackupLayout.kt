package com.totop.app.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 备份包内的条目规范（开发方案 FR-10.4：全量本地备份与恢复，换机迁移）。
 *
 * 备份是一个 **zip** 文件，内部布局固定：
 * ```
 * manifest.json          备份清单（版本、时间、设备、条目数量）
 * db/totop.db            数据库快照
 * settings.json          设置表内容（只含非敏感项；API Key 在 Keystore 中，不备份）
 * images/<sha256>.jpg    题目原图
 * images/thumbs/...      缩略图
 * ```
 *
 * 本对象只负责**命名与校验规则**，是纯函数，可在 JVM 单测里穷举；
 * 真正的 zip 读写由 [BackupManager] 用 java.util.zip 完成（不引入第三方压缩库）。
 */
object BackupLayout {
    /** 清单文件名。 */
    const val MANIFEST: String = "manifest.json"

    /** 数据库条目前缀。 */
    const val DB_PREFIX: String = "db/"

    /** 设置条目名。 */
    const val SETTINGS: String = "settings.json"

    /** 图片条目前缀。 */
    const val IMAGES_PREFIX: String = "images/"

    /** 当前备份格式版本：恢复时据此判断兼容性。 */
    const val FORMAT_VERSION: Int = 1

    /** 建议文件名前缀。 */
    const val FILE_PREFIX: String = "ToTop备份_"

    /** zip 后缀。 */
    const val SUFFIX: String = ".zip"

    /** 一段备份内容的元信息（写入 manifest）。 */
    data class Manifest(
        val formatVersion: Int,
        val createdAtUtc: Long,
        val appVersion: String,
        val dbVersion: Int,
        val imageCount: Int,
        val settingsCount: Int,
        /** 备份时设备型号，仅用于用户识别「这是哪台机器的备份」，不含任何硬件标识。 */
        val deviceModel: String = "",
    ) {
        init {
            require(formatVersion >= 1) { "备份格式版本必须 >= 1" }
            require(imageCount >= 0) { "图片数量不得为负" }
        }

        fun toJsonString(): String {
            val builder = StringBuilder()
            builder.append('{')
            builder.append("\"formatVersion\":").append(formatVersion).append(',')
            builder.append("\"createdAtUtc\":").append(createdAtUtc).append(',')
            builder.append("\"appVersion\":").append(quote(appVersion)).append(',')
            builder.append("\"dbVersion\":").append(dbVersion).append(',')
            builder.append("\"imageCount\":").append(imageCount).append(',')
            builder.append("\"settingsCount\":").append(settingsCount).append(',')
            builder.append("\"deviceModel\":").append(quote(deviceModel))
            builder.append('}')
            return builder.toString()
        }
    }

    /** 解析清单；字段缺失或格式错误返回 null（不抛异常，由调用方给出中文提示）。 */
    fun parseManifest(json: String): Manifest? =
        runCatching {
            val obj: JsonObject = Json.parseToJsonElement(json).jsonObject
            val version = obj["formatVersion"]?.jsonPrimitive?.content?.toIntOrNull() ?: return null
            val createdAt = obj["createdAtUtc"]?.jsonPrimitive?.content?.toLongOrNull() ?: return null
            Manifest(
                formatVersion = version,
                createdAtUtc = createdAt,
                appVersion = obj["appVersion"]?.jsonPrimitive?.content ?: "",
                dbVersion = obj["dbVersion"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                imageCount = obj["imageCount"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                settingsCount = obj["settingsCount"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                deviceModel = obj["deviceModel"]?.jsonPrimitive?.content ?: "",
            )
        }.getOrNull()

    /** 恢复前的兼容性判定。 */
    sealed class Compatibility {
        data object Ok : Compatibility()

        /** 备份文件版本过新（App 需升级后才能恢复）。 */
        data class TooNew(val backupFormat: Int) : Compatibility()

        /** 数据库版本不一致（表结构不同，不能直接覆盖）。 */
        data class DbVersionMismatch(val backupDbVersion: Int) : Compatibility()

        val canRestore: Boolean get() = this is Ok

        /** 面向用户的中文说明。 */
        val userMessage: String
            get() =
                when (this) {
                    is Ok -> "可以恢复"
                    is TooNew -> "备份文件来自更新版本的 To Top（格式 v" + backupFormat + "），请先升级应用再恢复。"
                    is DbVersionMismatch ->
                        "备份文件的数据库版本为 v" + backupDbVersion + "，与当前应用不一致，无法直接恢复。"
                }
    }

    /** 校验备份清单是否可恢复。 */
    fun checkCompatibility(
        manifest: Manifest,
        currentDbVersion: Int,
    ): Compatibility =
        when {
            manifest.formatVersion > FORMAT_VERSION -> Compatibility.TooNew(manifest.formatVersion)
            manifest.dbVersion != currentDbVersion -> Compatibility.DbVersionMismatch(manifest.dbVersion)
            else -> Compatibility.Ok
        }

    /** 建议的备份文件名：`ToTop备份_20250101_1830.zip`（用 UTC 日与时分，避免跨时区歧义）。 */
    fun fileNameOf(nowUtcMillis: Long): String {
        val day = Math.floorDiv(nowUtcMillis, 86_400_000L)
        val minuteOfDay = Math.floorMod(nowUtcMillis, 86_400_000L) / 60_000L
        val hour = minuteOfDay / 60
        val minute = minuteOfDay % 60
        return FILE_PREFIX + "d" + day + "_" + twoDigits(hour) + twoDigits(minute) + SUFFIX
    }

    /** 条目路径是否属于允许恢复的范围（防 zip slip：禁止绝对路径与 `..`）。 */
    fun isSafeEntryName(name: String): Boolean {
        if (name.isBlank()) return false
        if (name.startsWith("/") || name.startsWith("\\")) return false
        if (name.contains("..")) return false
        if (name.contains(':')) return false
        return name.startsWith(MANIFEST) || name.startsWith(DB_PREFIX) ||
            name == SETTINGS || name.startsWith(IMAGES_PREFIX)
    }

    /** 从 zip 条目名取文件名（不含目录）。 */
    fun baseNameOf(entryName: String): String = entryName.substringAfterLast('/')

    private fun twoDigits(value: Long): String = if (value < 10) "0" + value else value.toString()

    private fun quote(value: String): String {
        val builder = StringBuilder(value.length + 2)
        builder.append('"')
        for (ch in value) {
            when (ch) {
                '"' -> builder.append("\\\"")
                '\\' -> builder.append("\\\\")
                '\n' -> builder.append("\\n")
                '\r' -> builder.append("\\r")
                '\t' -> builder.append("\\t")
                else -> builder.append(ch)
            }
        }
        builder.append('"')
        return builder.toString()
    }
}

/** 备份结果。 */
sealed class BackupOutcome {
    data class Success(val uri: String, val bytes: Long, val imageCount: Int, val manifest: BackupLayout.Manifest) :
        BackupOutcome()

    data class Failure(val userMessage: String, val detail: String? = null) : BackupOutcome()
}

/** 恢复结果。 */
sealed class RestoreOutcome {
    data class Success(
        val restoredImages: Int,
        val manifest: BackupLayout.Manifest,
        val note: String,
    ) : RestoreOutcome()

    data class Failure(val userMessage: String, val detail: String? = null) : RestoreOutcome()
}

/** 备份进度的简单回调，用于 UI 上显示「正在备份 12/48」。 */
fun interface BackupProgressListener {
    fun onProgress(
        step: String,
        done: Int,
        total: Int,
    )
}

/** 内部辅助：从清单 JSON 里读出条目数量（单测用，避免依赖 Android）。 */
internal fun manifestImageCount(json: String): Int =
    runCatching { Json.parseToJsonElement(json).jsonObject["imageCount"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0 }
        .getOrDefault(0)

/** 内部辅助：统计 zip 条目里图片数量（单测用）。 */
internal fun countImageEntries(entryNames: List<String>): Int =
    entryNames.count { it.startsWith(BackupLayout.IMAGES_PREFIX) && !it.endsWith("/") }

/** 内部辅助：把设置表内容序列化为扁平 JSON 对象（单测用）。 */
internal fun settingsToJson(entries: Map<String, String>): String {
    if (entries.isEmpty()) return "{}"
    val keys = entries.keys.sorted()
    val builder = StringBuilder()
    builder.append('{')
    keys.forEachIndexed { index, key ->
        builder.append(
            JsonPrimitive(key).toString(),
        ).append(':').append(JsonPrimitive(entries.getValue(key)).toString())
        if (index != keys.lastIndex) builder.append(',')
    }
    builder.append('}')
    return builder.toString()
}

/** 内部辅助：把扁平 JSON 对象还原为设置表内容。 */
internal fun settingsFromJson(json: String): Map<String, String> =
    runCatching {
        Json.parseToJsonElement(json).jsonObject.entries.associate { (key, value) ->
            key to (value.jsonPrimitive.content)
        }
    }.getOrDefault(emptyMap())

/**
 * 内部辅助：清单里的图片条目排序（保证备份内容可复现）。
 *
 * 必须排除以 `/` 结尾的目录条目（zip 里 `images/`、`images/thumbs/` 是目录而不是文件），
 * 否则恢复时会把目录当成文件写出，产生 0 字节的"图片"。
 */
internal fun sortedImageEntries(names: List<String>): List<String> =
    names.filter { it.startsWith(BackupLayout.IMAGES_PREFIX) && !it.endsWith("/") }.sorted()

/** 内部辅助：数组转字符串列表（供 jsonArray 使用）。 */
internal fun jsonArrayStrings(
    json: String,
    field: String,
): List<String> =
    runCatching {
        Json.parseToJsonElement(json).jsonObject[field]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
    }.getOrDefault(emptyList())
