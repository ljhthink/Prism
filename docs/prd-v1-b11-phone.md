# v1 批次11 · 手机操控真机三连问 · 根因确认 + 执行方案（待决策）

> 依据真机手动测试（2026-08-21 00:00 + 00:35）+ 最新日志 `prism_20260821_003536.log` + 网络调研（MIUI 后台进程回收 / 无障碍保活方案）。按 CLAUDE.md 工程规范：**先记录调研与方案，由用户决策后再执行。**

| 项目 | 内容 |
|---|---|
| 版本 | v0.1 |
| 日期 | 2026-08-21 |
| 作者 | 主 Agent |
| 关联 | prd-v1-b10-phone.md、ADR-036（手机操控）、调研报告 |
| 状态 | **方案已确认（2026-08-21），阶段执行中** |

## 0. 本轮三个问题的测试反馈

1. **① 打开应用商店搜索并下载 → 提示「⚠️ 网络连接中断，请检查网络后重试」**（本轮由"工具超时"变为"网络连接中断"）
2. **② 打开微信给联系人回复 → 打开微信后无法感知当前状态，LLM 提示"无法读取UI树内容"**（与上一轮一致）
3. **③ 按 prd-v1-b10 §8 做批次11 增强**（用户要求的优化/新功能）

## 1. 日志证据（prism_20260821_003536.log）

| 时间 | 事件 | 含义 |
|---|---|---|
| 00:22:21 | round=1 get_ui_state | LLM 开始操控 |
| 00:22:24 | round=2 launch_app | 打开目标 App |
| 00:22:28~00:22:56 | round=3/4/5/6/7/8 wait/get_ui_state 交替 | LLM 一直感知不到，反复 wait+get_ui_state |
| 00:22:56 | round=9 screenshot；00:23:11 round=12 cross_app__open_app | LLM 换手势/换工具，仍未成功 |
| **00:23:29** | **PID 11700「手机操控无障碍服务已连接」** | **主进程 30643 已被系统回收，新进程 11700 重新绑定无障碍服务（工具回路 round=12→13 之间中断）** |

**确证根因**：工具回路中途主进程被系统回收重启（PID 30643→11700）。进程被杀 → 无障碍服务实例与内存缓存一并丢失 → 重连空窗期 get_ui_state 全部 null → LLM 永续循环 wait+get_ui_state；kimi SSE 流被中断 → 报"网络连接中断"。**这是上几轮应用内增量修复（retry/缓存/豁免确认门）都难以根治的根因。**

## 2. 网络调研结论（无障碍保活 / MIUI 进程回收）

调研 `MIUI/HyperOS 无障碍服务被杀`、`foreground service keep alive MIUI`、无障碍自动化类 App（Aido/Assists/AutoJS）官方文档：

- **MIUI 是对后台无障碍应用回收最激进的 ROM 之一**，属平台策略（省电/安全），**非应用代码可 100% 规避**。
- 业界公认的三类手段（按有效性与合规排序）：
  1. **用户侧 MIUI 配置（最高优先，不可绕过）**：电池优化→无限制；开启自启动/允许后台启动；**最近任务"上锁"（任务卡下滑锁定）**；必要时开发者选项关闭"MIUI 优化"；允许"后台弹出界面"。
  2. **应用侧·前台服务（辅助）**：进程提权，但 MIUI 下**仍需①②⑦的配合才能稳定**；未上锁时清后台仍会连带杀死。本质是"降低被杀概率"而非豁免。
  3. **降内存占用**：降低进程被系统列为回收候选的概率（Prism 后台仍加载 113MB ONNX 嵌入器 + RAG）。
- 违规/不推荐：双进程守护、Native 保活、透明 Activity 伪装前台（Google 政策风险）。

## 3. 我的异议（需你决策，本方案不擅自执行）

> 以下为技术判断，可能与你的预期不同，**需你拍板**：

**异议 A（最重要）**：MIUI 的进程回收**无法仅靠 App 代码根治**。即使应用内做足（前台服务+降内存），真机测试时**必须先在 MIUI 上完成保活配置**（电池优化无限制 + 最近任务上锁 + 自启动），否则量产与真机都会复现"打开微信/商店后进程被杀→无法感知"。**需要你在真机上执行这份 MIUI 配置清单**（我会在应用内加引导页）。否则本轮修复注定再度"尽力而为"。

**异议 B**：常驻**前台服务通知**会占用状态栏并可能被部分 ROM 视为"后台弹窗"——建议仅**在手机操控进行中动态启用**前台服务（开始操控→启动，会话结束→停止通知），而非常驻。需你确认接受"手机操控期间有常驻通知"代价。

**异议 C**：`cross_app__open_app` 出现在 round=12（LLM 在 flail）。手机操控工具集与跨 App 工具存在能力重叠/竞争，建议本轮明确**工具路由优先级**（进入手机操控场景后优先 phone_control 工具），否则 LLM 会在两套工具间摇摆加剧失败。

## 4. 方案矩阵（待你勾选）

