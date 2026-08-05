package io.prism.data

import io.objectbox.converter.PropertyConverter

/**
 * List<String> ↔ String 类型转换器（ObjectBox @Convert）。
 *
 * 序列化格式：换行符（`\n`）分隔。与 [StringMapConverter] 一致，
 * 对换行符做转义避免分隔符歧义：模型名中的字面换行符转义为 `\n`，
 * 字面反斜杠转义为 `\\`。
 *
 * **反转义**：使用单次扫描解析转义序列（BR-data-001），避免链式 replace
 * 在含反斜杠+特殊字符时产生错误匹配。
 *
 * 空列表序列化为空字符串。
 *
 * US-004 ProviderConfig.models 字段使用。
 *
 * @see StringMapConverter
 */
class StringListConverter : PropertyConverter<List<String>, String> {

    override fun convertToEntityProperty(databaseValue: String): List<String> {
        if (databaseValue.isEmpty()) return emptyList()
        // 按字面换行符分割（转义后的 \n 不会被分割，因为它是 \ + n 两个字符）
        return databaseValue.split("\n").map { unescape(it) }
    }

    override fun convertToDatabaseValue(entityProperty: List<String>): String =
        entityProperty.joinToString("\n") { escape(it) }

    /**
     * 转义：`\` → `\\`，换行符 → `\n`。
     * 先处理反斜杠避免后续转义引入的 `\` 被再次转义。
     */
    private fun escape(s: String): String {
        val sb = StringBuilder(s.length + 4)
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    /**
     * 反转义：单次扫描，遇到 `\` 读取下一个字符决定还原内容。
     * - `\\` → `\`
     * - `\n` → 换行符
     * - `\` 后跟其他字符或结尾：保留 `\`（容错）
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
