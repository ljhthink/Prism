# 验收测试报告 —— Prism Provider 配置详情页接入

## 元信息

| 项目 | 内容 |
|---|---|
| 执行 Agent | ac-verifier |
| 任务令牌 | TKN-SETTINGS-ACCEPT-001 |
| 日期 | 2026-08-05 |
| 验收对象 | Provider 配置详情页接入改动集（P2 跨模块） |
| 关联文档 | [ADR-003](../decisions/ADR-003-prism-provider-config-settings.md) / [PRD](../PRD.md) / [性能基线](perf/2026-08-02-us004-provider-config-baseline.md) / [guardrail](2026-08-05-settings-provider-guardrail.md) |
| 测试方式 | JVM 单元测试（:app:testDebugUnitTest）+ 编译 + 安全静态扫描 + 性能代码级分析 |
| 前端交互 | Android Compose，无 Web 前端，Playwright MCP 不适用（已跳过） |

---

## 1. 摘要

| 项目 | 内容 |
|---|---|
| 测试范围 | ProviderConfigRepository / SettingsViewModel / ApiKeyRepository / ProviderPresets / SettingsScreen / 组件 |
| 执行时间 | 2026-08-05 14:42–14:44 |
| 整体结论 | **有条件通过（Conditional Pass）** |
| 执行用例总数 | 121（非跳过） |
| 通过 | 121 |
| 失败 | 0 |
| 错误 | 0 |
| 跳过（性能基准 @Ignore） | 13 |
| 编译 | :app:compileDebugKotlin BUILD SUCCESSFUL |
| 缺陷 | 2 项待办（见 §7），1 项需澄清（预设数量） |

**结论**：全部 121 个执行用例通过，编译通过，安全专项检查无发现，回归无失败。核心功能（预设添加 / 编辑 / 单激活不变式 / 删除 / API Key 加密掩码读写 / providers StateFlow / 无死代码）均满足 PRD 验收。**有条件通过**：一处 `@Ignore` 使性能基准无法按文档命令复跑（AC-8 仅能代码级分析）；AC-1 预设数量（任务表述 6 个含「自定义」vs 实现 5 个）需主 Agent 澄清。

---

## 2. 验收标准覆盖矩阵

| AC | 验收标准 | 关联测试用例 | 结果 | 证据 |
|---|---|---|---|---|
| AC-1 | 从预设添加 Provider | `presets_contain_5_providers`、`presets_include_*`、`presets_have_*`、`create_from_preset_persists_config`、`provider_createFromPreset`(SettingsViewModelTest)、`createFromPreset adds provider` | **通过（需澄清）** | ProviderConfigRepositoryTest / ProviderPresets.kt；见 §7 DEF-01 |
| AC-2 | 可编辑名称/Base URL/模型列表 | `save_update_existing_config`、`saveProvider updates existing provider` | 通过 | ProviderConfigRepositoryTest / SettingsViewModelTest |
| AC-3 | 单激活不变式（setActive 与 save 均保证唯一激活） | `set_active_*`、`save_active_config_deactivates_others`、`save_active_new_config_deactivates_existing`、`concurrent_setActive_*`、`concurrent_setActive_and_clearActive_*` | 通过 | ProviderConfigRepositoryTest / ProviderConfigEdgeCaseTest（并发原子性） |
| AC-4 | 可删除 Provider | `remove_deletes_config`、`remove_all_clears_everything`、`deleteProvider removes provider and clears selection` | 通过 | ProviderConfigRepositoryTest / SettingsViewModelTest |
| AC-5 | API Key 加密存储、掩码、明文不落盘、可回显、可清空 | `save_and_read_*`、`datastore_stores_ciphertext_not_plaintext`、`datastore_never_contains_plaintext_for_any_key`、`save_empty_string_key_round_trip`、SettingsViewModelTest API Key 三例 | 通过 | ApiKeyRepositoryTest / ApiKeyEdgeCaseTest；PrismField(secret) 掩码 |
| AC-6 | providers StateFlow 实时反映列表变化 | `providers_flow_*`（save/createFromPreset/remove/sorted）、`saveProvider adds/updates`、`createFromPreset adds`、`deleteProvider` | 通过 | ProviderConfigRepositoryTest / SettingsViewModelTest |
| AC-7 | 无死代码（payload 移除）、无未用 import、编译通过 | 编译任务 + 静态扫描 | 通过 | `:app:compileDebugKotlin` SUCCESS；grep `payload` 无命中 |
| AC-8 | 性能无回退（>50% 判失败） | 性能基线对比（代码级） | **部分通过（有限验证）** | 见 §5；基准 @Ignore 不可复跑 → DEF-02 |

---

## 3. 测试用例设计与分层执行

### 3.1 静态分析（Phase 2.1）

