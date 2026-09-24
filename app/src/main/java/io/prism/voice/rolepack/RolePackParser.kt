package io.prism.voice.rolepack

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * RolePack 解析异常（批次23-B，ADR-048）—— 携带面向角色作者/导入流程的可读信息
 * （不含原始 JSON 内容，防潜在敏感人设文本进日志）。
 */
class RolePackParseException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

/**
 * 批次23-B（ADR-048）：RolePack JSON 解析/导出器（纯函数，可 JVM 单测）。
 *
 * **校验策略（宽松，方案 §三-B1）**：
 * - **未知字段忽略**：手工按字段提取（非反射映射），Schema 演进向前兼容；
 * - **缺省兜底**：除 `schema` 外全部字段缺省兜底（见 [RolePack] 各字段默认值；
 *   `id` 缺省时按内容哈希生成 `imported-*`，保证可落盘且同文件重复导入幂等）；
 * - **畸形抛可读异常**：JSON 语法错误 / 顶层非对象 / `schema` 缺失或不为
 *   [RolePack.SCHEMA_V1]（畸形 schema 版本）→ [RolePackParseException]；
 * - **类型宽松**：标量字段按 `content` 字符串再转目标类型（数字写成字符串也能解析），
 *   转换失败回退默认值（不抛）——对齐"校验宽松"约束。
 *
 * **保留 id 说明**：`default` 为出厂 B0 的合法 id（B0 自身就是标准 Schema 文档，
 * 导出 round-trip 必须可解析）；**导入通道**（[RolePackLoader.import]）拒绝用户以
 * `default` 作为自定义角色 id（防与内置角色语义冲突）。
 *
 * **JSON 库**：复用项目既有 kotlinx-serialization-json（不引新依赖）。
 *
 * **线程安全**：object 无状态，`json` 实例复用（对齐 ConversationViewModel 既有
 * "单例 Json format" 编译告警纪律）。
 */
object RolePackParser {

    /** 解析专用实例（宽松模式：重复键取最后值；ignoreUnknownKeys 对手工提取无影响，显式声明意图）。 */
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** 导出专用实例（prettyPrint，供 .rolepack 文件导出/示例生成）。 */
    private val prettyJson = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    /**
     * 解析 RolePack JSON 文本。
     *
     * @param json 角色包 JSON 全文（.rolepack/.json 文件内容）
     * @return 解析后的 [RolePack]（缺省字段已兜底）
     * @throws RolePackParseException JSON 语法错误 / 顶层非对象 / schema 版本畸形 / id 保留值冲突
     */
    fun parse(json: String): RolePack {
        val root = try {
            this.json.parseToJsonElement(json)
        } catch (e: Exception) {
            throw RolePackParseException(
                "RolePack JSON 解析失败：文件不是合法 JSON（${e.message ?: e::class.simpleName}）",
                e
            )
        }
        if (root !is JsonObject) {
            throw RolePackParseException("RolePack 顶层必须是 JSON 对象（收到 ${root::class.simpleName}）")
        }
        val schema = root.optString("schema")
        if (schema.isNullOrBlank()) {
            throw RolePackParseException("RolePack 缺少 schema 字段（期望 \"${RolePack.SCHEMA_V1}\"）")
        }
        if (schema != RolePack.SCHEMA_V1) {
            throw RolePackParseException(
                "不支持的 RolePack schema 版本：\"$schema\"（本版本仅支持 \"${RolePack.SCHEMA_V1}\"）"
            )
        }
        // id 缺省兜底：按内容哈希生成稳定 id（同一文件重复导入落同一文件，幂等）
        val id = root.optString("id")?.takeIf { it.isNotBlank() }
            ?: "imported-%08x".format(json.hashCode())
        val name = root.optString("name")?.takeIf { it.isNotBlank() } ?: id
        val version = root.optString("version")?.takeIf { it.isNotBlank() } ?: "1.0.0"
        return RolePack(
            schema = schema,
            id = id,
            name = name,
            version = version,
            persona = parsePersona(root.optObj("persona")),
            worldbook = parseWorldbook(root.opt("worldbook")),
            style = parseStyle(root.optObj("style")),
            voice = parseVoice(root.optObj("voice")),
            expression = parseExpression(root.optObj("expression")),
            memory = parseMemory(root.optObj("memory"))
        )
    }

