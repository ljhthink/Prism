package io.prism.data

import io.objectbox.Box
import io.objectbox.BoxStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 会话仓库（UX-001 问题 4，ADR-021）—— 历史对话记录 CRUD。
 *
 * **职责**：
 * - 会话的保存 / 更新（[save]）
 * - 按 id 获取 / 删除会话
 * - 全部会话列表（按 updatedAt 倒序，最新在前）经 [sessions] StateFlow 暴露给 UI
 *
 * **存储**：[Session.messagesJson] 以 JSON 编码消息列表整体存储。
 * 会话历史恢复时由 ViewModel 反序列化重建内存消息。
 *
 * **与既有仓库模式一致**（仿 [KnowledgeBaseRepository]）：`BoxStore` 提供持久化，
 * `Box<Session>` 延迟初始化，`refreshFlows` 刷新 StateFlow。
 */
class SessionRepository(private val boxStore: BoxStore) {

    private val box: Box<Session> = boxStore.boxFor(Session::class.java)

    private val _sessions = MutableStateFlow<List<Session>>(emptyList())
    /** 全部会话列表（按 updatedAt 倒序），供 UI 订阅。 */
    val sessions: StateFlow<List<Session>> = _sessions.asStateFlow()

    init {
        refreshFlows()
    }

    /**
     * 保存或更新会话。
     *
     * @param session 要保存的会话（id=0 为新建，id>0 为更新）
     * @return 保存后的 id
     */
    fun save(session: Session): Long {
        val id = box.put(session)
        refreshFlows()
        return id
    }

    /** 按 id 获取会话；不存在返回 null。 */
    fun get(id: Long): Session? = box.get(id)

    /**
     * 删除指定会话（历史记录删除，UX-001 问题 4）。
     *
     * @param id 会话 id
     * @return 是否删除成功（id 不存在返回 false）
     */
    fun remove(id: Long): Boolean {
        val existed = box.get(id) != null
        box.remove(id)
        refreshFlows()
        return existed
    }

    /**
     * 删除全部会话（测试清理或「清空历史」功能）。
     */
    fun removeAll() {
        box.removeAll()
        refreshFlows()
    }

    /**
     * 刷新会话列表 StateFlow。
     *
     * 按 [Session.updatedAt] 倒序（最新会话在前，对齐 Kimi/DeepSeek 时间倒序惯例）。
     * UXR8（ADR-028）：同毫秒更新的会话（快速连续操作/测试环境）以 id 倒序 tie-break，
     * 保证"最新创建的会话显示在最前"（稳定排序下纯 updatedAt 排序会保持物理 id 升序，
     * 导致同毫秒时旧会话反而在前 —— ConversationViewModelSessionPersistenceTest 真实触发）。
     */
    private fun refreshFlows() {
        _sessions.value = box.all.sortedWith(
            compareByDescending<Session> { it.updatedAt }
                .thenByDescending { it.id }
        )
    }
}