| 项 | 方案 | 成本 | 有效性 | 依赖决策 |
|---|---|---|---|---|
| F1 | 应用内"MIUI 保活配置引导"界面（电池优化/自启动/上锁） | 中 | 根治前提 | **异议A：需你真机配合配置** |
| F2 | 手机操控进行中动态前台服务（`foregroundServiceType`）+ 进出会话启动/停止 | 中高 | 降被杀概率 | **异议B：常驻通知代价** |
| F3 | 后台降内存：非对话空闲卸载 ONNX 嵌入器/清对象缓存 | 中 | 中 | 无 |
| F4 | 工具路由优先级：手机操控场景固定 phone_control 工具集 | 低 | 中 | 异议C |
| F5 | launch_app 前后「前台包名校验 + wait_after 轮询重试」（Open-AutoGLM `get_current_app` 思路） | 低 | 高 | 无 |
| B11-1 | Stuck 检测：连续 N 步同屏无变化 → 恢复序列/take_over | 中 | 高 | 无 |
| B11-2 | 前台 App 判定：`getWindows()` root.packageName | 低 | 高 | 并入 F5 |
| B11-3 | 动作后 before/after 轻量校验 + 失败回灌 | 中 | 高 | 无 |
| B11-4 | 树空视觉兜底 + 可诊断文案（已有 rebinding 文案） | 低 | 中 | 已部分落地 |
| B11-5 | 120+ 常用 App 包名映射库 + 高频确定性流程模板 | 高 | 中（支撑≥70%成功率指标） | 无 |

## 5. 执行顺序建议（你确认后按此开发）

阶段一（根治前提/保活）：F1 引导界面 + F2 动态前台服务 + F3 降内存
阶段二（感知/操控质量）：F5 前台包名校验 + 轮询、F4 工具路由、B11-1 Stuck 检测、B11-2 前台判定、B11-3 before/after
阶段三（规模化）：B11-4 视觉兜底、B11-5 包名映射库 + 确定性模板
每阶段经 guardrail + ac-verifier + 全量回归 + 模拟器验证后，由你真机手动测试。

## 6. 需你决策的点（汇总）

- [ ] D1（异议A）：是否接受"手机上操控保活需真机 MIUI 前置配置"为不可绕过前提？若要应用内尽力而为方案继续，请明示"不做引导页只做 F2/F3"。
- [ ] D2（异议B）：是否接受"手机操控进行中动态前台服务 + 期间常驻通知"？
- [ ] D3（异议C）：是否接受"手机操控场景固定 phone_control 工具优先级"？
- [ ] D4：批次11 B11-1~5 是否全部纳入本轮，还是分阶段（建议分三阶段）。

## 6.5 用户已确认的决策（2026-08-21）

- **D1（异议A）**：接受"MIUI 保活配置"为不可绕过前提，**并在应用内做引导页**。
- **D2（异议B）**：接受"手机操控进行中动态启用前台服务（期间常驻通知，会话结束即停）"。
- **D3（异议C）**：接受"手机操控场景固定 `phone_control` 工具优先级"。
- **D4**：批次11 **分三阶段逐步落地**（阶段一=保活，阶段二=感知质量，阶段三=规模化）。

## 6.6 执行进度

- **阶段一（保活）**：✅ F2 动态前台服务已落地并经真机确认生效（`isForeground=true`）；F1 引导页待办；F3 降内存待办
- **阶段二（感知质量）**：✅ F5 前台包名校验+轮询、B11-2 前台 App 判定、B11-4 树空引导（微信 childCount=0 铁证 + get_ui_state 引导截图坐标）已落地；F4 工具路由、B11-1 Stuck、B11-3 before/after 待办
- **阶段三（规模化）**：待办（B11-4 视觉兜底加固、B11-5 包名映射库）

## 6.7 真机复测新增根因（2026-08-21 01:31 + 01:38）

**问题① 微信树空确认（D5 已决：自动切视觉/坐标）**

- `mapNode: 仅 1 个节点（根 childCount=0 className=null）`——**微信对无障碍树完全屏蔽**，UI 树方案对它本质不可用；截图已验证可用（SkJpegEncoder 成功）。get_ui_state 已改为失败时引导 LLM 用 screenshot+坐标，仍需在系统层强制自动切换（D5）。

**问题② sensenova 400 确认（D6 已决：先调研再改）**

- `SSE 请求失败 status=400 {"error":{"message":"invalid tool_call function, function<path> cannot be empty","code":"3"}}`
- 网络调研确证：sensenova 6.7 Flash-Lite 校验严格——
  - `assistant.content: null`（伴随 tool_calls）在 sensenova 为 **unspecified/不被接受**（我们正是这样发的）
  - 流式 SSE 分片重组时 `tool_call_id` / `function.name` 易丢/变空，sensenova 直接 400
  - 业界（siclaw#140 / cc-switch#4164）通用解法：**发送前过滤空 id/name 的 tool_call 块 + 修正 finish_reason + 移除 assistant 空 content**

