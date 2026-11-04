package com.totop.app.data.remote

import kotlinx.serialization.json.JsonObject

/**
 * 把模型输出「抢救」成 JSON 对象。
 *
 * 为什么需要它：模型并不总是严格输出 JSON。实测最常见的四种偏差是
 * 1. 用 ```json 围栏包起来，或前后带一段「好的，我来分析」的解释；
 * 2. 字符串里有**未转义的换行**（写多步解答、LaTeX 时几乎必犯）；
 * 3. 对象或数组末尾多一个逗号；
 * 4. 结尾又补一段话，里面有花括号（按 `lastIndexOf('}')` 截取会截坏）。
 *
 * 这四种都能在不改变语义的前提下修好。修不好时返回 null，
 * 由调用方走「更严格提示词重试」，而不是把一份残缺数据塞进解析器。
 *
 * 只做文本切分与转义，不依赖 Android，可直接单测。
 */
object JsonSalvage {
    /**
     * 解析成 JSON 对象；失败返回 null。
     *
     * 依次尝试：原文 → 第一个平衡的 `{...}` → 转义修复后的原文 / 平衡片段。
     */
    fun parseObject(raw: String): JsonObject? {
        val text = raw.trim().removePrefix(BOM).trim()
        if (text.isEmpty()) return null
        parseObjectOrNull(text)?.let { return it }
        val balanced = extractBalancedObject(text) ?: return parseObjectOrNull(normalize(text))
        parseObjectOrNull(balanced)?.let { return it }
        return parseObjectOrNull(normalize(balanced))
    }

    /**
     * 截出第一个**闭合**的 JSON 对象。
     *
     * 与 `indexOf('{') … lastIndexOf('}')` 的区别：会跟踪字符串与转义，
     * 因此后面那段「补充说明 {见图 2}」不会把范围拉长；
     * 遇到嵌套对象（如 summaryCard）也能正确配平。
     *
     * @return 未闭合（常见于被 max_tokens 截断）时返回 null
     */
    fun extractBalancedObject(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (index in start until text.length) {
            val ch = text[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    ch == '"' -> inString = false
                }
                continue
            }
            when (ch) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, index + 1)
                }
            }
        }
        return null
    }

    /**
     * 判断一段文本像不像「被 max_tokens 截断的 JSON」。
     *
     * 为什么不只看 `finish_reason`：流式响应里不少厂商与中转会把 `length`
     * 写成 `stop`、或者干脆不返回，于是残 JSON 被当成「模型不按 JSON 输出」——
     * 走严格重试却不加预算，同一个上限再截一次，白付两次钱。
     *
     * 两个条件同时满足才判截断，避免把「解释文字里带一个 `{`」也算进去：
     * 1. [extractBalancedObject] 取不出完整对象；
     * 2. 开括号比闭括号多（真的断在半路）。
     */
    fun looksTruncated(raw: String): Boolean {
        val text = raw.trim()
        if (text.indexOf('{') < 0) return false
        if (extractBalancedObject(text) != null) return false
        return text.count { it == '{' } > text.count { it == '}' }
    }

    /**
     * 修复常见缺陷：字符串内的裸换行/制表符、对象与数组末尾的多余逗号。
     *
     * 逐个字符扫描而不是用正则，是因为这些都只对「字符串外」或「字符串内」生效，
     * 正则分不清 `",]"` 是内容还是结构。
     */
    fun normalize(text: String): String {
        val escaped = escapeControlCharsInStrings(text)
        return dropTrailingCommas(escaped)
    }

    /**
     * 把字符串字面量里的控制字符转成合法转义，并修掉非法转义。
     *
     * 非法转义是 LaTeX 的直接后果：模型写 `\frac`、`\dfrac`、`\sqrt` 时若只写一个反斜杠，
     * `\d`、`\s` 都不是合法转义，整份 JSON 会被判为非法——但它其实只是一段公式。
     * 这里把反斜杠补成两个（`\d` → `\\d`），语义不变，公式也保住了。
     * `\n`、`\t` 这类**合法**转义不动：那多半真的是换行/制表符，改了反而错。
     */
    private fun escapeControlCharsInStrings(text: String): String {
        val out = StringBuilder(text.length + 16)
        var inString = false
        var escaped = false
        var index = 0
        while (index < text.length) {
            val ch = text[index]
            if (!inString) {
                if (ch == '"') inString = true
                out.append(ch)
                index++
                continue
            }
            when {
                escaped -> {
                    out.append(ch)
                    escaped = false
                }

                ch == '"' -> {
                    out.append(ch)
                    inString = false
                }

                ch == '\\' -> {
                    if (isValidEscapeStart(text, index)) {
                        out.append(ch)
                        escaped = true
                    } else {
                        // 非法转义：补一个反斜杠，让它变成「字面反斜杠 + 字符」
                        out.append("\\\\")
                    }
                }

                ch == '\n' -> out.append("\\n")
                ch == '\r' -> out.append("\\r")
                ch == '\t' -> out.append("\\t")
                ch.code < 0x20 -> out.append(' ')
                else -> out.append(ch)
            }
            index++
        }
        return out.toString()
    }

    /** 判断 `text[backslashIndex]` 处的反斜杠是否构成合法 JSON 转义。 */
    private fun isValidEscapeStart(
        text: String,
        backslashIndex: Int,
    ): Boolean {
        val next = text.getOrNull(backslashIndex + 1) ?: return false
        if (next in SIMPLE_ESCAPES) return true
        if (next != 'u') return false
        val hex = text.substring(backslashIndex + 2, minOf(backslashIndex + 6, text.length))
        return hex.length == 4 && hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
    }

    /** 删掉 `,}` 与 `,]` 里的逗号（字符串内的原样保留）。 */
    private fun dropTrailingCommas(text: String): String {
        val out = StringBuilder(text.length)
        var inString = false
        var escaped = false
        var index = 0
        while (index < text.length) {
            val ch = text[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    ch == '"' -> inString = false
                }
                out.append(ch)
                index++
                continue
            }
            if (ch == '"') {
                inString = true
                out.append(ch)
                index++
                continue
            }
            if (ch == ',') {
                var lookahead = index + 1
                while (lookahead < text.length && text[lookahead].isWhitespace()) lookahead++
                val next = text.getOrNull(lookahead)
                if (next == '}' || next == ']') {
                    index++
                    continue
                }
            }
            out.append(ch)
            index++
        }
        return out.toString()
    }

    private fun parseObjectOrNull(text: String): JsonObject? =
        runCatching { OpenAiWire.json.parseToJsonElement(text) as? JsonObject }.getOrNull()

    /** UTF-8 BOM：部分本地模型会带，`trim()` 去不掉。 */
    private const val BOM: String = "\uFEFF"

    /** JSON 允许的单字符转义（`\u` 单独判断）。 */
    private const val SIMPLE_ESCAPES: String = "\"\\/bfnrt"
}
