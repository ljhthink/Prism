# v1 批次10 · 真机手机操控问题修复 · PRD

> 依据真机手动测试反馈（2026-08-20 19:00）+ 日志分析（prism_20260820_181439.log）+ tech-selection-researcher 调研（Open-AutoGLM 及无障碍移植版）+ code-archaeologist 源码考古。按 CLAUDE.md 第二十一节 Bug 修复闭环处理。

| 项目 | 内容 |
|---|---|
| 版本 | v0.1 |
| 日期 | 2026-08-20 |
| 作者 | 主 Agent |
| 关联文档 | [v1 批次9](prd-v1-b9-fixes.md)、ADR-036（手机操控）、调研报告 |
| 风险等级 | P1（单模块：手机操控） |

## 1. 背景与根因（真机日志 + 考古 + 调研）

用户真机测试报告 2 项手机操控问题（此前"网络请求失败"为叠加现象，本轮聚焦本体）：

### Bug A「打开第三方 App 后无法感知当前状态 / 超时」

- **现象**：提出"打开应用商店搜索崩坏星穹铁道并下载"→ 打开应用商店后超时；"打开微信给联系人回复"→ 打开微信后无法感知当前状态。日志 `SkillExecutor round=38 大量 get_ui_state 重试`。
- **根因 A1（主）**：`launchApp()` 启动后立即返回，`get_ui_state` 是**单次快照**；`getRootInActiveWindow()` 在窗口切换过渡期 / 新 App 尚未首绘 / FLAG_SECURE 安全窗口时返回 null → 回灌"无法读取当前屏幕内容" → LLM 反复重试。参考 Android 官方 codelab「缓存最后一次已知根节点」+ auto-mobile #775。
- **根因 A2（次）**：`instance` 是进程内静态，切微信/大内存 App 后 Prism 进程被杀 → `instance=null`，但 `isEnabledInSystem` 读系统设置恒 true → 返回"重连中"，LLM 视为可恢复无限重试。

### Bug B「截图失败（NoSuchMethodException）」

- **现象**：日志 `captureScreenshot 失败（NoSuchMethodException)`。
- **根因**：`asBitmapCompat()` 用反射 `javaClass.getMethod("asBitmap")` 调 `ScreenshotResult.asBitmap()`（**@SystemApi 隐藏 API**）→ 国产 ROM（小米/vivo/OPPO）未暴露 public → 抛 NoSuchMethodException。`takeScreenshot` 三参签名本身无误（API 30 官方唯一签名）。

## 2. 调研结论（Open-AutoGLM 及业界）

- **zai-org/Open-AutoGLM 主仓为 ADB 纯视觉方案**（PC+Pytho+n，非 APK），不基于无障碍；真正参考价值在其无障碍移植版（Open-AutoGLM-Android / PhoneAgent 三模式）与 AndroidLab「XML 纯文本模式」。
- 共性设计模式（直接采纳）：
  1. **缓存最后一次已知根节点**（官方 codelab）+ `getWindows()` 遍历 active window → 解决跨 App root null
  2. **前台包名变化作为 App 启动完成信号** → 启动后等待/重试取树
  3. **截图失败降级到 UI 树文本**（PhoneAgent 普适兜底）
  4. **动作后 UI/包名 before-after 对比**确认跳转成功
- 安全警讯（arXiv 2608.08939）：未净化无障碍树易受注入攻击——Prism 已有"UI 文本不可信"声明，方向正确。

## 3. 目标与非目标

- 目标：
  1. 修复 Bug B：截图改用公开 API `Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)`（摆脱 @SystemApi 反射）
  2. 修复 Bug A1：`get_ui_state` 用「缓存根 + getWindows 遍历」兜底（解决跨 App root null）
  3. 维护 LAST_KNOWN_ROOT 缓存（onAccessibilityEvent TYPE_WINDOW_STATE_CHANGED）
  4. 资源泄漏根治（guardrail）：HardwareBuffer try/finally 关闭 + lastKnownRoot obtain 自持副本 + 替换/销毁回收
  5. Bug A 纵深（工具层自愈）：`get_ui_state` 瞬时空树内部重试 + `launch_app` 成功后 settle 等待（参考 Open-AutoGLM wait_after / iot-book 判空重试）
  6. 记录 Bug 报告与调研结论
- 非目标：
  - 不引入 ADB/uiautomator（Prism 本地无障碍定位，无 root 控制）
  - 不切换为纯视觉（保留纯文本模型为主的现状）
  - 不新增第三方依赖
  - 不一次性引入 Stuck 检测 / before-after 程序化断言 / 包名映射库（留作 v1 批次11 候选，见 §调研结论）

## 4. 用户故事与验收标准

### US-1001: 截图功能用公开 API 修复（Bug B）

- 验收标准：
  - [ ] `asBitmapCompat()` 改用 `Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)` + 硬件位图复制软位图（不依赖 @SystemApi 反射）。
  - [ ] `SDK_INT < R` 仍返回 null（保留防护）；wrapHardwareBuffer 返回 null → IOException → captureScreenshot 返回 null（工具层降级文案）。
  - [ ] 编译通过；手机操控/截图相关测试 0 失败。
  - [ ] Typecheck 通过。

