package io.prism.data

import io.objectbox.annotation.Convert
import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import io.prism.skill.ToolCallListConverter
import kotlinx.serialization.Serializable

/**
 * Skill 执行记录实体 —— 持久化 Skill 执行的可观测数据（ADR-013 5.7，US-029）。
 *
 * **用途**：跨会话审计 Skill 执行历史，Skill 详情页展示最近 10 次执行记录，
 * 便于调试与运维（耗时分布、失败模式、工具调用链追溯）。
 *
 * **字段说明**：
 * - [id] 主键
 * - [skillConfigId] 关联 [SkillConfig] 的 id（扁平 Long 外键，遵循 ADR-008 5.2）
 * - [skillName] 冗余存储 Skill slug，便于 SkillConfig 被删除后仍可历史查询
 * - [startedAt] / [finishedAt] 执行起止时间戳（毫秒）
 * - [durationMs] 总耗时（finishedAt - startedAt）
 * - [status] 执行状态：[ExecutionStatus.SUCCESS] / [ExecutionStatus.FAIL] / [ExecutionStatus.CANCELLED]
 * - [toolCalls] 工具调用明细列表（[ToolCallRecord]），通过 [ToolCallListConverter] 序列化为 JSON String 存储
 * - [errorMessage] 失败/取消时的描述信息（已脱敏，CWE-209）
 * - [outputPreview] 输出预览（前 200 字符，截断展示）
 *
 * **持久化策略**：
 * - [ToolCallRecord] 为 `@Serializable data class`（非 @Entity），通过 JSON 序列化存储在
 *   [toolCalls] 字段（[ToolCallListConverter]）。避免引入 @Relation 副作用（ADR-008 5.2）。
 * - [errorMessage] 由调用方 [io.prism.skill.SkillExecutor.sanitizeErrorMessage] 脱敏后传入，
 *   不存储原始异常 message / 堆栈 / 路径。
 *
 * **关联模式**：扁平 Long 外键 [skillConfigId]，不引入 `@Relation`（遵循 ADR-008 5.2 + 考古 R-9）。
 *
 * @see ExecutionStatus
 * @see ToolCallRecord
 * @see ToolCallListConverter
 */
@Entity
data class SkillExecutionRecord(
    @Id var id: Long = 0,
    var skillConfigId: Long,
    var skillName: String,
    var startedAt: Long,
    var finishedAt: Long,
    var durationMs: Long,
    var status: String = ExecutionStatus.SUCCESS,
    @Convert(converter = ToolCallListConverter::class, dbType = String::class)
    var toolCalls: List<ToolCallRecord> = emptyList(),
    var errorMessage: String? = null,
    var outputPreview: String? = null
)

/**
 * Skill 执行状态常量（ADR-013 5.7）。
 *
 * - [SUCCESS]：执行成功完成（含工具调用全部成功或无工具调用的纯文本响应）
 * - [FAIL]：执行失败（流式请求失败 / 工具执行异常 / maxRounds 超限）
 * - [CANCELLED]：协程取消（用户退出 / 上游取消传播）
 *
 * 用 String 常量而非 enum，对齐 [SkillSource] 模式（避免 enum ObjectBox 转换器复杂度）。
 */
object ExecutionStatus {
    const val SUCCESS = "SUCCESS"
    const val FAIL = "FAIL"
    const val CANCELLED = "CANCELLED"
}

/**
 * 工具调用记录（[SkillExecutionRecord.toolCalls] 元素）。
 *
 * **持久化**：作为 [SkillExecutionRecord] 的嵌套数据，通过 [ToolCallListConverter]
 * 序列化为 JSON String 存储（非独立 @Entity）。
 *
 * **字段说明**：
 * - [toolName] 工具名（含 skill 命名空间前缀，如 `meeting-notes__read_file`）
 * - [arguments] 调用参数（JSON string，由 [io.prism.skill.SkillExecutor.encodeArguments] 序列化）
 * - [result] 工具返回结果（成功/失败描述，前 200 字符截断）
 * - [durationMs] 单次工具调用耗时（毫秒）
 * - [status] 工具执行状态：[ExecutionStatus.SUCCESS] / [ExecutionStatus.FAIL] / [ExecutionStatus.CANCELLED]
 *
 * **安全**：[result] 由 [io.prism.skill.SkillExecutor] 的格式化函数生成（formatRejection /
 * formatTimeout / formatToolError / formatNoServer / formatConfirmError），已脱敏路径与堆栈。
 *
 * @see SkillExecutionRecord
 * @see ExecutionStatus
 */
@Serializable
data class ToolCallRecord(
    val toolName: String,
    val arguments: String,
    val result: String,
    val durationMs: Long,
    val status: String
)
