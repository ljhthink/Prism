package io.prism.qq

import io.prism.qq.channel.QqMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * 入站消息**持久化收件箱**（2026-11-02 新增；ADR-071 / PRD US-3129）。
 *
 * **为什么必须有**（真机取证 2026-11-02）：
 * 1. 19:49 Termux 被杀 → 整链死亡；20:23 用户发的「在吗」在链恢复后**永远不会被回复**——
 *    协议端不缓存无人接收的事件，Prism 侧也没有任何补偿入口。
 * 2. 应答是"一次尝试"：LLM 超时/5xx/发送失败后该消息直接消失，没有任何重试。
 * 3. Prism 进程被系统回收后，进程内的待处理消息一并丢失。
 *
 * 本类只解决"**收到过、还没处理好**"这一段：消息先落盘，处理成功才删除；失败按退避重试。
 * 断连期间的消息由 [QqCatchUp] 在重连后从协议端历史补回，再进入本收件箱。
 *
 * 约束（对齐 [QqConversationStore]）：
 * - 仅应用私有目录；原子写（临时文件 + rename）；
 * - 条数上限 [MAX_ITEMS]，超出淘汰最旧；
 * - 写失败不抛异常（收件箱是可靠性增强，不能阻断在线回复）；
 * - **持久化媒体元数据**（2026-11-15 修订；ADR-074）：`kind/url/fileName/fileSize/summary`
 *   随条目落盘，重建 `QqMessage` 时恢复——否则视觉/OCR 会整条失效（真机「非内置表情包看不见」）。
 *   只存**元数据不存图片字节**：URL 的 rkey 有时效，重放失败时 `summary` 仍可如实说明。
 */
