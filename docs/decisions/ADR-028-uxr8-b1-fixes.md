# ADR-028: UXR8 批次1 修复（RagTarget 持久化 + L2 记忆保存触发 + 配置弹层可用高度）

> 解决 UXR8 真机使用反馈的 3 个 Bug：非检索对话仍被注入知识库内容 / L2 跨会话记忆始终无记录 / 能力配置弹层被软键盘顶出屏幕。

| 项目 | 内容 |
| --- | --- |
| 状态 | Proposed |
| 日期 | 2026-08-16 |
| 决策者 | 主 Agent |
| 关联文档 | [PRD UXR8](../prd-uxr8.md)、[ADR-015 M5 记忆系统](ADR-015-m5-memory-system-architecture.md)、[ADR-012 M3 RAG 对话集成](ADR-012-m3-rag-conversation-integration.md) |
| 上游调研 | [考古报告 2026-08-16-uxr8-archaeology.md](../reports/2026-08-16-uxr8-archaeology.md)、[护栏报告 2026-08-16-uxr8-b1-guardrail.md](../reports/2026-08-16-uxr8-b1-guardrail.md) |
| 风险等级 | P2（跨模块：RAG 配置层 + 记忆系统 + 通用 UI 组件） |

## 背景（Context）

UXR8 真机使用反馈 3 个 Bug（PRD docs/prd-uxr8.md 批次1）：

1. **Bug1（非检索对话被注入知识库内容）**：根因是 `RagTarget` 仅存内存态，`startNewConversation()` 无条件重置为 `AllLibraries`。用户明确关闭 RAG（`Off`）后点"新对话"，状态被静默重置回全库 → 每轮对话系统主动注入全库检索内容（非 LLM 主动调用）。
2. **Bug2（L2 跨会话记忆始终无记录）**：根因是 `persistSessionMemories()` 仅在 `ViewModel.onCleared()` 触发；而 `onCleared` 只在 Activity 销毁（退出应用/系统回收）时调用——用户正常"新对话/切换历史会话"的路径**从不经过** `onCleared`，L2 保存永不触发。
3. **Bug3（配置弹层被键盘顶出屏幕）**：`PrismSheetHost` 的 `maxSheetHeight` 按**全屏** 90% 计算，与同一容器的 `imePadding()` 叠加后"弹层(≤90%屏) + 键盘(~250-350dp)"超过屏幕可见高度 → 底部对齐弹层顶部出屏，配置 MCP / L3 画像时点击文本框整个界面上移不可达。

## 决策（Decision）

### 子决策 A：RagTarget DataStore 持久化（Bug1）

- 新增 `RagTargetConfigRepository`（DataStore<Preferences>，name = `prism_rag_config`）：
  - `setRagTarget(target)`：Off/AllLibraries/SpecificLibrary(kbId) 编码为 mode 字符串 + kbId Long。
  - `getRagTarget(): RagTarget`（suspend）：读侧 fail-safe（BR-security-005）——未知 mode / kbId 缺失或 ≤0 → 回退 `AllLibraries`（合法默认）。
- `ConversationViewModel`：
  - 新增构造参数 `ragTargetConfigRepository`（默认 null，向后兼容既有测试；null 时仅内存态，`startNewConversation` 同样不重置）。
  - `init` 异步恢复持久化值（`ragTargetToggledByUser` 标记竞态防护，与深度思考开关同一模式）。
  - `setRagTarget` 同步写内存 + 异步持久化。
  - `startNewConversation` **删除**无条件重置——RAG 目标由用户显式选择驱动，跨新对话保持。
  - **库存在性校验（guardrail MED-2）**：init 恢复时若持久化为 `SpecificLibrary(kbId)` 且该库已被删除 → **降级 `Off`**（而非回退 AllLibraries——避免"用户明确限定单库 → 突然注入全库"的意图/隐私双重意外）。
- `PrismApplication` 提供 `ragConfigDataStore` 单例 + `ragTargetConfigRepository` lazy，`Factory` 注入。

### 子决策 B：L2 记忆保存接入会话切换路径（Bug2）

- `persistSessionMemories(source)` 提取为 internal（可测性，BR-testing-004），fire-and-forget 走 `applicationScope`。
- **新增两个生产触发点**（同步捕获 sessionId/messages 快照后才置空/切换）：
  - `startNewConversation()`：持久化当前会话 → 清空。
  - `loadSession()`：持久化当前会话 → 切换。
- 保留 `onCleared()` 兜底触发。
- **幂等守卫（guardrail LOW-1）**：`persistedSessionIds` 并发 Set 保证同一 sessionId 至多持久化一次（`saveSessionMemories` 为无条件 insert，纵深防御未来调用路径重复保存）。
- 成功路径记录 `Log.i(TAG, "L2 memories persisted: source=... savedCount=...")`，供真机 RCA 区分"触发未执行 vs 保存 0 条"。

### 子决策 C：配置弹层按可用高度限高（Bug3，含 OBS-2 迭代修正）

- **初版（OBS-1 修正）**：`maxSheetHeight` 由"全屏 90%"改为 **(屏幕高度 − IME 高度 − 导航栏高度) × 90%**（下限 `coerceAtLeast(240.dp)`），修饰符顺序 `imePadding → navigationBarsPadding → heightIn`。
- **OBS-2 根因（TRAE-debugger 约束探针实测，[debug 报告](../reports/2026-08-16-uxr8-b1-bug3-obs2-debug.md)）**：初版在模拟器上仍复现"键盘弹出 → 弹层塌缩至 85dp"。像素级证据：
  - `adjustResize` 的 **window resize 已生效**：ComposeView 约束被系统压缩 2340px→1146px；
  - `WindowInsets.ime` 在 window resize 后**仍报告键盘完整物理高度**（912px）；
  - `imePadding()` 基于 insets 再扣 912px → **双重扣除** → 弹层可用仅 234px。
  - 关键差异：`navigationBars` insets 会自适应归 0（无重复），唯独 `ime` insets 与 window 状态解耦。
