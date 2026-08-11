package io.prism.crossapp

import android.util.Log
import kotlinx.serialization.json.Json
import java.io.InputStream

/**
 * 跨 App 调用兼容性清单加载与查询（M6，ADR-016）。
 *
 * 从 `assets/cross-app/app_schemes.json` 加载 [AppSchemeEntry] 列表，
 * 提供 appId / packageName 维度查询。
 *
 * **设计原则**（Karpathy Guidelines §2 简洁优先）：
 * - 仅支持从 [InputStream] 加载（解耦 Android Context，便于纯 JVM 测试）
 * - 加载失败降级为空列表（不抛异常，调用方处理"无可用 App"场景）
 * - 查询方法为纯函数，无副作用
 *
 * **配置驱动**（ADR-016 R1 缓解）：scheme 兼容性变化时只需更新 JSON 文件，
 * 无需修改代码。未来支持远程更新（复用 M4 SkillDownloader HTTPS 下载基建）。
 */
class SchemeRegistry private constructor(
    private val entries: List<AppSchemeEntry>
) {
    /**
     * 获取所有已加载的 App 配置。
     *
     * @return 配置列表（不可变视图）；加载失败时为空列表
     */
    fun getAllApps(): List<AppSchemeEntry> = entries

    /**
     * 按 appId 查询配置。
     *
     * @param appId 稳定标识符（如 `wechat`）
     * @return 匹配的配置；无匹配时 null
     */
    fun getAppById(appId: String): AppSchemeEntry? = entries.firstOrNull { it.appId == appId }

    /**
     * 按 packageName 查询配置。
     *
     * @param packageName 目标 App 包名（如 `com.tencent.mm`）
     * @return 匹配的配置；无匹配时 null
     */
    fun getAppByPackageName(packageName: String): AppSchemeEntry? =
        entries.firstOrNull { it.packageName == packageName }

    /**
     * 按 scheme 查询配置（用于 `<queries>` intent 过滤器匹配）。
     *
     * @param scheme URL Scheme 前缀（如 `weixin`）
     * @return 匹配的配置；无匹配时 null
     */
    fun getAppByScheme(scheme: String): AppSchemeEntry? =
        entries.firstOrNull { it.scheme == scheme }

    companion object {
        private const val TAG = "SchemeRegistry"

        /**
         * 默认配置文件路径（assets 下相对路径）。
         *
         * 供 [PrismApplication] 通过 `assets.open(DEFAULT_CONFIG_PATH)` 加载。
         */
        const val DEFAULT_CONFIG_PATH = "cross-app/app_schemes.json"

        /**
         * 用于解析配置的 Json 实例。
         *
         * - `ignoreUnknownKeys = true`：向前兼容，未来 JSON 增加字段不会破坏旧版本
         * - `isLenient = true`：宽松解析，容忍非标准 JSON 格式
         */
        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        /**
         * 从 [InputStream] 加载配置清单。
         *
         * **降级策略**：加载或解析失败时返回空 [SchemeRegistry]（不抛异常），
         * 调用方处理"无可用 App"场景。错误信息记录到日志（BR-error-handling-004）。
         *
         * @param inputStream JSON 配置文件输入流（调用方负责关闭）
         * @return 加载后的 [SchemeRegistry]；失败时返回空实例
         */
        fun load(inputStream: InputStream): SchemeRegistry {
            return try {
                val content = inputStream.bufferedReader().use { it.readText() }
                val entries = json.decodeFromString<List<AppSchemeEntry>>(content)
                SchemeRegistry(entries)
            } catch (e: Exception) {
                Log.w(TAG, "load app schemes failed", e)
                SchemeRegistry(emptyList())
            }
        }

        /**
         * 从 JSON 字符串加载配置清单（测试用）。
         *
         * @param jsonContent JSON 字符串
         * @return 加载后的 [SchemeRegistry]；失败时返回空实例
         */
        internal fun loadFromString(jsonContent: String): SchemeRegistry {
            return try {
                val entries = json.decodeFromString<List<AppSchemeEntry>>(jsonContent)
                SchemeRegistry(entries)
            } catch (e: Exception) {
                Log.w(TAG, "loadFromString failed", e)
                SchemeRegistry(emptyList())
            }
        }

        /**
         * 创建空配置清单（测试用 / 降级场景）。
         */
        fun empty(): SchemeRegistry = SchemeRegistry(emptyList())
    }
}
