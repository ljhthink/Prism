package io.prism.skill

import android.util.Log
import io.objectbox.converter.PropertyConverter
import io.prism.data.ToolCallRecord
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * List<ToolCallRecord> ↔ String 类型转换器（ObjectBox @Convert，US-029）。
 *
 * 序列化格式：JSON String（kotlinx.serialization）。
 *
 * **设计**：
 * - 编码：[Json.encodeToString] 序列化 List<ToolCallRecord> 为 JSON 字符串
 * - 解码：[Json.decodeFromString] 反序列化 JSON 字符串为 List<ToolCallRecord>
 * - 容错：解码失败时记录结构化日志（BR-error-handling-004）并返回空列表，
 *   避免单条损坏记录阻塞整体读取
 * - 空列表序列化为 `"[]"`（kotlinx.serialization 默认行为）
 *
 * **与 [io.prism.data.StringListConverter] 的差异**：
 * - StringListConverter 用换行符分隔 + 转义（适用于 String 列表）
 * - ToolCallListConverter 用 JSON（适用于结构化对象列表，[ToolCallRecord] 含多字段）
 *
 * **线程安全**：[Json] 实例不可变，可安全共享。
 *
 * @see ToolCallRecord
 * @see io.prism.data.SkillExecutionRecord
 */
class ToolCallListConverter : PropertyConverter<List<ToolCallRecord>, String> {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    private val serializer = ListSerializer(ToolCallRecord.serializer())

    override fun convertToEntityProperty(databaseValue: String?): List<ToolCallRecord> {
        if (databaseValue.isNullOrBlank()) return emptyList()
        return try {
            json.decodeFromString(serializer, databaseValue)
        } catch (e: Exception) {
            // BR-error-handling-004：记录结构化日志（异常类型 + 值长度），不含原始值避免日志膨胀。
            // 调用方是 ObjectBox 内部（PropertyConverter 接口），无法感知转换失败，
            // 必须在此处记录日志，否则数据损坏将静默丢失 toolCalls 无法定位根因。
            Log.w(
                TAG,
                "decode toolCalls failed: ${e.javaClass.simpleName}, valueLen=${databaseValue.length}",
                e
            )
            // 容错：损坏的 JSON 返回空列表，避免阻塞整体读取
            emptyList()
        }
    }

    override fun convertToDatabaseValue(entityProperty: List<ToolCallRecord>): String {
        return json.encodeToString(serializer, entityProperty)
    }

    companion object {
        private const val TAG = "ToolCallListConverter"
    }
}
