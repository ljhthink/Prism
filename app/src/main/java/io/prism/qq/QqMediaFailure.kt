package io.prism.qq

import io.prism.qq.channel.QqMediaKind
import io.prism.qq.channel.QqMediaRef

/**
 * 媒体获取的**结构化结果**（2026-11-15 新增；ADR-075 / PRD US-3134）。
 *
 * ## 为什么必须要有它
 *
 * 真机 2026-11-15：用户发非内置表情包，LLM 回「看到个头--1793kb」。
 * 根因是 NapCat 的 rkey 三通道全挂 → 图片 URL 是**硬编码占位 rkey** → 必然 `HTTP 400`；
 * 而旧实现 `catch (_: Exception) { null }` **把失败静默吞掉**，上层只能拿到 `null`，
 * 于是退化成 `QqMediaDescriber` 的一句「…约 1793KB」——模型把元数据当成了画面。
 *
 * 本类型把"失败"变成**可读、可分类、可展示**的一等公民：
 * - 层 A：明确告诉用户/模型"图取不到，原因是 X"，不再让模型瞎猜；
 * - 层 B：当原因是 [Kind.RKEY_EXPIRED] 时，自动改走本地文件回退（图片字节就在手机容器里）。
 */
sealed interface QqMediaResult {

    /** 成功：可直接作为 `image_url` 的 data URL。 */
    data class Success(
        val dataUrl: String,
        val mime: String,
        val bytes: Int,
        /** 数据来源（CDN / 本地文件），用于日志对账。 */
        val source: Source
    ) : QqMediaResult

    /** 失败：带可读原因与分类（**不抛异常**，调用方据此决定文案）。 */
    data class Failure(val kind: Kind, val detail: String) : QqMediaResult

    /** 数据来源。 */
    enum class Source {
        /** QQ CDN 直连（正常路径）。 */
        CDN,

        /** 协议端容器内本地文件回退（rkey 失效时）。 */
        LOCAL_FILE
    }

    /** 失败分类（决定文案与是否值得回退）。 */
    enum class Kind {
        /** 协议端 rkey 失效（URL 带占位 rkey → 400）。**值得走本地回退**。 */
        RKEY_EXPIRED,

        /** 网络不可达 / 超时。**值得走本地回退**（图可能已在容器里）。 */
        NETWORK,

        /** 策略拒绝：非 https / 非白名单域名 / URL 畸形。**不回退**（安全边界）。 */
        POLICY_REJECTED,

        /** 内容不合格：空体 / 超限 / magic 不是图片。**不回退**。 */
        BAD_CONTENT,

        /** 该类型本来就没有画面（语音/视频/内置表情）。 */
        NO_VISUAL
    }
}

/**
 * 媒体失败分类的**纯函数**（2026-11-15；ADR-075）。
 *
 * 关键判据来自真机取证：
 * - NapCat rkey 全挂时，所有图片 URL 形如
 *   `https://gchat.qpic.cn/download?appid=1406&fileid=…&rkey=<同一串>&spec=0`，
 *   且 **rkey 在 4 张不同图片上完全相同**（跨 15 小时）——真实签名不可能如此；
 * - 直连该 URL 稳定返回 `HTTP 400`。
 *
 * 因此"rkey 失效"可被**离线识别**（不需要先失败一次）：同一 host + 同一 rkey 在
 * 本次进程内反复出现，即判定为占位符。这让我们能在**发请求之前**就切换到本地回退，
 * 省掉一次必然失败的 10s 超时。
 */
object QqMediaFailure {

    /** 判定 rkey 失效所需的最少"同 rkey 不同图片"次数（占位符必然大量重复）。 */
    const val RKEY_REPEAT_THRESHOLD = 2

    /** 常见占位/降级 URL 特征：`gchat.qpic.cn` + `&spec=0`（真实签名走 multimedia.nt.qq.com.cn）。 */
    private const val LEGACY_HOST = "gchat.qpic.cn"

    /** 提取 URL 中的 `rkey` 参数（无则 null）。 */
    fun rkeyOf(url: String): String? {
        if (url.isBlank()) return null
        val m = Regex("""[?&]rkey=([^&]+)""").find(url) ?: return null
        return m.groupValues[1].takeIf { it.isNotBlank() }
    }

    /** 提取 host（小写；解析失败返回空串）。 */
    fun hostOf(url: String): String =
        runCatching { java.net.URI(url.trim()).host?.lowercase().orEmpty() }.getOrDefault("")

