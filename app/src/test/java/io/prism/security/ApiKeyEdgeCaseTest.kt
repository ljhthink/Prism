package io.prism.security

import androidx.datastore.preferences.core.byteArrayPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ac-verifier 补充极端/边缘场景测试（US-003，guardrail G-09 建议）。
 *
 * 覆盖主 Agent 基础用例未覆盖的盲区：
 * - 空白 key 标识符
 * - 超长 API Key（边界值）
 * - 超长 key 标识符
 * - 特殊字符 key 标识符
 * - IV 随机化验证（同一明文加密两次密文不同）
 * - 并发写入安全性
 * - 大量 key 资源边界
 * - 仅空白字符的 API Key
 * - 二进制-like 内容
 * - 密文篡改检测（AEAD 完整性）
 */
class ApiKeyEdgeCaseTest {

    private lateinit var dataStore: FakePreferenceDataStore
    private lateinit var cryptoService: RecordingCryptoService
    private lateinit var repository: ApiKeyRepository

    @Before
    fun setUp() {
        dataStore = FakePreferenceDataStore()
        cryptoService = RecordingCryptoService()
        repository = ApiKeyRepository(dataStore, cryptoService)
    }

    // ==================== 边界值：空白 key 标识符 ====================

    @Test
    fun save_with_empty_key_identifier_still_works() = runTest {
        // DataStore byteArrayPreferencesKey 接受空字符串作为 key
        // 当前实现未做 require(key.isNotBlank()) 校验（guardrail G-03）
        repository.saveApiKey("", "sk-value-for-empty-key")
        assertEquals(
            "空 key 标识符应能存取（DataStore 接受任意字符串 key）",
            "sk-value-for-empty-key",
            repository.readApiKey("").first()
        )
    }

    @Test
    fun save_with_whitespace_only_key_identifier() = runTest {
        repository.saveApiKey("   ", "sk-whitespace-key")
        assertEquals(
            "纯空白 key 标识符应能存取",
            "sk-whitespace-key",
            repository.readApiKey("   ").first()
        )
    }

    // ==================== 边界值：超长 API Key ====================

    @Test
    fun save_very_long_api_key_round_trip() = runTest {
        val longKey = "sk-" + "a".repeat(9997) // 总计 10000 字符
        repository.saveApiKey("openai", longKey)
        val retrieved = repository.readApiKey("openai").first()
        assertEquals("超长 API Key (10000 字符) 应正确往返", longKey, retrieved)
        assertEquals("长度应一致", 10000, retrieved!!.length)
    }

    @Test
    fun save_extremely_long_api_key_round_trip() = runTest {
        val extremeKey = "sk-" + "x".repeat(100_000) // 100KB API Key
        repository.saveApiKey("openai", extremeKey)
        assertEquals(
            "超长 API Key (100K 字符) 应正确往返",
            extremeKey,
            repository.readApiKey("openai").first()
        )
    }

    // ==================== 边界值：超长 key 标识符 ====================

    @Test
    fun save_with_very_long_key_identifier() = runTest {
        val longIdentifier = "provider-" + "x".repeat(500)
        repository.saveApiKey(longIdentifier, "sk-test")
        assertEquals(
            "超长 key 标识符应能存取",
            "sk-test",
            repository.readApiKey(longIdentifier).first()
        )
    }

    // ==================== 等价类：特殊字符 key 标识符 ====================

    @Test
    fun save_with_special_characters_in_key_identifier() = runTest {
        val specialKeys = listOf(
            "openai/api", "anthropic:key", "ollama@local",
            "provider-1", "provider_2", "provider.3",
            "供应商-1", "🔑-key"
        )
        for (key in specialKeys) {
            repository.saveApiKey(key, "sk-value-$key")
            assertEquals(
                "特殊字符 key '$key' 应正确往返",
                "sk-value-$key",
                repository.readApiKey(key).first()
            )
        }
    }

    // ==================== IV 随机化验证（AEAD 安全属性） ====================

    @Test
    fun encrypt_same_plaintext_produces_different_ciphertext() = runTest {
        val plaintext = "sk-same-secret-key".toByteArray(Charsets.UTF_8)
        val ciphertext1 = cryptoService.encrypt(plaintext)
        val ciphertext2 = cryptoService.encrypt(plaintext)

        assertFalse(
            "同一明文加密两次应产生不同密文（IV 随机化）",
            ciphertext1.contentEquals(ciphertext2)
        )
        // 但两次都能正确解密回同一明文
        assertNotEquals("密文1 ≠ 密文2", ciphertext1.toList(), ciphertext2.toList())
        assertEquals(
            "密文1 解密应还原明文",
            plaintext.toList(),
            cryptoService.decrypt(ciphertext1).toList()
        )
        assertEquals(
            "密文2 解密应还原明文",
            plaintext.toList(),
            cryptoService.decrypt(ciphertext2).toList()
        )
    }

