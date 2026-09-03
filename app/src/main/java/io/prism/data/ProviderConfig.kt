package io.prism.data

import io.objectbox.annotation.Convert
import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id

/**
 * BYOK Provider 配置实体 —— 定义 AI 服务端点。
 *
 * 每条记录对应一个用户配置的 AI Provider（如 OpenAI、Anthropic、Ollama 等），
 * 供聊天界面切换模型使用。
 *
 * **字段说明**：
 * - [name] Provider 显示名称（如 "OpenAI"、"Anthropic"）
 * - [baseUrl] API 端点基础 URL（如 "https://api.openai.com/v1"）
 * - [apiKeyRef] API Key 引用标识，对应 [io.prism.security.ApiKeyRepository] 中存储的 key
 *   （不存储明文 API Key，仅存储引用，明文由 Keystore 加密保护）
 * - [models] 可用模型名称列表（如 ["gpt-4o", "gpt-4o-mini"]）
 * - [headers] 自定义 HTTP 请求头（如 {"Authorization": "Bearer ..."}，通常由 Provider 自动填充）
 * - [isActive] 是否为当前激活的 Provider（同一时间仅一个激活）
 * - [createdAt] 创建时间戳（毫秒）
 *
 * **类型转换**：
 * - [models] 通过 [StringListConverter] 序列化为 String 存储
 * - [headers] 通过 [StringMapConverter] 序列化为 String 存储
 *
 * US-004 验收标准 1：ProviderConfig 数据类含 name/baseUrl/apiKeyRef/models/headers 字段
 *
 * @see StringListConverter
 * @see StringMapConverter
 */
@Entity
data class ProviderConfig(
    @Id var id: Long = 0,
    var name: String,
    var baseUrl: String,
    var apiKeyRef: String,
    @Convert(converter = StringListConverter::class, dbType = String::class)
    var models: List<String> = emptyList(),
    @Convert(converter = StringMapConverter::class, dbType = String::class)
    var headers: Map<String, String> = emptyMap(),
    var isActive: Boolean = false,
    var createdAt: Long = System.currentTimeMillis(),
    /**
     * v1 US-301（方案 B 云端视觉旁路）：是否为「视觉旁路 Provider」。
     *
     * 当主聊天 Provider 为纯文本模型（图片直传 400 + 视觉不支持信号）时，若用户已配置
     * 视觉旁路 Provider（支持 OpenAI 兼容 image_url 输入 → text 输出的视觉模型，如
     * GLM-4V-Plus / Qwen-VL / GPT-4o），则把图片发往该 Provider 生成文字描述，注入文本
     * 模型上下文回答。
     *
     * **语义**：辅助视觉角色，**不抢占 [isActive]**（聊天主 Provider 仍唯一激活，
     * 规避 ProviderConfigRepository.save 的单激活不变式冲突）。默认 false。
     */
    var isVisionFallback: Boolean = false,
    /**
     * v1 批次13（B/D16b，多模态）：该 Provider 是否支持视觉（多模态图片输入）。
     *
     * **作用**：手机操控 `phone_control__screenshot` 截图时，若当前激活 Provider 支持视觉，
     * 截图**图片**以 image_url 注入会话供模型直接查看（发挥多模态能力，无需 OCR 文本）；
     * 否则走 OCR 文字+坐标（纯文本模型路径）。也用于截图免 OCR（提速）。
     *
     * **判定**：设置页手动开关 + 保存时按模型名自动提示（[detectVisionSupport]）。
     * 默认 false（向后兼容）。
     */
    var supportsVision: Boolean = false,
    /**
     * v1 批次13（B/D16b，多模态）：[supportsVision] 是否已被**显式设置**（用户触碰开关）。
     *
     * **隐私语义**：截图内容会发送到 LLM 端点，属敏感数据外发。当用户**显式**关闭视觉
     * 开关时（supportsVision=false + supportsVisionSet=true），运行时与保存逻辑**必须尊重**，
     * 不得被「按模型名自动检测」覆盖（防隐私静默外发）。旧配置默认 false=未显式设置，
     * 走模型名自动检测兜底（开箱即用）。ObjectBox 新增布尔列自动迁移，旧数据补 false。
     */
    var supportsVisionSet: Boolean = false
) {
    companion object {
        /**
         * 按模型名启发式判断是否支持视觉（v1 批次13 B）：保存 Provider 时自动提示
         * [supportsVision]（用户仍可在设置页手动覆盖）。纯启发式，宁缺勿错——
         * 误判为视觉会把截图图片发给纯文本模型（400 走视觉旁路/OCR 兜底，可降级）。
         */
        fun detectVisionSupport(modelName: String?): Boolean {
            val m = modelName?.trim()?.lowercase() ?: return false
            return VISION_MODEL_PATTERNS.any { m.contains(it) }
        }

        /** 常见视觉模型名模式（小写包含匹配）。 */
        private val VISION_MODEL_PATTERNS = listOf(
            "glm-4v", "glm-4.5v", "glm-4.6v", "glm-4.7v", "glm-4.8v",
            "qwen-vl", "qwen2.5-vl", "qwen3-vl", "qwen2-vl",
            "gemini", "gpt-4o", "gpt-5", "claude", // Claude 3+ 全部支持视觉
            "kimi-k2", "kimi-vl", "step-1v", "llava", "minicpm-v",
            "grok-2v", "grok-4", "o3", "o4", "vision"
        )
    }
}
