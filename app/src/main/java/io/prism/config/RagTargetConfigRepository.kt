package io.prism.config

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.prism.rag.RagTarget
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * RAG 检索目标配置仓库（UXR8 Bug1，ADR-028）—— 持久化 RagTarget 三态模式。
 *
 * **背景（UXR8 Bug1）**：用户反馈"无检索需求时知识库资料仍被系统主动注入"。
 * 考古定位根因之一：`ConversationViewModel._ragTarget` 仅内存态（[RagTarget] KDoc
 * "US-019 范围内仅内存 StateFlow 暴露，DataStore 持久化延后"），且每次"新对话"
 * 强制重置回 `RagTarget.AllLibraries`——用户关闭 RAG 后只要点新会话/重启，
 * 自动检索又被静默打开。
 *
 * **修复**：新增本仓库持久化 [RagTarget]，`startNewConversation` 不再强制重置为全库，
 * 而是恢复用户上次持久化的模式。**v1 批次18 语义变更**（真机 RCA：用户未请求知识库
 * 却被注入——设备上 `prism_rag_config.preferences_pb` 缺失，缺失回退 AllLibraries 即
 * "首次默认开启"违背用户预期）：首次使用/缺失/未知 mode 默认 [RagTarget.Off]，
 * 用户需在输入区上方 RagModeChip 显式开启（opt-in）。
 *
 * **设计**：DataStore<Preferences> 存储，与 [ThinkingConfigRepository] 同模式。
 * 独立 DataStore 文件（`prism_rag_config`），避免耦合。序列化三态：
 * - `RagTarget.Off` → mode = "off"
 * - `RagTarget.AllLibraries` → mode = "all"（kbId 忽略）
 * - `RagTarget.SpecificLibrary(kbId)` → mode = "specific" + kbId = kbId
 * 缺失/未知 mode / 非法 kbId（<=0）→ 容错回退 [RagTarget.Off]（fail-safe：
 * 防外部写入脏数据导致下游检索异常 + 防未请求的知识库注入，对齐 BR-security-005）。
 *
 * @param dataStore RAG 配置专用 DataStore（`prism_rag_config`）
 */
class RagTargetConfigRepository(
    private val dataStore: DataStore<Preferences>
) {

    /** 观察 RAG 检索目标（热流，配置变更时自动推送）。 */
    fun ragTarget(): Flow<RagTarget> = dataStore.data.map { prefs ->
        decode(prefs[MODE_KEY], prefs[KB_ID_KEY])
    }

    /** 一次性读取 RAG 检索目标（suspend 单值）。 */
    suspend fun getRagTarget(): RagTarget = ragTarget().first()

    /**
     * 设置 RAG 检索目标（持久化到 DataStore）。
     *
     * [RagTarget.SpecificLibrary] 的 kbId 经 [RagTarget] init 校验 >0，
     * 此处无需重复校验；编码失败容错回退 all（防御）。
     */
    suspend fun setRagTarget(target: RagTarget) {
        dataStore.edit { prefs ->
            when (target) {
                RagTarget.Off -> {
                    prefs[MODE_KEY] = MODE_OFF
                    prefs.remove(KB_ID_KEY)
                }
                RagTarget.AllLibraries -> {
                    prefs[MODE_KEY] = MODE_ALL
                    prefs.remove(KB_ID_KEY)
                }
                is RagTarget.SpecificLibrary -> {
                    prefs[MODE_KEY] = MODE_SPECIFIC
                    prefs[KB_ID_KEY] = target.kbId
                }
            }
        }
    }

    companion object {
        /** 模式 key（"off"/"all"/"specific"）。 */
        private val MODE_KEY = stringPreferencesKey("rag_mode")

        /** 指定库 id key（仅 specific 模式使用）。 */
        private val KB_ID_KEY = longPreferencesKey("rag_kb_id")

        private const val MODE_OFF = "off"
        private const val MODE_ALL = "all"
        private const val MODE_SPECIFIC = "specific"

        /**
         * 解码持久化值 → [RagTarget]（容错 fail-safe，对齐 BR-security-005）。
         *
         * **v1 批次18（真机 RCA 2026-09-03）**：缺失/未知 mode 的回退语义由
         * AllLibraries 改为 [RagTarget.Off]——用户未明确开启知识库时**不得自动注入**
         * KB 内容到对话上下文（真机投诉：未请求知识库却被注入；旧默认"首次即全库注入"
         * 违背用户预期）。用户显式设置的 "off"/"all"/"specific" 均原样保留。
         * 非法 specific 数据（kbId<=0/损坏）→ Off（避免下游检索异常 + 注入意外）。
         */
        internal fun decode(mode: String?, kbId: Long?): RagTarget = when (mode) {
            MODE_OFF -> RagTarget.Off
            MODE_SPECIFIC -> if (kbId != null && kbId > 0) {
                try {
                    RagTarget.SpecificLibrary(kbId)
                } catch (e: IllegalArgumentException) {
                    // RagTarget init 防御：理论上 kbId>0 已满足，此处兜底
                    RagTarget.Off
                }
            } else {
                RagTarget.Off
            }
            MODE_ALL -> RagTarget.AllLibraries
            else -> RagTarget.Off // 缺失（首次安装/未切换过）或未知值 → 默认关闭
        }
    }
}