| 项目 | 命令 | 结果 | 证据 |
|---|---|---|---|
| 编译 | `:app:compileDebugKotlin` | 通过 | BUILD SUCCESSFUL（仅 deprecation warning，无错误/无未解析引用） |
| 死代码 | 源码 grep `payload`（大小写不敏感） | 通过 | 无命中文档 / 源 / 安全包 |
| lint | `disable += "CoroutineCreationDuringComposition"`（build.gradle.kts 既有豁免） | 通过 | 已知工具链豁免，非本次引入 |

### 3.2 单元测试（Phase 2.2）

框架：JUnit 4 + kotlinx-coroutines-test。命令 `:app:testDebugUnitTest --rerun-tasks`（强制真实执行，禁用缓存）。

| 测试类 | 用例 | 通过 | 失败 | 错误 | 跳过 |
|---|---|---|---|---|---|
| SettingsViewModelTest | 9 | 9 | 0 | 0 | 0 |
| ProviderConfigRepositoryTest | 42 | 42 | 0 | 0 | 0 |
| ProviderConfigEdgeCaseTest | 17 | 17 | 0 | 0 | 0 |
| ApiKeyRepositoryTest | 14 | 14 | 0 | 0 | 0 |
| ApiKeyEdgeCaseTest | 16 | 16 | 0 | 0 | 0 |
| ConversationViewModelTest | 4 | 4 | 0 | 0 | 0 |
| KnowledgeChunkCrudTest | 9 | 9 | 0 | 0 | 0 |
| KnowledgeChunkEdgeCaseTest | 9 | 9 | 0 | 0 | 0 |
| ProviderConfigDemo | 1 | 1 | 0 | 0 | 0 |
| **合计** | **121** | **121** | **0** | **0** | **0** |

覆盖率目标（语句 ≥90% / 分支 ≥80%）：本改动集核心逻辑（`ProviderConfigRepository.save/setActive/clearActive/createFromPreset/refreshFlows`、`ApiKeyRepository`、`SettingsViewModel`）均被等价类/边界/并发测试覆盖，含 guardrail 补充的并发原子性用例。Compose 组件（PrismField/PrismSheetHost/PrismSegmented）无 Compose UI 测试基础设施，暂无法用自动化断言覆盖率（见 §8 未覆盖项）。

### 3.3 集成测试（Phase 2.3）

- 数据层↔UI 桥接由 `SettingsViewModelTest` 覆盖：`providers`/`activeProvider` 经 `stateIn` 订阅仓库 Flow 的传播。
- 异步 `saveApiKey`/`loadApiKey` 经 `runTest(mainDispatcher)` 验证回显与保存。
- 单激活不变式跨模块原子性由 `ProviderConfigEdgeCaseTest` 并发用例（CyclicBarrier + Executors 多线程 setActive/clearActive）验证。

### 3.4 端到端测试（Phase 2.4）

Android Compose 无 Web 前端，Playwright MCP 不适用（已跳过）。核心业务路径（从预设添加→编辑→激活→删除→API Key 加密读写）已由 ViewModel 层用例串联验证；真实设备 UI 交互需后续 androidTest（见 §8）。

---

## 4. 安全专项验证（Phase 3）

| 检查项 | 结果 | 证据 |
|---|---|---|
| API Key 明文不落盘 | 通过 | `datastore_stores_ciphertext_not_plaintext`、`datastore_never_contains_plaintext_for_any_key`：DataStore 原始字节 ≠ 明文，且可解密还原 |
| 无硬编码密钥/令牌 | 通过 | grep `sk-[A-Za-z0-9]{8,}` / `api_key=` / `secret=` / `Bearer` 在 `app/src/main` 无命中 |
| 日志不泄露 | 通过 | settings 与 security 目录 grep `Log./println` 无命中；ApiKeyRepository 无日志输出 |
| 生产加密实现 | 通过 | [KeystoreCryptoService.kt](../app/src/main/java/io/prism/security/KeystoreCryptoService.kt)：Android Keystore + Tink AEAD（AES-256-GCM），StrongBox→TEE 回退，密钥不入硬件 |
| 注入防护 | 通过 | 数据层为 ObjectBox（非 SQL）+ DataStore（键值），无 SQL/命令拼接面；AEAD 完整性由 `tampered/truncated_ciphertext_returns_null` 验证 |
| 错误信息不泄露 | 通过 | 解密失败返回 null 不抛异常（`read_corrupted_ciphertext_returns_null`、`read_with_wrong_crypto_service_returns_null`） |

---

## 5. 性能验证（AC-8）

