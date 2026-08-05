package io.prism.data

import io.objectbox.converter.PropertyConverter

/**
 * Map<String, String> ↔ String 类型转换器（ObjectBox @Convert）。
 *
 * 序列化格式：每行 `key=value`，换行符分隔。
 * key 和 value 中的特殊字符转义规则：
 * - `\` → `\\`
 * - 换行符 → `\n`
 * - `=` → `\e`
 * 空映射序列化为空字符串。
 *
 * **反转义实现**：使用单次扫描解析转义序列，而非链式 [String.replace]。
 * 链式 replace 在 value 同时包含反斜杠与 `e`/`n` 字符时会产生错误匹配
 * （例如 value="a\eb" escape 后为 "a\\eb"，链式 unescape 先执行 `\e`→`=`
 * 会错误匹配第二个 `\` 与 `e`，得到 "a\=b"）。单次扫描遇到 `\` 时读取下一个
 * 字符决定还原内容，避免歧义。
 *
 * US-004 ProviderConfig.headers 字段使用。
 */
class StringMapConverter : PropertyConverter<Map<String, String>, String> {

    override fun convertToEntityProperty(databaseValue: String): Map<String, String> {
        if (databaseValue.isEmpty()) return emptyMap()
        return databaseValue.split("\n").associate { line ->
            val idx = line.indexOf("=")
            if (idx < 0) {
                unescape(line) to ""
            } else {
                unescape(line.substring(0, idx)) to unescape(line.substring(idx + 1))
            }
        }
    }

    override fun convertToDatabaseValue(entityProperty: Map<String, String>): String =
        entityProperty.entries.joinToString("\n") { "${escape(it.key)}=${escape(it.value)}" }

    /**
     * 转义：`\` → `\\`，换行符 → `\n`，`=` → `\e`。
     *
     * 顺序必须先处理 `\`（翻倍），再处理换行符和 `=`，避免后续转义引入的 `\`
     * 被再次转义。
     */
    private fun escape(s: String): String {
        val sb = StringBuilder(s.length + 4)
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '=' -> sb.append("\\e")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    /**
     * 反转义：单次扫描，遇到 `\` 读取下一个字符决定还原内容。
     *
     * - `\\` → `\`
     * - `\n` → 换行符
     * - `\e` → `=`
     * - `\` 后跟其他字符或结尾：保留 `\`（容错处理，不应出现在合法序列化串中）
     */
    private fun unescape(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    '\\' -> { sb.append('\\'); i += 2 }
                    'n' -> { sb.append('\n'); i += 2 }
                    'e' -> { sb.append('='); i += 2 }
                    else -> { sb.append('\\'); i += 1 }
                }
            } else {
                sb.append(c)
                i += 1
            }
        }
        return sb.toString()
    }
}