## 6.8 待用户决策/新决策

- [x] D7：sensenova 兼容修复范围已落地——① assistant 空 content 由 null 改空串；② 过滤空 id/name 的 tool_call 块（D6）。新增 2 测试。

## 6.9 已落地修复（2026-08-21 二轮）

- **问题① 微信树空（D5）**：[PhoneControlLocalToolExecutor.getUiState] 空树时返回「自动切视觉兜底」指令（明确引导 screenshot+坐标、不再 get_ui_state 死循环）
- **问题② sensenova 400（D6，已调研）**：[OpenAICompatibleProvider.toMessageBody] assistant 空 content+toolCalls 改空串；发送前过滤空 id/name 的 tool_call 块（sensenova/StepFun/Kimi 严格端点 400 根因）
- 全量回归 BUILD SUCCESSFUL（两次 test JVM 偶发崩溃与改动无关，重跑即过）+ 新增 2 测试（assistant 空串 / 过滤非法 tool_call）

## 6.10 三轮真机根因（2026-08-21 01:53 + 01:59）

**① sensenova 400 已修复 → 现仅剩 429 并发限流**

- 日志：`status=429 body={"error":{"message":"...max organization concurrency: 1..."}}`
- **结论**：`invalid tool_call function` 400 已不再出现（D6 修复生效）；现为 **sensenova 免费档账号并发=1** 的限流，非代码 bug。工具回路 3s/6s 自动重试仍在，但并发 1 会串行排队。

**② deepseek 视觉兜底指令已生效，但纯文本模型读不了截图**

- 日志：`round=5 toolCalls=[phone_control__screenshot]`（LLM 已按引导截图）→ 之后又 get_ui_state → take_over
- **结论**：`runScreenshot` 只返回图片 data URL；deepseek 是**纯文本模型读不了图**，截图后无法获取坐标 → 死循环。需给手机操控截图接 **OCR（ML Kit，US-302 已有）→ 返回屏幕文字 + 坐标**，纯文本模型才能继续。

## 6.11 待决策（三轮）

- [x] **D8（sensenova 429）**：保持现状（免费档并发=1 为账号限制，工具回路 3s/6s 自动重试已处理）
- [x] **D9（纯文本模型 OCR 坐标兜底）**：已落地——`OcrTextExtractor` 接口新增 `extractElements`（返回文字+坐标），`MlKitOcrTextExtractor` 实现；`runScreenshot` 在有 OCR 时回灌「【屏幕文字（OCR）+坐标】」；`getUiState` 树空引导提示纯文本模型用 OCR 坐标。已注入 PrismApplication。

## 6.12 D9 落地明细（2026-08-21）

- [OcrTextExtractor.kt] 接口 + `MlKitOcrTextExtractor` 新增 `extractElements`：ML Kit `line.elements` → `OcrElement(text, centerX, centerY)`（与降采样截图同一坐标系，可直接 tap）
- [PhoneControlLocalToolExecutor.runScreenshot]：有 OCR 时返回「截图 data URL + 【屏幕文字（OCR，可据此坐标 tap/swipe）】」
- [PrismApplication] 注入 `mlKitOcrTextExtractor`
- 全量回归 BUILD SUCCESSFUL + APK 已装（02:12:21）

## 6.13 四轮真机问题 · OCR 无法告诉 LLM 点击位置（D10 待决策）+ glm 429（D11 待决策）

> 依据真机手动测试（2026-08-21）+ 深度联网调研（GitHub 检索 OmniParser/Open-AutoGLM/MobileAgent-Android/
> Agent-S/adb-mcp/DroidRun 等 12+ 项目 + 学术文献 Set-of-Mark/UI-TARS/ScreenSpot）。按 CLAUDE.md §21 Bug 修复闭环
> 先记录根因与方案，由用户决策后执行。

### 6.13.1 问题① OCR 可用但 LLM 不主动调用 + 识别不准 → 无法告诉 LLM 点击位置

**用户反馈**：OCR 能出文字，但 LLM 不主动调用；识别准确率差，无法很好地告诉 LLM 应该点击哪个位置。

**根因链（代码 + 调研双重确认）**：

1. **【致命】OCR 坐标空间错位** —— 截图经 [captureScreenshot]（最长边 ≤1024px 降采样）后，OCR 在**降采样位图**上跑
   [extractElements]，返回的 centerX/Y 是**降采样像素空间**（如 461×1024）；而 tap 的 [performTap]/坐标吸附在
   **全屏屏幕空间**（如 1080×2400）执行。两者相差约 2.3 倍缩放因子 → LLM 按 OCR 坐标点击全部错位。
   **这是"识别不准、点不到位置"的直接代码根因**，先于任何 prompt/模型问题。
2. **LLM 不主动调用 OCR** —— [buildToolDefinitions] 中 screenshot 工具描述写死"**纯文本模型请勿调用**以免上下文膨胀"，
   与 [getUiState] 空树时引导"请使用 screenshot 截屏"自相矛盾；模型按工具描述行事 → 不调用 → 拿不到 OCR 坐标。
