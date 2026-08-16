package io.prism.config

import androidx.datastore.preferences.core.emptyPreferences
import io.prism.rag.RagTarget
import io.prism.security.FakePreferenceDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * RagTargetConfigRepository 单元测试（UXR8 Bug1，ADR-028）。
 *
 * 验证 RagTarget 三态（Off / AllLibraries / SpecificLibrary）的 DataStore 持久化：
 * - 默认值 AllLibraries（对齐 ADR-012 5.2「默认开启」）
 * - 三态设置/读取往返
 * - Flow 响应新值
 * - decode 容错（未知 mode / kbId<=0 → 回退 AllLibraries，fail-safe，BR-security-005）
 *
 * 背景（UXR8 Bug1，考古 TKN-UXR8-ARCHAEOLOGY-001）：修复"用户关闭 RAG 后
 * 新对话/重启又被重置为全库 → 知识库内容被系统主动注入"。
 */
class RagTargetConfigRepositoryTest {

    private lateinit var dataStore: FakePreferenceDataStore
    private lateinit var repository: RagTargetConfigRepository

    @Before
    fun setUp() {
        dataStore = FakePreferenceDataStore(emptyPreferences())
        repository = RagTargetConfigRepository(dataStore)
    }

    @Test
    fun `default rag target is AllLibraries`() = runBlocking {
        assertEquals("未配置时应默认全库（ADR-012 默认开启）", RagTarget.AllLibraries, repository.getRagTarget())
    }

    @Test
    fun `flow emits default AllLibraries initially`() = runBlocking {
        assertEquals("Flow 首次发射应为 AllLibraries", RagTarget.AllLibraries, repository.ragTarget().first())
    }

    @Test
    fun `set and read back AllLibraries`() = runBlocking {
        repository.setRagTarget(RagTarget.AllLibraries)
        assertEquals(RagTarget.AllLibraries, repository.getRagTarget())
    }

    @Test
    fun `set and read back Off`() = runBlocking {
        repository.setRagTarget(RagTarget.Off)
        assertEquals("关闭 RAG 后读取应为 Off（UXR8 Bug1 核心）", RagTarget.Off, repository.getRagTarget())
    }

    @Test
    fun `set and read back SpecificLibrary`() = runBlocking {
        repository.setRagTarget(RagTarget.SpecificLibrary(42L))
        val read = repository.getRagTarget()
        assertTrue("应为 SpecificLibrary", read is RagTarget.SpecificLibrary)
        assertEquals("kbId 应往返保留", 42L, (read as RagTarget.SpecificLibrary).kbId)
    }

    @Test
    fun `flow emits new value after set`() = runBlocking {
        repository.setRagTarget(RagTarget.Off)
        assertEquals("Flow 应反映设置后的新值", RagTarget.Off, repository.ragTarget().first())
    }

    @Test
    fun `decode unknown mode falls back to AllLibraries`() {
        assertEquals(
            "未知 mode 应回退 AllLibraries（fail-safe）",
            RagTarget.AllLibraries,
            RagTargetConfigRepository.decode("unknown", null)
        )
    }

    @Test
    fun `decode missing mode falls back to AllLibraries`() {
        assertEquals(
            "缺失 mode 应回退 AllLibraries",
            RagTarget.AllLibraries,
            RagTargetConfigRepository.decode(null, null)
        )
    }

    @Test
    fun `decode off mode returns Off`() {
        assertEquals(RagTarget.Off, RagTargetConfigRepository.decode("off", null))
    }

    @Test
    fun `decode specific with invalid kbId falls back to AllLibraries`() {
        // kbId <= 0（如被外部写入脏数据）→ 回退全库，避免下游检索异常（BR-security-005 纵深防御）
        assertEquals(RagTarget.AllLibraries, RagTargetConfigRepository.decode("specific", 0L))
        assertEquals(RagTarget.AllLibraries, RagTargetConfigRepository.decode("specific", -5L))
        assertEquals(RagTarget.AllLibraries, RagTargetConfigRepository.decode("specific", null))
    }

    @Test
    fun `decode specific with valid kbId returns SpecificLibrary`() {
        val target = RagTargetConfigRepository.decode("specific", 7L)
        assertTrue(target is RagTarget.SpecificLibrary)
        assertEquals(7L, (target as RagTarget.SpecificLibrary).kbId)
    }

    @Test
    fun `all mode returns AllLibraries`() {
        assertEquals(RagTarget.AllLibraries, RagTargetConfigRepository.decode("all", null))
        // all 模式下即使残留 kbId 也忽略
        assertEquals(RagTarget.AllLibraries, RagTargetConfigRepository.decode("all", 99L))
    }
}