- **终版修复（双模式自适应）**：
  - `BoxWithConstraints` 读取父级实际约束 `parentMax`；
  - 判定式 `imeAppliedByParent = parentMaxPx < screenPx − imePx/2`（resize 模式 1146 vs insets 模式 2208，阈值容差 >600px）：
    - **resize 模式**（window resize 生效）：不加 `imePadding`，`maxSheet = parentMax × 0.9`；
    - **insets 模式**（decorFitsSystemWindows 完全生效的设备）：`imePadding` 单一来源平移，`maxSheet = (parentMax − ime) × 0.9`；
  - `navigationBarsPadding` 保持在 `BoxWithConstraints` 外层（两模式均正确：insets 自适应）。
- 验证：MCP 配置弹层与 L3 画像弹层键盘弹出稳态 maxSheet=331.85dp、输入框完整可见可点击；键盘收起恢复 587dp。

**一句话**：RAG 目标 DataStore 持久化 + 删除新对话重置（Bug1）；L2 保存接入新对话/切换会话路径 + 幂等守卫（Bug2）；弹层按"扣键盘可用高度"限高 + 状态化 insets（Bug3）。

## 备选方案（Alternatives）

| 方案 | 优点 | 缺点 / 否决理由 |
|---|---|---|
| Bug1：保持内存态 + UI 每次新对话询问用户 | 无持久层引入 | 每次打断用户；与"用户显式选择应被记住"的预期相悖 |
| Bug1：指定库删除后回退 AllLibraries | 实现最简单 | "用户明确限定单库 → 突然注入全库"是意图/隐私双重意外（guardrail MED-2 结论），降级 Off 更安全 |
| Bug2：定时器周期性持久化 | 覆盖意外退出 | 周期唤醒耗电；L3 隐式抽取需 LLM 调用不能高频；会话切换点已覆盖 99% 场景 |
| Bug2：Activity onStop 触发 | 覆盖切后台 | Activity 级生命周期与 ViewModel 不对齐（旋转/分屏误触发）；onCleared 已兜底 |
| Bug3：弹层固定 maxHeight 上限（如 600dp） | 一行改动 | 大屏浪费空间；小屏 + 键盘仍可能出屏，未根除 |
| Bug3：弹层内 TextField 单独 imePadding | 不改宿主 | PrismSheet 内容不滚动时字段仍被遮；宿主统一处理所有弹层调用方 |

## 后果（Consequences）

- 正面：
  - 用户关闭 RAG 后跨新对话/重启保持关闭（Bug1 根治）；
  - 正常使用流（新对话/切换会话）下 L2 记忆真实落库，L3 隐式偏好随 L2 一起触发（Bug2 根治）；
  - 所有走 `PrismSheetHost` 的弹层（MCP/L3/Skills 等配置）键盘弹出时自动限高，顶部可达（Bug3 根治）；
  - MED-2 降级策略消除"指定库删除后状态误导"。
- 负面 / 代价：
  - RagTarget 持久化引入一次 DataStore 异步读取（init 恢复有约一帧默认值窗口，`ragTargetToggledByUser` 防覆盖）；
  - `startNewConversation`/`loadSession` 新增一次后台 L2 embed + L3 LLM 调用（fire-and-forget，失败静默降级不影响交互）；
  - 组合阶段读取 insets 每次键盘动画帧触发 PrismSheetHost 重组（已收敛为状态化读取，影响极小）。
- 需要同步更新的文档或代码：`docs/prd-uxr8.md` 批次1 状态、`AGENTS.md` 用户故事、本 ADR 索引（docs/decisions/README.md）、`docs/behavioral-rules.md`（BR-ui-002/BR-ui-005 正例双模式模板同步）。

## 风险与缓解

| 风险 | 等级 | 缓解措施 |
|---|---|---|
| init 异步恢复与用户手动切换竞态 | 低 | `ragTargetToggledByUser` 标记（与 thinkingEnabled 同一已验证模式） |
| 持久化库被删后状态残留 | 低 | init 存在性校验降级 Off（MED-2）；检索空结果本就降级 NormalChat，无错库注入 |
| L2 重复保存（saveSessionMemories 无去重） | 低 | `persistedSessionIds` 幂等守卫（LOW-1）；静态追踪当前三调用点无重复可达 |
| 键盘 insets 读取慢一帧/动画不同步 | 低 | `asPaddingValues()` 状态化读取（LOW-2）；真机 E2E 键盘场景已列 UNC 补测 |
| L2/L3 持久化失败 | 低 | fire-and-forget + 静默降级（BR-error-handling-004），不影响会话切换交互 |

## 参考

- [考古报告 2026-08-16-uxr8-archaeology.md](../reports/2026-08-16-uxr8-archaeology.md)
- [护栏报告 2026-08-16-uxr8-b1-guardrail.md](../reports/2026-08-16-uxr8-b1-guardrail.md)
- Compose WindowInsets 官方文档（edge-to-edge 下 IME insets 交付 + asPaddingValues 推荐用法）
