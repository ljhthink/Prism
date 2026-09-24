package io.prism.pet.asset

import io.prism.pet.model.PetActionCatalog
import io.prism.pet.model.PetActionKind
import io.prism.pet.model.PetActionRef
import io.prism.pet.model.PetAppearance
import io.prism.pet.model.PetAppearanceKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 桌宠形象包解析/安装异常 —— 面向导入流程的**可读**信息（不含资产正文，防用户内容进日志）。
 */
class PetPackException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

/**
 * 批次24（ADR-051 D4）：`.petpack` 的 `pet.json` 清单（wire 层，纯数据）。
 *
 * **与 [PetAppearance] 的分工**：本类是**用户资产的原始声明**（路径是作者写的字符串，尚未校验）；
 * [PetPackParser.toAppearance] 负责把它**转成已校验**的 [PetAppearance]。
 * 分两层的原因：清单可能被外部编辑/构造，校验必须集中在一处，且校验逻辑要能纯 JVM 单测。
 */
data class PetManifest(
    val schema: String,
    val id: String,
    val name: String,
    val kind: PetAppearanceKind,
    val model: String?,
    val images: List<String>,
    val frameDurationMs: Int,
    val scale: Float,
    val actions: Map<String, PetActionRef>,
    val hitActions: Map<String, String>,
    val idleAliases: List<String>,
    val tapAlias: String?,
    val defaultExpression: String?,
    val expressionResetSeconds: Int,
    val attribution: String?
)

/**
 * 批次24（ADR-051 风险表：「用户导入的 `.petpack` 恶意构造」）：**安装期硬限制与路径净化** —— 纯函数。
 *
 * 这些常量是**安全边界**而非"建议值"：任何一条不满足即拒绝整个包（fail-fast，见 rules/07 §19.4）。
 * 拒绝而非截断的理由：截断会产生"看起来装好了但缺文件"的半损坏状态，比直接拒绝更难排查。
 */
object PetPackLimits {

    /** 解压条目数上限（正常 Live2D 包 < 30 条；512 足以容纳大量序列帧，同时挡住 zip bomb 的条目爆炸）。 */
    const val MAX_ENTRIES = 512

    /** 单文件解压体积上限 64MB（昔涟 `texture_0.png` 约 9MB，留足余量）。 */
    const val MAX_FILE_BYTES = 64L * 1024 * 1024

    /** 解压总体积上限 256MB（防"很多个中等文件"式的体积炸弹）。 */
    const val MAX_TOTAL_BYTES = 256L * 1024 * 1024

    /**
     * 允许的文件扩展名白名单。
     *
     * **为什么需要**：形象包只需要模型与图片。允许任意扩展名会让 `.petpack` 变成一个"往应用私有目录
     * 投递任意文件"的通道（未来某处 `Runtime.exec` / `loadLibrary` 误用私有目录路径即成 RCE 跳板）。
     * 白名单是**纵深防御**的一层，不替代其它层。
     */
    val ALLOWED_EXTENSIONS: Set<String> = setOf(
        "json", "moc3", "png", "jpg", "jpeg", "webp", "md", "txt"
    )

    /** 路径最大长度（防超长路径撑爆文件系统路径上限/日志）。 */
    const val MAX_PATH_LENGTH = 240

