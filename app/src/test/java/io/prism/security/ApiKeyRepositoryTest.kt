package io.prism.security

import androidx.datastore.preferences.core.byteArrayPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ApiKeyRepository 单元测试（US-003 验收标准 3）。
 *
 * 验证内容：
 * 1. 保存/读取 API Key 往返一致
 * 2. 明文不落盘 —— DataStore 中仅存储密文（[RecordingCryptoService] 记录所有调用）
 * 3. 删除单个/全部 API Key
 * 4. 不存在的 Key 返回 null
 * 5. 解密失败返回 null（不崩溃）
 *
 * 使用 [RecordingCryptoService]（基于 Tink 纯 JVM AEAD）作为加密替身，
 * 使用 [FakePreferenceDataStore]（内存版）替代真实 DataStore，
 * 无需 Android Keystore / 文件 I/O 设备环境。
 */
class ApiKeyRepositoryTest {

    private lateinit var dataStore: FakePreferenceDataStore
    private lateinit var cryptoService: RecordingCryptoService
    private lateinit var repository: ApiKeyRepository

    @Before
    fun setUp() {
        dataStore = FakePreferenceDataStore()
        cryptoService = RecordingCryptoService()
        repository = ApiKeyRepository(dataStore, cryptoService)
    }

    // ==================== AC-3: 保存/读取 API Key 往返 ====================

    @Test
    fun save_and_read_api_key_round_trip() = runTest {
        val apiKey = "sk-test-key-12345"
        repository.saveApiKey("openai", apiKey)

        val retrieved = repository.readApiKey("openai").first()
        assertEquals("保存后读取应返回原始 API Key", apiKey, retrieved)
    }

    @Test
    fun save_multiple_keys_all_readable() = runTest {
        repository.saveApiKey("openai", "sk-openai-aaa")
        repository.saveApiKey("anthropic", "sk-ant-bbb")
        repository.saveApiKey("ollama", "http://localhost:11434")

        assertEquals("sk-openai-aaa", repository.readApiKey("openai").first())
        assertEquals("sk-ant-bbb", repository.readApiKey("anthropic").first())
        assertEquals("http://localhost:11434", repository.readApiKey("ollama").first())
    }

    @Test
    fun save_overwrite_existing_key() = runTest {
        repository.saveApiKey("openai", "old-key")
        repository.saveApiKey("openai", "new-key")

        assertEquals("覆盖后应返回新值", "new-key", repository.readApiKey("openai").first())
    }

    @Test
    fun save_empty_string_key_round_trip() = runTest {
        repository.saveApiKey("empty", "")
        assertEquals("空字符串应正确往返", "", repository.readApiKey("empty").first())
    }

    @Test
    fun save_unicode_key_round_trip() = runTest {
        val unicodeKey = "密钥-🔑-test-日本語"
        repository.saveApiKey("unicode", unicodeKey)
        assertEquals("Unicode 字符应正确往返", unicodeKey, repository.readApiKey("unicode").first())
    }

    // ==================== AC-3: 明文不落盘 ====================

    @Test
    fun save_api_key_calls_encrypt_with_plaintext() = runTest {
        val plaintext = "sk-secret-plaintext"
        repository.saveApiKey("openai", plaintext)

        assertTrue(
            "saveApiKey 应调用 cryptoService.encrypt",
            cryptoService.encryptCalls.isNotEmpty()
        )
        val encryptedInput = cryptoService.encryptCalls.last()
        assertEquals(
            "encrypt 应接收明文 UTF-8 字节",
            plaintext,
            String(encryptedInput, Charsets.UTF_8)
        )
    }

    @Test
    fun datastore_stores_ciphertext_not_plaintext() = runTest {
        val plaintext = "sk-very-secret-key".toByteArray(Charsets.UTF_8)
        repository.saveApiKey("openai", String(plaintext, Charsets.UTF_8))

        // 直接读取 DataStore 原始字节，验证不是明文
        val storedBytes = dataStore.data.first()[byteArrayPreferencesKey("openai")]
        assertNotNull("DataStore 应存储字节", storedBytes)
        assertFalse(
            "DataStore 中不应存储明文（密文 ≠ 明文）",
            storedBytes!!.contentEquals(plaintext)
        )
        // 验证密文与明文不同（加密生效）
        val decryptedByCrypto = cryptoService.decrypt(storedBytes)
        assertArrayEquals(
            "密文经 decrypt 后应还原明文",
            plaintext,
            decryptedByCrypto
        )
    }

    @Test
    fun read_api_key_calls_decrypt_with_ciphertext() = runTest {
        repository.saveApiKey("openai", "sk-test")
        cryptoService.decryptCalls.clear()

        repository.readApiKey("openai").first()

        assertTrue(
            "readApiKey 应调用 cryptoService.decrypt",
            cryptoService.decryptCalls.isNotEmpty()
        )
    }

    // ==================== AC-3: 删除操作 ====================

    @Test
    fun remove_api_key_deletes_it() = runTest {
        repository.saveApiKey("openai", "sk-test")
        assertNotNull("删除前应存在", repository.readApiKey("openai").first())

        repository.removeApiKey("openai")
        assertNull("删除后应返回 null", repository.readApiKey("openai").first())
    }

    @Test
    fun remove_nonexistent_key_is_idempotent() = runTest {
        // 删除不存在的 Key 不应抛异常
        repository.removeApiKey("nonexistent")
        assertNull(repository.readApiKey("nonexistent").first())
    }

    @Test
    fun remove_all_api_keys_clears_everything() = runTest {
        repository.saveApiKey("openai", "sk-1")
        repository.saveApiKey("anthropic", "sk-2")
        repository.saveApiKey("ollama", "sk-3")

        repository.removeAllApiKeys()

        assertNull(repository.readApiKey("openai").first())
        assertNull(repository.readApiKey("anthropic").first())
        assertNull(repository.readApiKey("ollama").first())
    }

    // ==================== AC-3: 不存在的 Key ====================

    @Test
    fun read_nonexistent_key_returns_null() = runTest {
        val result = repository.readApiKey("never-saved").first()
        assertNull("不存在的 Key 应返回 null", result)
    }

    // ==================== AC-3: 解密失败返回 null（不崩溃） ====================

    @Test
    fun read_corrupted_ciphertext_returns_null() = runTest {
        // 直接向 DataStore 写入损坏的密文（非加密格式字节）
        val corruptedBytes = byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04, 0x05)
        dataStore.edit { prefs ->
            prefs[byteArrayPreferencesKey("corrupted")] = corruptedBytes
        }

        val result = repository.readApiKey("corrupted").first()
        assertNull(
            "解密失败应返回 null 而非抛异常",
            result
        )
    }

    @Test
    fun read_with_wrong_crypto_service_returns_null() = runTest {
        // 用 cryptoService A 加密存储
        repository.saveApiKey("openai", "sk-secret")

        // 切换到不同的 cryptoService B（不同密钥），读取应返回 null
        val otherCryptoService = RecordingCryptoService()
        val otherRepository = ApiKeyRepository(dataStore, otherCryptoService)

        val result = otherRepository.readApiKey("openai").first()
        assertNull(
            "不同加密密钥解密应失败返回 null",
            result
        )
    }
}
