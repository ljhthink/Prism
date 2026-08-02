# Prism 行为规则累积（Behavioral Rules）

> 本文件是 CLAUDE.md 的动态累积层（第二十三节）。从 Bug 修复、PR 审查、运维 postmortem 中提炼可执行规则。
> 初始结构从 `docs/templates/behavioral-rules-template.md` 复制。试点期顺向累积，不回溯历史。

## 规则分类

### naming

（暂无规则，待累积）

### error-handling

（暂无规则，待累积）

### security

（暂无规则，待累积）

### concurrency

（暂无规则，待累积）

### interface

#### BR-interface-001: UI 设计必须用户审核通过后方可实现

- 类别：interface
- 规则：任何涉及视觉 UI 设计的任务（聊天界面、设置界面、主题、布局、配色、字体等），主 Agent 必须先产出设计方案提交用户审核，审核通过后方可进入实现阶段。脚手架阶段的空白界面（仅显示标题）不在此规则范围内。
- 反例：直接编写 Compose UI 代码而不先获取用户确认
- 正例：先输出 UI 设计方案描述/线框 → 用户审核通过 → 再编写 UI 代码
- 来源：用户 2026-08-02 明确要求
- 添加日期：2026-08-02
- 适用场景：dev
- 状态：active

### ops

（暂无规则，待累积）

### testing

（暂无规则，待累积）

### docs

（暂无规则，待累积）

### build

#### BR-build-001: AGP 与 Gradle 版本必须匹配

- 类别：build
- 规则：声明 AGP 版本时，必须同步核实并配置满足最低要求的 Gradle Wrapper 版本。AGP 版本与最低 Gradle 版本对应关系见 [Android 官方文档](https://developer.android.com/build/releases/gradle-plugin)。修改任一版本时必须交叉验证兼容性。
- 反例：AGP 8.13.0 + Gradle 8.11.1（不满足最低 8.13，构建必失败）
- 正例：AGP 8.13.0 + Gradle 8.13（满足最低要求）
- 来源：US-001 M0 脚手架审查（TKN-PRISM-GUARDRAIL-001，G-01 阻断级发现）
- 添加日期：2026-08-02
- 适用场景：dev
- 状态：active

#### BR-build-002: Windows 环境生成的 shell 脚本提交前必须设置可执行权限

- 类别：build
- 规则：在 Windows 环境（`core.filemode=false`）下生成的 Unix shell 脚本（如 `gradlew`、`mvnw`），提交到 git 前必须通过 `git update-index --chmod=+x <file>` 设置可执行权限，或创建 `.gitattributes` 文件确保跨平台权限与行结束符正确。否则 CI/CD 在 Linux 上执行时会报 Permission denied。
- 反例：在 Windows 上 `git add gradlew` 后直接 commit，未设置 `+x` 权限，Linux CI 执行 `./gradlew` 报 Permission denied。
- 正例：创建 `.gitattributes`（含 `gradlew text eol=lf`）+ `git update-index --chmod=+x gradlew` + commit。
- 来源：US-001 M0 脚手架第二轮审查（TKN-PRISM-GUARDRAIL-002，G-09 发现）
- 添加日期：2026-08-02
- 适用场景：dev
- 状态：active

#### BR-build-003: 第三方 Maven 镜像应使用 content 过滤限定包来源

- 类别：build
- 规则：配置第三方 Maven 镜像时，应尽可能使用 `content { includeGroupByRegex(...) }` 或 `excludeGroupByRegex(...)` 过滤，限定镜像只提供特定包名前缀的依赖。特别是 public/central 类聚合镜像应排除 AndroidX 组（`com.android.*` / `androidx.*`），确保这些依赖只从 google 镜像获取，降低交叉投毒风险。
- 反例：`maven { url = uri("https://maven.aliyun.com/repository/public") }`（无 content 过滤，任何包都可能从此镜像拉取）
- 正例：`maven { url = uri("https://maven.aliyun.com/repository/public"); content { excludeGroupByRegex("com\\.android.*"); excludeGroupByRegex("androidx.*") } }`
- 来源：US-001 M0 第三轮审查（TKN-PRISM-GUARDRAIL-003，G-10 发现）
- 添加日期：2026-08-02
- 适用场景：dev
- 状态：active

## 审计记录

| 日期 | 审计人 | 结果 | 备注 |
|---|---|---|---|
| 2026-08-02 | 主 Agent | 初始建立 | 试点期，无规则，待首期编码后累积 |
| 2026-08-02 | guardrail-enforcer | 新增 BR-build-001 | US-001 M0 审查发现 AGP/Gradle 版本不匹配（G-01 阻断级） |
| 2026-08-02 | guardrail-enforcer | 新增 BR-build-002 | US-001 M0 第二轮复审发现 gradlew 缺少 git 可执行权限（G-09） |
| 2026-08-02 | guardrail-enforcer | 新增 BR-build-003 | US-001 M0 第三轮审查发现镜像缺少 content 过滤（G-10） |