    /**
     * 净化 zip 条目路径为**安全的仓库内相对路径**。
     *
     * 拒绝规则（任一命中即返回 null → 调用方必须拒绝该条目）：
     * 1. 空白 / 超长；
     * 2. 含反斜杠 `\`（zip 规范用 `/`；`\` 在 Windows 上是分隔符，会被某些 shell 二次解析，统一拒绝）；
     * 3. 以 `/` 开头（绝对路径）或匹配 `^[A-Za-z]:`（Windows 盘符）；
     * 4. 含控制字符（`\u0000`–`\u001F`，防路径截断与日志注入）；
     * 5. 含 `.` / `..` 段（**zip slip**：`../` 会写到目标目录之外）；
     * 6. 含空段（`a//b`）或尾部 `/`（目录条目由调用方在净化前过滤）。
     *
     * **为什么不做"规范化后再判断"**：规范化（如 `File.canonicalPath`）只在真实文件系统上有效，
     * 而 zip 条目是**逻辑路径**——先净化成规范形式再拼接，比"拼接后再验证"更不容易被
     * 编码变体（`%2e%2e`、Unicode 归一化、大小写）绕过。本方法只做**白名单式字符与段校验**。
     *
     * @return 净化后的相对路径（`/` 分隔）；不合法返回 null
     */
    fun sanitizeRelativePath(raw: String?): String? {
        val path = raw?.trim().orEmpty()
        if (path.isEmpty() || path.length > MAX_PATH_LENGTH) return null
        if (path.contains('\\')) return null
        if (path.startsWith("/")) return null
        if (path.any { it.code < 0x20 || it.code == 0x7F }) return null
        if (Regex("^[A-Za-z]:").containsMatchIn(path)) return null
        val segments = path.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) return null
        return segments.joinToString("/")
    }

    /**
     * 该路径的扩展名是否在白名单内（大小写不敏感；无扩展名一律拒绝）。
     */
    fun extensionAllowed(path: String): Boolean {
        val name = path.substringAfterLast('/')
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return false
        return name.substring(dot + 1).lowercase() in ALLOWED_EXTENSIONS
    }

    /**
     * 形象 id → 安全目录名。
     *
     * **保留 Unicode 字母与数字**（不只 ASCII）：形象 id 常常直接来自用户的文件名
     * （"我的桌宠"），若只保留 ASCII，所有中文名都会被压成同一个 `pet`，
     * 第二个中文形象会覆盖第一个——这是用户可见的数据丢失。
     * 保留 CJK 在 Android 的文件系统（ext4/f2fs，UTF-8）上完全安全，
     * 且渲染侧的虚拟 URL 会用百分号编码承载它。
     *
     * **仍然拒绝**：路径分隔符（`/`、`\`）、控制字符、以及净化后只剩 `.` 的情况
     * （`..` 被 `trim` 掉后回退为 `pet`，因此不可能成为目录跳转）。
     */
    fun sanitizeAppearanceId(raw: String?): String {
        val source = raw?.trim().orEmpty()
        if (source.isEmpty()) return DEFAULT_APPEARANCE_ID
        val sb = StringBuilder(source.length)
        for (ch in source) {
            when {
                ch.isLetterOrDigit() -> sb.append(ch)
                ch == '-' || ch == '_' || ch == '.' -> sb.append(ch)
                else -> sb.append('_')
            }
        }
        val safe = sb.toString().trim('_', '.')
        return safe.ifBlank { DEFAULT_APPEARANCE_ID }.take(64)
    }

    /** 无法从名称推导 id 时的兜底目录名。 */
    const val DEFAULT_APPEARANCE_ID = "pet"
}

/**
 * 批次24（ADR-051 D1/D4）：`pet.json` 解析器与安装期校验 —— 纯函数，可 JVM 单测。
 *
 * **校验策略**（与 [io.prism.voice.rolepack.RolePackParser] 保持一致的宽松哲学，但**路径与引用收紧**）：
 * - **未知字段忽略**：手工按字段提取，Schema 演进向前兼容；
 * - **缺省兜底**：除 `schema` 外全部字段可缺省；
 * - **畸形抛可读异常**：[PetPackException]，消息面向用户（导入失败对话框直接展示）；
 * - **路径必须净化通过**：净化失败的路径**丢弃该项**（而非整包拒绝）——但若 `model` 净化失败或
 *   [PetAppearanceKind.LIVE2D] 缺 `model`，则整包拒绝（没有模型就没有形象）；
 * - **引用完整性由安装器负责**：解析器不碰文件系统。
 *
 * **线程安全**：object 无状态。
 */
object PetPackParser {

    /** `.petpack` 清单 schema 标记。 */
    const val SCHEMA_V1 = "prism-petpack/1"

    /** 清单文件名（包根固定为此名）。 */
    const val MANIFEST_NAME = "pet.json"

    /** 默认缩放合法域（与 [io.prism.pet.model.PetConfig] 的 scale 域一致）。 */
    private const val MIN_SCALE = 0.25f
    private const val MAX_SCALE = 4.0f

