package io.prism.ui.capabilities

import androidx.datastore.preferences.core.emptyPreferences
import io.objectbox.BoxStore
import io.prism.data.MemoryRepository
import io.prism.data.ProviderConfig
import io.prism.data.UserProfileRepository
import io.prism.memory.MemoryConfigRepository
import io.prism.memory.UserProfileManager
import io.prism.network.ChatCompletionProvider
import io.prism.security.FakePreferenceDataStore
import io.prism.ui.model.ChatMessage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * MemoryManagementViewModel saveProfile VM 级集成测试（G2-05，批次2 遗留技术债，ADR-029）。
 *
 * **背景**（guardrail TKN-UXR8-B2-GUARDRAIL-001 G2-05）：O1 L3 画像自然语言化的
 * saveProfile 仅覆盖 companion 纯函数（validateProfile / nextAvailableKey），
 * 缺少 **VM 级集成测试**——真实实例化 ViewModel 验证「saveProfile 调用 → 派生 key →
 * 冲突处理 → repository 持久化 → uiMessage 反馈」完整链路。
 *
 * **测试层级**：真实 ObjectBox（temp dir）+ 真实 UserProfileRepository / MemoryRepository /
 * MemoryConfigRepository（FakePreferenceDataStore）+ 真实 UserProfileManager（Fake
 * ChatCompletionProvider 仅用于构造，saveProfile 路径不调用 LLM）+ 真实 ViewModel。
 *
 * **覆盖**：
 * 1. 新建（blank key）→ 自然语言派生 key → 持久化 + uiMessage「已新增偏好」
 * 2. 同 key 同 value → 幂等「该偏好已存在，无需重复添加」，不产生重复
 * 3. 同 key 异 value（G-01/BR-interface-015）→ nextAvailableKey 追加 `_2`，两条并存
 * 4. 编辑模式（existingId>0）→ update 保留原 key + uiMessage「已更新偏好」
 * 5. 校验失败（空 value）→ uiMessage Error + 不持久化
 *
 * 关联 ADR：[ADR-029](../../docs/decisions/ADR-029-uxr8-b2-optimizations.md)、ADR-015。
 */
class MemoryManagementViewModelSaveProfileIntegrationTest {

    private lateinit var boxStore: BoxStore
    private lateinit var tempDir: File
    private lateinit var userProfileRepository: UserProfileRepository
    private lateinit var viewModel: MemoryManagementViewModel

    /** Fake ChatCompletionProvider（仅构造 UserProfileManager；saveProfile 不调用 LLM）。 */
    private class FakeCompletionProvider : ChatCompletionProvider {
        override suspend fun chatCompletion(
            config: ProviderConfig,
            messages: List<ChatMessage>,
            systemPrompt: String?,
            ragContext: String?,
            thinkingEnabled: Boolean?,
            reasoningEffort: String?
        ): String? = null
    }

    @Before
    fun setUp() {
        tempDir = kotlin.io.path.createTempDirectory(prefix = "mm-vm-saveprofile-").toFile()
        boxStore = io.prism.data.MyObjectBox.builder().directory(tempDir).build()
        userProfileRepository = UserProfileRepository(boxStore)
        val memoryRepository = MemoryRepository(boxStore)
        val memoryConfigRepository = MemoryConfigRepository(FakePreferenceDataStore(emptyPreferences()))
        val userProfileManager = UserProfileManager(FakeCompletionProvider(), userProfileRepository)
        viewModel = MemoryManagementViewModel(
            memoryRepository = memoryRepository,
            userProfileRepository = userProfileRepository,
            memoryConfigRepository = memoryConfigRepository,
            userProfileManager = userProfileManager
        )
    }

    @After
    fun tearDown() {
        boxStore.close()
        tempDir.deleteRecursively()
    }

    // ==================== 1. 新建：自然语言派生 key + 持久化 ====================