    /**
     * 该 URL 是否"看起来像 rkey 失效的降级地址"（**离线判据**）。
     *
     * 判据：`gchat.qpic.cn` + 带 `rkey` + 带 `spec=0`。
     * 真实的签名 URL 走 `multimedia.nt.qq.com.cn` 且无 `spec=0`（同类案例实证）。
     */
    fun looksLikeFallbackUrl(url: String): Boolean {
        val host = hostOf(url)
        if (host != LEGACY_HOST) return false
        if (rkeyOf(url) == null) return false
        return url.contains("spec=0")
    }

    /**
     * 依据"同一 rkey 是否被多张图片复用"判定 rkey 失效。
     *
     * @param url 当前图片 URL
     * @param rkeySeenCount 该 rkey 在本进程内已出现的次数（含本次）
     */
    fun isRkeyExpired(url: String, rkeySeenCount: Int): Boolean {
        if (!looksLikeFallbackUrl(url)) return false
        return rkeySeenCount >= RKEY_REPEAT_THRESHOLD
    }

    /** HTTP 状态码 → 失败分类（纯函数）。 */
    fun classifyHttp(status: Int, url: String): QqMediaResult.Kind = when {
        status in 200..299 -> QqMediaResult.Kind.BAD_CONTENT // 走到这里说明体不合格
        // 400/403 且是降级地址 → rkey 失效；否则按网络/对端拒绝处理
        status == 400 || status == 403 ->
            if (looksLikeFallbackUrl(url)) QqMediaResult.Kind.RKEY_EXPIRED
            else QqMediaResult.Kind.NETWORK
        status == 401 || status == 404 || status == 410 -> QqMediaResult.Kind.RKEY_EXPIRED
        status >= 500 -> QqMediaResult.Kind.NETWORK
        else -> QqMediaResult.Kind.NETWORK
    }

    /** 该失败类别是否值得尝试"本地文件回退"。 */
    fun shouldTryLocalFallback(kind: QqMediaResult.Kind): Boolean = when (kind) {
        QqMediaResult.Kind.RKEY_EXPIRED, QqMediaResult.Kind.NETWORK -> true
        QqMediaResult.Kind.POLICY_REJECTED,
        QqMediaResult.Kind.BAD_CONTENT,
        QqMediaResult.Kind.NO_VISUAL -> false
    }

    // ==================== 对用户的文案 ====================

    /**
     * 给**模型**看的一句话说明（拼进 user 文本）。
     *
     * 关键：**绝不能**只给一个"约 N KB"。真机教训：模型会把元数据当成画面内容来回答
     * （"看到个头--1793kb"）。这里必须显式说"你看不到画面，因为 X"。
     */
    fun noteFor(kind: QqMediaResult.Kind): String = when (kind) {
        QqMediaResult.Kind.RKEY_EXPIRED ->
            "图片无法获取：协议端的图片签名（rkey）已失效，下载被服务器拒绝。" +
                "你**看不到画面**，请勿描述画面内容，可据实说\"图我这边加载不出来\"。"
        QqMediaResult.Kind.NETWORK ->
            "图片无法获取：下载超时或网络不可达。你**看不到画面**，请勿描述画面内容。"
        QqMediaResult.Kind.POLICY_REJECTED ->
            "图片无法获取：来源不在允许范围内（安全策略拒绝）。你**看不到画面**。"
        QqMediaResult.Kind.BAD_CONTENT ->
            "图片无法获取：返回内容不是有效图片。你**看不到画面**。"
        QqMediaResult.Kind.NO_VISUAL ->
            "该消息内容没有画面可看。"
    }

    /** 给**设置页/诊断**看的一行结论（不含 URL/token）。 */
    fun diagnosticFor(kind: QqMediaResult.Kind): String = when (kind) {
        QqMediaResult.Kind.RKEY_EXPIRED ->
            "图片获取异常：协议端 rkey 失效（图片签名服务不可用）"
        QqMediaResult.Kind.NETWORK -> "图片获取异常：网络不可达或超时"
        QqMediaResult.Kind.POLICY_REJECTED -> "图片获取异常：来源被安全策略拒绝"
        QqMediaResult.Kind.BAD_CONTENT -> "图片获取异常：内容不是有效图片"
        QqMediaResult.Kind.NO_VISUAL -> "该消息无可视内容"
    }

    /** 该媒体是否本来就有画面（只有 IMAGE/STICKER 有）。 */
    fun hasVisual(ref: QqMediaRef): Boolean =
        ref.kind == QqMediaKind.IMAGE || ref.kind == QqMediaKind.STICKER
}
