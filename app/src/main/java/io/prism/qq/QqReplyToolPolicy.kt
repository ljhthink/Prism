package io.prism.qq

/**
 * QQ 聊天场景的**工具策略**（2026-11-16 新增；ADR-077）。
 *
 * ## 为什么需要单独一层
 *
 * 主聊天有完整工具回路（[io.prism.skill.SkillExecutor.executeLoop]），但 QQ 被动回复
 * 此前是**一次裸补全**（`QqProactiveComposer` 的 `stream=false` 请求里没有 `tools`）——
 * 于是同一套能力"在聊天页能用、在 QQ 里完全不能用"，角色只能嘴上说"人家去查"。
 *
 * 本类只负责**决策**：哪些工具在 QQ 场景可用、给模型多少轮、超时怎么收口。
 * 真正的执行复用既有 `executeLoop`（含熔断、429 重试、配对净化），不新写循环。
 *
 * ## 白名单（用户 2026-11-16 裁定：白名单 + 手机操控）
 *
 * | 能力 | 工具前缀 | 是否允许 | 理由 |
 * | --- | --- | --- | --- |
 * | 联网搜索 | `web_search__*` | ✅ | 聊天里最常用（"帮我查一下"） |
 * | 联网搜索（热榜） | `hotlist__*` | ❌ **2026-11-17 移除** | 两次串行 HTTP，真机实测 >30s，聊天里等不起 |
 * | 知识库 | `knowledge_base__*` | ✅ | 读用户自己的资料 |
 * | TODO | `todo_write`（无命名空间前缀） | ✅ | 记待办 |
 * | 文档生成 | `document__*` | ✅ | 生成 Word/Excel |
 * | 手机操控 | `phone_control__*` | ✅（用户显式开启） | 高风险：后台可能建副屏/抢前台 |
 * | 角色日记 | `diary__*` | ✅ | 本批次新增（ADR-077） |
 * | QQ 自身工具 | `qq__*` | ❌ | **自噬**：在应答链路里再发 QQ 消息会与回复流程打架 |
 * | 反问用户 | `ask_user__*` | ❌ | QQ 里"反问"等于要发消息，无法等待用户点按 |
 * | 桌宠 | `pet__*` | ❌ | 与聊天无关，纯噪声 |
 * | 跨应用 | `cross_app__*` | ❌ | 用户本轮未授权（会启动第三方应用） |
 *
 * ## 预算
 *
 * - [DEFAULT_MAX_ROUNDS] = 2：够「查一次 → 回答」，再多会让延迟不可控；
 * - [DEFAULT_BUDGET_MS] = 15 s：超预算用已有文本收口，**绝不因工具超时丢掉回复**。
 */
object QqReplyToolPolicy {

    /**
     * 允许的工具名前缀（白名单）。
     *
     * 注意 `todo_write` **没有命名空间前缀**（`TodoLocalToolExecutor.TOOL_NAME` 就是
     * `todo_write`），故必须单独列精确名——写成 `todo__` 永远不会命中。
     *
     * **2026-11-17 真机取证：`hotlist__` 已从白名单移除**。
     * `hotlist__get` 是"先拉榜单列表 → 再拉具体榜单"的**两次串行 HTTP**，
     * 真机实测单次调用 >30s（日志：`工具回路超预算（30000ms）已中断（工具=hotlist__get）`）。
     * 在一个聊天回复里等 30s+ 是不可接受的；而"接了却永远超时→回落"比没有更坏——
     * 它会白等 30s 且最终仍答不出。用户要"查热搜"时 `web_search__` 能覆盖同一意图
     * （单次调用、自带超时），故保留 web_search、去掉 hotlist。
     */
    private val ALLOWED_PREFIXES = listOf(
        "web_search__",
        "knowledge_base__",
        "document__",
        "phone_control__",
        "diary__"
    )

    /** 允许的**精确名**（无命名空间前缀的工具）。 */
    private val ALLOWED_EXACT = listOf("todo_write")

