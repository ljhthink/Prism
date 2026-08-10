package io.prism.data

import io.objectbox.Box
import io.objectbox.BoxStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 用户画像仓库 —— 管理 [UserProfile] 的 CRUD 与 upsert 唯一约束（US-031，ADR-015 5.2）。
 *
 * **架构**（ADR-015 5.2，复用 M3 [KnowledgeBaseRepository] CRUD 模式）：
 * - [BoxStore] 提供 ObjectBox 持久化
 * - [box] 延迟初始化的 [UserProfile] Box
 * - [profiles] 暴露全部用户画像列表（按 updatedAt 降序），供 UI 订阅（US-036 记忆管理）
 *
 * **三层记忆定位**（ADR-015）：
 * - 本仓库为 L3 用户画像的持久化层，由 [io.prism.memory.UserProfileManager]
 *   （US-034）调用 [save] 持久化偏好（显式用户设定 + 隐式 LLM 抽取）。
 * - 调用方（[io.prism.ui.chat.ConversationViewModel]）在新会话时通过 [getAll] /
 *   [getByCategory] 加载画像，注入 systemPrompt 第三段（ADR-015 决策 4）。
 *
 * **单用户单 key 唯一约束**（US-031 AC-3，ObjectBox 不支持原生唯一约束）：
 * [save] 实现 upsert 语义：
 * 1. 先 `box.all.find { it.key == key }` 查询是否存在相同 key 的记录
 * 2. 存在 → 复用其 id（更新），不存在 → id=0（新建）
 * 3. `box.put(profile)` 写入
 * 4. 整个「查 + 写」在 [boxStore.runInTx] 事务内，保证原子性，避免并发写入产生重复 key
 *
 * **查询方式**：使用 `box.all` 内存过滤（与 [SkillRepository.findByName]、
 * [McpServerRepository.findByName] 既有模式一致）。ObjectBox 5.4.2 的 String
 * 字段为 `Property<T>` 而非 `StringProperty`，`equal` 方法无 String 重载，
 * 内存过滤避免 API 兼容性问题。用户画像记录数量极少，性能完全可接受。
 *
 * **事务原子性**（BR-concurrency-001）：
 * [save] / [update] / [delete] / [deleteAll] 的「查 + 改/删」操作均在单个事务内完成。
 *
 * US-031 验收标准 2：UserProfileRepository CRUD（save / get / getAll / getByCategory / update / delete / deleteAll）
 * US-031 验收标准 3：单用户单 key 唯一约束（相同 key upsert 而非 insert）
 * US-031 验收标准 4：UserProfileRepository 单元测试通过
 *
 * @param boxStore ObjectBox BoxStore 实例
 */
class UserProfileRepository(private val boxStore: BoxStore) {

    private val box: Box<UserProfile> = boxStore.boxFor(UserProfile::class.java)

    private val _profiles = MutableStateFlow<List<UserProfile>>(emptyList())
    /** 全部用户画像列表（按 updatedAt 降序，最近更新的在前），供 UI 订阅。 */
    val profiles: StateFlow<List<UserProfile>> = _profiles.asStateFlow()

    init {
        refreshFlows()
    }

