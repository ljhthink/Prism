# 安全与质量审计报告（Provider 配置详情页接入 · 增量 R2）

> 依 CLAUDE.md 第七节 / 第十节，由 guardrail-enforcer 子 Agent 独立执行。
> 本轮审查对象：DEF-01（自定义 Provider 创建入口 + saveProvider 返回 id 的激活路径）
> 与 DEF-02（性能基准可复跑）增量改动集。任务令牌机制见 CLAUDE.md 20.4。

| 项目 | 内容 |
|---|---|
| 执行 Agent | guardrail-enforcer |
| 任务令牌 | TKN-SETTINGS-GUARDRAIL-002 |
| 审计日期 | 2026-08-05 |
| 关联 ADR | docs/decisions/ADR-003-prism-provider-config-settings.md |
| 关联代码变更 | SettingsViewModel.kt / SettingsScreen.kt / app/build.gradle.kts / ProviderConfigPerformanceBenchmark.kt / KnowledgeChunkPerformanceBenchmark.kt / SettingsViewModelTest.kt |
| 运行验证 | `:app:compileDebugKotlin` 通过；`:app:testDebugUnitTest` 通过；基准默认跳过 & `-PignorePerformanceTests=false` 复跑均验证 |

---

## 1. 代码质量审查（TRAE-code-review）

### 1.1 变更意图

将「新建自定义 Provider」接入设置界面：`newCustomProvider()` 生成 id=0 空草稿并选中，
`saveProvider` 返回真实 id，`ProviderEditSheet` 保存后用 `setActive(savedId)` 激活；
并将性能基准从 `@Ignore`（硬跳过）改为 `@Before` 中 `Assume` 条件跳过（DEF-02），
由 Gradle `testOptions` 注入系统属性控制复跑。

### 1.2 Karpathy Guidelines 符合性

- **命名**：`newCustomProvider` / `isNew` / `savedId` 语义清晰。✔
- **设计**：保存逻辑统一收敛——`config.copy(isActive = false)` 经 `saveProvider` 落库，
  激活态绝不直写 `isActive`，统一经 `setActive` 事务处理，延续上一轮 S1 的收敛方案。✔
- **错误处理**：`setActive(savedId)` 在 `saveProvider` 同步返回后调用，无异步竞态窗口。✔
- **边界**：新建模式 `激活` 按钮 `enabled = !isNew && !enabled`，`删除` 按钮 `if (!isNew)` 隐藏，
  防止对未保存 id=0 草稿执行无效激活/删除。✔

### 1.3 主 Agent 自问核查（盲区）

**盲区 #1：`saveProvider` 返回 id 后 `setActive(savedId)` 在新建路径的正确性。**
核实通过：`saveProvider` 为同步方法（内部 `repository.save` 同步 `runInTx` + `refreshFlows`），
先在 UI 线程返回真实 id，再 `_selectedProvider.value = null`，随后 `setActive(savedId)`。
两个调用均在 UI 主线程串行执行，无协程交错，无状态漂移。单激活不变式不变量成立：
新建时 `save` 以 `isActive=false` 写入（不触碰不变式），激活完全由 `setActive` 的 `runInTx`
「取消其他 + 激活目标」原子完成。测试 `save draft then activate uses returned id`（含
activeProvider 收集器）真实覆盖该路径并断言 `activeProvider.id == savedId`，通过。

**盲区 #2：DEF-02 双路径正确性。** 核实通过（见第 3 节运行验证）：
默认路径 `@Before` 的 `Assume` 失败整类跳过（skipped=5），`-PignorePerformanceTests=false`
注入属性后 5 用例全部执行产出真实延迟数据。JUnit 4 在 `@Before` 抛 `AssumptionViolatedException`
时仍执行 `@After`，`tearDown` 的 `::boxStore.isInitialized` / `::tempDir.isInitialized` 判空
护卫消除了未初始化 NPE（默认路径 failures=0/errors=0 证实）。

### 1.4 发现的非阻断项

| # | 严重度 | 问题 | 位置 |
|---|---|---|---|
| N1 | 中 | 新建 Provider 无输入校验：空名称（`name.trim().ifEmpty { config.name }`，新建时 `config.name=""` 无回退）、空 baseUrl、空 models 均可保存，产生不可用且有空白名称的配置 | app/src/main/java/io/prism/ui/settings/SettingsScreen.kt:314-321 |
| N2 | 低 | `import io.prism.data.ProviderPresets` 未使用（`createFromPreset` 参数类型为 `ProviderConfig`，非 `ProviderPresets`），Kotlin 仅告警不影响编译 | app/src/main/java/io/prism/ui/settings/SettingsViewModel.kt:11 |
| N3 | 低 | `apiKeyRef` 用 `custom-${System.currentTimeMillis()}` 唯一化，理论极端同毫秒双击可碰撞；因 `_selectedProvider` 单槽 + 未保存即丢弃，实际无孤儿引用，风险极低 | app/src/main/java/io/prism/ui/settings/SettingsViewModel.kt:82 |