3. **OCR 文本质量** —— ML Kit 用 `line.elements`（单字/词碎片）粒度输出，无行级聚合、无置信度过滤，
   碎片化文本让纯文本模型难以匹配目标（如"搜索"被切成单字）。
4. **纯文本模型坐标推理弱** —— 业界共识（Set-of-Mark 论文 + UI-TARS GROUNDING + GUI-Actor/GUI-AIMA"coordinate-free"趋势）：
   VLM 直接输出像素坐标的 grounding 失败率极高（实测坐标 0/5 vs 编号 5/5），应让模型"选文本/选编号"而非"猜坐标"。

### 6.13.2 联网调研结论（GitHub + 学术，重点解决"如何告诉 LLM 点击位置"）

| 项目 | 来源 | 核心方案 | 对我们可借鉴点 |
|---|---|---|---|
| **Set-of-Mark (SoM)** | Microsoft 论文 arXiv:2310.11441 | 给可点击元素画编号框，模型输出编号而非坐标 | **编号替代坐标**（5/5 vs 0/5）；文本模型版 = 输出 `[N] 文本 (x,y)` 列表 |
| **OmniParser** | microsoft/OmniParser | 屏幕解析：图标检测(YOLO)+图标描述(Florence-2)+OCR → 编号化元素 | 图标识别思路（移动端重，留后续） |
| **Open-AutoGLM** | zai-org + dascard/Open-AutoGLM-App | SoM 粉色编号 + Grid Overlay + 纯视觉三策略；`do(Tap, mark=5)` | **编号定位 + 多策略自适应**（Android 本地，与我们对齐） |
| **Agent-S** | simular-ai/Agent-S | 双 grounding：视觉 grounding（LMM→坐标）+ **文本 grounding（OCR+LLM 定位文本短语）** | **文本锚点**：LLM 说"点搜索"，系统 OCR 找"搜索"→点中心 |
| **adb-mcp** | HarounAbdelsamad/adb-mcp | `get_screen`=UI 树+OCR+SoM 一次返回；`tap` 支持 by text/selector | **tap by text**（一次感知含坐标与编号） |
| **DroidRun** | droidrun/droidrun | UI bounds + OCR + **图标分类（端侧 MobileNet 300+ icons）** | 图标识别可端侧（需数据，留后续） |
| **Appium ocr-click-plugin** | Jitu1888/ocr-click-plugin | OCR 找文本→点其中心 | **文本定位点击**（自动化测试验证成熟） |
| **MobileAgent-Android** | GiggleWang/MobileAgent-Android | VLM 路径：截图+Accessibility 树注解编号喂视觉模型 | 仅 VLM 可用，不解决纯文本模型 |
| **UI-TARS** | bytedance/UI-TARS | 原生 GUI 模型 + GROUNDING 模板（纯坐标输出 + 智能缩放归一化） | 坐标归一化 pipeline 佐证缩放关键性 |
| **ScreenSpot / Aria-UI / GUI-Actor / GUI-AIMA** | 学术 | GUI grounding 基准；attention/coordinate-free 新趋势 | 业界都在"逃离裸坐标" |

**结论**：纯文本模型 + 无障碍受限（微信/WebView）场景，业界最优组合 = **文本锚点为主（tap by text）+
编号列表辅助（SoM 文本版）+ 坐标兜底（修复缩放）**。图标检测（OmniParser/DroidRun 思路）需训练数据/端侧模型，
移动端成本高，列为后续增强。

### 6.13.3 问题② glm-4.6v-flash 429 限流

**现状**：v1 批次11 已落地指数退避（3s/6s/12s/24s × 4 次）。用户仍看到"⚠️ 请求过于频繁（429）"——
该消息来自 [SkillExecutor] 重试耗尽后补发 或 非工具流（无工具对话）直接转发。

**根因候选**：① 用户测的是旧 APK（批次11 刚完成）；② glm 持续过载超 45s 重试窗口；③ 429 发生在
非工具流（普通对话无工具）无重试覆盖。

**增强方案**：尊重服务端 **Retry-After 头**（行业标准）+ 重试上限 4→6 + 非工具流也做退避重试 +
重试耗尽文案明确"已自动重试 N 次仍限流，请稍后重试"。

### 6.13.4 方案矩阵（D10/D11 待用户勾选）

| 项 | 方案 | 成本 | 有效性 | 依赖 |
|---|---|---|---|---|
| **A（致命）** | OCR 坐标缩放还原：captureScreenshot 记录原始尺寸 + extractElements 加 screenWidth/Height + runScreenshot 传入 | 低 | **根治坐标错位** | 无 |
| **B** | OCR 输出升级：line 级聚合 + 置信度过滤 + `[N] 文本（x,y）` 编号列表（SoM 文本版） | 低 | 高（文本完整可匹配） | 无 |
| **C** | tap 新增 `text` 文本锚点：系统 OCR 模糊匹配→点中心→吸附可点击节点（Agent-S/adb-mcp 同款） | 中 | **高（LLM 不再猜坐标）** | 无 |
| **D** | 主动调用引导：修正 screenshot 工具描述（纯文本模型也可调用）+ 手机操控 systemPrompt 声明 OCR 兜底工作流 | 低 | 高 | 无 |
| **E** | 429 增强：Retry-After 尊重 + 重试 4→6 + 非工具流退避重试 + 文案明确 | 中 | 中高 | 无 |
| F（留后续） | 图标检测/描述（OmniParser/DroidRun 思路，需端侧模型或训练数据） | 高 | 中 | 用户确认后单独立项 |