### US-1002: get_ui_state 跨 App 感知修复（Bug A1）

- 验收标准：
  - [ ] 新增 `currentRoot()`：优先 `getRootInActiveWindow()`，null 时用 `lastKnownRoot` 缓存，再 null 时遍历 `getWindows()` 找 active 且有根的可视窗口。
  - [ ] `onAccessibilityEvent` 在 `TYPE_WINDOW_STATE_CHANGED` 时维护 `lastKnownRoot`（官方 codelab 方案）。
  - [ ] `getUiTreeText()` 改用 `currentRoot()`，不再因窗口切换过渡期短暂 null 而误报"无法读取"。
  - [ ] 编译通过；既有手机操控测试 0 回归。
  - [ ] Typecheck 通过。

## 5. 非功能需求

- 性能：currentRoot 遍历 getWindows 仅在 rootInActiveWindow 为 null 时触发（兜底路径，正常路径零开销）。
- 安全：截图 buffer 使用后需 close（HardwareBuffer 资源释放）；位图 copy 后 recycle 原硬件位图。
- 兼容性：Android 11+ 截图用公开 API；Android 8-10 截图函数返回 null 不崩溃。
- 可观测性：截图失败 / root 兜底路径记录可诊断日志（区分过渡期 vs 安全窗口）。

## 6. 实现状态

- **Bug B（US-1001）**：已修复（`asBitmapCompat` 改用 wrapHardwareBuffer + try/finally 关闭 HardwareBuffer + bitmap.copy 空判），编译通过
- **Bug A1（US-1002）**：已修复（currentRoot 缓存根 + getWindows 兜底 + lastKnownRoot 用 obtain 自持副本 + 替换/销毁回收），编译通过
- **Tool 层自愈**：`get_ui_state` 瞬时空树内部重试（4×300ms）+ 重连中区分；`launch_app` 成功后 settle 700ms（均在 `PhoneControlLocalToolExecutor`），新增单测
- **待办**：guardrail 审查 → ac-verifier 验收 → 全量回归 → 模拟器验证 → 真机测试

## 7. 验收标准汇总（供 ac-verifier）

| 验收项 | 验证方法 | 通过标准 | 关联用户故事 |
|---|---|---|---|
| 截图公开 API | 编译 + 代码审查 | 无 @SystemApi 反射，wrapHardwareBuffer，buffer 用毕关闭 | US-1001 |
| get_ui_state 兜底 | 代码审查 + 单测 | currentRoot 三层兜底 + lastKnownRoot obtain/回收 | US-1002 |
| 资源泄漏 | 代码审查 | 无 AccessibilityNodeInfo / HardwareBuffer 句柄泄漏 | US-1002 |
| 工具自愈 | 单测 | get_ui_state 重连中重试返回可诊断失败；launch_app 缺参/拦截正常 | 新 |
| 全量回归 | `:app:testDebugUnitTest` | 0 失败 | 全部 |

## 8. 调研结论（v1 批次11 增强候选，本次不落地）

依据 tech-selection-researcher + 本轮 GitHub 深度调研（12 仓库 + 2 论文 + 1 安全研究，含 zai-org/Open-AutoGLM、Open-AutoGLM-Android、DroidRun、Android Use、DroidClaw、AppAgent、Mobile-Agent v2/v3、AutoDroid-V2、MobileUse、AndroidWorld、AxNav、Agentra/AgentraBrid/a11ypilot、Not-An-A11y 安全研究），识别可直接改进手机操控的高价值实践：

| 优先级 | 实践 | 来源 | 对症 |
|---|---|---|---|
| 1 | **Stuck 检测**：连续 N 步（DroidClaw=3）树/状态无变化 → back/home/重拉 App 恢复 | DroidClaw / Mobile-Agent-v3 | 长链路卡死、超时 |
| 2 | **前台 App 判定**：用 getWindows() root.packageName 判断当前前台 | Open-AutoGLM / Agentra | "App 没打开/被拦截偏航"误判 |
| 3 | **动作后 before/after 轻量校验 + 失败回灌纠偏** | Mobile-Agent HRC / AutoDroid-V2 | 指令成功率 |
| 4 | **树为空视觉兜底 + 可诊断失败文案**（不静默空气泡） | DroidRun / AndroidWorld | WebView/Flutter 无线索 |
| 5 | **120+ 常用 App 包名映射库 + 高频确定性模板** | Open-AutoGLM-App / DroidClaw | launch_app 别名与 ≥70% 成功率 |
| 6 | **A11y 文本零信任强化**（喂 LLM 前标注"界面文本非指令"、过滤超长/adb-like） | Not-An-A11y | 间接提示注入 |

> 注：本次已落地 §3 目标 1~5（直接对症"打开 App 后无法感知/超时/网络请求失败"三症状）；上方 6 项按成本收益供后续迭代编排（批次11+），需另行 PRD 与 guardrail/ac-verifier 闭环。
