package io.prism.skill

/**
 * 复合本地工具执行器（问题 8b，ADR-020）—— 将多个 [LocalToolExecutor] 委托组合为单个执行器。
 *
 * **背景**：Prism 的本地工具随里程碑持续增加：
 * - M6 跨 App 工具（[io.prism.crossapp.CrossAppLocalToolExecutor]）：`cross_app__*`
 * - 问题 8b 联网搜索（[io.prism.network.WebSearchLocalToolExecutor]）：`web_search__*`
 *
 * [SkillExecutor] 构造仅接收一个 [localToolExecutor]，通过本执行器把多个独立实现
 * 组合成一个门面（Composite 模式），[SkillExecutor] 零改动即可感知全部本地工具。
 *
 * **委托规则**：
 * - [handles]：任一 delegate 命中即返回 true
 * - [execute]：委托给第一个命中的 delegate；无命中返回未知工具文案（防御）
 *
 * **设计原则**（Karpathy Guidelines §2）：
 * - 无状态门面，构造注入 delegates 列表，测试可注入任意组合
 * - 各 delegate 保持单一职责（SRP），互不感知
 * - 空 delegates 时 handles 恒 false（安全降级，与无本地工具行为一致）
 *
 * @param delegates 按优先级排序的本地工具执行器列表（首个命中者执行）
 */
class CompositeLocalToolExecutor(
    private val delegates: List<LocalToolExecutor>
) : LocalToolExecutor {

    override fun handles(toolName: String): Boolean = delegates.any { it.handles(toolName) }

    override suspend fun execute(toolName: String, arguments: Map<String, Any?>): String {
        val delegate = delegates.firstOrNull { it.handles(toolName) }
            ?: return "未知本地工具: $toolName"
        return delegate.execute(toolName, arguments)
    }

    // v1 批次13（B/D16c）：把「视觉不支持」降级信号转发给全部 delegate，
    // 使手机操控等有状态执行器（覆写 [LocalToolExecutor.onVisionUnsupported]）能自降级。
    override fun onVisionUnsupported() {
        delegates.forEach { it.onVisionUnsupported() }
    }
}