### 6.13.5 待用户决策

- [x] **D10**：OCR 定位方案 A+B+C+D 全部纳入本轮（用户已确认 2026-08-21）。
- [x] **D11**：429 增强方案 E 纳入（重试 4→6 + Retry-After 尊重 + 非工具流重试，用户已确认）。
- [x] **D12**：F（图标检测）**本轮一起做**（用户已确认 2026-08-21，非后续迭代）。

### 6.13.6 已实施（2026-08-21，全量回归 0 失败）

**A（致命）OCR 坐标空间还原**：

- [PhoneControlAccessibilityService.captureScreenshot] 降采样前记录原始屏幕尺寸 `lastScreenshotOrigW/H` +
  [lastScreenshotScreenSize]；[OcrTextExtractor.extractElements] 新增 `screenWidth/Height` 参数，
  [MlKitOcrTextExtractor] 按 `screenW/bitmapW`、`screenH/bitmapH` 把降采样坐标还原到屏幕空间
  （纯函数 [scaledOcrElement] 可测）；[PhoneControlLocalToolExecutor.runScreenshot] 传入。
  **修复前 OCR 坐标与 tap 空间相差约 2.3 倍 → 全部点错位（"识别不准、点不到"直接根因）。**

**B（质量）OCR 行级聚合 + 编号列表**：

- [MlKitOcrTextExtractor.extractElements] 从 element 碎片粒度改为 **line 行级**聚合（整行文本 + 行包围盒）
  - 置信度过滤（<0.15 丢弃）；[runScreenshot] 输出 `[N] 文本（坐标 x,y）` 编号列表（SoM 文本版）。

**C（核心）tap 文本锚点（text grounding）**：

- tap/long_press/double_tap 工具新增 `text` 参数；[runTargetAction] 优先 UI 树
  [findNodeByTextNid]（BFS 聚合可点击/可输入节点子树文本 + [textSimilarity] 模糊匹配）→ 兜底 OCR
  [resolveTextAnchor]（截图 → OCR 模糊匹配 → 命中元素中心 → tap 坐标吸附）。LLM 不再需要猜像素坐标。
- 敏感拦截保持：锚点文本/命中文本/密码节点/高危三态均复用原安全链。

**D（引导）主动调用 OCR**：

- screenshot 工具描述移除"纯文本模型请勿调用"（与空树引导自相矛盾），改为声明【OCR 文字+坐标】【图标区域】
  与 tap(text=...) 用法；get_ui_state 空树引导对齐；[PHONE_CONTROL_GUIDANCE] 新增「OCR 兜底工作流」第 4 条。

**F（D12）图标区域检测**：

- 新增 [IconRegionDetector]：纯像素启发式（灰度 → Sobel 边缘 → 排除 OCR 文字框 → 4-连通域 →
  尺寸/面积过滤 → 按面积取前 20），零新依赖/零模型；[detectIcons] 输出 `[N] 图标（坐标 x,y）` 占位元素，
  [runScreenshot] 分节输出。局限已文档明示：无模型无法识别图标"含义"，仅作候选坐标提示（主路径仍是文本锚点）。

**E（D11）429 限流增强**：

- [StreamEvent.Error] 新增 `retryAfterSeconds`；[OpenAICompatibleProvider] 从 429 响应头解析
  Retry-After（秒数/HTTP-date，[parseRetryAfter] 上限 60s）；[SkillExecutor] 退避**优先 Retry-After**，
  否则指数退避；重试上限 4→6；重试耗尽文案明确「已自动重试 N 次仍失败」；**非工具流**
  [ConversationViewModel.executePlainStream] 新增同策略自动退避重试（仅无内容时才重试，幂等安全）。

**测试**：新增 IconRegionDetectorTest（连通域/过滤/Sobel×6）、OcrElementScalingTest（坐标缩放/文本相似度×8）、
PhoneControlLocalToolExecutorTest 增补（tap text 参数/screenshot 描述）；SkillExecutorTest 429 测试适配
（注入 1ms 退避 + 新重试次数 1+6=7 + 新文案）。**全量回归 BUILD SUCCESSFUL（0 失败）。**

## 6.14 五轮真机分析 · glm-4.6v-flash 工具调用异常（D13 待决策）

> 依据真机手动测试（2026-08-21，日志 prism_20260821_035600.log）+ 用户反馈（glm-4.6v-flash 连工具都无法使用，异常打断；deepseek 已较好完成任务）。按 CLAUDE.md §21 Bug 修复闭环先记录根因与方案，由用户决策后执行。

