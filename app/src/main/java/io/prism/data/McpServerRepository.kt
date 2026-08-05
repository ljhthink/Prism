package io.prism.data

import io.objectbox.Box
import io.objectbox.BoxStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * MCP Server 配置仓库 —— 管理 [McpServerConfig] 的 CRUD 操作。
 *
 * **架构**（仿 [ProviderConfigRepository]，ADR-005 5.2）：
 * - [BoxStore] 提供 ObjectBox 持久化
 * - [box] 延迟初始化的 [McpServerConfig] Box
 * - [servers] 暴露全部 Server 配置列表，供 UI 订阅
 *
 * **激活机制**：MCP 允许多 Server 并存，**不需要**单激活不变式
 * （与 [ProviderConfigRepository] 的单激活不同）。每个 Server 独立 [McpServerConfig.isEnabled]。
 *
 * ADR-005 5.2：数据层模式仿 US-004 ProviderConfigRepository。
 */
class McpServerRepository(private val boxStore: BoxStore) {

    private val box: Box<McpServerConfig> = boxStore.boxFor(McpServerConfig::class.java)

    private val _servers = MutableStateFlow<List<McpServerConfig>>(emptyList())
    /** 全部 MCP Server 配置列表（按 createdAt 升序），供 UI 订阅 */
    val servers: StateFlow<List<McpServerConfig>> = _servers.asStateFlow()

    init {
        refreshFlows()
    }

    /**
     * 保存或更新 MCP Server 配置。
     *
     * @param config 要保存的配置（id=0 为新建，id>0 为更新）
     * @return 保存后的 id
     */
    fun save(config: McpServerConfig): Long {
        val id = box.put(config)
        refreshFlows()
        return id
    }

    /**
     * 按 id 获取 MCP Server 配置。
     *
     * @param id McpServerConfig id
     * @return 配置对象，不存在返回 null
     */
    fun get(id: Long): McpServerConfig? = box.get(id)

    /**
     * 获取全部 MCP Server 配置。
     *
     * @return 配置列表（按 createdAt 升序）
     */
    fun getAll(): List<McpServerConfig> = box.all.sortedBy { it.createdAt }

    /**
     * 按名称查找 MCP Server 配置。
     *
     * @param name Server 名称（精确匹配）
     * @return 匹配的配置，未找到返回 null
     */
    fun findByName(name: String): McpServerConfig? =
        box.all.find { it.name == name }

    /**
     * 删除指定 id 的 MCP Server 配置。
     *
     * @param id McpServerConfig id
     */
    fun remove(id: Long) {
        box.remove(id)
        refreshFlows()
    }

    /**
     * 删除所有 MCP Server 配置。
     */
    fun removeAll() {
        box.removeAll()
        refreshFlows()
    }

    /**
     * 设置启用的 MCP Server。
     *
     * @param id McpServerConfig id
     * @param enabled 是否启用
     */
    fun setEnabled(id: Long, enabled: Boolean) {
        box.get(id)?.let { config ->
            config.isEnabled = enabled
            box.put(config)
            refreshFlows()
        }
    }

    /**
     * 从预设模板创建 MCP Server 配置。
     *
     * @param preset 预设模板
     * @return 新建的配置 id
     */
    fun createFromPreset(preset: McpServerConfig): Long {
        val config = McpServerConfig(
            name = preset.name,
            serverType = preset.serverType,
            transport = preset.transport,
            baseUrl = preset.baseUrl,
            apiKeyRef = preset.apiKeyRef,
            headers = preset.headers
        )
        return save(config)
    }

    /**
     * 刷新 MCP Server 列表的 Flow。
     */
    private fun refreshFlows() {
        _servers.value = box.all.sortedBy { it.createdAt }
    }
}