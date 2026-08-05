# ADR-003: Provider 配置详情页接入（设置模块数据层 + UI 桥接）

> 依 CLAUDE.md 第十七节：修改架构的模块接口（`ProviderConfigRepository` 新增 `providers` StateFlow、
> `PrismApplication` 暴露 `ApiKeyRepository`）、确立数据层与 UI 桥接模式，必须写 ADR。
> 依 BR-interface-001：UI 设计须用户审核通过后方可实现，本 ADR 记录 2026-08-05 已获用户批准接入 Provider 配置详情页。

| 项目 | 内容 |
| --- | --- |
| 状态 | Accepted（实现已通过 guardrail-enforcer 审查 + ac-verifier 验收，含自定义 Provider 创建入口） |
| 日期 | 2026-08-05 |
| 决策者 | 主 Agent + 用户 |
| 关联文档 | [ADR-002](ADR-002-prism-chat-ui-architecture.md) / [ADR-001](ADR-001-prism-tech-stack.md) / [SettingsScreen.kt](../../app/src/main/java/io/prism/ui/settings/SettingsScreen.kt) |
| 上游调研 | [settings-provider-archaeology](../../docs/reports/2026-08-05-settings-provider-archaeology.md) |
| 风险等级 | P2 跨模块（修改数据层接口 + 新增 ViewModel + 加密 API Key 读写） |

## 背景（Context）

US-005 确立聊天 UI 骨架后，设置模块仍为静态占位（Provider 选择器仅展示，不接数据层）。需要将
已完成的 US-003（API Key 加密存储）与 US-004（Provider 配置 CRUD）数据层接入设置界面，提供
**Provider 配置详情页**：从预设添加、编辑名称/Base URL/模型、激活/删除、API Key 掩码读写。

接入前需解决：

1. UI 如何实时获知 Provider 列表变化（`ProviderConfigRepository` 原仅有 `activeProviderFlow`，无全量列表 Flow）
2. 设置屏如何获取 `ApiKeyRepository`（`PrismApplication` 未暴露，只存在于懒加载属性中）
3. ViewModel 如何桥接数据层与 Compose UI（沿用 ADR-002「不引入 Hilt」手动工厂注入）

## 决策（Decision）

### 4.1 数据层：`ProviderConfigRepository` 新增 `providers` StateFlow

**决策**：在 `ProviderConfigRepository` 新增 `providers: StateFlow<List<ProviderConfig>>`（按 `createdAt` 升序），
统一由 `refreshFlows()` 在 `save`/`remove`/`removeAll`/`setActive`/`clearActive`/`createFromPreset` 后刷新，
与既有 `activeProviderFlow` 同步维护。

**理由**：设置屏需订阅完整 Provider 列表来渲染列表弹层；单一 `refreshFlows()` 保证列表与激活态的
一致性，避免多处手工同步导致状态漂移（Karpathy Guidelines：单一事实来源）。

### 4.2 Application 层：`PrismApplication` 暴露 `apiKeyRepository`

**决策**：在 `PrismApplication` 中以 `lazy` 暴露 `apiKeyRepository`，复用进程级 DataStore 单例
（`preferencesDataStore(name = "prism_api_keys")`）。

**理由**：调用方（`SettingsViewModel.Factory`）需经 `APPLICATION_KEY` 获取仓库实例，避免在
Composable 中直接 `cast`（CLAUDE.code-archaeologist 建议）。DataStore 必须是进程级单例，
多个实例会崩溃，故用 `preferencesDataStore` 委托。

### 4.3 ViewModel 层：`SettingsViewModel` 桥接数据层与 UI

**决策**：新增 `SettingsViewModel`，注入 `ProviderConfigRepository` + `ApiKeyRepository`，经 `ViewModelProvider.Factory`
（`viewModelFactory { initializer { } }`）从 `PrismApplication` 获取。暴露：

- `providers` / `activeProvider`：`stateIn(viewModelScope, WhileSubscribed(5s))` 订阅仓库 Flow
- `selectedProvider`：当前编辑的 Provider（null 表示未选中）
- 操作：`saveProvider` / `newCustomProvider` / `createFromPreset` / `deleteProvider` / `setActive` / `selectProvider` / `saveApiKey` / `loadApiKey`

**理由**：沿用 ADR-002「不引入 Hilt」的轻量手动工厂注入；`WhileSubscribed(5s)` 在 UI 不可见时
自动停止订阅，避免常驻后台消耗。`saveProvider` 返回库层新 id，供新建后立即激活（见 4.6）。

### 4.4 API Key 安全：掩码编辑 + Keystore 加密

**决策**：API Key 在编辑弹层中以 `PrismField(secret = true)` 掩码显示（`PasswordVisualTransformation`）；
明文仅经 `loadApiKey` 在内存短暂存在用于回显，保存时经 `ApiKeyRepository.saveApiKey` 加密落盘
（明文不落盘，符合 US-003）。

**理由**：延续 US-003 安全契约（DataStore 仅存密文）；掩码输入避免用户输入时明文暴露。

### 4.5 UI：`SettingsScreen` 底部弹层结构

