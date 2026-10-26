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

    /** 用户导入的表情（id → 文件路径）。 */
    data class UserSticker(val id: String, val emotion: String, val fileName: String)

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
        val learned: List<LearnedMface> = emptyList()
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
     * @param bytes 已解码的图片字节（调用方负责大小/类型校验）
     * @param emotion 情绪标签（可为空字符串）
     * @return 新表情 ID（失败返回 null）
     */
    fun importImage(bytes: ByteArray, emotion: String): String? {
        if (bytes.isEmpty() || bytes.size > MAX_IMAGE_BYTES) return null
        val id = "u" + clock().toString(36) + "_" + (bytes.size % 1000)
        val fileName = "$id.img"
        val file = File(dir, fileName)
        return runCatching {
            file.writeBytes(bytes)
            val next = snapshot.copy(userStickers = snapshot.userStickers + UserSticker(id, emotion, fileName))
            persist(next)
            id
        }.getOrNull()
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
            if (id.isNullOrBlank() || fileName.isNullOrBlank()) null
            else UserSticker(id, emotion, fileName)
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
        Snapshot(users, learned)
    }.getOrDefault(Snapshot())

    @Synchronized
    private fun persist(next: Snapshot): Boolean = runCatching {
        val json = buildString {
            append("{\"userStickers\":[")
            append(next.userStickers.joinToString(",") { s ->
                "{\"id\":${q(s.id)},\"emotion\":${q(s.emotion)},\"fileName\":${q(s.fileName)}}"
            })
            append("],\"learned\":[")
            append(next.learned.joinToString(",") { m ->
                "{\"emojiId\":${q(m.emojiId)},\"emojiPackageId\":${q(m.emojiPackageId)}," +
                    "\"key\":${q(m.key)},\"summary\":${q(m.summary)}}"
            })
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

    companion object {
        /** 单张表情图上限（防导入超大文件）。 */
        const val MAX_IMAGE_BYTES = 2 * 1024 * 1024

        /** 学到的商城表情上限。 */
        const val MAX_LEARNED = 60

        /** 无情绪匹配时，随机使用**用户表情**的概率（百分比）。 */
        const val USER_STICKER_RATIO = 50
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
