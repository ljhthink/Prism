package io.prism.data

import io.objectbox.BoxStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors

/**
 * ProviderConfig 边缘/极端场景测试（ac-verifier 补充，US-004）。
 *
 * 补齐主 Agent 基础用例 [ProviderConfigRepositoryTest] 未覆盖的盲区：
 * 1. setActive 对不存在的 id 的行为（G-01 相关）
 * 2. StringMapConverter 空 key / 空 value / 仅含反斜杠 / 反斜杠结尾 value
 * 3. 超长模型名、大量模型（100+）往返
 * 4. setActive 并发冲突（多线程）—— BR-concurrency-001 事务原子性验证
 * 5. 特殊字符（中文 / Unicode / emoji）在模型名与请求头中的往返
 * 6. 空 name / baseUrl 持久化（G-03 当前无校验行为确认）
 *
 * 使用 [BoxStore.directory] 在临时目录构建纯 JVM ObjectBox 实例。
 */
class ProviderConfigEdgeCaseTest {

    private lateinit var boxStore: BoxStore
    private lateinit var repository: ProviderConfigRepository
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = kotlin.io.path.createTempDirectory(prefix = "provider-edge-").toFile()
        boxStore = MyObjectBox.builder().directory(tempDir).build()
        repository = ProviderConfigRepository(boxStore)
    }

    @After
    fun tearDown() {
        boxStore.close()
        tempDir.deleteRecursively()
    }

    // ==================== setActive 对不存在 id 的行为（G-01 相关） ====================

    @Test
    fun set_active_nonexistent_id_with_nothing_active_is_noop() {
        // 初始无激活，对不存在的 id 调用 setActive 不应抛异常、不应产生激活
        repository.setActive(99999L)
        assertEquals("不应产生任何激活 Provider", 0, repository.getAll().count { it.isActive })
    }

    @Test
    fun set_active_nonexistent_id_deactivates_current_active() {
        // 当前有激活，setActive(不存在 id) 会因遍历取消现有激活（事务内），结果无激活
        val id = repository.save(ProviderConfig(name = "A", baseUrl = "url-a", apiKeyRef = "a"))
        repository.setActive(id)
        assertTrue(repository.get(id)!!.isActive)

        repository.setActive(99999L)
        assertFalse("setActive 不存在 id 后原激活应被取消", repository.get(id)!!.isActive)
        assertEquals("不应有任何激活 Provider", 0, repository.getAll().count { it.isActive })
    }

    // ==================== StringMapConverter 空 key / 空 value / 反斜杠边界 ====================

    @Test
    fun map_round_trip_empty_key() {
        // 空字符串 key 往返（G-05 语义问题，非安全漏洞，确认当前行为）
        val headers = mapOf("" to "value-for-empty-key")
        val id = repository.save(
            ProviderConfig(name = "Test", baseUrl = "url", apiKeyRef = "test", headers = headers)
        )
        assertEquals(headers, repository.get(id)!!.headers)
    }

    @Test
    fun map_round_trip_empty_value() {
        val headers = mapOf("key-1" to "", "key-2" to "non-empty")
        val id = repository.save(
            ProviderConfig(name = "Test", baseUrl = "url", apiKeyRef = "test", headers = headers)
        )
        assertEquals(headers, repository.get(id)!!.headers)
    }

    @Test
    fun map_round_trip_value_consisting_only_of_backslash() {
        // value 仅含字面反斜杠，验证 escape 后 "\\" 在 unescape 单次扫描正确还原
        val headers = mapOf("key" to "\\")
        val id = repository.save(
            ProviderConfig(name = "Test", baseUrl = "url", apiKeyRef = "test", headers = headers)
        )
        assertEquals(headers, repository.get(id)!!.headers)
    }

    @Test
    fun map_round_trip_value_ending_with_backslash() {
        val headers = mapOf("key" to "abc\\", "tail" to "path\\")
        val id = repository.save(
            ProviderConfig(name = "Test", baseUrl = "url", apiKeyRef = "test", headers = headers)
        )
        assertEquals(headers, repository.get(id)!!.headers)
    }

    @Test
    fun map_round_trip_key_containing_equals_and_backslash() {
        // key 含 = 与反斜杠（会转义为 \e 与 \\），验证 key 侧转义正确
        val headers = mapOf("a\\b=c" to "v1", "x\\e" to "v2")
        val id = repository.save(
            ProviderConfig(name = "Test", baseUrl = "url", apiKeyRef = "test", headers = headers)
        )
        assertEquals(headers, repository.get(id)!!.headers)
    }

    @Test
    fun map_round_trip_multiple_entries_preserves_order_and_count() {
        val headers = mapOf(
            "k1" to "v1", "k2" to "v2", "k3" to "v3", "k4" to "v4", "k5" to "v5"
        )
        val id = repository.save(
            ProviderConfig(name = "Test", baseUrl = "url", apiKeyRef = "test", headers = headers)
        )
        assertEquals("5 条请求头应全部往返", headers, repository.get(id)!!.headers)
    }

    // ==================== 超长 / 大量数据 ====================

    @Test
    fun models_very_long_name_round_trip() {
        // 超长模型名（1000 字符），验证无长度截断
        val longName = "m-" + "x".repeat(1000)
        val id = repository.save(
            ProviderConfig(name = "Test", baseUrl = "url", apiKeyRef = "test", models = listOf(longName))
        )
        assertEquals(longName, repository.get(id)!!.models[0])
    }

    @Test
    fun models_100_models_round_trip() {
        // 大量模型（120 个），验证批量往返正确性与性能正确性
        val models = (1..120).map { "model-$it-${"a".repeat(20)}" }
        val id = repository.save(
            ProviderConfig(name = "Test", baseUrl = "url", apiKeyRef = "test", models = models)
        )
        assertEquals("120 个模型应全部往返", models, repository.get(id)!!.models)
    }

    @Test
    fun models_mixed_backslash_newline_equals_round_trip() {
        // 模型名同时含反斜杠、换行、等号（StringListConverter 对 \ 与换行转义；等号无需转义但需验证不破坏）
        val models = listOf("a\\b\nc=d", "e\\nf=g", "plain")
        val id = repository.save(
            ProviderConfig(name = "Test", baseUrl = "url", apiKeyRef = "test", models = models)
        )
        assertEquals(models, repository.get(id)!!.models)
    }

    // ==================== 特殊字符（中文 / Unicode / emoji） ====================

    @Test
    fun models_with_unicode_and_emoji_round_trip() {
        val models = listOf("中文模型-测试", "模型\u4E2D\u6587", "mo\ud83d\ude00d-\ud83c\udf89", "emoji\uD83D\uDE80")
        val id = repository.save(
            ProviderConfig(name = "Test", baseUrl = "url", apiKeyRef = "test", models = models)
        )
        assertEquals(models, repository.get(id)!!.models)
    }

    @Test
    fun headers_with_unicode_and_emoji_round_trip() {
        val headers = mapOf(
            "X-中文" to "值-\u4E2D\u6587",
            "X-Emoji" to "emoji-\uD83D\uDE00-\uD83C\uDF89",
            "Content-Type" to "application/json; charset=UTF-8"
        )
        val id = repository.save(
            ProviderConfig(name = "Test", baseUrl = "url", apiKeyRef = "test", headers = headers)
        )
        assertEquals(headers, repository.get(id)!!.headers)
    }

    @Test
    fun config_with_unicode_name_round_trip() {
        val id = repository.save(
            ProviderConfig(name = "Moonshot\u2122", baseUrl = "https://api.moonshot.cn/v1", apiKeyRef = "moonshot")
        )
        assertEquals("Moonshot\u2122", repository.get(id)!!.name)
    }

    // ==================== 空 name / baseUrl（G-03 当前无校验行为确认） ====================

    @Test
    fun empty_name_and_base_url_persisted() {
        // G-03：当前 save() 无输入校验，空 name/baseUrl 可持久化。确认当前行为（不抛异常）。
        // 这是已知技术债，后续迭代在 save() 或 UI 层增加校验。
        val id = repository.save(
            ProviderConfig(name = "", baseUrl = "", apiKeyRef = "test")
        )
        val saved = repository.get(id)
        assertEquals("", saved!!.name)
        assertEquals("", saved.baseUrl)
    }

    // ==================== setActive 并发冲突（BR-concurrency-001 事务原子性） ====================

    @Test
    fun concurrent_setActive_preserves_single_active_invariant() {
        // 5 个线程并发 setActive 不同 id，验证 ObjectBox runInTx 事务串行化后
        // "恰好一个激活"不变式始终成立（BR-concurrency-001）
        val ids = (1..5).map {
            repository.save(ProviderConfig(name = "P$it", baseUrl = "url-$it", apiKeyRef = "k$it"))
        }
        val threadCount = ids.size
        val barrier = CyclicBarrier(threadCount)
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(threadCount)
        val errors = Collections.synchronizedList(mutableListOf<Throwable>())
        val pool = Executors.newFixedThreadPool(threadCount)

        ids.forEachIndexed { i, id ->
            pool.execute {
                try {
                    barrier.await() // 所有线程就绪
                    startLatch.await() // 同时开始
                    repository.setActive(id)
                } catch (t: Throwable) {
                    errors.add(t)
                } finally {
                    doneLatch.countDown()
                }
            }
        }
        startLatch.countDown()
        doneLatch.await()
        pool.shutdown()
        // 等待线程池完全终止，避免 tearDown 中 boxStore.close() 与线程残留事务竞争
        pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)

        assertTrue("并发 setActive 不应有异常: $errors", errors.isEmpty())
        val active = repository.getAll().filter { it.isActive }
        assertEquals("并发 setActive 后应恰好一个激活", 1, active.size)
    }

    @Test
    fun concurrent_setActive_and_clearActive_never_leaves_multiple_active() {
        // 并发交替 setActive 与 clearActive，验证不变式：任何时刻磁盘状态至多一个激活
        val ids = (1..4).map {
            repository.save(ProviderConfig(name = "Q$it", baseUrl = "url-$it", apiKeyRef = "q$it"))
        }
        val ops = mutableListOf<Runnable>()
        ids.forEach { id -> ops.add(Runnable { repository.setActive(id) }) }
        ops.add(Runnable { repository.clearActive() })
        ops.add(Runnable { repository.clearActive() })

        val iterations = 20
        val pool = Executors.newFixedThreadPool(4)
        val doneLatch = CountDownLatch(iterations)
        val errors = Collections.synchronizedList(mutableListOf<Throwable>())
        repeat(iterations) { round ->
            pool.execute {
                try {
                    ops[round % ops.size].run()
                } catch (t: Throwable) {
                    errors.add(t)
                } finally {
                    doneLatch.countDown()
                }
            }
        }
        doneLatch.await()
        pool.shutdown()
        // 等待线程池完全终止，避免 tearDown 中 boxStore.close() 与线程残留事务竞争
        pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)

        assertTrue("并发操作不应有异常: $errors", errors.isEmpty())
        val activeCount = repository.getAll().count { it.isActive }
        assertTrue("最终状态激活数应为 0 或 1（实际 $activeCount）", activeCount <= 1)
    }
}