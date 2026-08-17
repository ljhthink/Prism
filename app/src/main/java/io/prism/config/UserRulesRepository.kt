package io.prism.config

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * 用户规则配置仓库（UXR8 N1，ADR-030）—— 持久化「关于我」+「如何回答」双字段。
 *
 * **背景**：类 ChatGPT Custom Instructions 的用户显式规则文件。用户可声明
 * 「关于我」（身份/背景/场景）与「如何回答」（语气/格式/禁忌），LLM 注入为
 * systemPrompt 的最高优先级层（除安全限制外），语义对齐 Claude Code 分层记忆
 * （用户显式规则 > 自动记忆 > 通用 persona）。
 *
 * **设计**：使用 DataStore<Preferences> 存储，与 [ThinkingConfigRepository] 同模式。
 * 独立 DataStore 文件（`prism_user_rules`），与 API Key / 思考 / 记忆 / 档位隔离。
 *
 * **配置项**：
 * - [ABOUT_ME_KEY]：关于我（默认空）
 * - [HOW_TO_RESPOND_KEY]：如何回答（默认空）
 *
 * **长度上限**（[MAX_RULE_LEN]=500，防 token 膨胀）：两个字段独立截断，超长时
 * 拒绝保存（fail-fast，BR-security-005 纵深防御）——避免注入超长规则拖垮每轮请求。
 *
 * **线程安全**：DataStore 保证原子读写，多协程并发安全。
 *
 * @param dataStore 用户规则配置专用 DataStore（`prism_user_rules`）
 */
class UserRulesRepository(
    private val dataStore: DataStore<Preferences>
) {

    /** 用户规则数据类（双字段，纯数据不可变）。 */
    data class UserRules(
        val aboutMe: String = "",
        val howToRespond: String = ""
    ) {
        /** 是否有任一字段非空白（用于 [ConversationViewModel] 判断是否注入 userRules 层）。 */
        val isBlank: Boolean get() = aboutMe.isBlank() && howToRespond.isBlank()

        /**
         * 合并为 systemPrompt 用户规则层文本（纯函数，可测）。
         *
         * 格式：`[用户规则 · 最高优先级] 关于我：… 如何回答：…`。两字段独立非空白
         * 才输出对应小节，全空返回 null（调用方跳过注入）。
         */
        fun toSystemPromptSection(): String? {
            if (isBlank) return null
            return buildString {
                append("[用户规则 · 除安全限制外最高优先级]")
                if (aboutMe.isNotBlank()) {
                    append("\n关于我：$aboutMe")
                }
                if (howToRespond.isNotBlank()) {
                    append("\n如何回答：$howToRespond")
                }
            }.trim()
        }
    }

    /** 观察用户规则（热流，配置变更时自动推送）。 */
    fun rules(): Flow<UserRules> = dataStore.data.map { prefs ->
        UserRules(
            aboutMe = prefs[ABOUT_ME_KEY] ?: "",
            howToRespond = prefs[HOW_TO_RESPOND_KEY] ?: ""
        )
    }

    /** 一次性读取用户规则（suspend 单值）。 */
    suspend fun getRules(): UserRules = rules().first()

    /**
     * 保存用户规则（持久化到 DataStore，双字段整体覆盖）。
     *
     * **长度校验**（fail-fast，BR-security-005）：任一字段超过 [MAX_RULE_LEN] 时
     * 抛出 [IllegalArgumentException] 拒绝保存（UI 层已先截断/提示，此处为纵深防御）。
     * 空字段存储为空串。
     *
     * @param aboutMe 关于我（≤ [MAX_RULE_LEN] 字符）
     * @param howToRespond 如何回答（≤ [MAX_RULE_LEN] 字符）
     * @throws IllegalArgumentException 任一字段超长
     */
    suspend fun setRules(aboutMe: String, howToRespond: String) {
        val about = aboutMe.trim()
        val respond = howToRespond.trim()
        require(about.length <= MAX_RULE_LEN && respond.length <= MAX_RULE_LEN) {
            "用户规则字段不能超过 $MAX_RULE_LEN 字符"
        }
        dataStore.edit { prefs ->
            prefs[ABOUT_ME_KEY] = about
            prefs[HOW_TO_RESPOND_KEY] = respond
        }
    }

    companion object {
        /** 「关于我」的 DataStore key。 */
        private val ABOUT_ME_KEY = stringPreferencesKey("user_rules_about_me")

        /** 「如何回答」的 DataStore key。 */
        private val HOW_TO_RESPOND_KEY = stringPreferencesKey("user_rules_how_to_respond")

        /** 单字段最大长度（防 token 膨胀：500 汉字 ≈ 750-1500 token，可接受）。 */
        const val MAX_RULE_LEN = 500
    }
}
