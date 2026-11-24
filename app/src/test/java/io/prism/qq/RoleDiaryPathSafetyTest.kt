package io.prism.qq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 日记页/日记存储的**越权与路径穿越**专项（ac-verifier，2026-11-22；rules/04 §十一 5）。
 *
 * 为什么单独测：
 * - 日记页的 `roleId` 来自 `rolePackLoader.effectiveRolePack()?.id`（外部 JSON 可控字段）；
 * - `RoleDiaryStore.fileFor` 用 `safeName(roleId) + ".json"` 拼路径，一旦词表放宽就是
 *   「读/删任意 `*.json`」——`clear()` 会**删除文件**，属破坏性操作，必须钉死。
 *
 * 断言口径：恶意 roleId 必须**落在 diaries 目录内**，且不得生成 diaries 之外的文件。
 */
class RoleDiaryPathSafetyTest {

    private fun newStore(root: File) = RoleDiaryStore(root) { 1_700_000_000_000L }

    /** 递归收集 root 下全部文件（相对路径）。 */
    private fun filesUnder(root: File): List<String> =
        root.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).invariantSeparatorsPath }.toList()

    @Test
    fun `路径穿越型 roleId 不得写出 diaries 目录`() {
        val root = Files.createTempDirectory("diary-traversal").toFile()
        try {
            val store = newStore(root)
            val evil = "../../../../tmp/evil"
            assertTrue("写入应成功（被净化到 diaries 内）", store.append(evil, "正文", "平静"))
            val files = filesUnder(root)
            assertEquals("只允许一个文件：$files", 1, files.size)
            assertTrue("必须落在 diaries/ 下：$files", files.single().startsWith("diaries/"))
            // 真实净化结果形如 `.._.._.._.._tmp_evil.json`——**含点但无分隔符**，
            // 因此仍在 diaries/ 内（关键安全属性 = 规范化父目录不逃逸）。
            val diaryDir = File(root, "diaries").canonicalFile
            val created = File(root, files.single()).canonicalFile
            assertEquals(
                "规范化后父目录必须仍是 diaries（实际=${created.parent}）",
                diaryDir,
                created.parentFile
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `绝对路径型 roleId 不得逃逸`() {
        val root = Files.createTempDirectory("diary-abs").toFile()
        try {
            val store = newStore(root)
            store.append("/etc/passwd", "正文")
            val files = filesUnder(root)
            assertEquals(1, files.size)
            assertTrue("必须落在 diaries/ 下：$files", files.single().startsWith("diaries/"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `超长 roleId 被截断到 64 且不崩溃`() {
        val root = Files.createTempDirectory("diary-long").toFile()
        try {
            val store = newStore(root)
            val long = "a".repeat(500)
            assertTrue(store.append(long, "正文"))
            val name = filesUnder(root).single().substringAfter("diaries/")
            assertTrue("文件名（含 .json）应被截断：len=${name.length}", name.length <= 64 + ".json".length)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `净化后同名的两个 roleId 共用同一文件（已知限制：内存缓存会发散）`() {
        val root = Files.createTempDirectory("diary-same").toFile()
        try {
            val store = newStore(root)
            // `a/b` 与 `a_b` 净化后同名 → 落同一文件。
            store.append("a/b", "第一条")
            store.append("a_b", "第二条")
            // 落盘是追加：第二条写入时从同一文件读回第一条 → 磁盘上 2 条，**没有数据丢失**。
            assertEquals("磁盘上只有一个文件（两者同名）", 1, filesUnder(root).size)
            // 已知限制（记入报告）：两个 roleId 的内存缓存各自独立、互不失效 →
            // 同实例内 `all("a/b")` 仍返回 1 条陈旧数据，需要新实例才读到真相。
            assertEquals("同实例缓存发散（已知限制，非数据丢失）", 1, store.all("a/b").size)
            val fresh = newStore(root)
            val persisted = fresh.all("a_b")
            assertEquals("新实例读到 2 条（无数据丢失）", 2, persisted.size)
            assertEquals("第一条", persisted[0].text)
            assertEquals("第二条", persisted[1].text)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `clear 只删目标角色文件，不波及同目录其它角色`() {
        val root = Files.createTempDirectory("diary-clear").toFile()
        try {
            val store = newStore(root)
            store.append("cyrene", "她的")
            store.append("other", "别人的")
            assertEquals(2, filesUnder(root).size)
            assertTrue(store.clear("cyrene"))
            val left = filesUnder(root)
            assertEquals("只能剩另一角色：$left", 1, left.size)
            assertTrue(left.single().contains("other"))
            assertTrue(store.all("cyrene").isEmpty())
            assertEquals(1, store.all("other").size)
        } finally {
            root.deleteRecursively()
        }
    }
}
