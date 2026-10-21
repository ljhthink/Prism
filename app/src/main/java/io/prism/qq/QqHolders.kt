package io.prism.qq

/**
 * QQ 小号助手的**进程级依赖持有器**（ADR-063；PRD US-3102 / US-3108）。
 *
 * **为什么用持有器**：定时触发发生在 [`ProactiveScheduleService`]（Service，无 DI 容器），
 * 而配置仓库、运行时状态仓库、发送链路均在应用层装配。持有器把"调度何时发生"与
 * "装配在哪里完成"解耦——`PrismApplication` 在启动时一次性装配（CR-4 修复点），
 * 服务侧只读不建。与项目既有 `VirtualDisplayManager` 的静态持有范式一致。
 */
object QqConfigRepositoryHolder {
    @Volatile
    var repo: QqConfigRepository? = null

    fun get(): QqConfigRepository? = repo
}

object QqRuntimeStateRepositoryHolder {
    @Volatile
    var repo: QqRuntimeStateRepository? = null

    fun get(): QqRuntimeStateRepository? = repo
}