    @Test
    fun save_same_api_key_twice_stores_different_ciphertext() = runTest {
        repository.saveApiKey("openai", "sk-secret")
        val ciphertext1 = dataStore.data.first()[byteArrayPreferencesKey("openai")]!!.copyOf()

        repository.saveApiKey("openai", "sk-secret")
        val ciphertext2 = dataStore.data.first()[byteArrayPreferencesKey("openai")]!!.copyOf()

        assertFalse(
            "同一明文保存两次应产生不同密文（每次加密使用不同 IV）",
            ciphertext1.contentEquals(ciphertext2)
        )
    }

    // ==================== 等价类：仅空白字符的 API Key ====================

    @Test
    fun save_whitespace_only_api_key_round_trip() = runTest {
        repository.saveApiKey("whitespace", "   \t\n  ")
        assertEquals(
            "纯空白字符 API Key 应正确往返",
            "   \t\n  ",
            repository.readApiKey("whitespace").first()
        )
    }

    // ==================== 等价类：二进制-like 内容 ====================

    @Test
    fun save_binary_like_content_round_trip() = runTest {
        // 模拟可能包含控制字符的 key
        val binaryLike = String(byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05).plus("text".toByteArray()), Charsets.UTF_8)
        repository.saveApiKey("binary", binaryLike)
        assertEquals(
            "二进制-like 内容应正确往返",
            binaryLike,
            repository.readApiKey("binary").first()
        )
    }

    // ==================== AEAD 完整性：密文篡改检测 ====================

    @Test
    fun tampered_ciphertext_returns_null() = runTest {
        repository.saveApiKey("openai", "sk-original-secret")
        val originalCiphertext = dataStore.data.first()[byteArrayPreferencesKey("openai")]!!.copyOf()

        // 篡改密文的最后一个字节（认证标签区域）
        val tampered = originalCiphertext.copyOf()
        tampered[tampered.size - 1] = (tampered[tampered.size - 1].toInt() xor 0xFF).toByte()
        dataStore.edit { prefs ->
            prefs[byteArrayPreferencesKey("openai")] = tampered
        }

        assertNull(
            "篡改后的密文解密应失败返回 null（AEAD 完整性保护）",
            repository.readApiKey("openai").first()
        )
    }

    @Test
    fun truncated_ciphertext_returns_null() = runTest {
        repository.saveApiKey("openai", "sk-secret")
        val originalCiphertext = dataStore.data.first()[byteArrayPreferencesKey("openai")]!!.copyOf()

        // 截断密文（去掉最后 5 字节）
        val truncated = originalCiphertext.copyOfRange(0, originalCiphertext.size - 5)
        dataStore.edit { prefs ->
            prefs[byteArrayPreferencesKey("openai")] = truncated
        }

        assertNull(
            "截断的密文解密应失败返回 null",
            repository.readApiKey("openai").first()
        )
    }

    // ==================== 资源边界：大量 key ====================

    @Test
    fun save_100_keys_all_readable() = runTest {
        for (i in 1..100) {
            repository.saveApiKey("provider-$i", "sk-key-$i")
        }
        for (i in 1..100) {
            assertEquals(
                "第 $i 个 key 应可读取",
                "sk-key-$i",
                repository.readApiKey("provider-$i").first()
            )
        }
    }

    // ==================== 并发安全性 ====================

    @Test
    fun concurrent_saves_to_different_keys_all_persisted() = runTest {
        val results = (1..10).map { i ->
            async {
                repository.saveApiKey("concurrent-$i", "sk-value-$i")
            }
        }
        results.awaitAll()

        for (i in 1..10) {
            assertEquals(
                "并发写入的第 $i 个 key 应持久化",
                "sk-value-$i",
                repository.readApiKey("concurrent-$i").first()
            )
        }
    }

    // ==================== 状态迁移：save -> remove -> save 循环 ====================

    @Test
    fun save_remove_save_cycle_works() = runTest {
        // 第一轮
        repository.saveApiKey("openai", "first-key")
        assertEquals("first-key", repository.readApiKey("openai").first())
        repository.removeApiKey("openai")
        assertNull(repository.readApiKey("openai").first())

        // 第二轮（不同值）
        repository.saveApiKey("openai", "second-key")
        assertEquals(
            "删除后重新保存应返回新值",
            "second-key",
            repository.readApiKey("openai").first()
        )
    }

    // ==================== 明文不落盘：深度验证 ====================

    @Test
    fun datastore_never_contains_plaintext_for_any_key() = runTest {
        val keys = mapOf(
            "openai" to "sk-openai-secret-12345",
            "anthropic" to "sk-ant-secret-67890",
            "ollama" to "http://localhost:11434"
        )
        for ((key, value) in keys) {
            repository.saveApiKey(key, value)
        }

        // 遍历所有存储的字节，确认没有一个等于明文
        val allPrefs = dataStore.data.first()
        for ((key, value) in keys) {
            val stored = allPrefs[byteArrayPreferencesKey(key)]
            assertTrue("key '$key' 应有存储值", stored != null)
            val plaintextBytes = value.toByteArray(Charsets.UTF_8)
            assertFalse(
                "key '$key' 的存储值不应是明文",
                stored!!.contentEquals(plaintextBytes)
            )
            // 验证可以正确解密
            assertEquals(
                "key '$key' 密文可解密为原明文",
                value,
                String(cryptoService.decrypt(stored), Charsets.UTF_8)
            )
        }
    }
}
