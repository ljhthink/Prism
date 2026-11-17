package io.prism.qq

import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * **角色日记**（2026-11-16 新增；ADR-077）。
 *
 * ## 与既有记忆的分工（关键，不要混为一谈）
 *
 * | 层 | 谁写的 | 形态 | 用途 |
 * | --- | --- | --- | --- |
 * | L2 `CrossSessionMemoryManager` | **系统**抽取 | 事实条目 | 跨会话事实召回 |
 * | L3 `UserProfileManager` | **系统**抽取 | 画像键值 | 用户偏好 |
 * | **本类（日记）** | **角色自己** | 第一人称随笔 | 角色感、情绪连续性 |
 *
 * 日记**不是**记忆的替代品，两者并存互不覆盖：记忆回答"知道什么"，日记回答"她怎么想"。
 *
 * ## 设计参考（不自己发明）
 *
 * [`SillyTavern-Plugin-HCDiary`](https://github.com/zhaoyichan/SillyTavern-Plugin-HCDiary)
 * 已生产验证的要点，我们采纳其中三条：
 * 1. **第一人称私密日记**（"活人感"，不是剧情汇报）；
 * 2. **回复后再沉淀**（不在回复链路内，零延迟影响）；
 * 3. **只注入近期若干条**（不把整本日记塞进上下文）。
 *
 * ## 约束
 *
 * - 仅应用私有目录 `files/diaries/<roleId>.json`，明文只在本地；
 * - 每角色上限 [MAX_PER_ROLE] 条（按时间淘汰最旧）；
 * - 每日写入上限 [MAX_PER_DAY] 条（防模型刷日记）；
 * - 原子写（临时文件 + rename），失败不抛异常（日记是增强，不能阻断回复）；
 * - **只读展示 + 可删**：不提供"用户代写"（代写会破坏"她自己写的"这一语义）。
 */
class RoleDiaryStore(
    private val rootDir: File,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    /** 一条日记。 */
    data class Entry(
        val at: Long,
        val text: String,
        /** 情绪/心情标签（可空，模型自由填）。 */
        val mood: String = ""
    )

    private val dir: File = File(rootDir, "diaries").apply { runCatching { mkdirs() } }

    /** 内存缓存（roleId → 时间升序条目），首次访问时读盘。 */
    private val cache = HashMap<String, MutableList<Entry>>()

    /** 读取某角色的全部日记（时间升序）。 */
    @Synchronized
    fun all(roleId: String): List<Entry> = load(roleId).toList()

    /** 读取最近 [limit] 条（时间升序）。 */
    @Synchronized
    fun recent(roleId: String, limit: Int): List<Entry> {
        if (limit <= 0) return emptyList()
        return load(roleId).takeLast(limit)
    }

    /** 今日已写条数（每日上限判据）。 */
    @Synchronized
    fun countToday(roleId: String, nowMs: Long = clock()): Int {
        val today = dayKey(nowMs)
        return load(roleId).count { dayKey(it.at) == today }
    }

    /**
     * 追加一条日记。
     *
     * @return true=已写入；false=被拒（今日额度用尽 / 正文为空 / 超长）
     */
    @Synchronized
    fun append(
        roleId: String,
        text: String,
        mood: String = "",
        nowMs: Long = clock()
    ): Boolean {
        val key = roleId.trim()
        if (key.isEmpty()) return false
        val body = text.trim()
        if (body.isEmpty()) return false
        if (countToday(key, nowMs) >= MAX_PER_DAY) return false
        val list = load(key)
        val entry = Entry(
            at = nowMs,
            text = body.take(MAX_TEXT_CHARS),
            mood = mood.trim().take(MAX_MOOD_CHARS)
        )
        // 2026-11-16（guardrail R2 自查）：**先快照再改内存**。
        // 原实现直接 `list += entry` 后再 persist；persist 失败时内存里已经多了一条，
        // 而调用方拿到 false → 内存与磁盘发散 → 下一次成功写入会把这条"报告失败的日记"
        // 静默落盘（"报了失败数据却还在"）。这里失败即回滚。
        val snapshotBefore = list.toList()
        list += entry
        while (list.size > MAX_PER_ROLE) list.removeAt(0)
        if (persist(key, list)) return true
        // 回滚内存：恢复成写前快照（不污染缓存）
        list.clear()
        list.addAll(snapshotBefore)
        return false
    }

    /** 删除某一条（按写入时间精确匹配；用于设置页"删掉这条"）。 */
    @Synchronized
    fun remove(roleId: String, atMs: Long): Boolean {
        val list = load(roleId)
        val index = list.indexOfFirst { it.at == atMs }
        if (index < 0) return false
        val snapshotBefore = list.toList()
        list.removeAt(index)
        if (persist(roleId, list)) return true
        list.clear()
        list.addAll(snapshotBefore)
        return false
    }

    /** 清空某角色全部日记。 */
    @Synchronized
    fun clear(roleId: String): Boolean {
        val key = roleId.trim().ifEmpty { return false }
        val before = cache[key]?.toList()
        cache[key] = mutableListOf()
        val deleted = runCatching { fileFor(key).delete() }.isSuccess
        if (!deleted) {
            // 删除失败 → 恢复缓存，避免"内存已清空、磁盘还有"（下次读盘又出现）
            if (before != null) cache[key] = before.toMutableList() else cache.remove(key)
        }
        return deleted
    }

    /** 已写日记的角色 id 列表（设置页分组展示）。 */
    @Synchronized
    fun roleIds(): List<String> =
        dir.listFiles()?.filter { it.isFile && it.name.endsWith(".json") }
            ?.map { it.nameWithoutExtension }
            ?.sorted()
            ?: emptyList()

    /**
     * 注入用的上下文片段（最近 [limit] 条）。
     *
     * @return 空列表表示没有日记（调用方不要拼空 section）
     */
    @Synchronized
    fun promptSection(roleId: String, limit: Int = INJECT_RECENT): List<String> =
        recent(roleId, limit).map { entry ->
            val mood = entry.mood.takeIf { it.isNotBlank() }?.let { "（$it）" }.orEmpty()
            "- ${formatDay(entry.at)}$mood：${entry.text}"
        }

    // ==================== 持久化 ====================

    private fun fileFor(roleId: String): File =
        File(dir, safeName(roleId) + ".json")

    private fun load(roleId: String): MutableList<Entry> {
        val key = roleId.trim()
        cache[key]?.let { return it }
        val list = readFile(key).toMutableList()
        cache[key] = list
        return list
    }

    private fun readFile(roleId: String): List<Entry> = runCatching {
        val file = fileFor(roleId)
        if (!file.isFile) return emptyList()
        val root = Json.parseToJsonElement(file.readText())
        val array = when (root) {
            is JsonArray -> root
            is JsonObject -> root["entries"] as? JsonArray ?: return emptyList()
            else -> return emptyList()
        }
        array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val text = obj["text"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            Entry(
                at = obj["at"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
                text = text,
                mood = obj["mood"]?.jsonPrimitive?.contentOrNull.orEmpty()
            )
        }.filter { it.text.isNotBlank() }.sortedBy { it.at }
    }.getOrElse { e ->
        // 2026-11-16（guardrail R2 复核）：**损坏不得静默当成"没有日记"**。
        // 原实现 `getOrDefault(emptyList())` 会让"文件损坏"看起来像"从未写过"，
        // 下一次 append 用单条覆盖 → 整本日记无日志消失。这里把损坏文件改名留证再返回空。
        Log.w(TAG, "日记文件损坏（role=$roleId）：${e::class.simpleName}")
        runCatching {
            fileFor(roleId).renameTo(
                File(dir, fileFor(roleId).name + ".corrupt-" + clock())
            )
        }
        emptyList()
    }

    /**
     * 原子写。
     *
     * **2026-11-16（guardrail M-05）**：原实现 `if (file.exists()) file.delete()` 后
     * `tmp.renameTo(file)` **忽略返回值**、末尾无条件 `true`——重命名失败时旧文件已删、
     * cache 已更新、`append` 返回 true、日志打"已写入" → **整本日记静默丢失还报成功**
     * （正是本批次要防的"部分失败当全部成功"族）。
     *
     * 现改为：
     * 1. 不先删目标——`renameTo` 在同目录同分区上是原子替换；失败时目标文件**仍在**；
     * 2. **检查 renameTo 返回值**，失败则回退为"直接覆盖写"（尽力保住数据）；
     * 3. **只有确认落盘成功才更新 cache 并返回 true**。
     */
    @Synchronized
    private fun persist(roleId: String, entries: List<Entry>): Boolean = runCatching {
        val file = fileFor(roleId)
        val json = buildString {
            append("{\"roleId\":").append(q(roleId)).append(",\"entries\":[")
            append(entries.joinToString(",") { e ->
                "{\"at\":${e.at},\"text\":${q(e.text)},\"mood\":${q(e.mood)}}"
            })
            append("]}")
        }
        val tmp = File(dir, file.name + ".tmp")
        tmp.writeText(json)
        // 2026-11-16（guardrail R2-M 复核）：**用 ATOMIC_MOVE 而不是先删后 rename**。
        // 上一版 renameTo 失败后回退 `file.writeText(json)` 直接覆盖目标 —— 若此时进程被杀，
        // 目标会变成半截 JSON，而 readFile 的 getOrDefault(emptyList()) 会**静默把损坏
        // 当成"没有日记"** → 下次 append 用单条覆盖 → 整本日记无日志消失（比原缺陷更难发现）。
        // minSdk=26 有 java.nio；同目录保证原子性，失败即返回 false（保留 tmp 供排查）。
        val moved = runCatching {
            java.nio.file.Files.move(
                tmp.toPath(),
                file.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE
            )
            true
        }.getOrElse { atomic ->
            // 极端文件系统不支持 ATOMIC_MOVE：退回普通 REPLACE_EXISTING（仍不删目标）
            Log.w(TAG, "ATOMIC_MOVE 不可用（${atomic::class.simpleName}），退回普通 move")
            runCatching {
                java.nio.file.Files.move(
                    tmp.toPath(),
                    file.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING
                )
                true
            }.getOrDefault(false)
        }
        if (!moved) {
            Log.w(TAG, "日记落盘失败（role=$roleId, entries=${entries.size}）：保留 tmp，不更新缓存")
            return false
        }
        cache[roleId] = entries.toMutableList()
        true
    }.getOrElse {
        Log.w(TAG, "日记落盘异常（role=$roleId）：${it::class.simpleName}")
        false
    }

    /** 与 [QqConversationStore] 同范式的转义（含控制字符，防整文件变非法 JSON）。 */
    private fun q(value: String): String {
        val sb = StringBuilder(value.length + 2)
        sb.append('"')
        for (ch in value) {
            when {
                ch == '\\' -> sb.append("\\\\")
                ch == '"' -> sb.append("\\\"")
                ch == '\n' -> sb.append("\\n")
                ch == '\r' -> sb.append("\\r")
                ch == '\t' -> sb.append("\\t")
                ch < ' ' -> sb.append(String.format("\\u%04x", ch.code))
                else -> sb.append(ch)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    /** 文件名安全化（防路径穿越；与 `RolePackLoader.safeFileName` 同思路）。 */
    private fun safeName(raw: String): String {
        val safe = raw.trim().replace(Regex("[^A-Za-z0-9._-]"), "_").trim('_')
        return safe.ifEmpty { "role" }.take(64)
    }

    /**
     * "今天"的键（2026-11-16 自查修复）。
     *
     * 原实现用 `Math.floorDiv(ms, 86_400_000L)`，那是 **UTC 天**。对 UTC+8 用户而言
     * 日界在**北京时间早上 8 点**——"每日 3 条"会在早八点重置，凌晨写满后早上又能写，
     * 与用户感知的"今天"不一致。改为按**本地时区**取日期（`LocalDate`），
     * 夏令时/时区切换由 `atZone` 正确处理。
     */
    private fun dayKey(ms: Long): String = runCatching {
        java.time.Instant.ofEpochMilli(ms)
            .atZone(java.time.ZoneId.systemDefault())
            .toLocalDate()
            .toString()
    }.getOrDefault("")

    /** 可读日期（注入用；不带年份，保持轻量）。 */
    private fun formatDay(ms: Long): String = runCatching {
        java.time.Instant.ofEpochMilli(ms)
            .atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"))
    }.getOrDefault("")

    companion object {
        private const val TAG = "RoleDiaryStore"

        /** 每角色保留条数（约半年日常）。 */
        const val MAX_PER_ROLE = 200

        /** 每日写入上限（防模型刷日记）。 */
        const val MAX_PER_DAY = 3

        /** 单条正文上限。 */
        const val MAX_TEXT_CHARS = 800

        /** 心情标签上限。 */
        const val MAX_MOOD_CHARS = 20

        /** 注入时取最近几条。 */
        const val INJECT_RECENT = 3
    }
}
