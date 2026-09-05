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
 * RagTargetConfigRepository 单元测试（UXR8 Bug1，ADR-028 + v1 批次18 语义变更）。
 *
 * 验证 RagTarget 三态（Off / AllLibraries / SpecificLibrary）的 DataStore 持久化：
 * - **默认值 Off（v1 批次18 语义变更，真机 RCA）**：用户未明确开启知识库时不得自动注入
 *   KB 内容（opt-in 语义；缺失/未知 mode / kbId<=0 → Off，fail-safe，BR-security-005）
 * - 三态设置/读取往返
 * - Flow 响应新值
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
    fun `default rag target is Off when never configured`() = runBlocking {
        assertEquals(
            "未配置时应默认关闭（v1 批次18：用户未开启知识库不得自动注入）",
            RagTarget.Off,
            repository.getRagTarget()
        )
    }

    @Test
    fun `flow emits default Off initially`() = runBlocking {
        assertEquals("Flow 首次发射应为 Off", RagTarget.Off, repository.ragTarget().first())
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
    fun `decode unknown mode falls back to Off`() {
        assertEquals(
            "未知 mode 应回退 Off（fail-safe + 防未请求注入）",
            RagTarget.Off,
            RagTargetConfigRepository.decode("unknown", null)
        )
    }

    @Test
    fun `decode missing mode falls back to Off`() {
        assertEquals(
            "缺失 mode 应回退 Off（首次安装 opt-in）",
            RagTarget.Off,
            RagTargetConfigRepository.decode(null, null)
        )
    }

    @Test
    fun `decode off mode returns Off`() {
        assertEquals(RagTarget.Off, RagTargetConfigRepository.decode("off", null))
    }

    @Test
    fun `decode specific with invalid kbId falls back to Off`() {
        // kbId <= 0（如被外部写入脏数据）→ 关闭，避免下游检索异常 + 注入意外（BR-security-005）
        assertEquals(RagTarget.Off, RagTargetConfigRepository.decode("specific", 0L))
        assertEquals(RagTarget.Off, RagTargetConfigRepository.decode("specific", -5L))
        assertEquals(RagTarget.Off, RagTargetConfigRepository.decode("specific", null))
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
