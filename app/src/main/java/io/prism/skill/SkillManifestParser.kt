package io.prism.skill

import it.krzeminski.snakeyaml.engine.kmp.api.Load
import it.krzeminski.snakeyaml.engine.kmp.api.LoadSettings
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * SKILL.md 解析器（ADR-013 5.2）。
 *
 * **职责**：将 SKILL.md 文件内容解析为 [SkillManifest] + Markdown body。
 *
 * **解析流程**：
 * 1. 分离 YAML frontmatter（`---` 围栏）与 Markdown body
 * 2. 用 snakeyaml-engine-kmp [Load] 解析 frontmatter 为 `Map<String, Any?>`（原生 Kotlin 类型）
 * 3. 映射为 [SkillManifest]，执行字段校验（fail-fast）
 *
 * **安全性**（ADR-013 5.6 + BR-security-004）：
 * - [Load] 默认使用 `StandardConstructor`，仅构造标准 YAML 类型（Map/List/String/Int/Boolean），
 *   **不构造任意 Java 类**，天然沙箱化（无 SnakeYAML 的 `Constructor` 反射风险）
 * - [LoadSettings] 显式配置 `allowRecursiveKeys = false` 禁止循环引用
 *   （防 `toJsonElement` 递归无限 → StackOverflowError；snakeyaml-engine-kmp 4.0.1 默认值即 false，
 *   此处显式设置以文档化安全意图，避免未来默认值变更引入风险）
 * - [LoadSettings] 显式配置 `maxAliasesForCollections = 50` 限制别名展开（防 billion laughs 攻击）
 * - [LoadSettings] 显式配置 `codePointLimit = 1MB` 限制单文档大小（默认 3MB，SKILL.md 收紧到 1MB）
 *
 * **偏差说明**：ADR-013 5.2 原文示意 `Yaml(defaultToNull = false).parseToJson(...)`，
 * 该 API 假设有误（kaml 风格，snakeyaml-engine-kmp 实际无此类）。
 * 本实现使用真实 API `Load().loadOne(): Any?`，意图与 ADR 一致。
 * ADR-013 5.2 将在本阶段末同步修订。
 *
 * **线程安全**：[Load] 实例**非线程安全**（stateful，单次使用）。
 * 本 object 每次调用 [parse] 创建新 [Load] 实例，无共享状态，可并发调用。
 */
object SkillManifestParser {

    /** name 字段 slug 正则（OpenClaw 规范：1-64 lowercase + 数字 + 连字符） */
    private val NAME_REGEX = Regex("^[a-z0-9-]{1,64}$")

    /** description 字段最大长度（ADR-013 5.2） */
    private const val DESCRIPTION_MAX_LENGTH = 160

    /**
     * [toJsonElement] 递归深度上限(R2-1 纵深防御)。
     *
     * snakeyaml-engine-kmp 4.0.1 `LoadSettings` 已通过 `allowRecursiveKeys=false`(防循环引用)
     * + `maxAliasesForCollections=50`(防 billion laughs)在解析阶段拦截恶意 YAML。
     * 此处 50 作为二级防护,防止解析后 Java 对象图的深层嵌套(非循环)导致遍历栈溢出。
     */
    private const val MAX_TO_JSON_DEPTH = 50

    /**
     * 解析 SKILL.md 全文。
     *
     * @param content SKILL.md 文件全文（含 frontmatter + body）
     * @return 解析结果，含 [SkillManifest] 与 Markdown body
     * @throws SkillParseException frontmatter 缺失 / YAML 语法错误 / 必填字段缺失 / 校验失败
     */
    fun parse(content: String): ParseResult {
        // 1. 分离 frontmatter 与 body
        val (frontmatterText, body) = splitFrontmatter(content)
            ?: throw SkillParseException("Missing YAML frontmatter (expected ---...--- fence)")

        // 2. 解析 YAML frontmatter（每次创建新 Load 实例，避免状态复用）
        //    G-02/G-07 修复（BR-security-004）：显式配置安全参数（纵深防御，避免依赖默认值）
        //    - allowRecursiveKeys = false：禁止循环引用，防 toJsonElement 递归无限 → StackOverflowError
        //      （snakeyaml-engine-kmp 4.0.1 默认值即 false，此处显式设置以文档化安全意图）
        //    - maxAliasesForCollections = 50：限制别名展开总数，防 billion laughs 攻击（默认值即 50）
        //    - codePointLimit = 1MB：限制单文档大小，SKILL.md frontmatter 不应超过此规模（默认 3MB，收紧到 1MB）
        val settings = LoadSettings(
            allowRecursiveKeys = false,
            maxAliasesForCollections = 50,
            codePointLimit = 1024 * 1024, // 1MB
        )
        val data: Any? = try {
            Load(settings).loadOne(frontmatterText)
        } catch (e: Exception) {
            throw SkillParseException("YAML frontmatter parse failed: ${e.message}", e)
        }

        // 3. 校验顶层为 Map
        @Suppress("UNCHECKED_CAST")
        val map = (data as? Map<String, Any?>)
            ?: throw SkillParseException(
                "Frontmatter must be a YAML mapping, got ${data?.javaClass?.simpleName ?: "null"}"
            )

        // 4. 映射为 SkillManifest + 回填 body + 校验
        val manifest = mapToManifest(map).copy(body = body)
        validate(manifest)
        return ParseResult(manifest, body)
    }