> 三者均**不构成安全缺陷**，不阻断。N1 为数据完整性/UX 建议；N2 为整洁度；
> N3 为理论边界，可加计数器/随机后缀防御。

---

## 2. 安全漏洞扫描（TRAE-security-review）

### 2.1 输入与边界审计

- 新建 Provider 的 `name`/`baseUrl`/`models` 均为本地单字段，无长度上限、无 scheme 白名单
  （N1）。当前改动集内无网络消费方（US-007 未接入发起请求），**无 SSRF 可达路径**。
  沿用上一轮建议：接入网络调用前在 US-007 补充 `http/https` scheme 校验。✔（非本轮阻断）
- `models.split(",").map{trim}.filter{isNotEmpty}` 无越界/异常风险；`models` 空时保存为空列表合法。✔
- `apiKeyRef` 唯一化后用作 DataStore 偏好键（非路径/命令），无非预期键碰撞。✔

### 2.2 执行安全审计（注入 / 最小权限 / 输出编码）

- **注入**：数据层为 ObjectBox（非 SQL 字符串拼接）；`PrismField` 输入不进入 `eval`/shell/模板；
  原生 Compose 渲染无 HTML/XSS 上下文。新建入口无头注入面。✔
- **最小权限**：无高权限操作，无新增权限请求。✔
- **输出编码**：无 HTML/JS/CSS/URL 输出上下文需转义。✔

### 2.3 密钥与配置安全

- **明文不落盘**：新建 Provider 的 API Key 仍经 `ApiKeyRepository.saveApiKey` → `CryptoService.encrypt`
  → DataStore 密文（ApiKeyRepository.kt:38-43）。✔
- **无硬编码密钥**：改动集无硬编码 API Key/Token/密码；`sk-test-*` 仅出现在测试 mock 中
  （ApiKeyRepositoryTest.kt:47 / ApiKeyPerformanceBenchmark.kt:64,83）。✔
- **日志脱敏**：基准打印的 `MAP_CONVERTER_ENCODE` 输入含 `Bearer sk-test-token-1234567890`
  （ProviderConfigPerformanceBenchmark.kt:132），为测试构造的假令牌，非真实凭证，仅输出统计量。✔
- **.gitignore**：已覆盖 `.env` `.env.local` `.env.*.local` `keystore/` `*.keystore` `*.jks`。✔

### 2.4 依赖与供应链风险

- 本轮**无依赖变更**（仅 build.gradle.kts 的 testOptions 配置），无锁文件改动，无新增供应链风险。✔

---

## 3. DEF-02 运行验证（实测证据）

| 验证项 | 命令 | 结果 |
|---|---|---|
| 编译 | `.\gradlew.bat :app:compileDebugKotlin` | BUILD SUCCESSFUL |
| 单元测试 | `.\gradlew.bat :app:testDebugUnitTest` | BUILD SUCCESSFUL（FROM-CACHE） |
| 基准复跑 | `.\gradlew.bat :app:testDebugUnitTest --tests "*.ProviderConfigPerformanceBenchmark" -PignorePerformanceTests=false --rerun-tasks` | BUILD SUCCESSFUL；XML tests=5 skipped=0 failures=0 errors=0，产出真实 p50/p95/p99 数据 |
| 基准默认跳过 | `.\gradlew.bat :app:testDebugUnitTest --tests "*.ProviderConfigPerformanceBenchmark" --rerun-tasks` | BUILD SUCCESSFUL；XML tests=5 skipped=5 failures=0 errors=0（Assume 跳过，tearDown 判空无 NPE） |
| 新用例 | `.\gradlew.bat :app:testDebugUnitTest --tests "io.prism.ui.settings.SettingsViewModelTest" --rerun-tasks` | BUILD SUCCESSFUL；XML tests=11 skipped=0 failures=0 errors=0 |

**DEF-02 双路径均正确**：默认跳过（不拖慢常规 CI），`-PignorePerformanceTests=false` 可复跑。

---

## 4. 测试充分性

- 新增用例 `newCustomProvider selects empty draft with unique apiKeyRef`：断言 id=0 且 apiKeyRef 以
  `custom-` 前缀唯一化。通过。
- 新增用例 `save draft then activate uses returned id`：新建草稿 → 保存取回 id → setActive →
  断言 activeProvider.id == savedId，含 activeProvider 收集器。通过，真实覆盖新建+激活路径。