    @Test
    fun `saveProfile new blank key derives key and persists`() {
        viewModel.saveProfile(key = "", value = "我喜欢简洁的回答", existingId = 0L)

        val saved = userProfileRepository.getAll()
        assertEquals("应持久化 1 条画像", 1, saved.size)
        assertTrue("派生 key 应为非空", saved[0].key.isNotBlank())
        assertEquals("value 应为 trim 后的原句", "我喜欢简洁的回答", saved[0].value)
        assertEquals(
            "uiMessage 应为「已新增偏好」",
            "已新增偏好",
            (viewModel.uiMessage.value as? MemoryManagementViewModel.UiMessage.Info)?.text
        )
        // VM profiles StateFlow 同步反映新画像
        assertEquals("VM profiles 应含新画像", 1, viewModel.profiles.value.size)
    }

    // ==================== 2. 同 key 同 value：幂等 ====================

    @Test
    fun `saveProfile same key and value is idempotent`() {
        // 显式 key：新建模式两次相同输入 → 第二次幂等提示，不重复
        viewModel.saveProfile(key = "tone", value = "简洁", existingId = 0L)
        viewModel.saveProfile(key = "tone", value = "简洁", existingId = 0L)

        assertEquals("不应产生重复条目", 1, userProfileRepository.getAll().size)
        val msg = (viewModel.uiMessage.value as? MemoryManagementViewModel.UiMessage.Info)?.text
        assertEquals("第二次应幂等提示", "该偏好已存在，无需重复添加", msg)
    }

    // ==================== 3. 同 key 异 value：G-01 追加序号 ====================

    @Test
    fun `saveProfile same key different value appends suffix key`() {
        viewModel.saveProfile(key = "tone", value = "简洁", existingId = 0L)
        viewModel.saveProfile(key = "tone", value = "正式", existingId = 0L)

        val saved = userProfileRepository.getAll()
        assertEquals("两条画像应并存", 2, saved.size)
        val keys = saved.map { it.key }.toSet()
        assertTrue("应含原 key tone", keys.contains("tone"))
        assertTrue("应含序号 key tone_2（BR-interface-015）", keys.contains("tone_2"))
        assertTrue("原 value 保留", saved.any { it.key == "tone" && it.value == "简洁" })
        assertTrue("新 value 落序号 key", saved.any { it.key == "tone_2" && it.value == "正式" })
        assertEquals("第三条应提示已新增", "已新增偏好", (viewModel.uiMessage.value as? MemoryManagementViewModel.UiMessage.Info)?.text)
    }

    // ==================== 4. 编辑模式：update 保留原 key ====================

    @Test
    fun `saveProfile edit mode updates value preserving key`() {
        val id = userProfileRepository.save(
            io.prism.data.UserProfile(key = "tone", value = "简洁", category = io.prism.data.ProfileCategory.EXPLICIT.name)
        )
        viewModel.saveProfile(key = "tone", value = "改为正式", existingId = id)

        val saved = userProfileRepository.getAll()
        assertEquals("编辑不应新增条目", 1, saved.size)
        assertEquals("应保留原 key", "tone", saved[0].key)
        assertEquals("value 应更新", "改为正式", saved[0].value)
        assertEquals("应提示已更新", "已更新偏好", (viewModel.uiMessage.value as? MemoryManagementViewModel.UiMessage.Info)?.text)
    }

    // ==================== 5. 校验失败：Error + 不持久化 ====================

    @Test
    fun `saveProfile empty value shows error and does not persist`() {
        viewModel.saveProfile(key = "", value = "   ", existingId = 0L)

        assertEquals("校验失败不应持久化", 0, userProfileRepository.getAll().size)
        assertTrue(
            "应提示校验错误",
            viewModel.uiMessage.value is MemoryManagementViewModel.UiMessage.Error
        )
    }

    // ==================== 6. 冲突保护不破坏既有画像（回归） ====================

    @Test
    fun `saveProfile idempotent check does not mutate existing profiles`() {
        viewModel.saveProfile(key = "language", value = "中文", existingId = 0L)
        val before = userProfileRepository.getAll().map { it.key to it.value }

        // 重复提交同 key 同 value → 幂等，无副作用
        viewModel.saveProfile(key = "language", value = "中文", existingId = 0L)
        val after = userProfileRepository.getAll().map { it.key to it.value }

        assertEquals(before, after)
        assertNotNull("uimessage 应为幂等提示", viewModel.uiMessage.value)
        assertNotEquals("不应报错", MemoryManagementViewModel.UiMessage.Error::class, viewModel.uiMessage.value?.javaClass)
    }
}
