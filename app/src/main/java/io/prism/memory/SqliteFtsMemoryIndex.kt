package io.prism.memory

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import io.prism.data.MemoryRecord

/**
 * SQLite FTS5 记忆关键词索引（v1 记忆深度优化 US-102，生产主路径）。
 *
 * **背景**：Android 系统 SQLite 自 API 24（Android 7.0）起内置 FTS5 扩展（含原生
 * `bm25()` 排名函数），minSdk=26 全覆盖，**零 APK 体积增量、零第三方依赖**。
 *
 * **中文分词**：FTS5 默认 unicode61 按空格切词，中文整句无法命中。本实现写入/查询
 * 前均经 [MemoryFtsTokenizer] 预分词（CJK 二元组 + 整段 + 字母数字），空格 join 后
 * 存入 FTS5 `content` 列；查询用 `MATCH ?`（AND 连接各 token）。
 *
 * **同步模型**：与 [InMemoryMemoryKeywordIndex] 相同——[reconcile] 以版本号跳过
 * 未变更重建；重建时清空 FTS5 表并批量重插（记忆规模 ≤1 万条，重建成本可接受）。
 *
 * **容错**：数据库打开/写入/检索异常均捕获并降级为空结果（不阻断主流程，
 * 检索降级为纯向量路径由 [CrossSessionMemoryManager] 统一处理）。
 *
 * **线程安全**：SQLiteDatabase 单实例由调用方串行访问（检索在协程内，重建在检索前）。
 *
 * @param context 应用上下文（用于打开私有数据库 `prism_memory_fts.db`）
 */