**决策**：设置屏「Provider 配置」「API Key」两行接入 `SettingsViewModel`，采用 `PrismSheetHost` +
`PrismSheet` 承载三个弹层：Provider 列表（含从预设添加）、Provider 详情编辑（名称/Base URL/模型/激活/删除）、
API Key 管理。沿用 v0.4 深空玻璃组件规范（`PrismButton`/`PrismField`/`PrismSheet`/`PrismSwitch`）。

**理由**：与知识库/MCP/Skill 配置详情页弹层模式一致（ADR-002 组件体系），保持交互一致性。

### 4.6 自定义 Provider 创建入口

**决策**（2026-08-05 用户确认）：在 Provider 列表弹层新增「＋ 新建自定义 Provider」入口，经
`SettingsViewModel.newCustomProvider()` 生成一个 `apiKeyRef` 已唯一化（`custom-<timestamp>`）、
其余字段为空的草稿配置（`id=0`），复用 `ProviderEditSheet` 新建模式（标题「新建 Provider」、
隐藏「删除」按钮、独立「激活」按钮禁用）完成手填创建。保存时 `saveProvider` 返回库层新 id，
若勾选「设为激活 Provider」则 `setActive(savedId)`，保证单激活不变式。

**理由**：PRD US-001 要求「至少 5 种 Provider」（预设 5 种已满足），用户确认额外补充自定义创建路径，
使任意 LLM 端点均可配置；复用既有编辑弹层避免新增 UI 组件。自定义请求头（headers）本轮不实现，
留待 US-007 聊天集成时补充（用户决策）。

---

## 备选方案（Alternatives）

| 方案 | 优点 | 缺点 / 否决理由 |
|---|---|---|
| **Provider 选择器直接接数据层（不新增弹层）** | 改动最小 | 无法编辑端点/模型/删除，配置能力缺失；US-007 切换仍需完整配置 |
| **Hilt 依赖注入** | 类型安全、扩展性好 | 骨架阶段过度设计；改动点集中在 SettingsScreen，后续引入时局部替换（ADR-002 已排期） |
| **`providerRepository.getAll()` 轮询** | 零 StateFlow 改动 | 无响应式更新，UI 需手动刷新；违背 Compose 响应式范式 |
| **`stateIn` 用 `Eagerly`** | 立即启动共享 | ViewModel 与 UI 同生命周期，`WhileSubscribed` 更省资源，避免后台常驻 |

---

## 后果（Consequences）

- 正面后果：
  - 设置屏 Provider 配置全功能可用（从预设添加/编辑/激活/删除/API Key 掩码加密）
  - `providers` StateFlow 为后续 US-007「Provider 切换」提供实时列表基础
  - `ApiKeyRepository` 经 Application 暴露，复用进程级 DataStore 单例，避免多实例崩溃
  - 手动工厂注入保持轻量，编译链路简单
- 负面后果 / 代价：
  - 修改 `ProviderConfigRepository` 接口（新增公开 `providers` 属性），P2 变更需 ADR
  - `SettingsViewModel` 依赖两个仓库，构造需 `PrismApplication` 上下文（Factory 中 cast）
  - 新增一层 ViewModel 与测试，维护面扩大
- 需要同步更新的文档或代码：
  - `ProviderConfigRepository` / `PrismApplication` / `SettingsScreen` / 新增 `SettingsViewModel`
  - 新增 `SettingsViewModelTest`、`ProviderConfigRepositoryTest` providers 用例
  - `docs/decisions/README.md` 索引、`README.md` 文档索引

---

## 风险与缓解

| 风险 | 等级 | 缓解措施 |
|---|---|---|
| ObjectBox 跨线程事务报错（JVM 测试关闭时） | 低 | 该报错为关闭 BoxStore 时随游标清理的 stderr 噪音，不影响断言；已确认全部测试通过；后续可评估显式关闭 |
| `stateIn(WhileSubscribed)` 测试传播时序 | 低 | 测试将 Main 与 runTest 共用同一 `UnconfinedTestDispatcher`，保证 stateIn 即时传播 |
| API Key 明文在内存短暂存在 | 中 | 仅编辑回显时存在，Keystore 加密落盘；`PrismField(secret)` 掩码；符合 US-003 契约 |
| SettingsViewModel 依赖 Application 上下文 | 低 | Factory 中 `APPLICATION_KEY` cast，变更点集中，后续引入 Hilt 时局部替换 |

---

## 参考

- [ADR-002](ADR-002-prism-chat-ui-architecture.md)：组件体系 / 不引入 Hilt / StateFlow 状态管理
- [ADR-001](ADR-001-prism-tech-stack.md)：Tink AEAD + DataStore 加密选型
- [settings-provider-archaeology](../../docs/reports/2026-08-05-settings-provider-archaeology.md)：设置模块源码考古
- [SettingsScreen.kt](../../app/src/main/java/io/prism/ui/settings/SettingsScreen.kt)、[SettingsViewModel.kt](../../app/src/main/java/io/prism/ui/settings/SettingsViewModel.kt)
