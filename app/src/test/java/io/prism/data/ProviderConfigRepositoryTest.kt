package io.prism.data

import io.objectbox.BoxStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * ProviderConfigRepository CRUD 单元测试（US-004 验收标准 3-4）。
 *
 * 验证内容：
 * 1. ProviderConfig 持久化到 ObjectBox（含 @Convert 类型转换）
 * 2. 配置列表增删改查
 * 3. 5 种预设 Provider 模板
 * 4. 激活机制（同一时间仅一个激活）
 * 5. 类型转换器往返（List<String>、Map<String, String>）
 *
 * 使用 [BoxStore.directory] 在临时目录中构建纯 JVM ObjectBox 实例。
 */
class ProviderConfigRepositoryTest {

    private lateinit var boxStore: BoxStore
    private lateinit var repository: ProviderConfigRepository
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = kotlin.io.path.createTempDirectory(prefix = "provider-test-").toFile()
        boxStore = MyObjectBox.builder().directory(tempDir).build()
        repository = ProviderConfigRepository(boxStore)
    }

    @After
    fun tearDown() {
        boxStore.close()
        tempDir.deleteRecursively()
    }

    // ==================== AC-3: 持久化到 ObjectBox ====================

    @Test
    fun save_assigns_positive_id() {
        val config = ProviderConfig(name = "Test", baseUrl = "https://test.api", apiKeyRef = "test")
        val id = repository.save(config)
        assertTrue("应分配正数 id", id > 0)
    }

    @Test
    fun get_returns_persisted_config() {
        val config = ProviderConfig(
            name = "OpenAI",
            baseUrl = "https://api.openai.com/v1",
            apiKeyRef = "openai",
            models = listOf("gpt-4o", "gpt-4o-mini"),
            headers = mapOf("X-Custom" to "value")
        )
        val id = repository.save(config)

        val retrieved = repository.get(id)
        assertNotNull("应能读取已保存的配置", retrieved)
        assertEquals("OpenAI", retrieved!!.name)
        assertEquals("https://api.openai.com/v1", retrieved.baseUrl)
        assertEquals("openai", retrieved.apiKeyRef)
        assertEquals(listOf("gpt-4o", "gpt-4o-mini"), retrieved.models)
        assertEquals(mapOf("X-Custom" to "value"), retrieved.headers)
    }

    @Test
    fun save_update_existing_config() {
        val config = ProviderConfig(name = "Test", baseUrl = "https://old.api", apiKeyRef = "test")
        val id = repository.save(config)

        config.id = id
        config.baseUrl = "https://new.api"
        config.models = listOf("model-a", "model-b")
        repository.save(config)

        val updated = repository.get(id)
        assertEquals("https://new.api", updated!!.baseUrl)
        assertEquals(listOf("model-a", "model-b"), updated.models)
    }

    // ==================== AC-4: 增删改查 ====================

    @Test
    fun get_all_returns_all_configs_sorted_by_created_at() {
        val id1 = repository.save(ProviderConfig(name = "A", baseUrl = "url-a", apiKeyRef = "a"))
        Thread.sleep(1)
        val id2 = repository.save(ProviderConfig(name = "B", baseUrl = "url-b", apiKeyRef = "b"))
        Thread.sleep(1)
        val id3 = repository.save(ProviderConfig(name = "C", baseUrl = "url-c", apiKeyRef = "c"))

        val all = repository.getAll()
        assertEquals(3, all.size)
        assertEquals("A", all[0].name)
        assertEquals("B", all[1].name)
        assertEquals("C", all[2].name)
    }

    @Test
    fun remove_deletes_config() {
        val id = repository.save(ProviderConfig(name = "Test", baseUrl = "url", apiKeyRef = "test"))
        assertNotNull(repository.get(id))

        repository.remove(id)
        assertNull("删除后应返回 null", repository.get(id))
    }

    @Test
    fun remove_all_clears_everything() {
        repository.save(ProviderConfig(name = "A", baseUrl = "url-a", apiKeyRef = "a"))
        repository.save(ProviderConfig(name = "B", baseUrl = "url-b", apiKeyRef = "b"))
        assertEquals(2, repository.getAll().size)

        repository.removeAll()
        assertEquals(0, repository.getAll().size)
    }

    @Test
    fun find_by_name_returns_matching_config() {
        repository.save(ProviderConfig(name = "OpenAI", baseUrl = "url", apiKeyRef = "openai"))
        repository.save(ProviderConfig(name = "Anthropic", baseUrl = "url", apiKeyRef = "anthropic"))

        val found = repository.findByName("OpenAI")
        assertNotNull(found)
        assertEquals("openai", found!!.apiKeyRef)
    }

    @Test
    fun find_by_name_returns_null_for_nonexistent() {
        assertNull(repository.findByName("Nonexistent"))
    }

    @Test
    fun get_nonexistent_returns_null() {
        assertNull(repository.get(99999L))
    }

    // ==================== AC-2: 预设 5 种 Provider ====================

    @Test
    fun presets_contain_5_providers() {
        assertEquals(5, ProviderPresets.all.size)
    }

    @Test
    fun presets_include_openai_anthropic_ollama_moonshot_openrouter() {
        val names = ProviderPresets.all.map { it.name }.toSet()
        assertTrue("应包含 OpenAI", "OpenAI" in names)
        assertTrue("应包含 Anthropic", "Anthropic" in names)
        assertTrue("应包含 Ollama", "Ollama" in names)
        assertTrue("应包含 Moonshot", "Moonshot" in names)
        assertTrue("应包含 OpenRouter", "OpenRouter" in names)
    }

    @Test
    fun presets_have_valid_base_urls() {
        ProviderPresets.all.forEach { preset ->
            assertTrue(
                "${preset.name} 的 baseUrl 应以 http 开头",
                preset.baseUrl.startsWith("http://") || preset.baseUrl.startsWith("https://")
            )
        }
    }

    @Test
    fun presets_have_non_empty_models() {
        ProviderPresets.all.forEach { preset ->
            assertTrue(
                "${preset.name} 应至少有一个模型",
                preset.models.isNotEmpty()
            )
        }
    }

    @Test
    fun presets_have_unique_api_key_refs() {
        val keyRefs = ProviderPresets.all.map { it.apiKeyRef }
        assertEquals(
            "API Key 引用应唯一",
            keyRefs.size,
            keyRefs.toSet().size
        )
    }

    @Test
    fun create_from_preset_persists_config() {
        val id = repository.createFromPreset(ProviderPresets.openai)
        val saved = repository.get(id)

        assertNotNull(saved)
        assertEquals("OpenAI", saved!!.name)
        assertEquals("https://api.openai.com/v1", saved.baseUrl)
        assertEquals("openai", saved.apiKeyRef)
        assertEquals(ProviderPresets.openai.models, saved.models)
    }

    @Test
    fun preset_find_by_name_case_insensitive() {
        assertNotNull(ProviderPresets.findByName("openai"))
        assertNotNull(ProviderPresets.findByName("OPENAI"))
        assertNotNull(ProviderPresets.findByName("Anthropic"))
        assertNull(ProviderPresets.findByName("Nonexistent"))
    }

    // ==================== 激活机制 ====================

    @Test
    fun set_active_marks_provider_as_active() {
        val id = repository.save(ProviderConfig(name = "Test", baseUrl = "url", apiKeyRef = "test"))
        repository.setActive(id)

        val config = repository.get(id)
        assertTrue("setActive 后 isActive 应为 true", config!!.isActive)
    }

    @Test
    fun set_active_deactivates_others() {
        val id1 = repository.save(ProviderConfig(name = "A", baseUrl = "url-a", apiKeyRef = "a"))
        val id2 = repository.save(ProviderConfig(name = "B", baseUrl = "url-b", apiKeyRef = "b"))

        repository.setActive(id1)
        assertTrue(repository.get(id1)!!.isActive)
        assertFalse(repository.get(id2)!!.isActive)

        repository.setActive(id2)
        assertFalse("切换后原激活应变为 false", repository.get(id1)!!.isActive)
        assertTrue("新设置应变为 true", repository.get(id2)!!.isActive)
    }

    @Test
    fun clear_active_deactivates_all() {
        val id = repository.save(ProviderConfig(name = "Test", baseUrl = "url", apiKeyRef = "test"))
        repository.setActive(id)
        assertTrue(repository.get(id)!!.isActive)

        repository.clearActive()
        assertFalse("clearActive 后应为 false", repository.get(id)!!.isActive)
    }

    @Test
    fun save_active_config_deactivates_others() {
        // guardrail S1 兜底防御：即使经通用 save 直写 isActive=true，也必须保证单激活不变式
        val id1 = repository.save(ProviderConfig(name = "A", baseUrl = "url-a", apiKeyRef = "a"))
        val id2 = repository.save(ProviderConfig(name = "B", baseUrl = "url-b", apiKeyRef = "b"))

        repository.save(repository.get(id1)!!.copy(isActive = true))

        assertTrue("A 应激活", repository.get(id1)!!.isActive)
        assertFalse("B 应被取消激活", repository.get(id2)!!.isActive)
    }

    @Test
    fun save_active_new_config_deactivates_existing() {
        val id1 = repository.save(ProviderConfig(name = "A", baseUrl = "url-a", apiKeyRef = "a"))
        repository.setActive(id1)

        val id2 = repository.save(ProviderConfig(name = "B", baseUrl = "url-b", apiKeyRef = "b", isActive = true))

        assertFalse("原激活 A 应被取消", repository.get(id1)!!.isActive)
        assertTrue("B 应激活", repository.get(id2)!!.isActive)
    }

    @Test
    fun active_provider_flow_reflects_active_state() {
        val id = repository.save(ProviderConfig(name = "Test", baseUrl = "url", apiKeyRef = "test"))
        assertNull(repository.activeProviderFlow.value)

        repository.setActive(id)
        assertEquals("Test", repository.activeProviderFlow.value?.name)

        repository.clearActive()
        assertNull(repository.activeProviderFlow.value)
    }

    // ==================== 类型转换器往返 ====================

    @Test
    fun models_list_round_trip() {
        val models = listOf("gpt-4o", "gpt-4o-mini", "gpt-4-turbo", "gpt-3.5-turbo")
        val id = repository.save(
            ProviderConfig(name = "Test", baseUrl = "url", apiKeyRef = "test", models = models)
        )
        val retrieved = repository.get(id)
        assertEquals("模型列表应正确往返", models, retrieved!!.models)
    }

    @Test
    fun headers_map_round_trip() {
        val headers = mapOf(
            "Authorization" to "Bearer token",
            "X-Custom-Header" to "custom-value",
            "Content-Type" to "application/json"
        )
        val id = repository.save(
            ProviderConfig(name = "Test", baseUrl = "url", apiKeyRef = "test", headers = headers)
        )
        val retrieved = repository.get(id)
        assertEquals("请求头映射应正确往返", headers, retrieved!!.headers)
    }

    @Test
    fun empty_models_round_trip() {
        val id = repository.save(
            ProviderConfig(name = "Test", baseUrl = "url", apiKeyRef = "test", models = emptyList())
        )
        val retrieved = repository.get(id)
        assertTrue("空模型列表应正确往返", retrieved!!.models.isEmpty())
    }

    @Test
    fun empty_headers_round_trip() {
        val id = repository.save(
            ProviderConfig(name = "Test", baseUrl = "url", apiKeyRef = "test", headers = emptyMap())
        )
        val retrieved = repository.get(id)
        assertTrue("空请求头映射应正确往返", retrieved!!.headers.isEmpty())
    }

    @Test
    fun models_with_special_characters_round_trip() {
        val models = listOf("model-with-dash", "model.with.dots", "model/with/slashes")
        val id = repository.save(
            ProviderConfig(name = "Test", baseUrl = "url", apiKeyRef = "test", models = models)
        )
        assertEquals(models, repository.get(id)!!.models)
    }

    @Test
    fun models_with_newline_round_trip() {
        // 模型名含字面换行符，验证 StringListConverter 转义不与分隔符冲突
        val models = listOf("model\nwith\nnewline", "normal-model")
        val id = repository.save(
            ProviderConfig(name = "Test", baseUrl = "url", apiKeyRef = "test", models = models)
        )
        assertEquals(models, repository.get(id)!!.models)
    }

    @Test
    fun models_with_backslash_round_trip() {
        // 模型名含字面反斜杠，验证转义正确
        val models = listOf("model\\with\\backslash", "normal")
        val id = repository.save(
            ProviderConfig(name = "Test", baseUrl = "url", apiKeyRef = "test", models = models)
        )
        assertEquals(models, repository.get(id)!!.models)
    }

    @Test
    fun models_with_backslash_followed_by_n_round_trip() {
        // 模型名含字面反斜杠+n（非换行符），验证不与 \n 转义冲突
        val models = listOf("model\\n", "path\\to\\model")
        val id = repository.save(
            ProviderConfig(name = "Test", baseUrl = "url", apiKeyRef = "test", models = models)
        )
        assertEquals(models, repository.get(id)!!.models)
    }

    @Test
    fun headers_with_equals_in_value_round_trip() {
        val headers = mapOf("key" to "value=with=equals", "another" to "a=b=c")
        val id = repository.save(
            ProviderConfig(name = "Test", baseUrl = "url", apiKeyRef = "test", headers = headers)
        )
        assertEquals(headers, repository.get(id)!!.headers)
    }

    @Test
    fun headers_with_newline_in_value_round_trip() {
        val headers = mapOf("key" to "line1\nline2", "multi" to "a\nb\nc")
        val id = repository.save(
            ProviderConfig(name = "Test", baseUrl = "url", apiKeyRef = "test", headers = headers)
        )
        assertEquals(headers, repository.get(id)!!.headers)
    }

    @Test
    fun headers_with_backslash_followed_by_e_round_trip() {
        // value 含字面反斜杠 + e + 等号，验证转义序列不产生歧义
        // 修复 StringMapConverter 链式 replace 的 bug（单次扫描反转义）
        val headers = mapOf("key" to "a\\eb=c", "another" to "x\\e=y\\e")
        val id = repository.save(
            ProviderConfig(name = "Test", baseUrl = "url", apiKeyRef = "test", headers = headers)
        )
        assertEquals(headers, repository.get(id)!!.headers)
    }

    @Test
    fun headers_with_backslash_followed_by_n_round_trip() {
        // value 含字面反斜杠 + n（非换行符），验证不与 \n 转义冲突
        val headers = mapOf("key" to "a\\nb", "another" to "path\\to\\file")
        val id = repository.save(
            ProviderConfig(name = "Test", baseUrl = "url", apiKeyRef = "test", headers = headers)
        )
        assertEquals(headers, repository.get(id)!!.headers)
    }

    @Test
    fun headers_with_mixed_escape_sequences_round_trip() {
        // value 同时包含 \n、=、\e、\\e、\n= 等多种组合
        val headers = mapOf(
            "k1" to "a\\e=b\nc",
            "k2" to "x=y\\n=z",
            "k3" to "\\n\\e\\\\"
        )
        val id = repository.save(
            ProviderConfig(name = "Test", baseUrl = "url", apiKeyRef = "test", headers = headers)
        )
        assertEquals(headers, repository.get(id)!!.headers)
    }

    @Test
    fun single_model_round_trip() {
        val id = repository.save(
            ProviderConfig(name = "Test", baseUrl = "url", apiKeyRef = "test", models = listOf("only-model"))
        )
        assertEquals(listOf("only-model"), repository.get(id)!!.models)
    }

    @Test
    fun default_values_when_not_set() {
        val id = repository.save(
            ProviderConfig(name = "Test", baseUrl = "url", apiKeyRef = "test")
        )
        val retrieved = repository.get(id)!!
        assertTrue("默认 models 应为空列表", retrieved.models.isEmpty())
        assertTrue("默认 headers 应为空映射", retrieved.headers.isEmpty())
        assertFalse("默认 isActive 应为 false", retrieved.isActive)
        assertTrue("createdAt 应为正数", retrieved.createdAt > 0)
    }

    // ==================== providers StateFlow（UI 订阅） ====================

    @Test
    fun providers_flow_empty_initial() {
        assertTrue("初始 providers 应为空", repository.providers.value.isEmpty())
    }

    @Test
    fun providers_flow_reflects_save() {
        repository.save(ProviderConfig(name = "OpenAI", baseUrl = "url-a", apiKeyRef = "openai"))
        repository.save(ProviderConfig(name = "Anthropic", baseUrl = "url-b", apiKeyRef = "anthropic"))

        val names = repository.providers.value.map { it.name }
        assertEquals(listOf("OpenAI", "Anthropic"), names)
    }

    @Test
    fun providers_flow_reflects_create_from_preset() {
        repository.createFromPreset(ProviderPresets.ollama)

        assertEquals(1, repository.providers.value.size)
        assertEquals("Ollama", repository.providers.value[0].name)
    }

    @Test
    fun providers_flow_reflects_remove() {
        val id = repository.save(ProviderConfig(name = "Temp", baseUrl = "url", apiKeyRef = "temp"))
        assertEquals(1, repository.providers.value.size)

        repository.remove(id)

        assertTrue("删除后 providers 应为空", repository.providers.value.isEmpty())
    }

    @Test
    fun providers_flow_sorted_by_created_at() {
        repository.save(ProviderConfig(name = "First", baseUrl = "url", apiKeyRef = "first"))
        Thread.sleep(1)
        repository.save(ProviderConfig(name = "Second", baseUrl = "url", apiKeyRef = "second"))

        assertEquals(
            listOf("First", "Second"),
            repository.providers.value.map { it.name }
        )
    }
}