    /**
     * 分离 YAML frontmatter 与 Markdown body。
     *
     * 支持两种围栏风格：
     * - 标准式：首行 `---`，第二行起为 YAML，遇下一个 `---` 行结束
     * - 容错式：跳过首行空白/换行后定位首个 `---`
     *
     * @return (frontmatter, body) 二元组；若无 frontmatter 返回 null
     */
    internal fun splitFrontmatter(content: String): Pair<String, String>? {
        val lines = content.split("\n")
        // 定位首行 `---`（允许前导空白行）
        var startIdx = -1
        for (i in lines.indices) {
            val trimmed = lines[i].trim()
            if (trimmed.isEmpty()) continue
            if (trimmed == "---") {
                startIdx = i
            }
            break
        }
        if (startIdx < 0) return null

        // 定位结束 `---`
        var endIdx = -1
        for (i in (startIdx + 1) until lines.size) {
            if (lines[i].trim() == "---") {
                endIdx = i
                break
            }
        }
        if (endIdx < 0) return null

        val frontmatter = lines.subList(startIdx + 1, endIdx).joinToString("\n")
        val body = lines.subList(endIdx + 1, lines.size).joinToString("\n").trimStart()
        return frontmatter to body
    }

    /**
     * 将原生 YAML Map 映射为 [SkillManifest]。
     *
     * **类型容错**：字段缺失时使用默认值；类型不匹配抛 [SkillParseException]。
     */
    private fun mapToManifest(map: Map<String, Any?>): SkillManifest {
        val name = map.getString("name")
            ?: throw SkillParseException("Missing required field: name")
        val description = map.getString("description")
            ?: throw SkillParseException("Missing required field: description")
        val version = map.getString("version")
        val homepage = map.getString("homepage")
        val userInvocable = map.getBoolean("user-invocable", defaultValue = true)
        val disableModelInvocation = map.getBoolean("disable-model-invocation", defaultValue = false)
        val os = map.getStringList("os")
        val systemPrompt = map.getString("system-prompt")
        val maxRounds = map.getInt("max-rounds", defaultValue = 10)
        val tools = map.getToolList("tools")

        return SkillManifest(
            name = name,
            description = description,
            version = version,
            userInvocable = userInvocable,
            disableModelInvocation = disableModelInvocation,
            homepage = homepage,
            os = os,
            tools = tools,
            systemPrompt = systemPrompt,
            maxRounds = maxRounds,
            body = "" // body 由 parse() 填充
        )
    }

    /**
     * 校验 [SkillManifest] 字段约束（fail-fast）。
     */
    private fun validate(manifest: SkillManifest) {
        require(manifest.name.matches(NAME_REGEX)) {
            "name must be 1-64 lowercase letters, digits, or hyphens (got: ${manifest.name})"
        }
        require(manifest.description.isNotBlank()) {
            "description must not be blank"
        }
        require(manifest.description.length <= DESCRIPTION_MAX_LENGTH) {
            "description must be <= $DESCRIPTION_MAX_LENGTH chars (got ${manifest.description.length})"
        }
        require(manifest.maxRounds in 1..50) {
            "max-rounds must be in 1..50 (got ${manifest.maxRounds})"
        }
        manifest.tools?.forEach { tool ->
            require(tool.name.isNotBlank()) { "tool name must not be blank" }
            require(tool.name.matches(Regex("^[a-zA-Z_][a-zA-Z0-9_]*$"))) {
                "tool name must be valid identifier (got: ${tool.name})"
            }
        }
    }