- **建议补充（非阻断）**：
  1. 新建但不勾选激活（enabled=false）→ 保存后 Provider 存在但 `activeProvider` 仍为 null；
  2. 新建 + 空名称/空 baseUrl → 当前无校验，锁定现状或触发 N1 校验。
  3. 「新建取消」路径（newCustomProvider 后 selectProvider(null)）——VM 无副作用残留，可由 selectedProvider 置 null 暗示，属低风险。

---

## 5. OWASP / CWE 发现

安全面（注入 / 硬编码密钥 / 明文落盘 / XSS / 命令注入 / eval）经扫描**无可利用漏洞**。
本轮增量无新增可利用 CWE；N1-N3 为数据完整性/整洁度/理论边界项，均非安全缺陷。

---

## 6. 结论

- [x] **通过**（可进入测试阶段 / 启动 ac-verifier）
- [ ] 阻断（存在严重缺陷或高危漏洞，回退编码阶段）
- [ ] 有条件通过

> 判定依据：
> - **DEF-01**：`saveProvider` 返回 id + `setActive(savedId)` 的新建激活路径经代码审计与测试
>   双重验证正确；单激活不变式延续上一轮 S1 收敛方案（save 恒 `isActive=false`，激活仅经
>   `setActive` 事务），无竞态、无状态漂移。新建模式按钮禁用/隐藏逻辑正确。
> - **DEF-02**：Gradle `testOptions` + `@Before Assume` + `tearDown` 判空三方配合经实测正确，
>   默认跳过、`-PignorePerformanceTests=false` 复跑两条路径均验证通过。
> - **安全面**全绿：无注入、无硬编码密钥、apiKey 加密落盘、.gitignore 覆盖敏感文件。
> - 无阻断级缺陷。N1（新建缺输入校验）为数据完整性建议，因当前无网络消费方、非安全面，
>   需在 US-007 接入网络调用前连同 baseUrl scheme 白名单一并处理；N2/N3 为低严重度整洁度/理论边界项。

### 6.1 建议项（非阻塞）

| # | 等级 | 建议 |
|---|---|---|
| N1 | 中 | 新建模式下禁用「保存配置」或校验 name/baseUrl 非空（`name.isBlank() || baseUrl.isBlank()` 时禁按钮），避免产生空白名称/不可用配置；US-007 接入前补 baseUrl `http/https` scheme 白名单 |
| N2 | 低 | 移除 `SettingsViewModel.kt:11` 未使用的 `ProviderPresets` import |
| N3 | 低 | `apiKeyRef` 唯一化可加自增计数器或 `UUID.randomUUID()` 后缀，消除同毫秒理论碰撞 |

---

## 7. 规则提议（accepted review → behavioral-rules 候选）

| 类别 | 规则 | 反例 | 正例 | 来源 |
|---|---|---|---|---|
| testing | 性能基准默认跳过宜用 `@Before Assume` + 构建注入系统属性，而非 `@Ignore`（硬跳过不可复跑）；Assume 失败时 `@After` 仍执行，须对 `lateinit` 字段判空 | `@Ignore` 基准，CI 无法复跑；tearDown 直接解引用未初始化字段 | `Assume.assumeTrue(System.getProperty("flag")=="true")` + `if (::field.isInitialized)` 守卫 | DEF-02 |
| data-integrity | 新建实体入口须校验必填字段非空，禁止允许空白名称/端点落库 | `name.trim().ifEmpty { config.name }` 对空草稿回退仍为空 | 保存前校验 name/baseUrl 非空，或禁用保存按钮 | DEF-01 / US-007 前置 |

---

## 8. 自动化建议（CI/CD Integration）

在 `.github/workflows/` 增加设置模块守卫流水线，供合并前置拦截（供参考，非本次强制交付）：

```yaml
name: settings-guardrail
on: [pull_request]
jobs:
  guardrail:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - run: ./gradlew :app:testDebugUnitTest --tests "*SettingsViewModelTest" --tests "*ProviderConfigRepositoryTest"
      # 性能基准在常规 CI 默认跳过；需跑基线时显式开启
      - run: ./gradlew :app:testDebugUnitTest --tests "*.ProviderConfigPerformanceBenchmark" -PignorePerformanceTests=false
  secret-scan:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: gitleaks/gitleaks-action@v2   # 扫描硬编码密钥/令牌
```

---

## 9. 复审交叉检查（相对上一轮 R1）

- 上一轮 R1（`PrismDanger` 未用 import）已消失：SettingsScreen.kt 当前 import 列表无 `PrismDanger`。✔
- 本轮新增未用 import 见 N2（`ProviderPresets`）。✔
- S1 单激活不变式方案在本轮「新建即激活」路径上继续正确复用（save 恒 isActive=false + setActive）。✔