class QqInboxStore(
    private val rootDir: File,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    /** 一条待处理消息。 */
    data class Item(
        val id: String,
        val text: String,
        val atMs: Long,
        val attempts: Int = 0,
        val nextAttemptAtMs: Long = 0L,
        val lastError: String = "",
        /**
         * 入站媒体快照（2026-11-15 新增；PRD US-3132）。
         *
         * **为什么必须有**：真机反馈「非内置表情包看不见了」。根因是收件箱只持久化
         * `text`，重建 `QqMessage` 时 `media` 为空 → 回复链路拿不到图片 URL →
         * 视觉/OCR 整条失效（只能给出"对方发来了表情包"的文字说明）。
         *
         * 存的字段刻意最小化：`kind/url/fileName/fileSize/summary`。
         * URL 的 rkey 有时效，重启后可能下载失败，但 `summary` 仍可用于说明。
         */
        val media: List<MediaSnapshot> = emptyList()
    )

    /** 媒体快照（可序列化，见 [Item.media]）。 */
    data class MediaSnapshot(
        val kind: String,
        val url: String? = null,
        val fileName: String? = null,
        val fileSize: Long = 0L,
        val summary: String? = null
    )

    private val file = File(rootDir, FILE_NAME)
    private val lock = Any()

    /** id → 条目（插入序） */
    private val items = LinkedHashMap<String, Item>()

    /** 已处理到的消息时间（用于重连补发的时间下界）。 */
    private var lastSeenAtMs: Long = 0L

    init {
        synchronized(lock) { load() }
    }

    /** 重连补发的时间下界。0=从未记录（首次运行，不补发历史）。 */
    fun lastSeenAtMs(): Long = synchronized(lock) { lastSeenAtMs }

    /**
     * 推进"已看到"时间（只增不减）。
     *
     * 收到事件、拉到历史、以及补发完成后都应调用。
     */
    fun markSeen(atMs: Long) {
        if (atMs <= 0L) return
        synchronized(lock) {
            if (atMs <= lastSeenAtMs) return
            lastSeenAtMs = atMs
            persist()
        }
    }

    /**
     * 入队（幂等：同 `id` 只保留一条）。
     *
     * 无 `message_id` 的消息（无障碍通道）用「文本指纹 + 时间」合成 id，保证重启后仍可去重。
     *
     * @return true=实际新增
     */
    fun enqueue(message: QqMessage): Boolean {
        if (!message.incoming) return false
        val id = idOf(message) ?: return false
        synchronized(lock) {
            if (items.containsKey(id)) return false
            if (items.size >= MAX_ITEMS) {
                // guardrail R3-02：淘汰最旧条目时**不推进水位**（水位只由 catch-up 的
                // advanceWatermark 显式推进），否则被淘汰的那条会被水位永久跨过。
                items.entries.firstOrNull()?.let { items.remove(it.key) }
            }
            items[id] = Item(
                id = id,
                // 无正文（纯媒体）时给占位符，保证重放时仍能触发一次应答
                text = message.text.take(MAX_TEXT_CHARS).ifBlank { "[媒体消息]" },
                atMs = if (message.timestampMs > 0) message.timestampMs else clock(),
                // 2026-11-15（US-3132）：**必须持久化 media**，否则重建消息时
                // 图片/表情包会被退化成纯文本（真机「非内置表情包看不见」的根因）。
                media = message.media.map { it.toSnapshot() }
            )
            // 注意：这里**不再**顺带推进 `lastSeenAtMs`（guardrail R2-03/R3-01）。
            // 水位只能由 `markSeen` / `advanceWatermark` 显式推进，否则 catch-up 里
            // "逐条 enqueue"会把水位一路推到最新，使"未着落就封顶"的保护失效。
            persist()
            return true
        }
    }

    /** 到期待处理（按消息时间升序；不修改存储）。诊断/测试用，**生产取件必须走 [claimDue]**。 */
    fun due(nowMs: Long): List<Item> = synchronized(lock) {
        evictStale(nowMs)
        items.values.filter { it.nextAttemptAtMs <= nowMs }.sortedBy { it.atMs }
    }

    /**
     * **原子取件**（guardrail R-03，2026-11-02）：取到的条目立刻置为「处理中」，
     * 在本轮结束前其它消费者（事件 collect / 15s worker）拿不到它们。
     *
     * 为什么必须有：`due()` 只是读快照，两条协程会同时拿到同一批 item →
     * **双重 LLM 生成 + 双重记账**（replyMutex 只串行化，不防重复取件）。
     * 双发虽被去重兜底，但首次判 HOLD（节流）时尚未写 `replied`，缝隙里就会真双发。
     *
     * 崩溃安全：处理中断（进程被杀）时条目停在「处理中」，[CLAIM_TTL_MS] 后自动重新可取。
     */
    fun claimDue(nowMs: Long, limit: Int = MAX_PER_ROUND): List<Item> = synchronized(lock) {
        evictStale(nowMs)
        val picked = items.values
            .filter { it.nextAttemptAtMs <= nowMs }
            .sortedBy { it.atMs }
            .take(limit)
        if (picked.isEmpty()) return emptyList()
        picked.forEach { item ->
            items[item.id] = item.copy(nextAttemptAtMs = nowMs + CLAIM_TTL_MS)
        }
        persist()
        picked
    }

    /** 待处理条数（UI/诊断用）。 */
    fun size(): Int = synchronized(lock) { items.size }

    /**
     * **启动时回收陈旧 claim**（2026-11-16 新增；guardrail R2-M-01）。
     *
     * 为什么必须有：`claimDue` 会把 `nextAttemptAtMs = now + CLAIM_TTL_MS` **持久化**。
     * 进程被杀/崩溃后重启，这些"处理中"条目要等满整段租约才能再被领取。ADR-077 把
     * 租约由 120s 提到 300s（覆盖"两轮排队"以消除双回复），代价是**崩溃重启后最长
     * 5 分钟不回复**——与 US-3129 AC-4「恢复后 ≤60s」直接冲突。
     *
     * 单进程应用：启动时**不可能有别的实例正在处理**，故可安全地把所有"未来才可取"的
     * 条目前移到 `nowMs`。重试退避（`retryLater`）同样被前移是**有意的**——启动是
     * 一次全新的处理机会，且 `attempts` 仍在，超额会照常放弃。
     *
     * @return 被回收的条目数（供启动日志）
     */
    fun reclaimStaleClaims(nowMs: Long): Int = synchronized(lock) {
        var reclaimed = 0
        items.entries.forEach { (id, item) ->
            if (item.nextAttemptAtMs > nowMs) {
                items[id] = item.copy(nextAttemptAtMs = nowMs)
                reclaimed++
            }
        }
        if (reclaimed > 0) persist()
        reclaimed
    }

    /** 处理成功：移除。 */
    fun done(id: String) {
        synchronized(lock) {
            if (items.remove(id) != null) persist()
        }
    }

    /** 暂时不动（如通道离线）：保持原样，等下一轮。 */
    fun hold(id: String, untilMs: Long) {
        synchronized(lock) {
            val item = items[id] ?: return
            items[id] = item.copy(nextAttemptAtMs = untilMs)
            persist()
        }
    }

    /**
     * 失败：次数 +1，按 [QqInboxPolicy.backoffMs] 安排下一次。
     *
     * 超过 [QqInboxPolicy.MAX_ATTEMPTS] 后放弃（移除并保留原因给诊断日志）。
     *
     * @return true=已放弃（调用方应记一条诊断）
     */
    fun retryLater(id: String, error: String, nowMs: Long): Boolean {
        synchronized(lock) {
            val item = items[id] ?: return false
            val attempts = item.attempts + 1
            if (attempts > QqInboxPolicy.MAX_ATTEMPTS) {
                items.remove(id)
                persist()
                return true
            }
            items[id] = item.copy(
                attempts = attempts,
                nextAttemptAtMs = nowMs + QqInboxPolicy.backoffMs(attempts),
                lastError = error.take(120)
            )
            persist()
            return false
        }
    }

    /** 清空（设置页"清空会话记录"时一并清）。 */
    fun clear() {
        synchronized(lock) {
            items.clear()
            runCatching { file.delete() }
        }
    }

    /** 诊断用快照（不含正文）。 */
    fun snapshot(): List<String> = synchronized(lock) {
        items.values.map { "${it.id.take(12)} attempts=${it.attempts} err=${it.lastError}" }
    }

    // ==================== 内部 ====================

    /** 淘汰超龄条目（挂起可能无限延长；没有年龄上限时收件箱会一直保留"昨天的消息"）。 */
    private fun evictStale(nowMs: Long) {
        val stale = items.values.filter { nowMs - it.atMs > MAX_AGE_MS }.map { it.id }
        if (stale.isEmpty()) return
        stale.forEach { items.remove(it) }
        persist()
    }

    private fun idOf(message: QqMessage): String? = idFor(message)

    /** `QqMediaRef` → 可持久化快照（2026-11-15；US-3132）。 */
    private fun io.prism.qq.channel.QqMediaRef.toSnapshot(): MediaSnapshot = MediaSnapshot(
        kind = kind.name, url = url, fileName = fileName,
        fileSize = fileSize, summary = summary
    )

    private fun load() {
        runCatching {
            if (!file.isFile) return@runCatching
            val root = Json.parseToJsonElement(file.readText()).jsonObject
            lastSeenAtMs = root["lastSeenAt"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
            val arr = root["items"]?.jsonArray ?: return@runCatching
            arr.forEach { element ->
                val obj = element as? JsonObject ?: return@forEach
                val id = obj["i"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                val text = obj["t"]?.jsonPrimitive?.contentOrNull ?: ""
                items[id] = Item(
                    id = id,
                    text = text,
                    atMs = obj["a"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
                    attempts = obj["n"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
                    nextAttemptAtMs = obj["x"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
                    lastError = obj["e"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    // 2026-11-15（US-3132）：旧数据无 `m` 字段 → 空列表（向后兼容）
                    media = (obj["m"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { m ->
                        val mo = m as? JsonObject ?: return@mapNotNull null
                        val kind = mo["k"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                        MediaSnapshot(
                            kind = kind,
                            url = mo["u"]?.jsonPrimitive?.contentOrNull,
                            fileName = mo["f"]?.jsonPrimitive?.contentOrNull,
                            fileSize = mo["s"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
                            summary = mo["y"]?.jsonPrimitive?.contentOrNull
                        )
                    }.orEmpty()
                )
            }
        }
    }

    private fun persist() = runCatching {
        val json = buildString {
            append("{\"lastSeenAt\":").append(lastSeenAtMs).append(",\"items\":[")
            append(
                items.values.joinToString(",") { item ->
                    "{\"i\":${q(item.id)},\"t\":${q(item.text)},\"a\":${item.atMs}," +
                        "\"n\":${item.attempts},\"x\":${item.nextAttemptAtMs}," +
                        "\"e\":${q(item.lastError)},\"m\":[" +
                        item.media.joinToString(",") { m ->
                            "{\"k\":${q(m.kind)},\"u\":${q(m.url.orEmpty())}," +
                                "\"f\":${q(m.fileName.orEmpty())},\"s\":${m.fileSize}," +
                                "\"y\":${q(m.summary.orEmpty())}}"
                        } + "]}"
                }
            )
            append("]}")
        }
        val tmp = File(rootDir, "$FILE_NAME.tmp")
        tmp.writeText(json)
        // guardrail R-06：delete→renameTo 不是原子操作，中间崩溃会**同时丢掉整箱与水位**。
        // 改用 Files.move(ATOMIC_MOVE)：失败再退回 renameTo（目标已存在时先删）。
        val moved = runCatching {
            java.nio.file.Files.move(
                tmp.toPath(),
                file.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE
            )
        }.isSuccess
        if (!moved) {
            if (file.exists()) file.delete()
            tmp.renameTo(file)
        }
    }

    /**
     * JSON 字符串转义（guardrail R-07）。
     *
     * 旧实现只处理 `\` `"` `\n` `\r`，**控制字符（\t、\u0000-\u001f）原样写入** →
     * 整个文件变成非法 JSON → 下次启动解析失败 → 收件箱与水位**静默清空**（数据丢失）。
     */
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

    companion object {
        const val FILE_NAME = "qq_inbox.json"

        /** 收件箱上限（防无限增长；按真实节奏远达不到，正常在线时几乎为空）。 */
        const val MAX_ITEMS = 500

        /** 单条最长保留时间（超过即淘汰，与补发窗口语义对齐）。 */
        const val MAX_AGE_MS = 2 * 60 * 60 * 1000L

        /** 单轮最多处理条数（防一次重连把风控/费用打爆）。 */
        const val MAX_PER_ROUND = 5

        /**
         * 单批合并条数（2026-11-15 新增；ADR-076）。
         *
         * 对方连发时一次生成覆盖整批（真人不会逐条点答）；上限 4 是为了一次回复
         * 不至于被 8 条无关内容稀释，同时保证租约（[CLAIM_TTL_MS]）远大于一次生成耗时。
         */
        const val BURST_LIMIT = 4

        /**
         * 「处理中」租约：进程中途被杀后，条目在该时长后自动重新可取。
         *
         * **2026-11-16（guardrail M-07）：120s → 300s**。
         * 最坏单轮临界区（`replyMutex` 内）实测量级：
         * 生成 ≤10s + 工具回路预算 15s + 拟人节奏 ≤25s + 气泡间隔 ≤8s ≈ **58s**。
         * 事件流与 15s worker 共用同一把锁，且**claim 发生在等锁之前**——两条协程排队
         * 即可超过 120s → 租约过期 → 二次领取 → **双回复**（与 ADR-076 R2-02 同族）。
         * 300s 覆盖"两轮排队 + 余量"；代价仅是进程被杀后最晚 5 分钟才重取（可接受：
         * 崩溃恢复本就是低频路径，而双回复是用户可见的严重缺陷）。
         */
        const val CLAIM_TTL_MS = 300_000L

        /** 单条正文上限。 */
        const val MAX_TEXT_CHARS = 2_000

        /**
         * 消息 → 收件箱 id（纯函数）。
         *
         * 有协议端 `message_id` 时直接用；否则用「文本或媒体指纹 + 时间」合成。
         * 2026-11-02（F-4 修正）：纯图片/表情/语音消息（无文本）也必须能入队——
         * 旧实现要求 text 非空，等于把"只发表情"的消息整条丢弃（用户感知为"不回"）。
         *
         * 暴露为公共函数的原因：`PrismApplication` 需要用**同一个 id** 把实时事件原文
         * （含 media）暂存在内存里，处理时优先用它，避免图片消息退化成 `[媒体消息]`。
         */
        fun idFor(message: QqMessage): String? {
            message.messageId?.takeIf { it.isNotBlank() }?.let { return it }
            val textKey = if (message.text.isBlank()) {
                "media" + message.media.joinToString("|") { it.kind.name + (it.summary ?: "") }
            } else {
                message.text
            }
            if (textKey.isBlank() || (message.text.isBlank() && message.media.isEmpty())) return null
            return "h${textKey.hashCode()}-${message.timestampMs}"
        }
    }
}

/**
 * 收件箱的**重试与结果判定**（纯函数，便于 JVM 单测）。
 *
 * 三态来自 [QqReplyOutcome]：`SENT` 完成、`FAILED` 重试、`SKIPPED` 视原因分流。
 * 关键区分：**通道不可用/账号离线不是消息本身的问题**，必须"挂起"而不是"重试消耗次数"，
 * 否则断链几分钟就会把消息的重试预算烧光（这正是"断线后再也不回"的机制之一）。
 */
object QqInboxPolicy {

    /** 最多尝试次数（含首次）。 */
    const val MAX_ATTEMPTS = 3

    /** 重试退避：30s → 2min → 10min。 */
    fun backoffMs(attempt: Int): Long = when (attempt) {
        1 -> 30_000L
        2 -> 120_000L
        else -> 600_000L
    }

    /** 通道类跳过：挂起，不计次数。 */
    private val HOLD_PATTERNS = listOf(
        "等待协议端事件", "QQ 已离线", "未读取到会话消息", "协议端", "熄灭", "显示面",
        // 2026-11-02（F-5 修正）：节流跳过**不能当完成**。旧实现把"节流：…"归为跳过即终局，
        // 而被动节流是 3s 最小间隔 / 60s 窗口，稍等即可，丢弃等于用户消息永久不回。
        "节流", "间隔过短", "过于频繁", "已熔断",
        // guardrail R-05：未配置目标联系人属**可修复的配置缺失**，不是"已处理"。
        // 旧实现把它归 DONE，等于配置好后那条消息也永远不会再被回复。
        "未配置目标联系人", "无激活的 LLM Provider", "API Key 不可用"
    )

    /**
     * 节流类挂起的时长（2026-11-15 新增；ADR-076）。
     *
     * **真机根因**：`QqThrottle.PASSIVE_MIN_GAP_MS` 只有 **3s**（防同一条消息双发），
     * 而挂起统一用 60s → 只差 3 秒就能发，却排到 60 秒后；这 60 秒里新消息继续进队，
     * 回复于是永远落后一条（真机实测 21:50:37 回的是 21:49:12 的消息）。
     * 这里按原因分流：**间隔过短 → 4s 短重试**；通道/环境类保持 60s。
     */
    fun holdMs(reason: String): Long =
        if (reason.contains("间隔过短")) THROTTLE_HOLD_MS else CHANNEL_HOLD_MS

    /** 节流短重试（略大于 [io.prism.qq.QqThrottle.PASSIVE_MIN_GAP_MS] 的 3s）。 */
    const val THROTTLE_HOLD_MS = 4_000L

    /** 通道/环境类挂起。 */
    const val CHANNEL_HOLD_MS = 60_000L

    /** 消息已处理过：直接完成。 */
    private val DONE_PATTERNS = listOf("已回应过", "该消息已回应过", "最后一条为自己发出")

    enum class Decision { DONE, RETRY, HOLD }

    fun decide(kind: QqReplyOutcome.Kind, reason: String): Decision = when (kind) {
        QqReplyOutcome.Kind.SENT -> Decision.DONE
        QqReplyOutcome.Kind.SKIPPED -> when {
            DONE_PATTERNS.any { reason.contains(it) } -> Decision.DONE
            HOLD_PATTERNS.any { reason.contains(it) } -> Decision.HOLD
            // 其余跳过（无激活 Provider/未解析目标等罕见分支）：按完成处理，避免无限重放
            else -> Decision.DONE
        }
        QqReplyOutcome.Kind.FAILED -> Decision.RETRY
    }
}