    /** motion 下标上限（仅作病态输入防御；真实组内条目数远小于此）。 */
    private const val MAX_MOTION_INDEX = 4096

    private val prettyJson = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * 解析 `pet.json` 全文。
     *
     * @throws PetPackException JSON 语法错误 / 顶层非对象 / `schema` 缺失或不是 [SCHEMA_V1] / 关键字段非法
     */
    fun parse(jsonText: String): PetManifest {
        val root = try {
            json.parseToJsonElement(jsonText)
        } catch (e: Exception) {
            throw PetPackException(
                "pet.json 不是合法 JSON（${e.message ?: e::class.simpleName}）",
                e
            )
        }
        if (root !is JsonObject) {
            throw PetPackException("pet.json 顶层必须是 JSON 对象（收到 ${root::class.simpleName}）")
        }

        val schema = root.str("schema")?.takeIf { it.isNotBlank() }
            ?: throw PetPackException("pet.json 缺少 schema 字段（期望 \"$SCHEMA_V1\"）")
        if (schema != SCHEMA_V1) {
            throw PetPackException("不支持的形象包 schema：\"$schema\"（本版本仅支持 \"$SCHEMA_V1\"）")
        }

        val kind = PetAppearanceKind.fromWire(root.str("kind"))
            ?: throw PetPackException("pet.json 的 kind 非法（期望 \"live2d\" 或 \"image\"）")

        val model = root.str("model")?.let { raw ->
            PetPackLimits.sanitizeRelativePath(raw)
                ?: throw PetPackException("pet.json 的 model 路径不合法（不允许绝对路径或 .. 跳转）")
        }
        if (kind == PetAppearanceKind.LIVE2D && model == null) {
            throw PetPackException("live2d 形象必须在 pet.json 中声明 model（.model3.json 的相对路径）")
        }

        val images = root.strList("images")
            .mapNotNull { PetPackLimits.sanitizeRelativePath(it) }
            .distinct()
        if (kind == PetAppearanceKind.IMAGE && images.isEmpty()) {
            throw PetPackException("image 形象必须在 pet.json 的 images 中至少声明一张图片")
        }

        val id = PetPackLimits.sanitizeAppearanceId(root.str("id"))
        val name = root.str("name")?.trim()?.takeIf { it.isNotBlank() } ?: id

        return PetManifest(
            schema = schema,
            id = id,
            name = name,
            kind = kind,
            model = model,
            images = images,
            frameDurationMs = (root.num("frameDurationMs") ?: 0f).toInt().coerceIn(0, 5000),
            scale = (root.num("scale") ?: 1.0f).let {
                if (it.isFinite()) it.coerceIn(MIN_SCALE, MAX_SCALE) else 1.0f
            },
            actions = parseActions(root.obj("actions")),
            hitActions = parseHitActions(root.obj("hitActions")),
            idleAliases = root.strList("idle").mapNotNull { PetActionCatalog.normalizeAlias(it) }.distinct(),
            tapAlias = PetActionCatalog.normalizeAlias(root.str("tap")),
            defaultExpression = root.str("defaultExpression")?.trim()?.takeIf { it.isNotBlank() },
            expressionResetSeconds = (root.num("expressionResetSeconds")
                ?: PetAppearance.DEFAULT_EXPRESSION_RESET_SECONDS.toFloat())
                .toInt().coerceIn(0, 60),
            attribution = root.str("attribution")?.trim()?.takeIf { it.isNotBlank() }
        )
    }

    /**
     * 把已校验的清单与**已安装目录**组合为运行期形象描述。
     *
     * @param manifest 已解析清单
     * @param installedId 实际落盘目录名（= [PetPackLimits.sanitizeAppearanceId] 的结果，可能被截断）
     */
    fun toAppearance(manifest: PetManifest, installedId: String = manifest.id): PetAppearance =
        PetAppearance(
            id = installedId,
            name = manifest.name,
            kind = manifest.kind,
            model = manifest.model,
            images = manifest.images,
            frameDurationMs = manifest.frameDurationMs,
            scale = manifest.scale,
            actions = manifest.actions,
            hitActions = manifest.hitActions,
            idleAliases = manifest.idleAliases,
            tapAlias = manifest.tapAlias,
            defaultExpression = manifest.defaultExpression,
            expressionResetSeconds = manifest.expressionResetSeconds,
            attribution = manifest.attribution
        )

