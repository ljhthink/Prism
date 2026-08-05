package io.prism

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import io.objectbox.BoxStore
import io.prism.data.MyObjectBox
import io.prism.data.ProviderConfigRepository
import io.prism.security.ApiKeyRepository
import io.prism.security.CryptoService
import io.prism.security.KeystoreCryptoService

/**
 * Prism 应用入口 —— 初始化 ObjectBox 数据库与加密服务。
 *
 * 在 [onCreate] 中构建 [BoxStore] 单例，供全应用持久化模块使用
 * （知识库 / 记忆系统 / Provider 配置等）。
 * [cryptoService] 延迟初始化，首次访问时创建 Android Keystore 主密钥。
 * [providerConfigRepository] 延迟初始化，供 UI 层读取激活 Provider（ADR-002 4.5）。
 * [apiKeyRepository] 延迟初始化，供 UI 层安全地读写加密 API Key（ADR-003）。
 *
 * DataStore 必须是进程级单例 —— 通过顶层委托 [dataStore] 保证，避免多实例崩溃。
 */
class PrismApplication : Application() {

    lateinit var boxStore: BoxStore
        private set

    /** 加密服务（延迟初始化，首次访问时创建 Keystore 主密钥） */
    val cryptoService: CryptoService by lazy { KeystoreCryptoService(this) }

    /** Provider 配置仓库（延迟初始化，供 UI 读取激活 Provider） */
    val providerConfigRepository: ProviderConfigRepository by lazy { ProviderConfigRepository(boxStore) }

    /** API Key 加密存储仓库（DataStore 单例 + 加密服务） */
    val apiKeyRepository: ApiKeyRepository by lazy { ApiKeyRepository(dataStore, cryptoService) }

    override fun onCreate() {
        super.onCreate()
        boxStore = MyObjectBox.builder()
            .androidContext(this)
            .build()
    }

    /** DataStore 进程级单例（多实例会崩溃，必须惰性单例）。 */
    private val Context.dataStore by preferencesDataStore(name = "prism_api_keys")
}
