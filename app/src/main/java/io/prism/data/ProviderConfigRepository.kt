package io.prism.data

import io.objectbox.Box
import io.objectbox.BoxStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Provider 配置仓库 —— 管理 [ProviderConfig] 的 CRUD 操作。
 *
 * **架构**：
 * - [BoxStore] 提供 ObjectBox 持久化
 * - [box] 延迟初始化的 [ProviderConfig] Box
 * - [activeProviderFlow] 暴露当前激活的 Provider，供 UI 订阅
 *
 * **激活机制**：
 * - 同一时间仅一个 Provider 可激活（[ProviderConfig.isActive] = true）
 * - [setActive] 自动取消其他 Provider 的激活状态
 *
 * US-004 验收标准 3：Provider 配置持久化到 ObjectBox
 * US-004 验收标准 4：配置列表可增删改查单元测试通过
 *
 * @param boxStore ObjectBox BoxStore 实例
 */
class ProviderConfigRepository(private val boxStore: BoxStore) {

    private val box: Box<ProviderConfig> = boxStore.boxFor(ProviderConfig::class.java)

    private val _activeProviderFlow = MutableStateFlow<ProviderConfig?>(null)
    /** 当前激活的 Provider，供 UI 订阅 */
    val activeProviderFlow: StateFlow<ProviderConfig?> = _activeProviderFlow.asStateFlow()

    private val _providers = MutableStateFlow<List<ProviderConfig>>(emptyList())
    /** 全部 Provider 配置列表（按 createdAt 升序），供 UI 订阅 */
    val providers: StateFlow<List<ProviderConfig>> = _providers.asStateFlow()

    init {
        refreshFlows()
    }

    /**
     * 保存或更新 Provider 配置。
     *
     * **单激活不变式（BR-concurrency-001 / guardrail S1）**：当 [ProviderConfig.isActive]==true 时，
     * 在单个 ObjectBox 事务中先取消其他激活 Provider，再写入目标，保证同一时间仅一个激活。
     * 调用方绝不应经通用 [save] 直写 isActive=true 绕过 [setActive]；此处兜底防御，杜绝状态漂移。
     *
     * @param config 要保存的配置（id=0 为新建，id>0 为更新）
     * @return 保存后的 id
     */
    fun save(config: ProviderConfig): Long {
        var resultId = 0L
        boxStore.runInTx {
            if (config.isActive) {
                box.all.forEach { other ->
                    if (other.id != config.id && other.isActive) {
                        other.isActive = false
                        box.put(other)
                    }
                }
            }
            resultId = box.put(config)
        }
        refreshFlows()
        return resultId
    }

    /**
     * 按 id 获取 Provider 配置。
     *
     * @param id ProviderConfig id
     * @return 配置对象，不存在返回 null
     */
    fun get(id: Long): ProviderConfig? = box.get(id)

    /**
     * 获取全部 Provider 配置。
     *
     * @return 配置列表（按 createdAt 升序）
     */
    fun getAll(): List<ProviderConfig> = box.all.sortedBy { it.createdAt }

    /**
     * 按名称查找 Provider 配置。
     *
     * @param name Provider 名称（精确匹配）
     * @return 匹配的配置，未找到返回 null
     */
    fun findByName(name: String): ProviderConfig? =
        box.all.find { it.name == name }

    /**
     * v1 US-301：查找视觉旁路 Provider（[ProviderConfig.isVisionFallback] == true）。
     *
     * @return 第一个视觉旁路配置；未配置返回 null
     */
    fun findVisionFallback(): ProviderConfig? =
        box.all.firstOrNull { it.isVisionFallback }

    /**
     * 删除指定 id 的 Provider 配置。
     *
     * @param id ProviderConfig id
     */
    fun remove(id: Long) {
        box.remove(id)
        refreshFlows()
    }

    /**
     * 删除所有 Provider 配置。
     */
    fun removeAll() {
        box.removeAll()
        refreshFlows()
    }

    /**
     * 设置激活的 Provider。
     *
     * 自动取消其他 Provider 的激活状态，确保同一时间仅一个激活。
     *
     * **原子性保证**（BR-concurrency-001）：整个"取消其他 + 激活目标"操作
     * 在单个 ObjectBox 事务 [boxStore.runInTx] 中执行。若中途异常，
     * 事务回滚，不会留下多个 isActive=true 的 Provider，保证不变式。
     *
     * @param id 要激活的 ProviderConfig id
     */
    fun setActive(id: Long) {
        boxStore.runInTx {
            box.all.forEach { config ->
                if (config.id == id && !config.isActive) {
                    config.isActive = true
                    box.put(config)
                } else if (config.id != id && config.isActive) {
                    config.isActive = false
                    box.put(config)
                }
            }
        }
        refreshFlows()
    }

    /**
     * 取消激活当前 Provider。
     *
     * **原子性保证**（BR-concurrency-001）：所有 isActive 置 false 操作
     * 在单个事务中执行，保证不变式。
     */
    fun clearActive() {
        boxStore.runInTx {
            box.all.forEach { config ->
                if (config.isActive) {
                    config.isActive = false
                    box.put(config)
                }
            }
        }
        refreshFlows()
    }

    /**
     * 从预设模板创建 Provider 配置。
     *
     * @param preset 预设模板
     * @return 新建的配置 id
     */
    fun createFromPreset(preset: ProviderConfig): Long {
        val config = ProviderConfig(
            name = preset.name,
            baseUrl = preset.baseUrl,
            apiKeyRef = preset.apiKeyRef,
            models = preset.models,
            headers = preset.headers
        )
        return save(config)
    }

    /**
     * 刷新 Provider 列表与激活 Provider 的 Flow。
     */
    private fun refreshFlows() {
        _providers.value = box.all.sortedBy { it.createdAt }
        _activeProviderFlow.value = _providers.value.find { it.isActive }
    }
}