    /**
     * 把清单回写为 `pet.json` 文本（单图导入通道用）。
     *
     * **为什么必须有**：安装器把形象目录视为"有 `pet.json` 才算形象"——单图通道若只落图片
     * 不落清单，目录会被 [PetPackInstaller.list] 当作损坏项**静默跳过**，
     * 表现为"导入提示成功，但列表里什么都没有"（本批次单测实测到的缺陷）。
     */
    fun toManifestJson(manifest: PetManifest): String = buildJsonObject {
        put("schema", manifest.schema)
        put("id", manifest.id)
        put("name", manifest.name)
        put("kind", if (manifest.kind == PetAppearanceKind.LIVE2D) "live2d" else "image")
        manifest.model?.let { put("model", it) }
        put("images", buildJsonArray { manifest.images.forEach { add(JsonPrimitive(it)) } })
        put("frameDurationMs", manifest.frameDurationMs)
        put("scale", manifest.scale.toDouble())
        put("expressionResetSeconds", manifest.expressionResetSeconds)
        manifest.defaultExpression?.let { put("defaultExpression", it) }
        manifest.tapAlias?.let { put("tap", it) }
        manifest.attribution?.let { put("attribution", it) }
        put("idle", buildJsonArray { manifest.idleAliases.forEach { add(JsonPrimitive(it)) } })
        put("actions", buildJsonObject {
            manifest.actions.forEach { (alias, ref) ->
                put(
                    alias,
                    buildJsonObject {
                        put("kind", if (ref.kind == PetActionKind.MOTION) "motion" else "expression")
                        put("target", ref.target)
                        put("index", ref.index)
                        ref.displayName?.let { put("name", it) }
                        ref.fallbackExpression?.let { put("fallbackExpression", it) }
                    }
                )
            }
        })
        put("hitActions", buildJsonObject { manifest.hitActions.forEach { (k, v) -> put(k, v) } })
    }.let { prettyJson.encodeToString(JsonObject.serializer(), it) }

    /** 单图导入（用户直接选一张图片）时合成的清单：不走 `.petpack`，但仍复用同一套形象模型。 */
    fun singleImageManifest(id: String, name: String, imagePath: String): PetManifest {
        val safePath = PetPackLimits.sanitizeRelativePath(imagePath)
            ?: throw PetPackException("图片路径不合法")
        if (!PetPackLimits.extensionAllowed(safePath)) {
            throw PetPackException("不支持的图片格式（仅支持 png / jpg / jpeg / webp）")
        }
        return PetManifest(
            schema = SCHEMA_V1,
            id = PetPackLimits.sanitizeAppearanceId(id),
            name = name.trim().ifBlank { id },
            kind = PetAppearanceKind.IMAGE,
            model = null,
            images = listOf(safePath),
            frameDurationMs = 0,
            scale = 1.0f,
            actions = emptyMap(),
            hitActions = emptyMap(),
            idleAliases = emptyList(),
            tapAlias = null,
            defaultExpression = null,
            expressionResetSeconds = PetAppearance.DEFAULT_EXPRESSION_RESET_SECONDS,
            attribution = null
        )
    }

    // ==================== 分层解析 ====================

    /**
     * 解析别名表。
     *
     * 支持两种写法（对作者友好，减少出错面）：
     * - **全写**：`"happy": { "kind": "motion", "target": "动作#6", "index": 1 }`（动作**必须**用这种）；
     * - **简写**：`"happy": "开心眼"` —— 裸字符串**一律视为表情名**（见 [shorthandRef] 的消歧说明）。
     *
     * **别名归一化**：键统一经 [PetActionCatalog.normalizeAlias]（大小写不敏感、裁剪空白）；
     * 空白键或空白目标**丢弃该项**（不整包拒绝——一个坏别名不该让整个形象装不上）。
     */
    private fun parseActions(obj: JsonObject?): Map<String, PetActionRef> {
        if (obj == null) return emptyMap()
        val out = LinkedHashMap<String, PetActionRef>(obj.size)
        for ((rawKey, value) in obj) {
            val key = PetActionCatalog.normalizeAlias(rawKey) ?: continue
            val ref = parseActionValue(value) ?: continue
            if (ref.target.isBlank()) continue
            out[key] = ref
        }
        return out
    }

