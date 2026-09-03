# ADR-037: v1 真机二次问题修复（搜索乱码+质量 / Fetch 反爬 / L2 记忆原子化 / 视觉旁路 / 手机操控 UI 与上限）

> 落实 v1（v1.0.0）真机手动测试暴露的 6 项问题 + 1 项启动崩溃（MemoryRecord 迁移 NULL）的架构决策与修复。
> 参照调研：Fetch 反爬最佳实践（tech-selection-researcher）、TencentDB-Agent-Memory 分层记忆哲学。

| 项目 | 内容 |
| --- | --- |
| 状态 | Accepted |
| 日期 | 2026-08-19 |
| 决策者 | 主 Agent + 用户确认（真机反馈 6 项问题 + 1 启动崩溃） |
| 关联文档 | [PRD](../PRD.md)、[ADR-033 UXR11](ADR-033-uxr11-real-device-fixes.md)、[ADR-034 记忆深度优化](ADR-034-v1-memory-deep-optimization.md)、[ADR-035 视觉方案B](ADR-035-v1-vision-plan-b.md)、[ADR-036 手机操控](ADR-036-v1-phone-control.md)、[ADR-014 工具回路](ADR-014-m4-skills-system.md) |
| 风险等级 | P2（跨模块：搜索渲染 / Fetch 网络 / L2 记忆 / 视觉旁路 / 手机操控 UI 与工具回路） |

## 背景（Context）

v1.0.0 真机手动测试发现 6 项问题 + 1 项启动崩溃：

1. **联网搜索回归**：关键词无法命中对应网址、参考来源与问题无关；LLM 回答中出现 `<｜tool_calls>` 全角竖线乱码（工具调用净化失效）。
2. **Fetch 反爬未绕开**：现行浏览器请求头 + 手动重定向 + SSRF 方案仍无法绕过 Cloudflare/反爬，多次失败。
3. **L2 记忆不符合预期**：无论会话是否有重要信息都会被记忆进 L2，一次性问答/普通查询被当作记忆入库。
4. **云端视觉旁路不可用**：deepseek-v4-flash 发图仍提示"当前模型端点不支持图片"，用户询问是否需要额外配置、设置里未显示。
5. **手机操控设置界面明显过大**。
6. **手机操控几乎无法完成任何任务**（`⚠️ 工具调用循环达上限 10`），要求解除该功能工具循环上限、其他工具提升至 50。

另：真机启动即崩（`FATAL: MemoryRecord.<init> parameter sourceMessageIds`）——ObjectBox 自动迁移新增非空 String 列为 SQL NULL，读库传非空构造参数触发 Intrinsics NPE（本会话已先修复，见下）。

## 决策（Decision）

### 子决策 0：MemoryRecord 迁移 NULL 崩溃（已修复）

- 根因：US-101 新增 `sourceMessageIds: String = ""`（非空），ObjectBox 自动迁移对新增列历史行写 SQL NULL；读库时 `nativeFirstEntity` 传 null 给 Kotlin 非空构造参数 → NPE 启动崩溃。
- 修复：`MemoryRecord.sourceMessageIds` 改可空 `String? = null`；读取点（`MemoryRepository.refreshFlows` 结果对象 / `CrossSessionMemoryManager` 检索结果）`?: ""` 归一化。equals/hashCode 已 `==` 兼容 null。

### 子决策 A：搜索乱码 + 质量（问题 1）

- **乱码根治**：`ConversationScreen.sanitizeToolCallSyntax` 开/闭标签正则由 `<(?!/)[^\w>]{0,4}?(?:tool_calls|invoke)` 放宽为 `<(?!/)[^>\n]{0,8}?(?:tool_calls|invoke)`。旧 `[^\w>]` 拒绝 `<` 与关键词间夹 `\w` 词字符（如 `<1tool_calls>`/带编号分隔）导致开标签漏配而残留乱码；`[^>\n]` 容忍任意 0~8 个非 `>` 字符，`tool_calls|invoke` 关键词足够特异不误伤正文。新增含词字符分隔的回归用例锁定。
- **搜索质量**：沿用既有「多候选核心词整词降级重试 + 条目级过滤 + 多查询合并」（ADR-031/033）+ Fetch 反爬优化（子决策 B）提升命中；乱码根治后 LLM 不再把工具调用块当正文输出。

### 子决策 B：Fetch 反爬优化（问题 2，参照 tech-selection-researcher P0 清单，零新依赖）