    /** 明确禁止的前缀（即使将来白名单放宽也不该放进来）。 */
    private val DENIED_PREFIXES = listOf(
        "qq__",
        "ask_user__",
        "pet__",
        "cross_app__",
        "mcp_" // 任意 MCP 服务器工具：QQ 场景不暴露（用户未授权，且不可控延迟）
    )

    /**
     * 该工具是否允许在 QQ 应答链路中使用。
     *
     * @param toolName 含命名空间的工具名
     * @param phoneControlEnabled 用户是否显式开启了"QQ 里可用手机操控"（默认 false）
     */
    fun isAllowed(toolName: String, phoneControlEnabled: Boolean = false): Boolean {
        if (toolName.isBlank()) return false
        if (DENIED_PREFIXES.any { toolName.startsWith(it) }) return false
        if (toolName.startsWith("phone_control__")) return phoneControlEnabled
        if (toolName in ALLOWED_EXACT) return true
        return ALLOWED_PREFIXES.any { toolName.startsWith(it) }
    }

    /**
     * 按策略过滤工具定义列表。
     *
     * @return 过滤后的列表（可能为空——为空时调用方应走**单次补全**旧路径）
     */
    fun filter(
        tools: List<io.prism.network.ToolDefinition>,
        phoneControlEnabled: Boolean = false
    ): List<io.prism.network.ToolDefinition> =
        tools.filter { isAllowed(it.function.name, phoneControlEnabled) }

    /** 工具名（诊断日志用；不含参数，无隐私风险）。 */
    fun namesOf(tools: List<io.prism.network.ToolDefinition>): List<String> =
        tools.map { it.function.name }

    /**
     * 默认最大工具轮数。
     *
     * 2 = 「调一次工具 → 回答」；模型若在第二轮还要再查，则由 [QqReplyToolLoop] 的
     * **强制作答**兜底（把已有的工具结果交给模型，禁用工具再要一次正文），
     * 因此不必把轮数调大——真机实测调到 3 只会多烧一次搜索、并不会更容易拿到正文。
     */
    const val DEFAULT_MAX_ROUNDS = 2

    /**
     * 默认总预算（毫秒）：超时用已有文本收口。
     *
     * **2026-11-16 真机校准**：初值 15s，真机第一次实测就被打断——
     * 日志 `工具回路超预算（15000ms）已中断（已用 15008ms，工具=hotlist__get）`。
     * 热榜/搜索这类工具要并发抓多个源（5–15s 很正常），15s 会让**所有网络类工具**
     * 实际都无法完成，等于"接了工具但永远用不上"。改为 30s。
     *
     * 与租约的关系：30s 工具 + 25s 节奏 + 生成 ≈ 65s/轮；两轮排队 ≈ 130s <
     * [QqInboxStore.CLAIM_TTL_MS]（300s），仍安全。
     */
    const val DEFAULT_BUDGET_MS = 30_000L

    /** 预算下限/上限（设置页可调时的收敛区间）。 */
    const val MIN_BUDGET_MS = 5_000L
    const val MAX_BUDGET_MS = 60_000L

    /**
     * **临界区总预算**（2026-11-16；guardrail M-07）。
     *
     * 拟人节奏延迟发生在 `replyMutex` 内。若本轮生成 + 工具回路已花掉大量时间，
     * 再叠加最多 25s 的表演性延迟，会显著拉长锁占用 → 另一条协程的 claim 早于等锁
     * → 租约过期 → 二次领取 → **双回复**；同时用户实际等待也会失控。
     *
     * 因此给"生成 + 工具 + 节奏"一个**总上限**：超过后节奏延迟被压缩（表演让位于体验与正确性）。
     *
     * **2026-11-17 真机校准**：初值 60s 时，一次"搜索 + 长回复"实测总耗时 **49s**
     * （工具 ~22s + 节奏 24.9s）——虽然参数上都"合理"，但一次聊天回复等 49 秒不可接受。
     * 收到 **35s** 后：纯聊天（生成 ~4s）仍可拿到完整 25s 节奏；
     * 带工具的轮次则把节奏压到剩余额度，总时长收敛在 35s 内。
     * 35s 仍远小于租约 300s（两轮排队 70s < 300s）。
     */
    const val PACING_CRITICAL_BUDGET_MS = 35_000L
}
