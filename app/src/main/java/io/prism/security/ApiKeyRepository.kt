package io.prism.security

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.byteArrayPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * API Key 加密存储仓库。
 *
 * **架构**：[CryptoService] 加密 → [DataStore] 持久化加密密文。
 * 明文绝不落盘 —— DataStore 中仅存储加密后的字节数组（[ByteArray]）。
 *
 * **安全保证**：
 * - API Key 明文仅在内存中短暂存在（encrypt/decrypt 调用期间）
 * - DataStore 文件（`prism_api_keys.preferences_pb`）中只有密文
 * - 日志中不输出 API Key（本类无日志输出，符合 US-003 AC-4）
 *
 * US-003 验收标准 2：DataStore + Tink AEAD 加密 API Key，不落明文
 * US-003 验收标准 3：保存/读取 API Key 单元测试通过（明文不出 Keystore）
 *
 * @param dataStore DataStore 实例（Preferences DataStore）
 * @param cryptoService 加密服务（生产环境使用 [KeystoreCryptoService]）
 */
class ApiKeyRepository(
    private val dataStore: DataStore<Preferences>,
    private val cryptoService: CryptoService
) {

    /**
     * 保存 API Key（加密后存入 DataStore）。
     *
     * @param key API Key 标识符（如 "openai"、"anthropic"）
     * @param value API Key 明文值
     */
    suspend fun saveApiKey(key: String, value: String) {
        val encrypted = cryptoService.encrypt(value.toByteArray(Charsets.UTF_8))
        dataStore.edit { prefs ->
            prefs[byteArrayPreferencesKey(key)] = encrypted
        }
    }

    /**
     * 读取 API Key（从 DataStore 读取密文并解密）。
     *
     * @param key API Key 标识符
     * @return 解密后的 API Key 明文；若 Key 不存在或解密失败则返回 null
     */
    fun readApiKey(key: String): Flow<String?> =
        dataStore.data.map { prefs ->
            prefs[byteArrayPreferencesKey(key)]?.let { encrypted ->
                try {
                    String(cryptoService.decrypt(encrypted), Charsets.UTF_8)
                } catch (e: Exception) {
                    // 解密失败（密文损坏/密钥变更/篡改）—— 返回 null 而非崩溃
                    null
                }
            }
        }

    /**
     * 删除指定的 API Key。
     *
     * @param key API Key 标识符
     */
    suspend fun removeApiKey(key: String) {
        dataStore.edit { prefs ->
            prefs.remove(byteArrayPreferencesKey(key))
        }
    }

    /**
     * 删除所有 API Key。
     */
    suspend fun removeAllApiKeys() {
        dataStore.edit { prefs -> prefs.clear() }
    }
}