### 6.14.1 日志证据（prism_20260821_035600.log，03:57-04:33）

- **DeepSeek 会话工作正常**（03:57 微信任务 + 04:01 起拼多多任务）：`round=N toolCalls=[phone_control__launch_app/get_ui_state/screenshot/tap]` 连续；`currentRoot: hit rootInActiveWindow pkg=com.xunmeng.pinduoduo`（拼多多正确进入）；`tap text 锚点 UI 树命中 nid=27（query=…）` / `tap text 锚点 OCR 命中 …（score=0.95）` —— **v1 批次11 的 UI 树锚点 + OCR 锚点 + 坐标吸附均已生效**。
- **04:33 出现熔断**：`round=1/2 toolCalls=[phone_control__launch_app]` ×2 连续失败 → `tool circuit breaker`。结合用户 glm 输出使用了**错误包名 `com.pinduoduo.pinduoduo`**（正确为 `com.xunmeng.pinduoduo`）→ launch_app 返回"未找到应用"→ 失败。**印证 prd-v1-b10 §8 #5「120+ 常用 App 包名映射库」必要性。**

### 6.14.2 glm-4.6v-flash 根因（用户证据 + 代码核验）

**用户证据**（glm 输出原样）：

```
我会帮助您打开拼多多并搜索相关产品。首先，我需要启动拼多多应用。
我需要重新尝试启动拼多多应用。如果仍然失败，我将考虑使用搜索功能直接搜索相关内容。

```html 
<tool_call>phone_control__launch_app 
<arg_key>package</arg_key> 
<arg_value>com.pinduoduo.pinduoduo</arg_value> 
</tool_call> 
```

```

**根因**：glm-4.6v-flash 通过配置端点**不产生原生 `tool_calls`**（OpenAI 结构化函数调用未被该模型/端点支持或被理解），而是把工具调用写成**文本型 `<tool_call>` XML 块**（HTML 代码围栏内）。应用链路只认流式原生 `ToolCallComplete`：
- `SkillExecutor.executeLoop` 收集 `completedToolCalls`（原生）→ 空 → 判为纯文本响应 → 回路结束，工具从不执行；
- 显示层 `sanitizeToolCallSyntax` 只剥离 `<tool_calls>`/`<invoke>`（`TOOL_BLOCK_OPEN_REGEX = tool_calls|invoke`）——**单数 `<tool_call>` 不匹配**，且块位于 ` ```html ` 围栏内被 `inFence` 原样保留 → 用户看到原始 XML，"异常打断"。

**结论**：需让应用能**解析并执行文本型工具调用**（模型无关的兜底），而非只依赖原生 tool_calls。

### 6.14.3 prd-v1-b10 §8 增强候选落地核对

| # | 实践 | 状态 |
|---|---|---|
| 1 | Stuck 检测（连续 N 步无变化恢复） | ❌ 未落地（本轮候选） |
| 2 | 前台 App 判定（getWindows root.packageName） | ✅ 已落地（v1-B11 B11-2 currentForegroundPackage） |
| 3 | 动作后 before/after 轻量校验 + 失败回灌 | ❌ 未落地（本轮候选） |
| 4 | 树空视觉兜底 + 可诊断文案 | ✅ 已落地（v1-B11 D5/D9 + 截图 OCR/图标） |
| 5 | 120+ 常用 App 包名映射库 + 高频确定性模板 | ❌ 未落地（本轮候选，glm 错包名实证必要性） |
| 6 | A11y 文本零信任强化 | ⚠️ 部分（systemPrompt 声明已落地；喂 LLM 前标注待强化） |

### 6.14.4 方案矩阵（D13 待用户勾选）

| 项 | 方案 | 成本 | 有效性 | 对症 |
|---|---|---|---|---|
| **A（核心）** | **文本工具调用解析**：新增 `TextToolCallParser`（纯函数可测）解析 `<tool_call>name <arg_key>k</arg_key><arg_value>v</arg_value></tool_call>` 块；`SkillExecutor.executeLoop` 无原生 tool_calls 轮次解析 → 合成 ToolCallComplete → 走既有工具执行回路（含用户确认/手机操控安全链） | 中 | **高（glm 等文本工具型模型开箱可用）** | glm 连工具都用不了 |
| **B（配套）** | 渲染净化扩展：sanitizeToolCallSyntax 增加 `<tool_call>`（单数）匹配 + 剥离含工具调用的 HTML 围栏，执行后 UI 不显示原始 XML | 低 | 高（UX） | 异常打断视觉残留 |
| **C（prd §8#5）** | 常用 App 包名映射库（120+）：launch_app 别名/错误包名纠正（如 com.pinduoduo.pinduoduo → com.xunmeng.pinduoduo） | 低 | 高（≥70% 成功率） | glm 错包名 |
| **D（prd §8#1）** | Stuck 检测：连续 N 步树/状态无变化 → back/home/重拉 App 恢复 | 中 | 高（长链路卡死） | 长任务卡死 |
| E（prd §8#3） | 动作后 before/after 校验 + 失败回灌 | 中 | 中高 | 指令成功率 |

### 6.14.5 待用户决策

- [x] **D13**：A（文本工具调用解析，必须）+ B（渲染净化）全纳入（用户已确认 2026-08-21）。
- [x] **D14**：C（120+ 常用 App 包名映射库）本轮纳入（用户已确认，glm 错包名实证）。
- [x] **D15**：**D（Stuck 检测）+ E（before/after 校验）均追加到本轮**（用户已确认）。

### 6.14.6 已实施（2026-08-21，全量回归 0 失败）

**A（核心）文本工具调用解析（glm-4.6v-flash 可用）**：
- 新增 [TextToolCallParser]（纯函数可测）：解析 `<tool_call>name<arg_key>k</arg_key><arg_value>v</arg_value></tool_call>` 文本块
  （容忍空白/换行/属性变体，支持多块、JSON 参数值）；[stripTextToolCalls] 剥离含 HTML 围栏包裹的工具块。
- [SkillExecutor.executeLoop]：无原生 tool_calls 轮次解析文本工具调用 → 执行（复用 executeToolCall 的
  用户确认/手机操控安全链）→ 结果以【工具执行结果】user 消息回灌（**模型无关，不依赖 OpenAI tool 协议**，
  规避 glm 端点不认 tool role 而 400）→ 继续回路。助手正文（剥离块后）入历史保上下文连贯。

**B（配套）渲染净化**：[sanitizeToolCallSyntax] 开头预剥离文本型 `<tool_call>` 块（含 ```html 围栏），
执行后 UI 不再显示原始 XML（glm 输出证据原样）。

