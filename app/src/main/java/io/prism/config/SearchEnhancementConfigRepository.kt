package io.prism.config

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * 搜索增强配置仓库（v1 批次15，PRD prd-search-fetch-enhancement US-1507）。
 *
 * 持久化两类配置（独立 DataStore 文件 `prism_search_enhancement`，原子读写、多协程安全，
 * 与 [HighRiskApprovalRepository] / [ThinkingConfigRepository] 同模式）：
 *
 * 1. **SearXNG 自建搜索端点**（[SearxngSettings]）：
 *    - `settings_searxng_endpoint`：自建 SearXNG 服务地址（如 `http://192.168.1.10:8080`）；
 *      空白 = 未配置（引擎链完全跳过，零行为变化）
 *    - `settings_searxng_username` / `settings_searxng_password`：可选 Basic Auth 凭据
 *      （任务口径：明文 String 存储；凭据不落日志——消费方 [io.prism.network.WebSearchLocalToolExecutor]
 *      仅注入请求头，logcat 无凭据输出）
 * 2. **WebView 渲染抓取开关**（`settings_webview_fetch_enabled`，默认 false）：
 *    由抓取侧（US-1506，第三级 Fetch 降级）消费；本仓库只负责存储与设置页读写。
 *
 * **设计**：key 名固定为 `settings_*` 前缀（任务约定，避免与其他 Agent 的 key 冲突）。
 * SearXNG 端点为用户显式配置（SSRF 豁免口径：允许局域网/家宽地址，UI 提示用户自担可达性）。
 */
class SearchEnhancementConfigRepository(
    private val dataStore: DataStore<Preferences>
) {

    /** SearXNG 自建端点配置（endpoint/username/password 三元组）。 */
    data class SearxngSettings(
        val endpoint: String,
        val username: String,
        val password: String
    ) {
        /** 端点已配置（非空白）才参与引擎链。 */
        val isConfigured: Boolean get() = endpoint.isNotBlank()
    }

    /** 观察 SearXNG 配置（热流；端点空白/未配置时返回 null，消费方跳过该引擎）。 */
    fun searxngSettings(): Flow<SearxngSettings?> = dataStore.data.map { prefs ->
        val endpoint = prefs[KEY_SEARXNG_ENDPOINT].orEmpty()
        if (endpoint.isBlank()) {
            null
        } else {
            SearxngSettings(
                endpoint = endpoint,
                username = prefs[KEY_SEARXNG_USERNAME].orEmpty(),
                password = prefs[KEY_SEARXNG_PASSWORD].orEmpty()
            )
        }
    }

    /** 一次性读取当前 SearXNG 配置。 */
    suspend fun getSearxngSettings(): SearxngSettings? = searxngSettings().first()

    /** 保存 SearXNG 配置（endpoint 为空白时视为清除配置）。 */
    suspend fun setSearxngSettings(endpoint: String, username: String, password: String) {
        dataStore.edit { prefs ->
            if (endpoint.isBlank()) {
                prefs.remove(KEY_SEARXNG_ENDPOINT)
                prefs.remove(KEY_SEARXNG_USERNAME)
                prefs.remove(KEY_SEARXNG_PASSWORD)
            } else {
                prefs[KEY_SEARXNG_ENDPOINT] = endpoint.trim()
                prefs[KEY_SEARXNG_USERNAME] = username.trim()
                prefs[KEY_SEARXNG_PASSWORD] = password
            }
        }
    }

    /** 观察 WebView 渲染抓取开关（默认 false；由抓取侧 US-1506 消费）。 */
    fun webviewFetchEnabled(): Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_WEBVIEW_FETCH_ENABLED] ?: DEFAULT_WEBVIEW_FETCH_ENABLED
    }

    /** 一次性读取 WebView 渲染抓取开关。 */
    suspend fun getWebviewFetchEnabled(): Boolean = webviewFetchEnabled().first()

    /** 设置 WebView 渲染抓取开关（持久化到 DataStore）。 */
    suspend fun setWebviewFetchEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_WEBVIEW_FETCH_ENABLED] = enabled }
    }

    /**
     * 首选搜索引擎（v1 批次15.1，US-1509）：结构化引擎链中**首个尝试**的引擎。
     *
     * 空白 = 跟随默认顺序（Bocha → 智谱 → SearXNG → Tavily）。用户自建 SearXNG 后
     * 若 Bocha Key 也在配，默认链会在 Bocha 成功时短路、SearXNG 永不被尝试——
     * 本设置让用户显式把自建引擎提为首选。
     */
    fun preferredEngine(): Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_SEARCH_ENGINE_PREFERRED].orEmpty()
    }

    /** 一次性读取首选引擎（空白 = 跟随默认顺序）。 */
    suspend fun getPreferredEngine(): String = preferredEngine().first()

    /** 保存首选引擎（空白/空串 = 恢复默认顺序；合法值由调用方 UI 约束）。 */
    suspend fun setPreferredEngine(engine: String) {
        dataStore.edit { prefs ->
            if (engine.isBlank()) {
                prefs.remove(KEY_SEARCH_ENGINE_PREFERRED)
            } else {
                prefs[KEY_SEARCH_ENGINE_PREFERRED] = engine.trim().lowercase()
            }
        }
    }

    companion object {
        /** SearXNG 自建端点（String，默认空 = 未配置）。 */
        private val KEY_SEARXNG_ENDPOINT = stringPreferencesKey("settings_searxng_endpoint")

        /** SearXNG Basic Auth 用户名（String，可选）。 */
        private val KEY_SEARXNG_USERNAME = stringPreferencesKey("settings_searxng_username")

        /** SearXNG Basic Auth 密码（String，可选）。 */
        private val KEY_SEARXNG_PASSWORD = stringPreferencesKey("settings_searxng_password")

        /** WebView 渲染抓取开关（Boolean，默认 false；由抓取侧消费，本仓库只存储）。 */
        private val KEY_WEBVIEW_FETCH_ENABLED = booleanPreferencesKey("settings_webview_fetch_enabled")

        /** 首选搜索引擎（String，空白 = 默认顺序；合法值 bocha/zhipu/searxng/tavily）。 */
        private val KEY_SEARCH_ENGINE_PREFERRED = stringPreferencesKey("settings_search_engine_preferred")

        /** WebView 渲染抓取开关默认值（关闭：默认行为向后兼容）。 */
        const val DEFAULT_WEBVIEW_FETCH_ENABLED = false
    }
}
