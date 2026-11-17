package io.prism.voice.rolepack

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 批次23-B（ADR-048）：[RolePackLoader] 单测 —— 临时目录注入（JVM 纯单测，不依赖 Robolectric）。
 *
 * 覆盖：导入/列举 round-trip / 选中与生效 / 删除自动回落 B0 / 畸形文件跳过 /
 * 选中文件损坏回落 B0 / 文件名防路径穿越 / 选中持久化（内存 store + DataStore 实现）。
 */
class RolePackLoaderTest {

    /** 内存选中 store（JVM 可测；生产为 DataStore 实现）。 */
    private class MemorySelectedIdStore : RolePackSelectedIdStore {
        var value: String? = null
        override suspend fun get(): String? = value
        override suspend fun set(id: String?) { value = id }
    }

    private lateinit var baseDir: File
    private lateinit var store: MemorySelectedIdStore
    private lateinit var loader: RolePackLoader

    private val packJsonA = """{"schema":"prism-rolepack/1","id":"pack-a","name":"角色A","version":"1.0.0",
        "persona":{"systemPrompt":"A 的人设"},"style":{"voiceCall":{"maxSentenceLen":20}}}"""
    private val packJsonB = """{"schema":"prism-rolepack/1","id":"pack-b","name":"角色B"}"""

    @Before
    fun setUp() {
        baseDir = Files.createTempDirectory("prism-rolepack-test").toFile()
        store = MemorySelectedIdStore()
        loader = RolePackLoader(baseDir, store)
    }

    @After
    fun tearDown() {
        baseDir.deleteRecursively()
    }

    @Test
    fun `导入与列举 - round-trip`() = runBlocking<Unit> {
        loader.import("角色A.rolepack", packJsonA)
        loader.import("角色B.rolepack", packJsonB)
        val packs = loader.list()
        assertEquals(listOf("角色A", "角色B"), packs.map { it.name })  // 按显示名排序
        assertEquals("A 的人设", packs.first { it.id == "pack-a" }.persona.systemPrompt)
        assertEquals(20, packs.first { it.id == "pack-a" }.style.voiceCall.maxSentenceLen)
    }

    @Test
    fun `导入 - 落盘文件名为 id 规范化`() = runBlocking<Unit> {
        loader.import("任意来源名.rolepack", packJsonA)
        assertEquals(listOf("pack-a.json"), baseDir.listFiles()!!.map { it.name })
    }

    @Test
    fun `导入 - 同 id 重复导入覆盖更新（幂等）`() = runBlocking<Unit> {
        loader.import("a.rolepack", packJsonA)
        val updated = packJsonA.replace("A 的人设", "更新后的人设")
        loader.import("a.rolepack", updated)
        val packs = loader.list()
        assertEquals(1, packs.size)
        assertEquals("更新后的人设", packs.single().persona.systemPrompt)
    }

    @Test
    fun `生效包 - 同 id 存在遗留副本时必须优先规范存储名`() = runBlocking<Unit> {
        // 2026-11-15（ADR-076 复审）：真机升级 v7.1 后目录里同时有 cyrene.json（新）与
        // cyrene.rolepack（旧）。`findPackFile` 用 `firstOrNull` 按 id 匹配，若文件枚举
        // 无序就可能加载旧版本（与 ADR-052 E1 同类陷阱）。这里刻意先写"旧"副本。
        val stale = packJsonA.replace("A 的人设", "旧版本人设")
        File(baseDir, "pack-a.rolepack").writeText(stale)
        loader.import("a.rolepack", packJsonA)  // 规范名 pack-a.json
        store.value = "pack-a"
        assertEquals(
            "必须优先规范存储名 .json（否则真机会静默加载旧资产）",
            "A 的人设",
            loader.effectiveRolePack().persona.systemPrompt
        )
        // 反向对照：只留遗留副本时也能正常加载（不因优先级规则而失效）
        File(baseDir, "pack-a.json").delete()
        assertEquals(
            "仅有遗留副本时应仍可加载（扩展名不限）",
            "旧版本人设",
            loader.effectiveRolePack().persona.systemPrompt
        )
    }

    @Test
    fun `删除 - 同 id 存在遗留副本时必须一并删除（R4-L-02）`() = runBlocking<Unit> {
        // 只删 .json 会让遗留 .rolepack 残留 → "删除后列表里还有、且静默加载旧版本"
        File(baseDir, "pack-a.rolepack").writeText(packJsonA.replace("A 的人设", "旧版本人设"))
        loader.import("a.rolepack", packJsonA)
        assertTrue("应报告删除成功", loader.remove("pack-a"))
        assertTrue(
            "同 id 的全部文件都必须删除（实际残留：${baseDir.listFiles()?.map { it.name }}）",
            baseDir.listFiles().isNullOrEmpty()
        )
        assertTrue(loader.list().isEmpty())
    }

    @Test
    fun `列举 - 同 id 存在遗留副本时只展示一张卡（R4-L-01）`() = runBlocking<Unit> {
        // 遗留副本的 name 刻意不同：若"先排序再去重"，展示的可能是旧那份
        File(baseDir, "pack-a.rolepack").writeText(
            """{"schema":"prism-rolepack/1","id":"pack-a","name":"旧名字","persona":{"systemPrompt":"旧版本人设"}}"""
        )
        loader.import("a.rolepack", packJsonA)
        val packs = loader.list()
        assertEquals("同 id 只能出现一张卡", 1, packs.size)
        assertEquals("展示的必须是规范名那份（新）", "角色A", packs.single().name)
        assertEquals("A 的人设", packs.single().persona.systemPrompt)
    }