**C（D14）包名映射库**：新增 [PhoneControlPackageMap]（120+ 常用 App 中/英/拼音名 + 别名/错包名 → 正确包名，
宁缺毋错）；[runLaunchAction] 启动前纠正（拼多多 com.pinduoduo.pinduoduo → com.xunmeng.pinduoduo）。
金融敏感黑名单包仍由 [PhoneControlSecurity] 硬拦截，映射不改变拦截。

**D（D15）Stuck 检测**（prd-v1-b10 §8 #1）：[getUiState] 用「前台包名+UI 树 hash」签名跟踪连续 N=3 步无变化
→ 附恢复引导（back/home/重拉 App/take_over）；launch_app 确认进入新 App 时重置。

**E（D15）before/after 校验**（prd §8 #3）：tap/long_press/double_tap/swipe 执行前捕获屏幕签名、执行后复检，
签名一致且动作确已执行（非错误/拦截/ask_user）→ 附软提示"可能未命中/未生效"（软提示非硬失败，合法无变化点击不误伤）。

**测试**：新增 TextToolCallParserTest（8 用例：围栏/裸块/多块/JSON 参数/非法名/剥离/保代码围栏）、
PhoneControlPackageMapTest（6 用例）、SkillExecutorTest 增补（文本工具调用执行 + 用户拒绝不执行 ×2）。
**全量回归 BUILD SUCCESSFUL（0 失败）。**

## 6.15 六轮真机分析 · glm 崩溃闪退 + 多模态最大化 + 响应速度（D16 待决策）

> 依据真机手动测试（2026-08-21，日志 prism_20260821_054307.log）+ 用户反馈（glm-4.6v-flash 可打开拼多多但后续任务失败后软件崩溃闪退，重开历史对话卡顿再崩；glm 为多模态模型是否沿用 OCR；响应慢；继续 E）。按 CLAUDE.md §21 Bug 修复闭环先记录根因与方案，由用户决策后执行。

### 6.15.1 日志证据（prism_20260821_054307.log，05:37-05:51）

- **glm 混合原生 + 文本工具调用**：05:37 `round=1 toolCalls=[phone_control__launch_app]` + `launch_app 包名纠正：com.pinduoduo.pinduoduo → com.xunmeng.pinduoduo`（包名映射生效，打开拼多多成功）；05:39 `round=2 tap text 锚点 UI 树命中 nid=27（query=搜索）`；05:46 `round 15 解析到 1 个文本工具调用: [phone_control__screenshot]`（glm 也用文本工具调用）
- **E 已生效**：05:39 `动作后屏幕无变化（before/after 签名一致）`
- **429 限流**：05:38-05:39 连续 4 次 `429 code:1302 您的账户已达到速率限制`，退避 3s/6s/12s/24s 后恢复
- **崩溃（ANR）**：05:49:20 PID 23902 `MIUIScout ANR: LazyLayoutPrefetcher duration=5152ms` + `AndroidComposeViewAccessibilityDelegateCompat` 慢 → 主线程被 Compose 聊天列表渲染阻塞 >5s → ANR → 系统杀进程（随后多个新 PID 重启）

### 6.15.2 崩溃根因（确证）