class SqliteFtsMemoryIndex(
    private val context: Context
) : MemoryKeywordIndex {

    private var db: SQLiteDatabase? = null

    /** 上次同步的版本号。 */
    private var lastVersion: Long = -1

    /**
     * v1 批次19（真机 RCA：FTS 影子表损坏后 reconcile/search 永久失败且无自愈——
     * CREATE IF NOT EXISTS 不会修复缺失/损坏的 FTS5 影子表）：自愈重建计数。
     *
     * **风暴防护（guardrail P2-1）**：进程生命周期内最多 [MAX_REBUILD_ATTEMPTS] 次重建
     * 尝试（reconcileOnce/searchOnce 成功即归零）——确定性失败稳态（磁盘满/FTS5 模块
     * 缺失）下不会无限删库重建，超限后永久降级为纯向量检索。
     */
    internal var rebuildAttempts = 0
        private set

    /** 懒加载数据库并建表（线程安全：double-check）。 */
    private fun database(): SQLiteDatabase? {
        db?.let { return it }
        synchronized(this) {
            db?.let { return it }
            val opened = try {
                context.openOrCreateDatabase(
                    DB_NAME,
                    Context.MODE_PRIVATE,
                    null
                )
            } catch (e: Exception) {
                Log.w(TAG, "database: 打开记忆 FTS 数据库失败（${e::class.simpleName}），降级纯向量检索")
                null
            }
            if (opened != null) {
                try {
                    opened.execSQL(CREATE_FTS_TABLE_SQL)
                } catch (e: Exception) {
                    Log.w(TAG, "database: 创建 FTS5 表失败（${e::class.simpleName}），降级纯向量检索")
                }
            }
            db = opened
            return opened
        }
    }

    override fun reconcile(records: List<MemoryRecord>, version: Long) {
        if (version == lastVersion) return
        val ok = reconcileOnce(records, version)
        if (ok) {
            rebuildAttempts = 0 // 成功归零（guardrail P2-1：仅确定性失败耗尽预算）
            return
        }
        if (rebuildAttempts < MAX_REBUILD_ATTEMPTS) {
            // v1 批次19：索引=派生物（真源 MemoryRecord 在 ObjectBox）——失败即删库重建
            //（业界无现成实现，自研"派生索引自愈"模式，见调研报告 §2.2-c）
            rebuildAttempts++
            rebuildDatabase()
            reconcileOnce(records, version)
        }
    }

    /** 单次重建尝试（返回是否成功；失败日志带 exception message——旧版只打类名无法定位）。 */
    private fun reconcileOnce(records: List<MemoryRecord>, version: Long): Boolean {
        val database = database() ?: return false
        database.beginTransaction()
        try {
            database.execSQL("DELETE FROM $TABLE_NAME")
            for (record in records) {
                val ftsText = MemoryFtsTokenizer.tokenizeForFts(record.content)
                if (!MemoryFtsTokenizer.isIndexable(ftsText)) continue
                database.execSQL(
                    "INSERT INTO $TABLE_NAME (rowid, content) VALUES (?, ?)",
                    arrayOf<Any>(record.id, ftsText)
                )
            }
            database.setTransactionSuccessful()
            lastVersion = version
            return true
        } catch (e: Exception) {
            Log.w(TAG, "reconcile: 重建 FTS 索引失败（${e::class.simpleName}）：${e.message}")
            return false
        } finally {
            database.endTransaction()
        }
    }

    /**
     * 自愈：关闭并删除损坏的 FTS 数据库文件，下次访问时重建（真源数据不受影响）。
     */
    private fun rebuildDatabase() {
        Log.w(TAG, "FTS index appears corrupted, rebuilding database $DB_NAME")
        try {
            db?.close()
        } catch (e: Exception) {
            Log.w(TAG, "rebuildDatabase: close failed（${e::class.simpleName}）：${e.message}")
        }
        db = null
        lastVersion = -1
        val deleted = try {
            context.deleteDatabase(DB_NAME)
        } catch (e: Exception) {
            Log.w(TAG, "rebuildDatabase: delete failed（${e::class.simpleName}）：${e.message}")
            false
        }
        if (!deleted) {
            // guardrail P3：删除失败（文件被占用等）→ 下次 database() 打开的仍是旧库，
            // 重建无效但已计入预算，不会风暴
            Log.w(TAG, "rebuildDatabase: delete returned false, old database may persist")
        }
    }

    override fun search(query: String, topK: Int): List<MemoryKeywordHit> {
        if (topK <= 0 || MemoryFtsTokenizer.tokenize(query).isEmpty()) return emptyList()
        val database = database() ?: return emptyList()
        val first = searchOnce(database, query, topK)
        if (first != null) return first
        // v1 批次19：检索失败（表损坏）→ 预算内自愈一次后重试（guardrail P2-1：预算防风暴）
        if (rebuildAttempts < MAX_REBUILD_ATTEMPTS) {
            rebuildAttempts++
            rebuildDatabase()
            val reopened = database() ?: return emptyList()
            return searchOnce(reopened, query, topK) ?: emptyList()
        }
        return emptyList()
    }

    /** 单次检索尝试（返回 null 表示失败，可触发自愈重试）。 */
    private fun searchOnce(database: SQLiteDatabase, query: String, topK: Int): List<MemoryKeywordHit>? {
        return try {
            val matchQuery = buildMatchQuery(query)
            if (matchQuery == null) return emptyList()
            database.rawQuery(
                SEARCH_SQL,
                arrayOf(matchQuery, topK.toString())
            ).use { cursor ->
                val hits = ArrayList<MemoryKeywordHit>(cursor.count.coerceAtMost(topK))
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val score = cursor.getDouble(1)
                    hits.add(MemoryKeywordHit(id, score))
                }
                hits
            }
        } catch (e: Exception) {
            Log.w(TAG, "search: FTS 检索失败（${e::class.simpleName}）：${e.message}，降级为空")
            null
        }
    }

    companion object {
        private const val TAG = "MemoryFts"
        private const val DB_NAME = "prism_memory_fts.db"

        /**
         * v1 批次19（guardrail P2-1）：进程生命周期内自愈重建尝试上限——
         * 确定性失败稳态（磁盘满/FTS5 模块缺失）下最多尝试该次数后永久降级纯向量检索，
         * 不会无限删库重建；任一次 reconcileOnce 成功即归零。
         */
        internal const val MAX_REBUILD_ATTEMPTS = 2
        private const val TABLE_NAME = "memory_fts"

        /**
         * 构造 FTS5 MATCH 查询串（AND 连接各 token，双引号包裹 + 内嵌引号转义，纯函数可测）。
         *
         * **注入防御**（guardrail FIX-2 复核）：token 字符集被 [MemoryFtsTokenizer] 严格限制为
         * `[A-Za-z0-9]` + CJK（不含 `"` / `*` / 空格等 FTS 语法符号）；即便如此仍对每个 token
         * 双引号包裹 + 内部 `"` 翻倍转义，杜绝 FTS 语法注入；查询走 `MATCH ?` 参数化绑定。
         *
         * @param query 用户查询文本
         * @return MATCH 表达式；无有效 token 返回 null
         */
        internal fun buildMatchQuery(query: String): String? {
            val tokens = MemoryFtsTokenizer.tokenize(query)
            if (tokens.isEmpty()) return null
            return tokens.joinToString(" AND ") { token ->
                "\"" + token.replace("\"", "\"\"") + "\""
            }
        }

        /** FTS5 虚拟表（content 存空格 join 的预分词串，rowid 映射 MemoryRecord.id）。 */
        private const val CREATE_FTS_TABLE_SQL =
            "CREATE VIRTUAL TABLE IF NOT EXISTS $TABLE_NAME USING fts5(content)"

        /** BM25 检索（bm25() 返回分数，越小越相关；此处取负号使分数越大越相关）。 */
        private const val SEARCH_SQL =
            "SELECT rowid, -bm25($TABLE_NAME) AS score FROM $TABLE_NAME " +
                "WHERE $TABLE_NAME MATCH ? ORDER BY score DESC LIMIT ?"
    }
}