- **请求头指纹**：UA 升级 Chrome/126 移动版 + 新增 `Sec-CH-UA` 系列（`sec-ch-ua`/`sec-ch-ua-mobile`/`sec-ch-ua-platform`，版本与 UA 一致，规避 Cloudflare 指纹实锤 bot）。刻意不设 Accept-Encoding（保持 OkHttp 透明 gzip）。
- **可诊断分层**：新增 `503` 挑战页文案；新增 `isAntiBotOrEmpty` 内容纯度判定——200 但正文为 JS 挑战/动态渲染空壳/登录墙时降级"页面无有效正文"，避免无意义脚本回灌 LLM。marker 分级（guardrail A 收敛）：Cloudflare 强特征独立命中；中文泛词（人机验证/滑动验证/异常流量）需正文极短（≤80 字符）才判定，避免误伤含"验证码"等词的正常长文。
- **SSRF 纵深不破坏**：`followRedirects=false` + 逐跳 `isPublicHttpUrl` 复检 + 3 跳上限原样保留（ADR-033 不变量）。

### 子决策 C：L2 记忆只存原子信息（问题 3，参照 TencentDB-Agent-Memory L1-Atom）

- 哲学对齐：L1-Atom 只沉淀「用户的事实 / 偏好 / 决策 / 约束」，**不沉淀对话与一次性查询**。跨会话记忆价值在未来复用用户画像，而非复述历史问答。
- `isImportantTurnPair` 收紧：移除"问题即重要"与"长度≥8 即重要"的宽泛判定；改为仅放行**自我指涉 ATOM_KEYWORDS**（我喜欢/我是/我住在/我计划/请记住/别忘了…）**且非一次性查询**（`isPureQuery`：疑问词收尾/任务请求句式 → 不沉淀）。普通问题/一次性任务请求不再入库。
- guardrail C 收敛：ATOM_KEYWORDS 剔除淡化的"记住/以后/下次/重要/关键/务必/一定要"等会被一次性陈述触发的非自我指涉词。

### 子决策 D：云端视觉旁路可用 + 配置引导（问题 4）

- **OCR 兜底解锁**：`handleVisionUnsupportedError` 放宽早退条件（`visionConfig == null` 不再直接报错），无视觉 Provider 时仍进入 orchestrator 走**本地 OCR**（云端外发仍受 `isBypassAvailable()` 授权闸门保护，隐私边界不破坏）。
- **配置引导**：设置页"云端视觉旁路"副标题动态显示当前视觉回退 Provider 名（或"需配置并标记一个视觉 Provider"），明示该功能需在 Provider 配置中勾选 `isVisionFallback` 的视觉模型端点才能用云端旁路。

### 子决策 E：手机操控设置界面过大（问题 5）

- 精简"无障碍服务"与"安全拦截"两行超长副标题，消除行高膨胀与状态切换抖动，UI 更紧凑。

### 子决策 F：工具循环上限（问题 6）

- 全局 `DEFAULT_MAX_ROUNDS` 10 → **50**（SkillExecutor + ConversationViewModel 双常量同步）。
- 手机操控 `phone_control__*` 工具按工具类别分层放行：`resolveToolLoopMaxRounds` 检出含手机操控工具 → **200 轮**（对"完成一次任务"实际上限，解除频繁截断）；普通工具维持 50。
- 防失控兜底保留：`MAX_CONSECUTIVE_TOOL_FAILURES=2` 重复失败熔断仍触发；轮间退避仍在。

## 结果（Consequences）

- 全量回归通过（~2100 用例，0 失败；首次 ObjectBox JNI 原生崩溃为本机既有 flaky，重跑通过）。
- 新增/更新单测：sanitize 含词字符分隔乱码块、resolveToolLoopMaxRounds 分层上限、Fetch 200 挑战壳/503 文案、isImportantTurnPair 一次性查询与长度边界、ATOM_KEYWORDS 偏好偏好；同步收紧的记忆测试消息体。
- guardrail-enforcer（TKN-V1FIX-GUARDRAIL-001）：**通过**（0 阻断，无注入/密钥/SSRF 回归/CWE-209；隐私授权边界完好），A/C 质量建议已采纳修复。
- 模拟器验证：APK（含 x86_64 ABI）安装启动无崩溃，UI 渲染正常。
- 已知待真机补测：Bing 真实关键词命中、Fetch 真实反爬站点、视觉旁路端到端、手机操控长链路成功率。

## 后续跟踪

- 记忆系统跨会话基准（PersonaMem 类似）未建立；手机操控 200 轮上限按工具类别放行有 token 成本放大风险，若真机实测过长需按"本轮实际调用工具"收敛轮数 + 维护上下文预算。
