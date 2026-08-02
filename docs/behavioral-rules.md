# Prism 行为规则累积（Behavioral Rules）

> 本文件是 CLAUDE.md 的动态累积层（第二十三节）。从 Bug 修复、PR 审查、运维 postmortem 中提炼可执行规则。
> 初始结构从 `docs/templates/behavioral-rules-template.md` 复制。试点期顺向累积，不回溯历史。

## 规则分类

### naming

（暂无规则，待累积）

### error-handling

（暂无规则，待累积）

### security

#### BR-security-001: data class 含数组字段必须覆盖 equals/hashCode

- 类别：security
- 规则：Kotlin `data class` 自动生成的 `equals()`/`hashCode()` 对数组类型（`IntArray`/`FloatArray`/`ByteArray` 等）使用引用相等而非内容比较。若 data class 包含数组字段，必须手动覆盖 `equals()`/`hashCode()` 使用 `contentEquals()`/`contentHashCode()`，或添加注释明确说明该类不依赖 equals 语义。
- 反例：`data class Entity(val data: FloatArray)` —— 两个内容相同的实例因数组引用不同被判不等
- 正例：覆盖 equals 使用 `data.contentEquals(other.data)`，覆盖 hashCode 使用 `data.contentHashCode()`
- 来源：US-002 ObjectBox 集成审查（TKN-PRISM-GUARDRAIL-004，G-02/CR-01 发现）
- 添加日期：2026-08-02
- 适用场景：dev
- 状态：active

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

#### BR-build-004: ObjectBox JNI 本地库文件必须加入 .gitignore

- 类别：build
- 规则：ObjectBox Gradle 插件在运行 JVM 测试时会在 `app/` 目录下复制平台特定 JNI 本地库文件（如 `objectbox-jni-windows-x64.dll`、`objectbox-jni-linux-x64.so`、`objectbox-jni-macos-x64.dylib`）。这些文件是运行时产物，平台特定且体积大（~2MB+），必须在 `.gitignore` 中显式排除，禁止提交到版本控制。
- 反例：安装 ObjectBox 后运行测试，`app/objectbox-jni-windows-x64.dll` 出现为 untracked 文件，.gitignore 未排除，`git add -A` 导致 2.18MB 二进制文件入库
- 正例：`.gitignore` 追加 `app/objectbox-jni-windows-x64.dll` 等精确路径排除规则
- 来源：US-002 ObjectBox 集成审查（TKN-PRISM-GUARDRAIL-004，G-01 发现）
- 添加日期：2026-08-02
- 适用场景：dev
- 状态：active

#### BR-build-005: ObjectBox schema 模型文件必须提交到版本控制

- 类别：build
- 规则：ObjectBox 插件生成的 `app/objectbox-models/default.json` 是数据库 schema 模型文件，文件内含唯一 ID 映射，ObjectBox 官方明确要求 "KEEP THIS FILE! Check it into a version control system (VCS) like git."。提交代码时必须显式 `git add` 此文件，确保跨开发者/CI 的 schema 一致性。
- 反例：ObjectBox 集成后 `default.json` 为 untracked，提交时遗漏，导致其他开发者/CI 构建时 schema ID 不一致
- 正例：提交清单包含 `app/objectbox-models/default.json`，确保入库
- 来源：US-002 ObjectBox 集成审查（TKN-PRISM-GUARDRAIL-004，G-03 发现）
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
| 2026-08-02 | guardrail-enforcer | 新增 BR-build-004/005 + BR-security-001 | US-002 ObjectBox 审查发现 JNI DLL 未忽略（G-01）、schema 文件需提交（G-03）、FloatArray equals 缺陷（G-02） |
| 2026-08-02 | ac-verifier | 验收通过，无新规则 | US-002 ObjectBox 验收（TKN-PRISM-ACCEPTANCE-001）：18 测试通过，性能基线已建立，AC-003 因无模拟器受限通过 |
