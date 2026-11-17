package io.prism.qq

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * 表情目录（PRD US-3122；2026-10-26 用户裁定：内置 QQ 表情 + 用户导入图片 + 回发收到的商城表情）。
 *
 * **为什么不做第三方表情包**：素材许可不清晰，且 QQ 内置表情零素材、零风险、覆盖日常情绪。
 *
 * 三类素材：
 * 1. **内置 QQ 表情**：情绪标签 → 表情 ID（[BUILTIN_FACES]），走 OneBot `face` 段；
 * 2. **用户导入图片**：保存到私有目录 `qq_stickers/`，走 OneBot `image` + `base64://`；
 * 3. **收到的商城表情（mface）**：只在收到时**学习**其 `emoji_id/package_id/key`，
 *    之后允许"回发同一表情"，走 `mface` 段。不抓取、不内置任何第三方素材。
 */
class QqStickerStore(
    private val rootDir: File,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    /**
     * 用户导入的表情（id → 文件路径）。
     *
     * 2026-11-16（ADR-077）扩展三个字段——依据 `dsh-qq-bot` 的"目录即图库 + 内容哈希
     * 增量打标 + 发送闸门"生产实现：
     * - [description]：一句可读描述（导入时由视觉模型生成、用户可改）。**这是模型能"指名"
     *   某张表情的前提**——此前只有 [emotion] 这一档模糊情绪，模型无法选具体图。
     * - [sha1]：图片内容哈希。**只有哈希变化时才重新打标**，避免每次启动重复烧 token。
     * - [lastSentAtMs]：最近一次发送时间，用于"同图 TTL 内不重复发"（防刷图）。
     */
    data class UserSticker(
        val id: String,
        val emotion: String,
        val fileName: String,
        val description: String = "",
        val sha1: String = "",
        val lastSentAtMs: Long = 0L
    )

    /** 学到的商城表情。 */
    data class LearnedMface(
        val emojiId: String,
        val emojiPackageId: String,
        val key: String,
        val summary: String
    )

    /** 持久化容器。 */
    private data class Snapshot(
        val userStickers: List<UserSticker> = emptyList(),
        val learned: List<LearnedMface> = emptyList(),
        /** 最近若干次表情发送时间（发送闸门：每小时上限）。2026-11-16（ADR-077）。 */
        val recentSends: List<Long> = emptyList()
    )

    private val dir: File = File(rootDir, "qq_stickers").apply { runCatching { mkdirs() } }
    private val snapshotFile: File = File(dir, "stickers.json")

    @Volatile
    private var snapshot: Snapshot = readSnapshot()

    /** 用户导入的表情列表（供 UI 展示与随机挑选）。 */
    fun userStickers(): List<UserSticker> = snapshot.userStickers

    /** 学到的商城表情列表。 */
    fun learnedMfaces(): List<LearnedMface> = snapshot.learned

    /** 用户表情图片文件（不存在返回 null）。 */
    fun imageFile(id: String): File? =
        snapshot.userStickers.firstOrNull { it.id == id }
            ?.let { File(dir, it.fileName) }
            ?.takeIf { it.isFile }

    /**
     * 登记一张用户导入的表情图。
     *
     * 2026-11-16（ADR-077）：新增 [description] 与 [sha1]。**按内容哈希去重**——
     * 同一张图重复导入直接返回既有 id（不再产生重复条目）。
     *
     * @param bytes 已解码的图片字节（调用方负责大小/类型校验）
     * @param emotion 情绪标签（可为空字符串；不再限于内置 24 个）
     * @param description 一句可读描述（可由视觉模型生成，用户可改；空=尚未打标）
     * @return 新（或既有）表情 ID（失败返回 null）
     */
    fun importImage(
        bytes: ByteArray,
        emotion: String,
        description: String = ""
    ): String? {
        if (bytes.isEmpty() || bytes.size > MAX_IMAGE_BYTES) return null
        val hash = sha1Hex(bytes)
        // 内容哈希去重：同一张图再次导入 → 复用既有条目（顺带补齐空描述）
        snapshot.userStickers.firstOrNull { it.sha1.isNotEmpty() && it.sha1 == hash }?.let { existing ->
            if (description.isNotBlank() && existing.description.isBlank()) {
                setDescription(existing.id, description)
            }
            return existing.id
        }
        val id = "u" + clock().toString(36) + "_" + (bytes.size % 1000)
        val fileName = "$id.img"
        val file = File(dir, fileName)
        return runCatching {
            file.writeBytes(bytes)
            val next = snapshot.copy(
                userStickers = snapshot.userStickers + UserSticker(
                    id = id,
                    emotion = emotion.trim(),
                    fileName = fileName,
                    description = description.trim().take(MAX_DESCRIPTION_CHARS),
                    sha1 = hash
                )
            )
            persist(next)
            id
        }.getOrNull()
    }

    /**
     * 写入/修改表情描述（导入后手动编辑，或打标完成后回填）。
     *
     * @return true=已更新
     */
    fun setDescription(id: String, description: String): Boolean {
        val target = snapshot.userStickers.firstOrNull { it.id == id } ?: return false
        val next = snapshot.copy(
            userStickers = snapshot.userStickers.map {
                if (it.id == target.id) it.copy(description = description.trim().take(MAX_DESCRIPTION_CHARS))
                else it
            }
        )
        return persist(next)
    }

    /** 需要打标的表情（有图、哈希非空、描述为空）——增量打标的输入。 */
    fun pendingTagging(): List<UserSticker> =
        snapshot.userStickers.filter { it.description.isBlank() && it.sha1.isNotEmpty() }

    /** 记录一次表情发送（供"同图 TTL"与"每小时上限"使用）。 */
    fun markSent(id: String, atMs: Long): Boolean {
        val next = snapshot.copy(
            userStickers = snapshot.userStickers.map {
                if (it.id == id) it.copy(lastSentAtMs = atMs) else it
            },
            recentSends = (snapshot.recentSends + atMs).takeLast(MAX_SEND_TIMESTAMPS)
        )
        return persist(next)
    }

    /**
     * 只记录**发送时刻**，不关联具体条目（2026-11-16 自查修复；ADR-077）。
     *
     * **为什么必须有**：`markSent(id)` 只对用户导入的表情更新 `lastSentAtMs`，
     * 而小时上限读的是 `recentSends`。原实现只在"指名用户表情"时调用 `markSent`，
     * 于是**内置 face / 商城 mface 完全不记账** → "每小时 6 张"对它们形同虚设
     * （用户一天发几十张内置表情也不会被拦）。任何表情真正发出后都应调用本方法。
     */
    fun recordSend(atMs: Long): Boolean {
        val next = snapshot.copy(
            recentSends = (snapshot.recentSends + atMs).takeLast(MAX_SEND_TIMESTAMPS)
        )
        return persist(next)
    }

    /** 最近一小时内已发出的表情数（发送闸门用）。 */
    fun sendsInLastHour(nowMs: Long): Int =
        snapshot.recentSends.count { ts -> ts > 0L && nowMs - ts < 60 * 60_000L }

    /** 指定表情是否仍在"同图去重"的 TTL 内。 */
    fun sentWithinTtl(id: String, nowMs: Long, ttlMs: Long): Boolean {
        val last = snapshot.userStickers.firstOrNull { it.id == id }?.lastSentAtMs ?: return false
        return last > 0L && nowMs - last < ttlMs
    }

    /**
     * 供 prompt 使用的**可用表情目录**（最多 [CATALOG_IN_PROMPT] 条）。
     *
     * 2026-11-16（guardrail R2-M-02）：描述为空时**回落到情绪标签**。
     * 原实现过滤 `description.isNotBlank()`，而设置页最初只能给"最后一张"写描述——
     * 于是"先批量导入 5 张再补描述"时只有第 5 张进目录，**前 4 张永久不可见**
     * （且因 sha1 去重，删掉重导也只会"补描述"而不会重新进入可见集合）。
     *
     * @return 形如 `u5x_12：粉发小人举着"抱抱"牌子（情绪=抱抱）`
     */
    fun catalogForPrompt(): List<String> = snapshot.userStickers
        .asSequence()
        .mapNotNull { s ->
            val desc = s.description.trim().ifBlank { s.emotion.trim() }
            // 描述与情绪都为空 → 模型无从判断何时用，列出来只是噪声
            if (desc.isBlank()) return@mapNotNull null
            val emotion = s.emotion.takeIf { it.isNotBlank() }?.let { "（情绪=$it）" }.orEmpty()
            "${s.id}：$desc$emotion"
        }
        .take(CATALOG_IN_PROMPT)
        .toList()

    /**
     * 按 **id 或关键词** 找一张表情（供 `[[sticker:id:xxx]]` 指名发送）。
     *
     * 命中顺序：精确 id → 描述/情绪包含关键词（命中越多越靠前）。
     */
    fun findByIdOrKeyword(query: String): UserSticker? {
        val q = query.trim()
        if (q.isEmpty()) return null
        snapshot.userStickers.firstOrNull { it.id.equals(q, ignoreCase = true) }?.let { return it }
        return snapshot.userStickers
            .map { sticker ->
                val haystack = sticker.description + " " + sticker.emotion
                val score = q.split(' ', '　', '，', ',')
                    .filter { it.isNotBlank() }
                    .count { haystack.contains(it, ignoreCase = true) }
                sticker to score
            }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }
            ?.first
    }

    /** 删除用户导入的表情。 */
    fun removeUserSticker(id: String): Boolean {
        val target = snapshot.userStickers.firstOrNull { it.id == id } ?: return false
        runCatching { File(dir, target.fileName).delete() }
        val next = snapshot.copy(userStickers = snapshot.userStickers.filterNot { it.id == id })
        return persist(next)
    }

    /**
     * 从一条收到的消息事件中**学习**商城表情（`mface` 段），供之后回发。
     *
     * 只记录元数据，不下载素材；同一 `emoji_id` 只保留一条。
     */
    fun learnFromEvent(event: JsonObject) {
        val segments = event["message"] as? kotlinx.serialization.json.JsonArray ?: return
        segments.forEach { element ->
            val obj = element as? JsonObject ?: return@forEach
            if (obj["type"]?.jsonPrimitive?.contentOrNull != "mface") return@forEach
            val data = obj["data"]?.jsonObject ?: return@forEach
            val emojiId = data["emoji_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val packageId = data["emoji_package_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val key = data["key"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (emojiId.isBlank() || key.isBlank()) return@forEach
            if (snapshot.learned.any { it.emojiId == emojiId && it.key == key }) return@forEach
            val next = snapshot.copy(
                learned = (snapshot.learned + LearnedMface(
                    emojiId = emojiId,
                    emojiPackageId = packageId,
                    key = key,
                    summary = data["summary"]?.jsonPrimitive?.contentOrNull.orEmpty().take(40)
                )).takeLast(MAX_LEARNED)
            )
            persist(next)
        }
    }

    /**
     * 为自动回复挑一个表情。
     *
     * 优先匹配情绪（内置表情 → 用户同情绪图片 → 学到的商城表情），
     * 都不命中则返回 null（发送端按纯文本处理，绝不因表情失败丢消息）。
     */
    fun pick(emotion: String?, random: kotlin.random.Random = kotlin.random.Random.Default): Sticker? {
        val tag = emotion?.trim().orEmpty()
        if (tag.isNotEmpty()) {
            // 用户显式导入并标注了同一情绪 → 优先用用户的（数据即意图）
            snapshot.userStickers.firstOrNull { it.emotion == tag }?.let {
                imageFile(it.id)?.let { file -> return Sticker.ImageFile(file) }
            }
            QqStickerCatalog.faceIdFor(tag)?.let { return Sticker.BuiltinFace(it) }
            snapshot.learned.firstOrNull { it.summary == tag }?.let {
                return Sticker.Mface(it)
            }
        }
        // 无匹配（或用户没给情绪）：随机挑一个用户表情/内置表情，保持自然
        val userCandidates = snapshot.userStickers.filter { imageFile(it.id) != null }
        if (userCandidates.isNotEmpty() && random.nextInt(100) < USER_STICKER_RATIO) {
            val pick = userCandidates[random.nextInt(userCandidates.size)]
            imageFile(pick.id)?.let { return Sticker.ImageFile(it) }
        }
        val faces = QqStickerCatalog.allFaceIds()
        return Sticker.BuiltinFace(faces[random.nextInt(faces.size)])
    }

    /** 表情发送意图。 */
    sealed interface Sticker {
        data class BuiltinFace(val faceId: String) : Sticker
        data class ImageFile(val file: File) : Sticker
        data class Mface(val mface: LearnedMface) : Sticker
    }

    // ==================== 持久化 ====================

    private fun readSnapshot(): Snapshot = runCatching {
        if (!snapshotFile.isFile) return Snapshot()
        val json = Json.parseToJsonElement(snapshotFile.readText()).jsonObject
        val users = json["userStickers"]?.jsonArray.orEmpty().mapNotNull { element ->
            val obj = element.jsonObject
            val id = obj["id"]?.jsonPrimitive?.contentOrNull
            val emotion = obj["emotion"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val fileName = obj["fileName"]?.jsonPrimitive?.contentOrNull
            // 2026-11-17（guardrail F-5）：**fileName 必须校验**。它是磁盘文件名的
            // 唯一来源，而 `stickers.json` 位于可被外部写入的私有目录（例如 adb/备份恢复）。
            // 未校验时 `fileName="../../databases/x"` 会让 [imageFile] 解析到任意文件，
            // 随后 [io.prism.qq.channel.OneBotQqChannel.sendSticker] 会把该文件 base64
            // **外发给 QQ 联系人**——这是数据外泄面，不是本地读取面。
            // `MAX_IMAGE_BYTES` 只在 [importImage] 生效，load/send 路径都不复核，故必须在此收口。
            if (id.isNullOrBlank() || fileName.isNullOrBlank() || !QqStickerStore.isSafeStickerFileName(fileName)) null
            else UserSticker(
                id = id,
                emotion = emotion,
                fileName = fileName,
                // 旧数据无这三个字段 → 取默认值（向后兼容，不迁移、不报错）
                description = obj["description"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                sha1 = obj["sha1"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                lastSentAtMs = obj["lastSentAtMs"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
            )
        }
        val learned = json["learned"]?.jsonArray.orEmpty().mapNotNull { element ->
            val obj = element.jsonObject
            val emojiId = obj["emojiId"]?.jsonPrimitive?.contentOrNull
            val key = obj["key"]?.jsonPrimitive?.contentOrNull
            if (emojiId.isNullOrBlank() || key.isNullOrBlank()) null
            else LearnedMface(
                emojiId = emojiId,
                emojiPackageId = obj["emojiPackageId"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                key = key,
                summary = obj["summary"]?.jsonPrimitive?.contentOrNull.orEmpty()
            )
        }
        Snapshot(
            userStickers = users,
            learned = learned,
            recentSends = json["recentSends"]?.jsonArray.orEmpty()
                .mapNotNull { it.jsonPrimitive.contentOrNull?.toLongOrNull() }
        )
    }.getOrDefault(Snapshot())

    @Synchronized
    private fun persist(next: Snapshot): Boolean = runCatching {
        val json = buildString {
            append("{\"userStickers\":[")
            append(next.userStickers.joinToString(",") { s ->
                "{\"id\":${q(s.id)},\"emotion\":${q(s.emotion)},\"fileName\":${q(s.fileName)}," +
                    "\"description\":${q(s.description)},\"sha1\":${q(s.sha1)}," +
                    "\"lastSentAtMs\":${s.lastSentAtMs}}"
            })
            append("],\"learned\":[")
            append(next.learned.joinToString(",") { m ->
                "{\"emojiId\":${q(m.emojiId)},\"emojiPackageId\":${q(m.emojiPackageId)}," +
                    "\"key\":${q(m.key)},\"summary\":${q(m.summary)}}"
            })
            append("],\"recentSends\":[")
            append(next.recentSends.joinToString(","))
            append("]}")
        }
        // 原子写：先写临时文件再改名，避免写一半被读到（与 QqConversationStore 同范式）
        val tmp = File(dir, "stickers.json.tmp")
        tmp.writeText(json)
        if (snapshotFile.exists()) snapshotFile.delete()
        tmp.renameTo(snapshotFile)
        snapshot = next
        true
    }.getOrDefault(false)

    /** 极简 JSON 字符串转义（内容为本机生成的 id/情绪标签，仍做转义防破坏结构）。 */
    private fun q(value: String): String = "\"" + value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", " ")
        .replace("\r", " ") + "\""

    /** 内容哈希（用于增量打标与导入去重；截断到 16 字符足够）。 */
    internal fun sha1Hex(bytes: ByteArray): String = runCatching {
        val digest = java.security.MessageDigest.getInstance("SHA-1").digest(bytes)
        digest.joinToString("") { "%02x".format(it) }.take(16)
    }.getOrDefault("")

    companion object {
        /**
         * 表情文件名是否安全（2026-11-17；guardrail F-5）。
         *
         * 为什么必须有：`stickers.json` 位于可被外部写入的私有目录（adb / 备份恢复 /
         * 未来的导入通道）。未校验时 `fileName="../../databases/x"` 会让 [imageFile]
         * 解析到任意文件，随后 [io.prism.qq.channel.OneBotQqChannel.sendSticker] 会把该
         * 文件 base64 **外发给 QQ 联系人**——这是数据外泄面。
         *
         * 规则（全部满足才放行）：
         * 1. 不含路径分隔符（`/`、`\`）与 `..`；长度 ≤ 64；
         * 2. 只允许 `[A-Za-z0-9._-]`（拒绝隐藏文件/空格/控制符）；
         * 3. 扩展名在白名单内（本 App 写 `.img`；旧版本可能留 `.png/.jpg/.webp`）。
         *
         * 纯函数，便于单测直接钉住穿越样本。
         */
        fun isSafeStickerFileName(fileName: String): Boolean {
            if (fileName.isBlank() || fileName.length > 64) return false
            if (fileName.contains('/') || fileName.contains('\\') || fileName.contains("..")) return false
            if (!fileName.matches(Regex("[A-Za-z0-9._-]+"))) return false
            val ext = fileName.substringAfterLast('.', "").lowercase()
            return ext in SAFE_STICKER_EXTENSIONS
        }

        /** 单张表情图上限（防导入超大文件）。 */
        const val MAX_IMAGE_BYTES = 2 * 1024 * 1024

        /** 学到的商城表情上限。 */
        const val MAX_LEARNED = 60

        /** 无情绪匹配时，随机使用**用户表情**的概率（百分比）。 */
        const val USER_STICKER_RATIO = 50

        /** 描述长度上限（进 prompt 的字段，必须短）。 */
        const val MAX_DESCRIPTION_CHARS = 80

        /** prompt 中最多列出的表情条数（控 token；20 × 约 30 字 ≈ 600 字）。 */
        const val CATALOG_IN_PROMPT = 20

        /** 发送时间戳保留条数（够算"每小时上限"即可）。 */
        const val MAX_SEND_TIMESTAMPS = 60

        /** 允许的表情文件扩展名（guardrail F-5：防把任意文件当表情外发）。 */
        private val SAFE_STICKER_EXTENSIONS = setOf("img", "png", "jpg", "jpeg", "webp", "gif")
    }
}

/**
 * 内置 QQ 表情映射（情绪标签 → `face` 段 ID）。
 *
 * ID 取自 QQ/CQ 码既有表情表；这里只收录高频、语义稳定的 24 个，
 * 每个情绪取一个最贴切的 ID（用户仍可通过导入表情覆盖）。
 */
object QqStickerCatalog {

    /** 情绪 → face id。 */
    private val EMOTION_TO_FACE: Map<String, String> = linkedMapOf(
        "开心" to "14", "笑" to "13", "大笑" to "12", "微笑" to "14",
        "难过" to "5", "哭" to "9", "委屈" to "9", "生气" to "11",
        "无语" to "27", "汗" to "27", "尴尬" to "10", "惊讶" to "26",
        "疑问" to "32", "思考" to "32", "赞同" to "76", "赞" to "76",
        "加油" to "78", "抱抱" to "49", "亲亲" to "66", "害羞" to "20",
        "撇嘴" to "1", "调皮" to "21", "困" to "25", "晕" to "25"
    )

    /** 情绪标签 → 内置表情 ID（未命中返回 null）。 */
    fun faceIdFor(emotion: String): String? = EMOTION_TO_FACE[emotion.trim()]

    /** 全部内置表情 ID（随机挑选用）。 */
    fun allFaceIds(): List<String> = EMOTION_TO_FACE.values.distinct()

    /** 全部情绪标签（UI 提示用）。 */
    fun emotions(): List<String> = EMOTION_TO_FACE.keys.toList()
}