- **基线存在**：[2026-08-02-us004-provider-config-baseline.md](perf/2026-08-02-us004-provider-config-baseline.md) 记录 SAVE p50≈280us / GET p50≈1.9us / SET_ACTIVE p50≈291us。
- **代码级分析**：本改动集新增 `refreshFlows()`（每次变更做一次 `box.all.sortedBy` 全表读 + 排序）与 `providers` StateFlow。基线 SET_ACTIVE p50 已含旧 `refreshActiveProvider` 的全表读；新增开销为一次 N（<20）元素的读 + 排序，有界且远低于 >50% 回退阈值。ASSUME 无实质回退。
- **限制**：`ProviderConfigPerformanceBenchmark`/`ApiKeyPerformanceBenchmark` 被 `@Ignore` 硬编码，文档命令 `-PignorePerformanceTests=false` 无效（build.gradle.kts 未处理该属性），实测仍 `skipped=5`。故 **AC-8 无法用新测量数据佐证** → 记为 DEF-02。

---

## 6. 回归测试（Phase 4）

- 全量 `:app:testDebugUnitTest`（含既有 KnowledgeChunk / ApiKey / Conversation 用例）121 通过，0 失败。
- 未发现回归。

---

## 7. 缺陷清单

| ID | 级别 | 关联 AC | 描述 | 复现/证据 | 处置建议 |
|---|---|---|---|---|---|
| DEF-01 | 中（需澄清） | AC-1 | 任务表述 AC-1 称「ProviderPresets 6 个：…+自定义」，实现仅 5 个预设（无「自定义」），`presets_contain_5_providers` 断言 5。PRD US-001 要求「至少 5 种」——已满足。属任务 AC 表述与实现的差异。 | `ProviderPresets.all.size == 5`；ProviderListSheet 仅渲染 5 个预设，无自定义入口 | 主 Agent 澄清：若 AC 应为 5（对齐 PRD）则更新任务 AC；若确需「自定义」创建路径则补功能 |
| DEF-02 | 低（测试基建） | AC-8 | 性能基准被硬 `@Ignore`，文档命令 `-PignorePerformanceTests=false` 无效，无法复跑获取新基线。 | 实测 `ProviderConfigPerformanceBenchmark` 仍 `skipped=5`；build.gradle.kts 无忽略解除逻辑 | 让基准可复跑（条件 @Ignore 或独立 Gradle task），补跑并回填基线 |
| DEF-03 | 信息 | - | ObjectBox 跨线程 stderr 噪音「Aborting a read transaction in non-creator thread」在测试 teardown 出现。 | `:app:testDebugUnitTest` 输出 system-err | 确认 test-only（`boxStore.close()` 与游标清理竞争），生产 UI 单线程调用路径无此隐患（runInTx 均在主线程），不阻断 |
| DEF-04 | 信息 | AC-5 | 「可清空」语义：清空 = 保存空串 → 存空密文（未调 `removeApiKey`）。用户侧显示已清空（placeholder「未设置 API Key」），满足功能语义。 | ProviderEditSheet 保存逻辑 + `save_empty_string_key_round_trip` | 可选优化：UI 增加显式「清除」走 `removeApiKey`；非阻断 |

---

## 8. 未覆盖项与风险

| 项 | 原因 | 风险 |
|---|---|---|
| 真实设备 stateIn(WhileSubscribed) 传播时序 | 测试用 `UnconfinedTestDispatcher` 与真实 Main 调度器存在差异；测试已验证当前传播，但首帧时序无法在 JVM 复现 | 低：`stateIn(WhileSubscribed(5s))` + `collectAsState()` 为生产标准模式，UI 订阅即传播 |
| Android 真机 UI 交互（弹层/掩码/动画） | 无 Compose UI 测试 / androidTest 基础设施，Playwright 不适用 | 中：建议后续 androidTest 补 UI 冒烟 |
| PrismSegmented 空 options 防御 | 守卫 `if (options.isEmpty()) return` 已代码级验证，但无 Compose 单测覆盖 | 低：守卫逻辑平凡正确 |
| 性能新数据 | 基准 @Ignore 不可复跑（DEF-02） | 中：AC-8 仅代码级推断，建议补跑 |
| 并发 setActive 在真实设备线程模型下的表现 | 仅在 JVM 多线程验证 | 低：ObjectBox runInTx 提供事务级串行化 |

---

## 9. 主 Agent 自问盲区回应

1. **stateIn(WhileSubscribed) 传播**：`SettingsViewModelTest` 将 Main 与 runTest 共用同一 `UnconfinedTestDispatcher`，9 个用例全部通过，验证了 stateIn 在测试环境即时传播。生产正确性由 `stateIn` + `collectAsState` 标准模式保证，差异仅为测试保真度，非生产缺陷（见 §8）。
2. **runInTx 跨线程**：生产路径 `save/setActive/clearActive` 均由 ViewModel 在主线程调用，`runInTx` 在调用线程执行，无跨线程隐患。测试 stderr 噪音确认为 `boxStore.close()` 与 ObjectBox 读线程竞争的 test-only 产物（DEF-03），与 ADR-003 风险表一致。
