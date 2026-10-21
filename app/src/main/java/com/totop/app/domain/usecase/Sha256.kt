package com.totop.app.domain.usecase

import java.security.MessageDigest

/**
 * SHA-256 摘要（纯 JDK 实现，可在 JVM 单测中直接使用）。
 *
 * 用途：纯文本输入（手输题干 / 无图片）时生成缓存与去重锚点，
 * 与 ml 层对图片字节计算的 sha256 保持同一形态（小写十六进制）。
 */
object Sha256 {
    /** 计算小写十六进制摘要。 */
    fun hex(text: String): String = hex(text.toByteArray(Charsets.UTF_8))

    /** 计算小写十六进制摘要。 */
    fun hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val builder = StringBuilder(digest.size * 2)
        for (byte in digest) {
            val value = byte.toInt() and 0xFF
            builder.append(HEX[value ushr 4]).append(HEX[value and 0x0F])
        }
        return builder.toString()
    }

    /** 是否为合法的 64 位小写十六进制摘要。 */
    fun isValidHex(value: String?): Boolean =
        value != null && value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }

    private const val HEX: String = "0123456789abcdef"
}