    /**
     * 将原生 `Any?` 值递归转换为 [JsonElement]（供 [SkillToolDecl.parameters] 使用）。
     *
     * 支持类型映射：
     * - `null` → [JsonPrimitive] (null)
     * - `String` → [JsonPrimitive] (string)
     * - `Boolean` → [JsonPrimitive] (boolean)
     * - `Number`（Int/Long/Double/Float）→ [JsonPrimitive] (number)
     * - `List<*>` → [JsonArray]
     * - `Map<String, *>` → [JsonObject]
     *
     * **R2-1 修复(纵深防御)**:递归深度上限 [MAX_TO_JSON_DEPTH],防止恶意深层嵌套 YAML
     * 导致 StackOverflowError。即使 parser 层 `allowRecursiveKeys=false` 失效,此层仍能拦截。
     *
     * @param depth 当前递归深度(内部使用,外部调用不传)
     * @throws SkillParseException 深度超过 [MAX_TO_JSON_DEPTH] 或 Map key 非 String
     */
    internal fun toJsonElement(value: Any?, depth: Int = 0): JsonElement {
        require(depth < MAX_TO_JSON_DEPTH) {
            "YAML nesting depth exceeds $MAX_TO_JSON_DEPTH (got depth=$depth)"
        }
        return when (value) {
            null -> JsonNull
            is String -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is Int -> JsonPrimitive(value)
            is Long -> JsonPrimitive(value)
            is Double -> JsonPrimitive(value)
            is Float -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value.toDouble())
            is List<*> -> buildJsonArray {
                value.forEach { add(toJsonElement(it, depth + 1)) }
            }
            is Map<*, *> -> buildJsonObject {
                value.forEach { (k, v) ->
                    require(k is String) { "YAML map key must be String, got ${k?.javaClass}" }
                    put(k, toJsonElement(v, depth + 1))
                }
            }
            else -> {
                // 兜底：未知类型转为字符串（避免解析崩溃）
                JsonPrimitive(value.toString())
            }
        }
    }

    // ============ Map 取值扩展（类型安全 + 容错） ============

    private fun Map<String, Any?>.getString(key: String): String? =
        (this[key] as? String)?.takeIf { it.isNotBlank() }

    private fun Map<String, Any?>.getBoolean(key: String, defaultValue: Boolean): Boolean =
        when (val v = this[key]) {
            is Boolean -> v
            null -> defaultValue
            is String -> v.lowercase() == "true"
            else -> throw SkillParseException("Field '$key' must be boolean, got ${v.javaClass.simpleName}")
        }

    private fun Map<String, Any?>.getInt(key: String, defaultValue: Int): Int =
        when (val v = this[key]) {
            is Int -> v
            is Long -> v.toInt()
            is Number -> v.toInt()
            is String -> v.toIntOrNull()
                ?: throw SkillParseException("Field '$key' must be int, got string '$v'")
            null -> defaultValue
            else -> throw SkillParseException("Field '$key' must be int, got ${v.javaClass.simpleName}")
        }

    private fun Map<String, Any?>.getStringList(key: String): List<String>? =
        when (val v = this[key]) {
            null -> null
            is List<*> -> v.mapNotNull { it?.toString() }
            is String -> listOf(v) // 容错：单值视为单元素列表
            else -> throw SkillParseException("Field '$key' must be list, got ${v.javaClass.simpleName}")
        }

    private fun Map<String, Any?>.getToolList(key: String): List<SkillToolDecl>? {
        val list = (this[key] as? List<*>) ?: return null
        return list.mapIndexed { idx, item ->
            val map = item as? Map<String, Any?>
                ?: throw SkillParseException("Tool[$idx] must be a mapping, got ${item?.javaClass?.simpleName}")
            val name = (map["name"] as? String)
                ?: throw SkillParseException("Tool[$idx] missing 'name' field")
            val description = (map["description"] as? String) ?: ""
            val parameters = toJsonElement(map["parameters"] ?: emptyMap<String, Any?>())
            SkillToolDecl(name = name, description = description, parameters = parameters)
        }
    }

    /**
     * 解析结果。
     *
     * @property manifest 解析后的 Skill 元数据（含 [SkillManifest.body]，已回填）
     * @property body Markdown 正文（frontmatter 之后的内容，已 trim 前导空白）；
     *   与 [manifest.body] 一致，单独保留便于直接访问
     */
    data class ParseResult(
        val manifest: SkillManifest,
        val body: String
    )
}
