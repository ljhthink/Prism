package io.prism.config

/**
 * 手机操控高危动作（发送/删除/拨号/短信等）的人工确认策略（v1 真机二次修复 Issue 4b）。
 *
 * 用户可在设置页选择对这类操作的管控级别：
 * - [BLOCK]：全部拦截（最高安全：任何发送/删除/拨号都不执行，请用户手动操作）
 * - [ALLOW]：全部放行（最大便捷：直接执行，不询问）
 * - [ASK]：逐次询问（默认：每次命中高危动作前弹出确认，由用户决定）
 */
enum class HighRiskApprovalMode {
    BLOCK,
    ALLOW,
    ASK;

    companion object {
        /** 默认策略：逐次询问（安全优先）。 */
        const val DEFAULT_NAME = "ASK"

        /** DataStore 持久化使用的字符串名（枚举 name 的稳定快照，防重构改名破坏旧数据）。 */
        const val STORED_ASK = "ASK"
        const val STORED_BLOCK = "BLOCK"
        const val STORED_ALLOW = "ALLOW"

        /** 从持久化字符串解析（未知值回退默认 ASK，兼容旧数据）。 */
        fun fromStored(name: String?): HighRiskApprovalMode = when (name) {
            STORED_BLOCK -> BLOCK
            STORED_ALLOW -> ALLOW
            else -> ASK
        }
    }
}