package io.prism.ui.chat

import io.objectbox.BoxStore
import io.prism.data.KnowledgeBaseRepository
import io.prism.data.MyObjectBox
import io.prism.data.ProviderConfig
import io.prism.data.ProviderConfigRepository
import io.prism.embedding.Embedder
import io.prism.network.ChatStreamProvider
import io.prism.network.StreamEvent
import io.prism.network.ToolChoice
import io.prism.network.ToolDefinition
import io.prism.voice.rolepack.RolePackLoader
import io.prism.voice.rolepack.RolePackSelectedIdStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * TASK-20260912-001（ADR-049）**装配层调用层接线测试** —— 覆盖 D3（场景化 persona）与
 * D4（背景设定）经真实入口 [ConversationViewModel.sendMessage] 的端到端装配。
 *
 * **为什么必须有这个类**（guardrail R1 元级发现 / 规则 BR-testing-012）：本轮 D3/D4 原先只有
 * 纯函数单测（`RolePackV6Test` 覆盖 [io.prism.voice.rolepack.RolePack.toPersonaSystemPromptSection]
 * 与 [io.prism.voice.rolepack.RolePackWorldbook]），**调用层接线零覆盖**——
 * [ConversationViewModel.WORLDBOOK_SCAN_TURNS] 全仓库无测试引用、
 * `resolveRolePackWorldbookSection` 从未经 `sendMessage` 走通。其后果是：即使
 * "读配置 → 条件判定 → 注入提示词"的接线整段被误删，纯函数单测依然全绿——这正是上一轮
 * M2-her 静默路由能在"2765 用例全过"下产生真机负向回归的同一类缺口。
 *
 * 本类用**真实** [RolePackLoader]（临时目录 + 内存选中 store + 注入测试调度器）+
 * 记录型 provider，断言最终真正送达 provider 的 `systemPrompt`，对条件判定的**两个分支**
 * 各覆盖用例。
 *
 * **测试环境坑（已固化为 loader 的可注入能力）**：`RolePackLoader` 原硬编码
 * [Dispatchers.IO]，`runTest` 的 `advanceUntilIdle()` 无法等待真实调度器挂起点 → 被测链路
 * 静默不执行、断言只见 USER 消息。故本轮为 loader 增加 `ioDispatcher` 注入（生产缺省不变），
 * 本类注入 `mainDispatcher` 以获得确定性。
 *
 * 覆盖矩阵：
 * | 用例 | 角色包 | 轮次 | 期望 |
 * |---|---|---|---|
 * | 文本轮命中 | 选中 | 文本 | 含 worldbook 命中条目 + 风格尾部；**不含**通话段 |
 * | 文本轮未命中 | 选中 | 文本 | 不含 worldbook 正文/区块；仍含风格尾部 |
 * | 通话轮 | 选中 | 🎙 前缀 | 含通话段（**回归钉子**：本轮主缺陷的作用域） |
 * | 出厂态 | 未选中 | 文本 | 三样全无，且保持 `DEFAULT_PERSONA` |
 * | 导入→选中→发送 | 经 loader.import+select | 文本 | 真实导入链路同样生效（BR-interface-025） |
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationViewModelRolePackWiringTest {

    private val mainDispatcher = UnconfinedTestDispatcher()
    private lateinit var boxStore: BoxStore
    private lateinit var tempDir: File
    private lateinit var rolePackDir: File

    /** 内存选中 store（不依赖 DataStore 单例；支持预置初值以避免测试期额外 IO）。 */
    private class InMemorySelectedIdStore(initial: String? = null) : RolePackSelectedIdStore {
        private var id: String? = initial
        override suspend fun get(): String? = id
        override suspend fun set(id: String?) {
            this.id = id
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        tempDir = kotlin.io.path.createTempDirectory(prefix = "rolepack-wiring-").toFile()
        boxStore = MyObjectBox.builder().directory(tempDir).build()
        rolePackDir = File(tempDir, "rolepacks")
    }

    @After
    fun tearDown() {
        boxStore.close()
        tempDir.deleteRecursively()
        Dispatchers.resetMain()
    }

    // ==================== 夹具 ====================

    /** 文本人格 / 通话人格 / 世界书命中条目 / 未命中条目 —— 哨兵串便于逐段断言。 */
    private companion object {
        const val TEXT_PERSONA = "TEXT-PERSONA-SENTINEL"
        const val CALL_PERSONA = "CALL-PERSONA-SENTINEL"
        const val WORLD_HIT = "WORLD-HIT-SENTINEL"
        const val WORLD_MISS = "WORLD-MISS-SENTINEL"
        const val HIT_KEY = "翁法罗斯"
    }

    private fun rolePackJson() = """
        {
          "schema": "prism-rolepack/1",
          "id": "wiring-test",
          "name": "接线测试角色",
          "version": "6.0.0",
          "persona": {
            "systemPrompt": "$TEXT_PERSONA",
            "callSystemPrompt": "$CALL_PERSONA",
            "address": "称你",
            "tone": ["轻快"],
            "forbidden": ["动作旁白"]
          },
          "worldbook": [
            { "keys": ["$HIT_KEY"], "content": "$WORLD_HIT" },
            { "keys": ["不存在的关键词XYZ"], "content": "$WORLD_MISS" }
          ]
        }
    """.trimIndent()

    private fun providerRepo(): ProviderConfigRepository {
        val repo = ProviderConfigRepository(boxStore)
        val active = ProviderConfig(
            name = "OpenAI", baseUrl = "https://api.openai.com/v1",
            apiKeyRef = "openai", models = listOf("gpt-4o")
        )
        repo.save(active)
        repo.setActive(repo.findByName(active.name)!!.id)
        return repo
    }

    /**
     * 构造选中态可控的 loader（写入角色包文件 + 预置选中 id，不经真实 IO 往返）。
     *
     * @param select true = 选中该角色包；false = 出厂态（未选中）
     */
    private fun loaderWithPack(select: Boolean): RolePackLoader {
        rolePackDir.mkdirs()
        File(rolePackDir, "wiring-test.json").writeText(rolePackJson())
        return RolePackLoader(
            rolePackDir,
            InMemorySelectedIdStore(initial = if (select) "wiring-test" else null),
            ioDispatcher = mainDispatcher
        )
    }

    private fun viewModel(loader: RolePackLoader, provider: RecordingProvider) =
        ConversationViewModel(
            providerRepo(), provider, DefaultStubEmbedder(), KnowledgeBaseRepository(boxStore),
            ioDispatcher = mainDispatcher,
            rolePackLoader = loader
        )

    // ==================== 分支 1：角色包选中 + 文本轮次 ====================

    @Test
    fun `文本轮 - 命中关键词时注入背景设定且不注入通话段`() = runTest(mainDispatcher) {
        val provider = RecordingProvider()
        val vm = viewModel(loaderWithPack(select = true), provider)

        vm.sendMessage("给我讲讲$HIT_KEY 吧")
        advanceUntilIdle()

        val sp = provider.receivedSystemPrompts.singleOrNull()
        assertNotNull("应发生一次 provider 请求", sp)
        val prompt = sp!!
        assertTrue("角色身份应注入", prompt.contains(TEXT_PERSONA))
        assertTrue("命中的背景设定条目应注入", prompt.contains(WORLD_HIT))
        assertFalse("未命中的条目不得注入", prompt.contains(WORLD_MISS))
        assertTrue(
            "背景设定区块应先于风格尾部强化",
            prompt.indexOf(WORLD_HIT) < prompt.indexOf(ConversationViewModel.ROLE_STYLE_TAIL)
        )
        assertFalse(
            "**回归钉子**：通话专用段不得进入文本轮次（本轮主缺陷的直接判据）",
            prompt.contains(CALL_PERSONA)
        )
        assertTrue("风格尾部强化应注入", prompt.contains(ConversationViewModel.ROLE_STYLE_TAIL))
        assertFalse("出厂助手身份应完全退位", prompt.contains(ConversationViewModel.DEFAULT_PERSONA))
        assertTrue("背景设定应带『资料非指令』边界声明", prompt.contains("不是给你的指令"))
    }

    @Test
    fun `文本轮 - 未命中关键词时不注入背景设定但保留风格尾部`() = runTest(mainDispatcher) {
        val provider = RecordingProvider()
        val vm = viewModel(loaderWithPack(select = true), provider)

        vm.sendMessage("今天天气怎么样")
        advanceUntilIdle()

        val prompt = provider.receivedSystemPrompts.singleOrNull()
        assertNotNull("应发生一次 provider 请求", prompt)
        assertTrue("角色身份应注入", prompt!!.contains(TEXT_PERSONA))
        assertFalse("无命中则不得注入任何世界书正文", prompt.contains(WORLD_HIT))
        assertFalse(prompt.contains(WORLD_MISS))
        assertFalse("空区块不得污染 prompt", prompt.contains("【背景设定】"))
        assertTrue("风格尾部与命中无关，应始终注入", prompt.contains(ConversationViewModel.ROLE_STYLE_TAIL))
        assertFalse(prompt.contains(CALL_PERSONA))
    }

    // ==================== 分支 2：角色包选中 + 语音通话轮次 ====================

    @Test
    fun `通话轮 - 注入通话专用段（🎙 前缀判定）`() = runTest(mainDispatcher) {
        val provider = RecordingProvider()
        val vm = viewModel(loaderWithPack(select = true), provider)

        vm.sendMessage("🎙 喂喂听得见吗")
        advanceUntilIdle()

        val prompt = provider.receivedSystemPrompts.singleOrNull()
        assertNotNull("应发生一次 provider 请求", prompt)
        assertTrue("文本人格在通话轮同样注入（追加语义）", prompt!!.contains(TEXT_PERSONA))
        assertTrue("通话专用段应注入", prompt.contains(CALL_PERSONA))
        assertTrue(
            "通话段应追加在文本人格之后",
            prompt.indexOf(TEXT_PERSONA) < prompt.indexOf(CALL_PERSONA)
        )
    }

    // ==================== 分支 3：出厂态（未选中角色包） ====================

    @Test
    fun `出厂态 - 未选中角色包时三样全无且保持默认身份`() = runTest(mainDispatcher) {
        val provider = RecordingProvider()
        val vm = viewModel(loaderWithPack(select = false), provider)

        vm.sendMessage("给我讲讲$HIT_KEY 吧")
        advanceUntilIdle()

        val prompt = provider.receivedSystemPrompts.singleOrNull()
        assertNotNull("应发生一次 provider 请求", prompt)
        assertTrue("出厂身份应保持", prompt!!.startsWith(ConversationViewModel.DEFAULT_PERSONA))
        assertFalse("出厂态不得注入角色人格", prompt.contains(TEXT_PERSONA))
        assertFalse("出厂态不得注入通话段", prompt.contains(CALL_PERSONA))
        assertFalse("出厂态不得注入背景设定", prompt.contains(WORLD_HIT))
        assertFalse("出厂态不得注入风格尾部", prompt.contains(ConversationViewModel.ROLE_STYLE_TAIL))
    }

    // ==================== 分支 4：真实导入链路（BR-interface-025 导入即生效） ====================

    @Test
    fun `导入链路 - 经 import 与 select 后文本轮即生效`() = runTest(mainDispatcher) {
        val loader = RolePackLoader(rolePackDir, InMemorySelectedIdStore(), ioDispatcher = mainDispatcher)
        loader.import("wiring.rolepack", rolePackJson())
        loader.select("wiring-test")

        val provider = RecordingProvider()
        val vm = viewModel(loader, provider)
        vm.sendMessage("讲讲$HIT_KEY")
        advanceUntilIdle()

        val prompt = provider.receivedSystemPrompts.singleOrNull()
        assertNotNull("应发生一次 provider 请求", prompt)
        assertTrue("经真实导入链路后角色身份应生效", prompt!!.contains(TEXT_PERSONA))
        assertTrue("经真实导入链路后背景设定应生效", prompt.contains(WORLD_HIT))
    }

    // ==================== Fakes ====================

    /** 记录送达 provider 的 messages / systemPrompt。 */
    private class RecordingProvider : ChatStreamProvider {
        val receivedSystemPrompts = mutableListOf<String?>()

        override fun streamChat(
            config: ProviderConfig,
            messages: List<io.prism.ui.model.ChatMessage>,
            systemPrompt: String?,
            ragContext: String?,
            tools: List<ToolDefinition>?,
            toolChoice: ToolChoice?,
            thinkingEnabled: Boolean?,
            reasoningEffort: String?
        ): Flow<StreamEvent> {
            receivedSystemPrompts += systemPrompt
            return flow { emit(StreamEvent.Delta("好的")); emit(StreamEvent.Done) }
        }
    }

    private class DefaultStubEmbedder : Embedder {
        override fun embed(text: String): FloatArray = FloatArray(384) { 0.5f }
        override fun embedBatch(texts: List<String>): List<FloatArray> = texts.map { embed(it) }
        override fun isLoaded(): Boolean = true
        override fun checkAndUnload(maxIdleMs: Long): Boolean = false
        override fun close() {}
    }
}