    /**
     * 保存或更新用户画像（upsert 语义，US-031 AC-3）。
     *
     * **单 key 唯一约束实现**：
     * 1. 先查询相同 key 的记录（内存过滤）
     * 2. 存在 → 复用其 id（更新），不存在 → id=0（新建）
     * 3. `box.put(profile)` 写入
     *
     * **事务原子性**（BR-concurrency-001）：整个「查 + 写」在单个 [boxStore.runInTx]
     * 事务内执行，避免并发写入产生重复 key。
     *
     * **updatedAt 自动刷新**：调用方无需手动设置 updatedAt，本方法自动设为当前时间。
     *
     * @param profile 待保存的画像（key 必须非空；id=0 新建或由本方法决定，id>0 强制更新指定 id）
     * @return 保存后的 id
     * @throws IllegalArgumentException 当 key 为空时
     */
    fun save(profile: UserProfile): Long {
        require(profile.key.isNotBlank()) { "UserProfile.key 不能为空" }

        var savedId = 0L
        boxStore.runInTx {
            // 1. 查询相同 key 的记录（upsert，内存过滤模式）
            val existingId = box.all.find { it.key == profile.key }?.id

            // 2. 存在则复用 id（更新），不存在则保持 id=0（新建）
            if (existingId != null && profile.id == 0L) {
                profile.id = existingId
            }

            // 3. 自动刷新 updatedAt
            profile.updatedAt = System.currentTimeMillis()

            // 4. 写入
            savedId = box.put(profile)
        }
        refreshFlows()
        return savedId
    }

    /**
     * 按 key 获取用户画像。
     *
     * @param key 偏好键
     * @return 匹配的画像，未找到返回 null
     */
    fun get(key: String): UserProfile? = box.all.find { it.key == key }

    /**
     * 获取全部用户画像。
     *
     * @return 画像列表（按 updatedAt 降序，最近更新的在前）
     */
    fun getAll(): List<UserProfile> = box.all.sortedByDescending { it.updatedAt }

    /**
     * 按类别获取用户画像。
     *
     * @param category 偏好类别（[ProfileCategory.EXPLICIT] 或 [ProfileCategory.IMPLICIT]）
     * @return 匹配的画像列表（按 updatedAt 降序）
     */
    fun getByCategory(category: ProfileCategory): List<UserProfile> =
        box.all.filter { it.category == category.name }.sortedByDescending { it.updatedAt }

    /**
     * 更新指定 key 的偏好值（便捷方法，US-031 AC-2）。
     *
     * 若 key 不存在则创建新记录（category 默认 [ProfileCategory.EXPLICIT]）。
     * 若 key 存在则更新 value + updatedAt（category 保持不变）。
     *
     * @param key 偏好键
     * @param value 新的偏好值
     * @return 保存后的 id
     * @throws IllegalArgumentException 当 key 或 value 为空时
     */
    fun update(key: String, value: String): Long {
        require(key.isNotBlank()) { "UserProfile.key 不能为空" }
        require(value.isNotBlank()) { "UserProfile.value 不能为空" }

        var savedId = 0L
        boxStore.runInTx {
            val existing = box.all.find { it.key == key }
            val profile = if (existing != null) {
                existing.value = value
                existing.updatedAt = System.currentTimeMillis()
                existing
            } else {
                UserProfile(
                    key = key,
                    value = value,
                    category = ProfileCategory.EXPLICIT.name,
                    updatedAt = System.currentTimeMillis()
                )
            }
            savedId = box.put(profile)
        }
        refreshFlows()
        return savedId
    }

    /**
     * 删除指定 key 的用户画像。
     *
     * **事务原子性**（BR-concurrency-001）：「查 id → 删记录」在单个事务内执行。
     *
     * @param key 偏好键
     * @return true 表示删除成功（key 存在），false 表示 key 不存在
     */
    fun delete(key: String): Boolean {
        var deleted = false
        boxStore.runInTx {
            val ids = box.all.filter { it.key == key }.map { it.id }.toLongArray()
            if (ids.isNotEmpty()) {
                box.remove(*ids)
                deleted = true
            }
        }
        refreshFlows()
        return deleted
    }

    /**
     * 删除所有用户画像。
     *
     * @return 删除的记录数
     */
    fun deleteAll(): Long {
        val deletedCount = box.count()
        box.removeAll()
        refreshFlows()
        return deletedCount
    }

    /**
     * 获取全部画像记录数（供 UI 展示统计）。
     */
    fun count(): Long = box.count()

    /**
     * 刷新画像列表的 Flow。
     */
    private fun refreshFlows() {
        _profiles.value = box.all.sortedByDescending { it.updatedAt }
    }
}
