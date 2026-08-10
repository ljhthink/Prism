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
 * UserProfileRepository CRUD + upsert 唯一约束单元测试（US-031，ADR-015 5.2）。
 *
 * **验证内容**：
 * 1. UserProfile 持久化到 ObjectBox（含 @Index key 字段）
 * 2. save / get / getAll / getByCategory / update / delete / deleteAll / count 全方法覆盖
 * 3. 单用户单 key 唯一约束（相同 key upsert 而非 insert，US-031 AC-3）
 * 4. ProfileCategory 枚举（EXPLICIT / IMPLICIT）正确存储与查询
 * 5. updatedAt 自动刷新
 * 6. 空值校验 fail-fast（key 为空抛 IllegalArgumentException）
 *
 * **测试策略**（BR-testing-004，复用 [SkillExecutionRepositoryTest] 模式）：
 * - 真实 ObjectBox（`MyObjectBox.builder().directory(tempDir).build()`），无 mock
 * - 不依赖 Android Context（ObjectBox directory 模式可在纯 JVM 运行）
 *
 * 关联 ADR：[ADR-015](../../docs/decisions/ADR-015-m5-memory-system-architecture.md)
 */
class UserProfileRepositoryTest {

    private lateinit var boxStore: BoxStore
    private lateinit var repository: UserProfileRepository
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = kotlin.io.path.createTempDirectory(prefix = "user-profile-test-").toFile()
        boxStore = MyObjectBox.builder().directory(tempDir).build()
        repository = UserProfileRepository(boxStore)
    }

    @After
    fun tearDown() {
        boxStore.close()
        tempDir.deleteRecursively()
    }

    // ==================== AC-1：UserProfile @Entity 持久化 ====================

    @Test
    fun save_assigns_positive_id() {
        val profile = UserProfile(
            key = "language",
            value = "中文",
            category = ProfileCategory.EXPLICIT.name
        )
        val id = repository.save(profile)
        assertTrue("应分配正数 id", id > 0)
    }

    @Test
    fun save_persists_all_fields() {
        val original = UserProfile(
            key = "tone",
            value = "简洁",
            category = ProfileCategory.EXPLICIT.name
        )
        val id = repository.save(original)

        val retrieved = repository.get("tone")
        assertNotNull(retrieved)
        assertEquals(id, retrieved!!.id)
        assertEquals("tone", retrieved.key)
        assertEquals("简洁", retrieved.value)
        assertEquals(ProfileCategory.EXPLICIT.name, retrieved.category)
        assertTrue("updatedAt 应被设置", retrieved.updatedAt > 0)
    }

    @Test
    fun save_with_default_category_explicit() {
        // 不显式指定 category 时，默认为 EXPLICIT
        val profile = UserProfile(key = "tech_stack", value = "Python")
        val id = repository.save(profile)

        val retrieved = repository.get("tech_stack")
        assertNotNull(retrieved)
        assertEquals(
            "默认 category 应为 EXPLICIT",
            ProfileCategory.EXPLICIT.name,
            retrieved!!.category
        )
    }

    // ==================== AC-3：单用户单 key 唯一约束（upsert） ====================

    @Test
    fun save_upsert_same_key_updates_existing_record() {
        // 第一次保存
        val id1 = repository.save(
            UserProfile(
                key = "language",
                value = "中文",
                category = ProfileCategory.EXPLICIT.name
            )
        )
        // 第二次保存相同 key，不同 value
        val id2 = repository.save(
            UserProfile(
                key = "language",
                value = "English",
                category = ProfileCategory.EXPLICIT.name
            )
        )

        assertEquals("相同 key 的 upsert 应返回相同 id", id1, id2)
        assertEquals("应只有 1 条记录", 1L, repository.count())

        val retrieved = repository.get("language")
        assertEquals("value 应为最新值", "English", retrieved!!.value)
    }

    @Test
    fun save_upsert_preserves_category_on_value_change() {
        repository.save(
            UserProfile(
                key = "language",
                value = "中文",
                category = ProfileCategory.IMPLICIT.name
            )
        )
        repository.save(
            UserProfile(
                key = "language",
                value = "English",
                category = ProfileCategory.IMPLICIT.name
            )
        )

        val retrieved = repository.get("language")
        assertEquals("English", retrieved!!.value)
        assertEquals(
            "category 应保持 IMPLICIT",
            ProfileCategory.IMPLICIT.name,
            retrieved.category
        )
    }

    @Test
    fun save_different_keys_create_separate_records() {
        repository.save(
            UserProfile(
                key = "language",
                value = "中文",
                category = ProfileCategory.EXPLICIT.name
            )
        )
        repository.save(
            UserProfile(
                key = "tone",
                value = "简洁",
                category = ProfileCategory.EXPLICIT.name
            )
        )

        assertEquals("应有 2 条记录", 2L, repository.count())
        assertNotNull(repository.get("language"))
        assertNotNull(repository.get("tone"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun save_throws_for_blank_key() {
        repository.save(UserProfile(key = "", value = "值"))
    }

    // ==================== AC-2：get ====================

    @Test
    fun get_returns_null_for_nonexistent_key() {
        val result = repository.get("nonexistent")
        assertNull("不存在的 key 应返回 null", result)
    }

    @Test
    fun get_returns_correct_record() {
        repository.save(
            UserProfile(
                key = "favorite_language",
                value = "Kotlin",
                category = ProfileCategory.IMPLICIT.name
            )
        )

        val result = repository.get("favorite_language")
        assertNotNull(result)
        assertEquals("Kotlin", result!!.value)
        assertEquals(ProfileCategory.IMPLICIT.name, result.category)
    }

    // ==================== AC-2：getAll ====================

    @Test
    fun getAll_returns_all_records_sorted_by_updated_at_desc() {
        val t1 = System.currentTimeMillis()
        repository.save(
            UserProfile(
                key = "k1", value = "v1",
                category = ProfileCategory.EXPLICIT.name, updatedAt = t1
            )
        )
        // 确保时间戳不同
        Thread.sleep(5)
        repository.save(
            UserProfile(
                key = "k2", value = "v2",
                category = ProfileCategory.EXPLICIT.name, updatedAt = System.currentTimeMillis()
            )
        )
        Thread.sleep(5)
        repository.save(
            UserProfile(
                key = "k3", value = "v3",
                category = ProfileCategory.EXPLICIT.name, updatedAt = System.currentTimeMillis()
            )
        )

        val all = repository.getAll()
        assertEquals("应返回 3 条", 3, all.size)
        // 按 updatedAt 降序（最近更新的在前）
        assertTrue(
            "应按 updatedAt 降序: ${all[0].updatedAt} >= ${all[1].updatedAt}",
            all[0].updatedAt >= all[1].updatedAt
        )
        assertTrue(
            "应按 updatedAt 降序: ${all[1].updatedAt} >= ${all[2].updatedAt}",
            all[1].updatedAt >= all[2].updatedAt
        )
    }

    @Test
    fun getAll_returns_empty_for_empty_repository() {
        val all = repository.getAll()
        assertTrue("空库应返回空 list", all.isEmpty())
    }

    // ==================== AC-2：getByCategory ====================

    @Test
    fun getByCategory_returns_only_matching_records() {
        repository.save(
            UserProfile(
                key = "explicit_pref", value = "v1",
                category = ProfileCategory.EXPLICIT.name
            )
        )
        repository.save(
            UserProfile(
                key = "implicit_pref", value = "v2",
                category = ProfileCategory.IMPLICIT.name
            )
        )
        repository.save(
            UserProfile(
                key = "another_explicit", value = "v3",
                category = ProfileCategory.EXPLICIT.name
            )
        )

        val explicit = repository.getByCategory(ProfileCategory.EXPLICIT)
        assertEquals("EXPLICIT 应返回 2 条", 2, explicit.size)
        assertTrue(
            "应包含 explicit_pref",
            explicit.any { it.key == "explicit_pref" }
        )
        assertTrue(
            "应包含 another_explicit",
            explicit.any { it.key == "another_explicit" }
        )

        val implicit = repository.getByCategory(ProfileCategory.IMPLICIT)
        assertEquals("IMPLICIT 应返回 1 条", 1, implicit.size)
        assertEquals("implicit_pref", implicit[0].key)
    }

    @Test
    fun getByCategory_returns_empty_for_nonexistent_category() {
        repository.save(
            UserProfile(
                key = "k1", value = "v1",
                category = ProfileCategory.EXPLICIT.name
            )
        )

        val implicit = repository.getByCategory(ProfileCategory.IMPLICIT)
        assertTrue("IMPLICIT 应返回空 list", implicit.isEmpty())
    }

    // ==================== AC-2：update（便捷方法） ====================

    @Test
    fun update_creates_new_record_when_key_not_exists() {
        val id = repository.update("new_key", "new_value")

        assertTrue("应创建新记录", id > 0)
        val retrieved = repository.get("new_key")
        assertNotNull(retrieved)
        assertEquals("new_value", retrieved!!.value)
        assertEquals(
            "新记录 category 应默认为 EXPLICIT",
            ProfileCategory.EXPLICIT.name,
            retrieved.category
        )
    }

    @Test
    fun update_modifies_existing_record_value() {
        repository.save(
            UserProfile(
                key = "language", value = "中文",
                category = ProfileCategory.IMPLICIT.name
            )
        )
        val original = repository.get("language")!!

        val id = repository.update("language", "English")
        val updated = repository.get("language")!!

        assertEquals("应返回相同 id", original.id, id)
        assertEquals("value 应更新", "English", updated.value)
        assertEquals(
            "category 应保持不变（IMPLICIT）",
            ProfileCategory.IMPLICIT.name,
            updated.category
        )
        assertTrue(
            "updatedAt 应刷新",
            updated.updatedAt >= original.updatedAt
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun update_throws_for_blank_key() {
        repository.update("", "value")
    }

    @Test(expected = IllegalArgumentException::class)
    fun update_throws_for_blank_value() {
        repository.update("key", "")
    }

    // ==================== AC-2：delete ====================

    @Test
    fun delete_removes_record_by_key() {
        repository.save(
            UserProfile(
                key = "to_delete", value = "v",
                category = ProfileCategory.EXPLICIT.name
            )
        )
        assertEquals("删除前应有 1 条", 1L, repository.count())

        val result = repository.delete("to_delete")
        assertTrue("应返回 true（删除成功）", result)
        assertEquals("删除后应为 0 条", 0L, repository.count())
        assertNull("get 应返回 null", repository.get("to_delete"))
    }

    @Test
    fun delete_returns_false_for_nonexistent_key() {
        val result = repository.delete("nonexistent")
        assertFalse("不存在的 key 应返回 false", result)
    }

    @Test
    fun delete_does_not_affect_other_records() {
        repository.save(
            UserProfile(
                key = "k1", value = "v1",
                category = ProfileCategory.EXPLICIT.name
            )
        )
        repository.save(
            UserProfile(
                key = "k2", value = "v2",
                category = ProfileCategory.EXPLICIT.name
            )
        )

        repository.delete("k1")
        assertEquals("应剩余 1 条", 1L, repository.count())
        assertNotNull("k2 应保留", repository.get("k2"))
    }

    // ==================== AC-2：deleteAll ====================

    @Test
    fun deleteAll_removes_all_records() {
        repository.save(
            UserProfile(
                key = "k1", value = "v1",
                category = ProfileCategory.EXPLICIT.name
            )
        )
        repository.save(
            UserProfile(
                key = "k2", value = "v2",
                category = ProfileCategory.IMPLICIT.name
            )
        )
        repository.save(
            UserProfile(
                key = "k3", value = "v3",
                category = ProfileCategory.EXPLICIT.name
            )
        )

        val deletedCount = repository.deleteAll()
        assertEquals("应删除 3 条", 3L, deletedCount)
        assertEquals("count 应为 0", 0L, repository.count())
        assertTrue("getAll 应为空", repository.getAll().isEmpty())
    }

    @Test
    fun deleteAll_returns_zero_for_empty_repository() {
        val deletedCount = repository.deleteAll()
        assertEquals("空库应返回 0", 0L, deletedCount)
    }

    // ==================== count ====================

    @Test
    fun count_returns_total_record_count() {
        repository.save(
            UserProfile(
                key = "k1", value = "v1",
                category = ProfileCategory.EXPLICIT.name
            )
        )
        repository.save(
            UserProfile(
                key = "k2", value = "v2",
                category = ProfileCategory.EXPLICIT.name
            )
        )

        assertEquals("count 应返回 2", 2L, repository.count())
    }

    @Test
    fun count_returns_zero_for_empty_repository() {
        assertEquals("空库 count 应为 0", 0L, repository.count())
    }

    // ==================== StateFlow 订阅 ====================

    @Test
    fun profiles_flow_updates_after_save() {
        assertEquals("初始应为空", 0, repository.profiles.value.size)

        repository.save(
            UserProfile(
                key = "k1", value = "v1",
                category = ProfileCategory.EXPLICIT.name
            )
        )

        assertEquals("save 后应有 1 条", 1, repository.profiles.value.size)
    }

    @Test
    fun profiles_flow_updates_after_delete() {
        repository.save(
            UserProfile(
                key = "k1", value = "v1",
                category = ProfileCategory.EXPLICIT.name
            )
        )
        repository.save(
            UserProfile(
                key = "k2", value = "v2",
                category = ProfileCategory.EXPLICIT.name
            )
        )

        repository.delete("k1")
        assertEquals("删除后应有 1 条", 1, repository.profiles.value.size)
        assertEquals("k2", repository.profiles.value[0].key)
    }

    // ==================== ProfileCategory 枚举 ====================

    @Test
    fun profileCategory_fromName_roundtrip() {
        assertEquals(
            ProfileCategory.EXPLICIT,
            ProfileCategory.fromName(ProfileCategory.EXPLICIT.name)
        )
        assertEquals(
            ProfileCategory.IMPLICIT,
            ProfileCategory.fromName(ProfileCategory.IMPLICIT.name)
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun profileCategory_fromName_throws_for_invalid_value() {
        ProfileCategory.fromName("INVALID")
    }

    @Test
    fun profileCategory_values_contains_both_options() {
        val values = ProfileCategory.values().map { it.name }
        assertTrue("应包含 EXPLICIT", values.contains("EXPLICIT"))
        assertTrue("应包含 IMPLICIT", values.contains("IMPLICIT"))
        assertEquals("应只有 2 个值", 2, values.size)
    }
}