    /**
     * 解析命中区映射（命中区名 → 别名）。
     *
     * 键是 **Cubism 模型内的 HitArea 名**（如 `左侧秋千`），值是别名（经归一化）。
     * 值必须能在 [PetManifest.actions] 中解析出目标才有意义——**不在此处校验**（解析器不跨层），
     * 由渲染层的"未映射别名安全忽略"契约兜底（[PetActionCatalog.resolve] 返回 null）。
     */
    private fun parseHitActions(obj: JsonObject?): Map<String, String> {
        if (obj == null) return emptyMap()
        val out = LinkedHashMap<String, String>(obj.size)
        for ((rawKey, value) in obj) {
            val area = rawKey.trim()
            if (area.isEmpty()) continue
            val alias = when (value) {
                is JsonPrimitive -> value.takeIf { it !is JsonNull }?.content
                else -> null
            }
            val normalized = PetActionCatalog.normalizeAlias(alias) ?: continue
            out[area] = normalized
        }
        return out
    }

    private fun parseActionValue(value: Any?): PetActionRef? = when (value) {
        is JsonPrimitive -> {
            val raw = value.takeIf { it !is JsonNull }?.content?.trim().orEmpty()
            shorthandRef(raw)
        }
        is JsonObject -> {
            val target = value.str("target")?.trim().orEmpty()
            if (target.isBlank()) return null
            val kind = PetActionKind.fromWire(value.str("kind")) ?: PetActionKind.MOTION
            PetActionRef(
                kind = kind,
                target = target,
                index = (value.num("index") ?: 0f).toInt()
                    .coerceIn(PetActionRef.RANDOM_MOTION_INDEX, MAX_MOTION_INDEX),
                displayName = value.str("name")?.trim()?.takeIf { it.isNotBlank() },
                fallbackExpression = value.str("fallbackExpression")?.trim()?.takeIf { it.isNotBlank() }
            )
        }
        else -> null
    }

    /**
     * 简写推断：**一律视为 expression 名**。
     *
     * **为什么简写不支持 motion**（本批次实测踩到的歧义）：昔涟模型的 motion **组名本身含 `#`**
     * （`动作#6`、`绳子开关#5`、`表情#2`…）。于是裸字符串 `动作#6` 有两种同样合理的读法：
     * 「组 `动作#6`」还是「组 `动作` 的第 6 条」——解析器拿不到模型文件，无法消歧。
     * 早期实现按"最后一段是纯数字即为下标"来猜，结果把 `动作#6` 猜成了「组 `动作` 下标 6」，
     * 而该组根本不存在 ⇒ 用户点了没反应，且**没有任何错误提示**。
     *
     * 因此定死规则：**动作必须写全写形式** `{"kind":"motion","target":"动作#6","index":0}`；
     * 裸字符串只用于表达"这是一个表情"（绝大多数场景：`"happy": "开心眼"`）。
     */
    private fun shorthandRef(raw: String): PetActionRef? {
        if (raw.isBlank()) return null
        return PetActionRef(PetActionKind.EXPRESSION, raw)
    }

    // ==================== 模型引用收集 ====================

