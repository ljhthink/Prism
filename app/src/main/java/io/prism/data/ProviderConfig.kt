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
    var createdAt: Long = System.currentTimeMillis()
)
