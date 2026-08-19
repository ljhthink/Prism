package io.prism.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1 US-102/guardrail FIX-2：SqliteFtsMemoryIndex.buildMatchQuery 注入防御测试（纯函数）。
 *
 * 验证 FTS5 MATCH 查询串构造：token 双引号包裹 + 内嵌引号转义 + 特殊字符被分词器剥离，
 * 杜绝 FTS 语法注入（`"` / `*` / 保留字 OR/AND/NOT 等）。
 */
class SqliteFtsMemoryIndexTest {

    @Test
    fun `buildMatchQuery quotes each token and joins with AND`() {
        val query = SqliteFtsMemoryIndex.buildMatchQuery("Kotlin 协程")
        assertNotNull(query)
        assertTrue("token 应被双引号包裹", query!!.contains("\"kotlin\""))
        assertTrue("CJK token 应被双引号包裹", query.contains("\"协程\""))
        assertTrue("应按 AND 连接", query.contains(" AND "))
    }

    @Test
    fun `buildMatchQuery blank query returns null`() {
        assertNull(SqliteFtsMemoryIndex.buildMatchQuery("   "))
        assertNull(SqliteFtsMemoryIndex.buildMatchQuery(""))
    }

    @Test
    fun `buildMatchQuery strips FTS operators from query`() {
        // FTS 语法符号（引号/星号/括号/冒号）经 MemoryFtsTokenizer 被剥离，不进入 MATCH 表达式
        val query = SqliteFtsMemoryIndex.buildMatchQuery("\"*kotlin*\" OR \"协程\":")
        assertNotNull(query)
        // 结果中不应出现裸引号（除 token 包裹外）、星号、OR 保留字作为裸 token
        assertTrue("不应含星号", !query!!.contains("*"))
        assertTrue("不应含裸 OR 保留字（token 间是 AND）", query.contains(" AND "))
        // 每个 token 都应是 [A-Za-z0-9]+CJK（分词器产出），用正则核验
        query.split(" AND ").forEach { tok ->
            val inner = tok.removeSurrounding("\"")
            assertTrue("token 应为安全字符集", inner.matches(Regex("^[a-z0-9\u4e00-\u9fff]+$")))
        }
    }

    @Test
    fun `buildMatchQuery escapes embedded quotes defensively`() {
        // 直接锚定生产 buildMatchQuery：即便传入含引号/操作符的查询，输出中出现的引号
        // 只可能是成对的 token 包裹引号（偶数个），且不存在裸的 FTS 操作符泄漏
        val query = SqliteFtsMemoryIndex.buildMatchQuery("Kotlin\" OR \"协程\"")
        assertNotNull(query)
        // 双引号总数 = 2 × token 数（每个 token 一对包裹引号）
        val quoteCount = query!!.count { it == '"' }
        val tokens = query.split(" AND ")
        assertEquals("引号应成对（每 token 一对）", tokens.size * 2, quoteCount)
        // 不存在未包裹的裸 token（AND 分隔后每个片段两端都应是引号）
        tokens.forEach { tok ->
            assertTrue("每段应以引号包裹", tok.startsWith("\"") && tok.endsWith("\""))
        }
    }
}