    /**
     * 从 Cubism `model3.json` 收集全部**被引用的相对路径**（纯函数，可 JVM 单测）。
     *
     * 覆盖官方 `FileReferences` 的 `Moc` / `Textures[]` / `Physics` / `Pose` / `UserData` /
     * `Motions{ 组: [{ File }] }` / `Expressions[{ File }]`。
     *
     * **未知字段必须忽略**：真实资产常带第三方编辑器扩展块（昔涟模型就带 Live2DViewerEX 的
     * `Controllers` / `HitAreas[].Motion` / `Motions[].VarFloats`，均非 Cubism 官方规范），
     * 用严格 schema 反序列化会直接失败。
     *
     * @return 去重保序的引用路径（仅含能净化通过的项）；畸形 JSON 返回空列表——
     *   此时"引用完整性校验"退化为不检查，比"因为解析不了就拒绝整包"更宽容：
     *   真正的加载失败会在渲染层被捕获并触发降级（PRD US-2413 AC-1），
     *   而**导入期拒绝**会让用户面对一个其实可能能用的包。
     */
    fun collectModelReferences(modelJson: String): List<String> {
        val root = runCatching { json.parseToJsonElement(modelJson) as? JsonObject }.getOrNull()
            ?: return emptyList()
        val refs = root.obj("FileReferences") ?: return emptyList()
        val out = LinkedHashSet<String>()

        fun addPath(raw: String?) {
            if (raw.isNullOrBlank()) return
            PetPackLimits.sanitizeRelativePath(raw)?.let { out.add(it) }
        }

        addPath(refs.str("Moc"))
        addPath(refs.str("Physics"))
        addPath(refs.str("Pose"))
        addPath(refs.str("UserData"))
        refs.strList("Textures").forEach { addPath(it) }

        (refs["Motions"] as? JsonObject)?.forEach { (_, value) ->
            (value as? JsonArray)?.forEach { item ->
                addPath((item as? JsonObject)?.str("File"))
            }
        }
        (refs["Expressions"] as? JsonArray)?.forEach { item ->
            addPath((item as? JsonObject)?.str("File"))
        }
        return out.toList()
    }

    // ==================== 模型引用 URL 化 ====================

    /**
     * 把 `model3.json` 的 `FileReferences` 内所有路径改写为 **URL 安全的百分号编码路径**（纯函数）。
     *
     * **为什么必须做**（本批次实测到的**静默失败**陷阱，见移动端调研报告 §6）：
     * 昔涟模型的 motion 文件名形如 `动作#6_0.motion3.json`——含 `#`。而 `#` 在 URL 里是
     * **fragment 分隔符**：无论上游用 `path.resolve` 还是 `resolve-url` 做相对路径拼接，
     * 也无论 PIXI 内部是否先 `removeUrlParams`（源码逐字为 `url.split("?")[0].split("#")[0]`），
     * 结果都是 `.../motions/动作`——**后缀连同 `#` 一起被吃掉**，请求 404。
     *
     * 后果是**静默的**：5 个 motion 文件里 4 个取不到，用户表现为"点了有些地方有反应、有些没反应"，
     * 而日志里只有一条 JS 的加载失败告警。故在**安装期**把引用改写为 `%23` /
     * `%E5%8A%A8...` 形式，让运行时完全不必处理这个问题。
     *
     * **只改 `FileReferences`**：Cubism 规范中所有外部文件引用都集中在该块；
     * `.motion3.json` / `.exp3.json` / `.physics3.json` 自身不引用其它文件。
     * 逐字段处理的是 `Moc` / `Physics` / `Pose` / `UserData` / `Textures[]` /
     * `Motions{组}[].File` / `Motions{组}[].Sound` / `Expressions[].File`
     * （guardrail L-7：早期 KDoc 漏列了 `Pose` / `UserData` / `Sound`）。
     * 第三方扩展块（如 Live2DViewerEX 的 `Controllers`）原样保留（标准运行时不读它们）。
     *
     * @return 改写后的 JSON 文本；无法解析时**原样返回**（不做无谓的破坏）
     */
    fun encodeModelReferences(modelJson: String): String {
        val root = runCatching { json.parseToJsonElement(modelJson) as? JsonObject }.getOrNull()
            ?: return modelJson
        val refs = root["FileReferences"] as? JsonObject ?: return modelJson
        val rewritten = rewriteFileReferences(refs)
        val newRoot = JsonObject(LinkedHashMap(root).apply { put("FileReferences", rewritten) })
        return runCatching { prettyJson.encodeToString(JsonObject.serializer(), newRoot) }.getOrDefault(modelJson)
    }