**[runScreenshot] 返回内嵌全量 base64 data URL**（`"截图成功（data URL，仅多模态模型可解读）：$dataUrl$screenText"`，dataUrl ≈ 200-400KB）：
1. 截图工具结果（含 base64）作为 TOOL/user 消息**持久化进会话 JSON**——glm 多次截图 → 历史膨胀至数 MB；
2. 渲染时 [sanitizeToolCallSyntax]/markdown 对 400KB 单行处理 → **主线程阻塞 >5s → ANR 崩溃**；
3. 重开历史对话 → 重新加载并渲染这些大消息 → 再次卡顿崩溃；
4. 该 base64 作为**文本**喂回模型 → glm 上下文膨胀 → **响应慢**（且多模态模型根本不需要 base64 文本，它需要**图片**）。

这即 AGENTS.md 既有技术债"图片 base64 随会话 JSON 膨胀"。**多模态模型场景下，base64 文本对模型既无用又致命。**

### 6.15.3 方案矩阵（D16 待用户勾选）

| 项 | 方案 | 成本 | 有效性 | 对症 |
|---|---|---|---|---|
| **A（P0 崩溃）** | runScreenshot **不再内嵌 base64**：纯文本模型返回 OCR 文字+坐标（不含 base64）；视觉模型返回图片标记，图片经 SkillExecutor 以 **image_url 注入会话**（模型真正"看到"屏幕），base64 不进历史 | 中 | **根治 ANR + 提速** | 崩溃闪退 / 卡顿 |
| **B（多模态 #2）** | ProviderConfig 增 `supportsVision` 开关（设置页可配 + 按模型名自动提示）；视觉模型截图**跳过 OCR**（看图即可，更快更准） | 中 | 高 | 发挥多模态 |
| **C（提速 #3）** | 去掉 base64 上下文膨胀（A 已含）+ 视觉模型免 OCR + 截图降采样适度 + 精简工具结果长度 | 低 | 高 | 响应慢 |
| **D（E #4）** | before/after 校验扩展：覆盖 type 动作 + 软提示强化（附"建议改动作"） | 低 | 中 | 失败回灌 |

### 6.15.4 待用户决策

- [x] **D16a**：A（崩溃根治，必须）+ B（多模态图片注入）全纳入（用户已确认 2026-08-21）。
- [x] **D16b**：C（提速，含视觉模型免 OCR + 精简工具结果）+ D（E 强化，覆盖 type + 软提示强化）全纳入。

### 6.15.5 已实施（2026-08-21，全量回归 2456 用例 0 失败）

**A（P0 崩溃根治）**：
- [PhoneControlLocalToolExecutor.runScreenshot] **不再内嵌 base64 文本**进工具结果/会话历史（真机 ANR 根因：
  400KB base64 单行渲染阻塞主线程 >5s）——纯文本模型返回 OCR 文字+坐标（无 base64）；视觉模型返回图片标记。
- 历史对话重开不再渲染 400KB 行 → ANR/卡顿/闪退根治。

**B（多模态图片注入，发挥 glm 视觉能力）**：
- [ProviderConfig] 新增 `supportsVision`（ObjectBox 自动迁移）+ [detectVisionSupport] 模型名启发式 +
  设置页「支持视觉（多模态）」开关。
- [PrismApplication] 注入 `visionCapableProvider`（读激活 Provider.supportsVision）。
- [runScreenshot] 视觉模型时**跳过 OCR**、返回 `【手机截图图片】+dataUrl` 标记；
  [SkillExecutor.extractScreenshotImage] 提取标记 → 截图**图片以 user 消息 image_url 注入会话**
  （glm 等直接"看"屏幕，非 OCR 文本）→ base64 从持久化结果剥离（不进历史）。原生 + 文本工具路径均接入。

**C（提速）**：
- 去 base64 上下文膨胀（A 已含，每截图省 ~400KB）。
- 视觉模型免 OCR（B 已含，省 ~1s/次 + 免 OCR 误读）。
- 截图 OCR/图标条目上限（OCR 40 / 图标 15）+ 省略提示，防上下文膨胀。

**D（E 强化）**：
- before/after 校验扩展覆盖 **type** 动作；软提示强化（附"换更精确目标 / back 重试 / take_over"引导，
  帮 LLM 走出卡住循环）。

**测试**：新增 ProviderConfigVisionTest（detectVisionSupport ×3）、SkillExecutorTest 增补
（extractScreenshotImage 剥离 base64 + 截图图片 image_url 注入 + base64 不进持久化消息 ×2）。
**全量回归 2456 用例 0 失败。**

## 7. 非目标 / 明确不做的（防范围蔓延）
- 不引入 ADB / uiautomator / root（Prism 本地无障碍定位不变）
- 不采用违规保活（双进程守护 / Native 保活 / 透明前台伪装）
- 不切纯视觉方案（保留纯文本模型为主现状）
- 不引入图标检测**模型**（OmniParser YOLO / DroidRun MobileNet 需训练数据/端侧模型；本轮 F 为
  纯像素启发式近似，无模型；识别"图标含义"留后续迭代）
