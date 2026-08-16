package io.prism.config

/**
 * 工具审批模式（UXR3 问题 10，ADR-023）—— LLM 调用工具的权限策略三选一。
 *
 * **背景**：此前工具权限「没有实际划分」—— [io.prism.skill.SkillExecutor] 对大部分工具
 * 强制用户确认，但 `web_search__search` 走白名单免审批。用户要求明确三模式并在设置中切换：
 *
 * - [MANUAL]（手动审批）：LLM 每次调用工具都需询问用户（白名单只读工具 `web_search__search`
 *   保持免审批，避免高频只读操作弹窗轰炸，ADR-021 问题 9 既有优化保留）
 * - [AUTO]（自动审批）：所有工具直接放行，不询问用户
 * - [DISABLED]（禁用）：不向 LLM 注入任何工具定义（LLM 无法感知与调用工具），
 *   且 [io.prism.skill.SkillExecutor] 对任何工具调用一律拒绝（纵深防御）
 *
 * **默认值**：[DEFAULT] = [MANUAL]（安全优先，与历史行为一致，向后兼容）。
 *
 * **存储**：经 [ToolApprovalConfigRepository] 持久化到 DataStore，运行时即时生效（无需重启）。
 */
enum class ToolApprovalMode {
    /** 手动审批：每次工具调用询问用户（白名单只读工具免审批）。 */
    MANUAL,

    /** 自动审批：所有工具直接放行，不需用户审核。 */
    AUTO,

    /** 禁用：不注入工具定义，任何工具调用均拒绝。 */
    DISABLED;

    companion object {
        /** 默认审批模式（手动审批，安全优先）。 */
        val DEFAULT: ToolApprovalMode = MANUAL
    }
}