    /**
     * 单条路径的 URL 化：按 `/` 分段，每段做 RFC 3986 严格编码
     * （保留 unreserved 集合 `A-Za-z0-9-._~`，其余字节转 `%XX`）。
     *
     * **不用 [java.net.URLEncoder]**：它按表单编码把空格写成 `+`，而路径里的 `+` 语义是字面加号，
     * 会造成"文件名含 `+` 的资产永远读不到"。
     */
    fun encodePathForUrl(path: String): String =
        path.split('/').joinToString("/") { segment -> encodeSegment(segment) }

    private fun encodeSegment(segment: String): String {
        val sb = StringBuilder(segment.length)
        for (byte in segment.toByteArray(Charsets.UTF_8)) {
            val c = byte.toInt().toChar()
            if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_' || c == '.' || c == '~') {
                sb.append(c)
            } else {
                sb.append('%').append(String.format("%02X", byte.toInt() and 0xFF))
            }
        }
        return sb.toString()
    }

    /**
     * **只对真正的文件路径字段**做 URL 化。
     *
     * 早期实现把 `FileReferences` 子树里**所有**字符串一律编码——那是错的：
     * 同一子树里还有 `Motions[].Name`（动作显示名，如 `动作回正`）、`VarFloats[].Code`
     * （如 `assign 1`）、`Expressions[].Name`，编码它们会让动作名与参数表达式全部损坏
     * （而且损坏是静默的：名字变得不匹配，动作直接找不到）。
     */
    private fun rewriteFileReferences(refs: JsonObject): JsonObject {
        val out = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>(refs.size)
        refs.forEach { (key, value) ->
            when (key) {
                "Moc", "Physics", "Pose", "UserData" -> out[key] = encodePrimitive(value)
                "Textures" -> out[key] = encodeArrayOfPrimitives(value)
                "Motions" -> out[key] = (value as? JsonObject)?.let { groups ->
                    JsonObject(LinkedHashMap<String, kotlinx.serialization.json.JsonElement>(groups.size).apply {
                        groups.forEach { (group, list) ->
                            put(group, (list as? JsonArray)?.let { arr ->
                                JsonArray(arr.map { item ->
                                    val obj = item as? JsonObject ?: return@map item
                                    JsonObject(LinkedHashMap(obj).apply {
                                        (obj["File"] as? JsonPrimitive)?.let { put("File", JsonPrimitive(encodePathForUrl(it.content))) }
                                        (obj["Sound"] as? JsonPrimitive)?.let { put("Sound", JsonPrimitive(encodePathForUrl(it.content))) }
                                    })
                                })
                            } ?: list)
                        }
                    })
                } ?: value
                "Expressions" -> out[key] = (value as? JsonArray)?.let { arr ->
                    JsonArray(arr.map { item ->
                        val obj = item as? JsonObject ?: return@map item
                        JsonObject(LinkedHashMap(obj).apply {
                            (obj["File"] as? JsonPrimitive)?.let { put("File", JsonPrimitive(encodePathForUrl(it.content))) }
                        })
                    })
                } ?: value
                else -> out[key] = value
            }
        }
        return JsonObject(out)
    }

    private fun encodePrimitive(value: kotlinx.serialization.json.JsonElement): kotlinx.serialization.json.JsonElement =
        (value as? JsonPrimitive)?.let { JsonPrimitive(encodePathForUrl(it.content)) } ?: value

    private fun encodeArrayOfPrimitives(value: kotlinx.serialization.json.JsonElement): kotlinx.serialization.json.JsonElement =
        (value as? JsonArray)?.let { arr -> JsonArray(arr.map { encodePrimitive(it) }) } ?: value

    // ==================== 宽松标量提取 ====================

    private fun JsonObject.obj(key: String): JsonObject? = (this[key] as? JsonObject)

    private fun JsonObject.str(key: String): String? {
        val v = this[key] ?: return null
        if (v is JsonNull) return null
        return (v as? JsonPrimitive)?.content
    }

    private fun JsonObject.num(key: String): Float? = str(key)?.toFloatOrNull()

    private fun JsonObject.strList(key: String): List<String> {
        val arr = this[key] as? JsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            (el as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.trim()?.takeIf { it.isNotEmpty() }
        }
    }
}
