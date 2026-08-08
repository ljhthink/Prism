package io.prism.skill

import kotlinx.serialization.json.JsonElement

/**
 * Skill manifest 内存数据类 —— SKILL.md frontmatter 解析结果（ADR-013 5.1）。
 *
 * 与 [io.prism.data.SkillConfig]（持久化层）分层：
 * - [SkillConfig] 稳定持久化（启用状态、来源、目录路径）
 * - [SkillManifest] 随 SKILL.md 规范演进，纯内存，不持久化
 *
 * **字段语义**（对齐 OpenClaw SKILL.md 规范 + Prism 扩展）：
 * - [name] 必填，slug（`^[a-z0-9-]{1,64}$`），与 SkillConfig.name 一致
 * - [description] 必填，短描述（≤160 字符），用于 prompt 注入与路由决策
 * - [version] 可选，版本号
 * - [userInvocable] 可选，默认 true，是否允许用户手动调用
 * - [disableModelInvocation] 可选，默认 false，是否禁止 LLM 自主调用
 * - [homepage] 可选，主页 URL
 * - [os] 可选，平台筛选列表（android 固定允许）
 * - [tools] Prism 扩展字段：Skill 显式声明的工具（非 OpenClaw 标准），便于权限控制与可观测
 * - [systemPrompt] Skill 专用 system prompt 片段（注入到对话 system prompt）
 * - [maxRounds] 工具调用循环上限（默认 10，per-Skill 可配置）
 * - [body] Markdown 正文（指令），SKILL.md frontmatter 之后的完整内容
 *
 * @see SkillToolDecl
 */
data class SkillManifest(
    val name: String,
    val description: String,
    val version: String? = null,
    val userInvocable: Boolean = true,
    val disableModelInvocation: Boolean = false,
    val homepage: String? = null,
    val os: List<String>? = null,
    val tools: List<SkillToolDecl>? = null,
    val systemPrompt: String? = null,
    val maxRounds: Int = 10,
    val body: String
)

/**
 * Skill 声明的工具定义（ADR-013 5.1，Prism 对 OpenClaw 规范的扩展）。
 *
 * OpenClaw 靠 LLM 自主决定调用工具，Prism 显式声明便于：
 * - 权限控制：仅暴露声明的工具
 * - 可观测：记录工具调用链
 * - 命名空间隔离：运行时转换为 `skillName__toolName`
 *
 * **字段语义**：
 * - [name] 工具名（不含 skill 前缀）
 * - [description] 工具描述（供 LLM 决策）
 * - [parameters] JSON Schema 描述参数结构（kotlinx.serialization [JsonElement]）
 *
 * @see SkillManifest
 */
data class SkillToolDecl(
    val name: String,
    val description: String,
    val parameters: JsonElement
)
