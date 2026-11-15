package io.prism.qq

import io.prism.qq.channel.QqMediaKind
import io.prism.qq.channel.QqMediaRef

/**
 * 入站媒体的**可读描述**（2026-11-15 新增；PRD US-3131）。
 *
 * ## 为什么必须单独做
 *
 * 真机反馈：「LLM 看不到用户发的表情」。取证发现两个叠加原因：
 * 1. `autoReplyLocked` 只挑 `IMAGE` / `STICKER` 处理，**`face`（QQ 内置表情）被整条丢弃**——
 *    模型只从 `extractTextWithPlaceholder` 拿到 `[表情]` 三个字，既不知道是哪个表情，
 *    也不知道情绪，于是回答显得"没接住"；
 * 2. 表情包（`mface`/`image sub_type=1`）的 CDN 链接会过期，下载失败时原实现只说
 *    "图片内容未能获取"，把已经拿到的 `summary`（如 `[动画表情]`）也浪费了。
 *
 * ## 设计
 *
 * 纯函数、零网络：只把消息段里**已有**的信息翻译成人话。
 * 图片的视觉/OCR 增强仍由调用方负责，本类只保证"至少说清楚对方发了什么"。
 */
object QqMediaDescriber {

    /** 常见 QQ face id → 情绪（反向查 [QqStickerCatalog] 的映射，并补齐高频项）。 */
    private val FACE_ID_TO_EMOTION: Map<String, String> by lazy {
        val reversed = linkedMapOf<String, String>()
        // 先用目录里的稳定映射（情绪→id 反查），同名 id 取第一个情绪标签
        QqStickerCatalog.allFaceIds().forEach { id ->
            val emotion = QqStickerCatalog.emotions().firstOrNull { QqStickerCatalog.faceIdFor(it) == id }
            if (emotion != null) reversed[id] = emotion
        }
        // 补齐 QQ 经典表情里目录没覆盖的高频 id（只写确定语义的）
        reversed.putIfAbsent("178", "捂脸")
        reversed.putIfAbsent("182", "哭")
        reversed.putIfAbsent("179", "doge")
        reversed.putIfAbsent("264", "偷笑")
        reversed.putIfAbsent("307", "裂开")
        reversed
    }

    /** face id → 情绪标签（未知返回 null）。 */
    fun emotionOfFace(faceId: String?): String? =
        faceId?.trim()?.takeIf { it.isNotEmpty() }?.let { FACE_ID_TO_EMOTION[it] }

    /**
     * 把媒体列表描述成一句/多句可读文本（无媒体返回 null）。
     *
     * 每条媒体都会出现在结果里——**不允许静默丢弃**（这正是本类要修的缺陷）。
     */
    fun describe(media: List<QqMediaRef>): String? {
        if (media.isEmpty()) return null
        return media.joinToString("\n") { ref -> describeOne(ref) }
    }

    private fun describeOne(ref: QqMediaRef): String = when (ref.kind) {
        QqMediaKind.FACE -> {
            val emotion = emotionOfFace(ref.summary)
            when {
                emotion != null -> "对方发来 QQ 表情：$emotion"
                !ref.summary.isNullOrBlank() -> "对方发来 QQ 表情（id=${ref.summary}）"
                else -> "对方发来一个 QQ 表情"
            }
        }

        QqMediaKind.STICKER -> buildString {
            append("对方发来一个表情包")
            // summary 是协议端给的可读摘要（如 `[动画表情]`），下载失败时更不能丢
            ref.summary?.takeIf { it.isNotBlank() }?.let { append("（$it）") }
            if (ref.fileSize > 0) append("，约 ${ref.fileSize / 1024}KB")
        }

        QqMediaKind.IMAGE -> buildString {
            append("对方发来一张图片")
            ref.summary?.takeIf { it.isNotBlank() && !it.contains("表情") }
                ?.let { append("（$it）") }
            if (ref.fileSize > 0) append("，约 ${ref.fileSize / 1024}KB")
        }

        QqMediaKind.VOICE -> buildString {
            append("对方发来一条语音")
            if (ref.fileSize > 0) append("，约 ${ref.fileSize / 1024}KB")
        }

        QqMediaKind.VIDEO -> buildString {
            append("对方发来一段视频")
            if (ref.fileSize > 0) append("，约 ${ref.fileSize / 1024}KB")
        }
    }
}
