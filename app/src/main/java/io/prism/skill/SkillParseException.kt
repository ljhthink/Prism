package io.prism.skill

/**
 * SKILL.md 解析异常（ADR-013 5.2）。
 *
 * 在以下场景抛出：
 * - frontmatter 缺失（无 `---...---` 围栏）
 * - YAML 语法错误
 * - 必填字段缺失（name / description）
 * - 字段格式校验失败（name slug 格式、description 长度等）
 * - 字段类型不匹配（如 name 不是字符串）
 *
 * **fail-fast 原则**（CLAUDE.md 第十九节 19.4）：解析阶段立即失败，
 * 避免运行时错误。调用方应捕获此异常并降级为「Skill 加载失败」展示。
 *
 * @param message 错误描述
 * @param cause 底层异常（如 YAML 解析异常），可选
 */
class SkillParseException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)
