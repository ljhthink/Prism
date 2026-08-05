package io.prism.data

/**
 * 预设 Provider 模板 —— 5 种常用 AI 服务端点。
 *
 * 用户可基于这些模板一键创建 ProviderConfig，填入 API Key 后即可使用。
 * 模板中的 [ProviderConfig.apiKeyRef] 使用 Provider 标识符（如 "openai"），
 * 对应 [io.prism.security.ApiKeyRepository] 中存储的加密 API Key。
 *
 * US-004 验收标准 2：支持预设 5 种 Provider：
 * OpenAI 兼容 / Anthropic / Ollama / Moonshot / OpenRouter
 */
object ProviderPresets {

    /** OpenAI 兼容端点（也兼容其他 OpenAI API 格式的服务） */
    val openai = ProviderConfig(
        name = "OpenAI",
        baseUrl = "https://api.openai.com/v1",
        apiKeyRef = "openai",
        models = listOf("gpt-4o", "gpt-4o-mini", "gpt-4-turbo", "gpt-3.5-turbo"),
        headers = emptyMap()
    )

    /** Anthropic Claude 端点 */
    val anthropic = ProviderConfig(
        name = "Anthropic",
        baseUrl = "https://api.anthropic.com/v1",
        apiKeyRef = "anthropic",
        models = listOf("claude-3-5-sonnet", "claude-3-opus", "claude-3-sonnet", "claude-3-haiku"),
        headers = emptyMap()
    )

    /** Ollama 本地端点（无需 API Key） */
    val ollama = ProviderConfig(
        name = "Ollama",
        baseUrl = "http://localhost:11434",
        apiKeyRef = "ollama",
        models = listOf("llama3.1", "qwen2.5", "mistral"),
        headers = emptyMap()
    )

    /** Moonshot Kimi 端点 */
    val moonshot = ProviderConfig(
        name = "Moonshot",
        baseUrl = "https://api.moonshot.cn/v1",
        apiKeyRef = "moonshot",
        models = listOf("moonshot-v1-8k", "moonshot-v1-32k", "moonshot-v1-128k"),
        headers = emptyMap()
    )

    /** OpenRouter 聚合端点（可访问多种模型） */
    val openRouter = ProviderConfig(
        name = "OpenRouter",
        baseUrl = "https://openrouter.ai/api/v1",
        apiKeyRef = "openrouter",
        models = listOf("openrouter/auto", "openai/gpt-4o", "anthropic/claude-3.5-sonnet"),
        headers = emptyMap()
    )

    /** 全部预设模板列表 */
    val all: List<ProviderConfig> = listOf(openai, anthropic, ollama, moonshot, openRouter)

    /**
     * 按名称查找预设模板。
     *
     * @param name Provider 名称（不区分大小写）
     * @return 匹配的预设模板，未找到返回 null
     */
    fun findByName(name: String): ProviderConfig? =
        all.find { it.name.equals(name, ignoreCase = true) }
}
