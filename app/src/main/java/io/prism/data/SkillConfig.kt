package io.prism.data

import io.objectbox.annotation.Convert
import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id

/**
 * Skill 配置实体 —— 持久化 Skill 的启用状态与来源元数据（ADR-013 5.1）。
 *
 * 与 [SkillManifest]（内存层，SKILL.md frontmatter 解析结果）分层：
 * - [SkillConfig] 稳定持久化（启用状态、来源、目录路径），跨会话保留
 * - [SkillManifest] 随 SKILL.md 规范演进，纯内存，不持久化
 *
 * **字段说明**：
 * - [id] 主键
 * - [name] slug，唯一，与 frontmatter `name` 一致（`^[a-z0-9-]{1,64}$`）
 * - [displayName] 展示名（取 frontmatter description 首行或 name）
 * - [source] 来源：[SkillSource.LOCAL_BUILTIN] / [SkillSource.LOCAL_USER] / [SkillSource.REMOTE]
 * - [sourceUri] REMOTE 时的下载 URL；本地 Skill 为 null
 * - [skillDir] Skill 目录绝对路径（含 SKILL.md + 资源）
 * - [isEnabled] 启用开关（落库，跨会话保留，修复考古 R-6：RagTarget 仅内存态未持久化的教训）
 * - [isInstalled] 安装状态（远程下载失败可标记 false，文件缺失扫描后标记 false）
 * - [version] 版本号（frontmatter version，默认 "0.0.0"）
 * - [dependsOnMcpServers] 依赖的 MCP Server name 列表（运行时检查可用性）
 * - [createdAt] / [updatedAt] 时间戳（毫秒）
 *
 * **类型转换**：
 * - [dependsOnMcpServers] 通过 [StringListConverter] 序列化为 String 存储（复用 US-004 模式，BR-data-001）
 *
 * **关联模式**：扁平 Long 外键，不引入 `@Relation`（遵循 ADR-008 5.2 + 考古 R-9）。
 *
 * @see SkillSource
 * @see StringListConverter
 */
@Entity
data class SkillConfig(
    @Id var id: Long = 0,
    var name: String,
    var displayName: String,
    var source: String = SkillSource.LOCAL_BUILTIN,
    var sourceUri: String? = null,
    var skillDir: String,
    var isEnabled: Boolean = false,
    var isInstalled: Boolean = true,
    var version: String = "0.0.0",
    @Convert(converter = StringListConverter::class, dbType = String::class)
    var dependsOnMcpServers: List<String> = emptyList(),
    var createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis()
)

/**
 * Skill 来源常量（ADR-013 5.1）。
 *
 * - [LOCAL_BUILTIN]：内置预设，从 `assets/skills/builtin/` 读取（APK 内置，不可修改）
 * - [LOCAL_USER]：用户自建，从 `filesDir/skills/user/` 读取（可增删）
 * - [REMOTE]：远程下载，从 `filesDir/skills/remote/` 读取（URL 下载安装）
 *
 * 加载优先级（从高到低，对齐 OpenClaw 6 层）：用户自建 > 远程下载 > 内置预设。
 */
object SkillSource {
    const val LOCAL_BUILTIN = "LOCAL_BUILTIN"
    const val LOCAL_USER = "LOCAL_USER"
    const val REMOTE = "REMOTE"
}