    @Test
    fun `导入 - 畸形 JSON 抛异常且不落盘`() = runBlocking<Unit> {
        assertThrows(RolePackParseException::class.java) {
            runBlocking { loader.import("bad.rolepack", "{ broken json") }
        }
        assertTrue(baseDir.listFiles().isNullOrEmpty())
    }

    @Test
    fun `导入 - schema 版本不支持抛异常且不落盘`() = runBlocking<Unit> {
        assertThrows(RolePackParseException::class.java) {
            runBlocking { loader.import("v2.rolepack", """{"schema":"prism-rolepack/2","id":"x","name":"x"}""") }
        }
        assertTrue(baseDir.listFiles().isNullOrEmpty())
    }

    @Test
    fun `导入 - 保留 id default 被拒绝且不落盘`() = runBlocking<Unit> {
        assertThrows(RolePackParseException::class.java) {
            runBlocking { loader.import("builtin.rolepack", """{"schema":"prism-rolepack/1","id":"default","name":"冒充内置"}""") }
        }
        assertTrue(baseDir.listFiles().isNullOrEmpty())
    }

    @Test
    fun `出厂态 - 未选中时 effective 为 B0`() = runBlocking<Unit> {
        val effective = loader.effectiveRolePack()
        assertEquals(RolePack.DEFAULT_ROLE_PACK, effective)
        assertTrue(effective.isBuiltin)
        assertNull(loader.selectedId())
    }

    @Test
    fun `选中与生效 - 选中后 effective 为选中包`() = runBlocking<Unit> {
        loader.import("a.rolepack", packJsonA)
        loader.select("pack-a")
        assertEquals("pack-a", loader.selectedId())
        val effective = loader.effectiveRolePack()
        assertEquals("pack-a", effective.id)
        assertFalse(effective.isBuiltin)
    }

    @Test
    fun `选中不存在 id - effective 回落 B0`() = runBlocking<Unit> {
        loader.select("ghost")
        assertTrue(loader.effectiveRolePack().isBuiltin)
    }

    @Test
    fun `删除选中包 - 自动回落 B0 且选中态清除`() = runBlocking<Unit> {
        loader.import("a.rolepack", packJsonA)
        loader.select("pack-a")
        assertTrue(loader.remove("pack-a"))
        assertNull(loader.selectedId())  // 选中态同步清除
        assertTrue(loader.effectiveRolePack().isBuiltin)  // 回落 B0
    }

    @Test
    fun `删除非选中包 - 选中态不受影响`() = runBlocking<Unit> {
        loader.import("a.rolepack", packJsonA)
        loader.import("b.rolepack", packJsonB)
        loader.select("pack-b")
        assertTrue(loader.remove("pack-a"))
        assertEquals("pack-b", loader.selectedId())
        assertEquals("pack-b", loader.effectiveRolePack().id)
    }

    @Test
    fun `删除不存在 id - 返回 false`() = runBlocking<Unit> {
        assertFalse(loader.remove("ghost"))
    }

    @Test
    fun `畸形文件 - 列举跳过且不影响其它包`() = runBlocking<Unit> {
        loader.import("a.rolepack", packJsonA)
        File(baseDir, "corrupted.json").writeText("{ not json")
        File(baseDir, "wrong-schema.json").writeText("""{"schema":"prism-rolepack/9","id":"w","name":"w"}""")
        val packs = loader.list()
        assertEquals(listOf("角色A"), packs.map { it.name })
    }

    @Test
    fun `选中文件被外部损坏 - effective 回落 B0 不抛`() = runBlocking<Unit> {
        loader.import("a.rolepack", packJsonA)
        loader.select("pack-a")
        // 外部改写为损坏内容（模拟文件被外部工具破坏）
        baseDir.resolve("pack-a.json").writeText("{ corrupted by external tool")
        assertTrue(loader.effectiveRolePack().isBuiltin)
    }

    @Test
    fun `路径穿越 - id 含路径分隔符被规范化在目录内`() = runBlocking<Unit> {
        val evil = """{"schema":"prism-rolepack/1","id":"../../evil","name":"穿越"}"""
        loader.import("evil.rolepack", evil)
        // 分隔符替换为 _（../../evil → .._.._evil），无目录逃逸
        val files = baseDir.listFiles()!!.map { it.name }
        assertEquals(listOf(".._.._evil.json"), files)
        assertEquals(1, baseDir.walkTopDown().filter { it.isFile }.count())
    }

    @Test
    fun `clearSelection - 清除后回落 B0`() = runBlocking<Unit> {
        loader.import("a.rolepack", packJsonA)
        loader.select("pack-a")
        loader.clearSelection()
        assertNull(loader.selectedId())
        assertTrue(loader.effectiveRolePack().isBuiltin)
    }

    // ==================== DataStore 实现 ====================

    @Test
    fun `DataStoreSelectedIdStore - set get 清除持久化语义`() = runBlocking<Unit> {
        val dataStoreDir = Files.createTempDirectory("prism-rolepack-ds-test").toFile()
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        ) { File(dataStoreDir, "test_rolepack.preferences_pb") }
        val dsStore = DataStoreRolePackSelectedIdStore(dataStore)
        assertNull(dsStore.get())
        dsStore.set("pack-a")
        assertEquals("pack-a", dsStore.get())
        dsStore.set(null)
        assertNull(dsStore.get())
        dataStoreDir.deleteRecursively()
    }
}