    /**
     * 导出 [RolePack] 为格式化 JSON（.rolepack 文件导出用）。
     * 全字段显式写出（含默认值），保证导出文件自描述、可直接再导入（round-trip 幂等）。
     */
    fun toPrettyJson(pack: RolePack): String {
        val obj = buildJsonObject {
            put("schema", pack.schema)
            put("id", pack.id)
            put("name", pack.name)
            put("version", pack.version)
            put("persona", buildJsonObject {
                put("systemPrompt", pack.persona.systemPrompt)
                put("callSystemPrompt", pack.persona.callSystemPrompt)
                put("address", pack.persona.address)
                put("tone", jsonArray(pack.persona.tone))
                put("forbidden", jsonArray(pack.persona.forbidden))
            })
            put("worldbook", kotlinx.serialization.json.buildJsonArray {
                pack.worldbook.forEach { entry ->
                    add(buildJsonObject {
                        put("keys", jsonArray(entry.keys))
                        put("content", entry.content)
                        put("constant", entry.constant)
                    })
                }
            })
            put("style", buildJsonObject {
                put("voiceCall", buildJsonObject {
                    put("maxSentenceLen", pack.style.voiceCall.maxSentenceLen)
                    put("sentenceBreak", pack.style.voiceCall.sentenceBreak)
                    put("fillers", jsonArray(pack.style.voiceCall.fillers))
                })
            })
            put("voice", buildJsonObject {
                put("minimax", buildJsonObject {
                    put("voiceId", pack.voice.minimax.voiceId)
                    put("speed", pack.voice.minimax.speed.toDouble())
                    put("emotion", pack.voice.minimax.emotion)
                    put("cloneVoiceId", pack.voice.minimax.cloneVoiceId)
                })
                put("gptsovits", buildJsonObject {
                    put("baseUrl", pack.voice.gptsovits.baseUrl)
                    put("refAudioPath", pack.voice.gptsovits.refAudioPath)
                    put("promptText", pack.voice.gptsovits.promptText)
                    put("promptLang", pack.voice.gptsovits.promptLang)
                })
                put("system", buildJsonObject {
                    put("pitch", pack.voice.system.pitch.toDouble())
                    put("speechRate", pack.voice.system.speechRate.toDouble())
                })
                put("local", buildJsonObject {
                    put("voiceId", pack.voice.local.voiceId)
                    put("numSteps", pack.voice.local.numSteps)
                })
            })
            put("expression", buildJsonObject {
                put("avatars", jsonArray(pack.expression.avatars))
                put("stickers", jsonArray(pack.expression.stickers))
                put("enabled", pack.expression.enabled)
            })
            put("memory", buildJsonObject {
                put("worldbookImport", pack.memory.worldbookImport)
            })
        }
        return prettyJson.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), obj)
    }

    // ==================== 分层解析 ====================

    private fun parsePersona(obj: JsonObject?): RolePack.Persona = RolePack.Persona(
        systemPrompt = obj?.optString("systemPrompt").orEmpty(),
        callSystemPrompt = obj?.optString("callSystemPrompt").orEmpty(),
        address = obj?.optString("address").orEmpty(),
        tone = obj?.optStringList("tone") ?: emptyList(),
        forbidden = obj?.optStringList("forbidden") ?: emptyList()
    )

    /**
     * 解析背景设定条目（TASK-20260912-001，ADR-049 D4）。
     *
     * 宽松策略与其它层一致：非数组 → 空列表；数组元素非对象 → 跳过该元素；`keys` 非数组 →
     * 空关键词（该条目退化为不可命中，恒定条目仍生效）；缺失 `content` → 空正文（渲染时跳过）。
     */
    private fun parseWorldbook(value: Any?): List<RolePack.WorldbookEntry> {
        val array = value as? JsonArray ?: return emptyList()
        return array.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            RolePack.WorldbookEntry(
                keys = obj.optStringList("keys"),
                content = obj.optString("content").orEmpty(),
                constant = obj.optBoolean("constant") ?: false
            )
        }
    }

    private fun parseStyle(obj: JsonObject?): RolePack.Style = RolePack.Style(
        voiceCall = obj?.optObj("voiceCall")?.let { vc ->
            RolePack.VoiceCallStyle(
                maxSentenceLen = vc.optInt("maxSentenceLen") ?: 30,
                sentenceBreak = vc.optBoolean("sentenceBreak") ?: true,
                fillers = vc.optStringList("fillers")
            )
        } ?: RolePack.VoiceCallStyle()
    )

    private fun parseVoice(obj: JsonObject?): RolePack.Voice = RolePack.Voice(
        minimax = obj?.optObj("minimax")?.let { m ->
            RolePack.MiniMaxVoice(
                voiceId = m.optString("voiceId"),
                speed = m.optFloat("speed") ?: 1.0f,
                emotion = m.optString("emotion"),
                cloneVoiceId = m.optString("cloneVoiceId")
            )
        } ?: RolePack.MiniMaxVoice(),
        gptsovits = obj?.optObj("gptsovits")?.let { g ->
            RolePack.GptSovitsVoice(
                baseUrl = g.optString("baseUrl"),
                refAudioPath = g.optString("refAudioPath"),
                promptText = g.optString("promptText"),
                promptLang = g.optString("promptLang")
            )
        } ?: RolePack.GptSovitsVoice(),
        system = obj?.optObj("system")?.let { s ->
            RolePack.SystemVoice(
                pitch = s.optFloat("pitch") ?: 1.0f,
                speechRate = s.optFloat("speechRate") ?: 1.0f
            )
        } ?: RolePack.SystemVoice(),
        // LVC-1（ADR-053）：本地音色包**按 id 引用**（资产侧载，不内嵌）；缺失 → voiceId=null（不可选）
        local = obj?.optObj("local")?.let { l ->
            RolePack.LocalVoice(
                voiceId = l.optString("voiceId")?.takeIf { it.isNotBlank() },
                numSteps = l.optInt("numSteps")
            )
        } ?: RolePack.LocalVoice()
    )

    private fun parseExpression(obj: JsonObject?): RolePack.Expression = RolePack.Expression(
        avatars = obj?.optStringList("avatars") ?: emptyList(),
        stickers = obj?.optStringList("stickers") ?: emptyList(),
        enabled = obj?.optBoolean("enabled") ?: false
    )

    private fun parseMemory(obj: JsonObject?): RolePack.Memory = RolePack.Memory(
        worldbookImport = obj?.optString("worldbookImport")
    )

    // ==================== 宽松标量提取 ====================

    /**
     * 提取字符串标量：非 JsonPrimitive/显式 null 返回 null；数字/布尔按 content 取值
     * （宽松类型）。JsonNull 已被 [opt] 拦截。
     */
    private fun JsonObject.optString(key: String): String? =
        (opt(key) as? JsonPrimitive)?.content

    /** 提取 int 标量（宽松：字符串数字可解析；失败回退 null → 调用方兜底默认值）。 */
    private fun JsonObject.optInt(key: String): Int? =
        optString(key)?.let { it.toIntOrNull() ?: it.toDoubleOrNull()?.toInt() }

    /** 提取 float 标量（宽松：字符串数字可解析）。 */
    private fun JsonObject.optFloat(key: String): Float? =
        optString(key)?.toFloatOrNull()

    /** 提取 boolean 标量（宽松：字符串 "true"/"false" 可解析）。 */
    private fun JsonObject.optBoolean(key: String): Boolean? =
        optString(key)?.toBooleanStrictOrNull()

    /** 提取嵌套对象（非对象返回 null → 调用方兜底默认值）。 */
    private fun JsonObject.optObj(key: String): JsonObject? = opt(key) as? JsonObject

    /** 提取字符串数组（缺失/非数组/含 null 元素均宽松跳过）。 */
    private fun JsonObject.optStringList(key: String): List<String> =
        (opt(key) as? JsonArray)
            ?.mapNotNull { el -> (el as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content }
            ?: emptyList()

    /** 统一取值入口：显式 JSON null 返回 null（区分"字段存在但为 null"与"字段缺失"→ 同样兜底）。 */
    private fun JsonObject.opt(key: String): Any? {
        val v = this[key] ?: return null
        return if (v is JsonNull) null else v
    }

    private fun jsonArray(items: List<String>) = kotlinx.serialization.json.buildJsonArray {
        items.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
    }
}
