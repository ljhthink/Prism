package io.prism.skill

/**
 * 本地工具执行器接口（M6，ADR-016）。
 *
 * 与 [io.prism.network.McpToolProvider] 平行的执行后端，用于非 MCP 协议的本地工具
 * （如跨 App 调用 [io.prism.crossapp.CrossAppLocalToolExecutor]）。
 *
 * [SkillExecutor.executeToolCall] 在调用 MCP Server 前先查询 [handles]，
 * 若返回 true 则走 [execute] 本地路径，否则走 MCP 路径。
 *
 * **设计原则**（Karpathy Guidelines §2 简洁优先）：
 * - 纯函数式接口，不依赖 Android Context（具体实现通过注入解耦）
 * - suspend [execute] 返回 String（成功结果 / 失败描述，均回灌给 LLM，
 *   与 [io.prism.network.McpToolProvider.callTool] 一致）
 * - [handles] 必须无副作用且快速（O(1) 查表），供 SkillExecutor 前置判断
 *
 * **向后兼容**：[SkillExecutor] 的 localToolExecutor 参数默认 null，
 * 未注入时 SkillExecutor 行为与 M4 完全一致（仅走 MCP 路径）。
 *
 * **命名空间约定**：本地工具名使用 `cross_app__` 前缀（与 Skill 的
 * `skillName__` 前缀平行），[handles] 通过前缀匹配判断。
 */
interface LocalToolExecutor {

    /**
     * 判断是否由本执行器处理该工具。
     *
     * @param toolName 工具名（含命名空间前缀，如 `cross_app__open_app`）
     * @return true 表示由本执行器处理，[execute] 将被调用；false 表示走 MCP 路径
     */
    fun handles(toolName: String): Boolean

    /**
     * 执行本地工具。
     *
     * **降级策略**（与 McpToolProvider.callTool 一致）：所有失败场景返回描述性字符串
     * （而非抛异常），由 SkillExecutor 作为 tool result 回灌给 LLM，让 LLM 决定如何降级。
     *
     * **协程取消**（BR-error-handling-007）：实现方须重抛 CancellationException，
     * 不得用 runCatching 吞掉。
     *
     * @param toolName 工具名（含命名空间前缀）
     * @param arguments 工具参数（LLM 传入的 JSON 参数 Map）
     * @return 执行结果文本（成功/失败描述，回灌给 LLM）
     */
    suspend fun execute(toolName: String, arguments: Map<String, Any?>): String
}
