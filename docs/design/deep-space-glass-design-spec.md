# Prism · UI 设计规范 · 深空玻璃肌理（Deep Space Glass）v0.4

> 交互原型见 [deep-space-glass-prototype.html](deep-space-glass-prototype.html)（本地启动静态服务后访问）。
> 本规范为权威设计定义，供 Android Compose 实现（后续 ADR-003）与任何视觉评审使用。

| 项目 | 内容 |
|---|---|
| 版本 | v0.4 |
| 日期 | 2026-08-05 |
| 视觉方向 | 深空玻璃肌理 · **实体化表面 + 克制降噪**（用户选定方向 + AI 味根治） |
| 导航结构 | 4 Tab + 能力中枢 + **配置详情页** |
| 关联 | [PRD](../PRD.md) / [ADR-002](../decisions/ADR-002-prism-chat-ui-architecture.md) |
| 对齐能力 | 六位一体：BYOK / 知识库 RAG / MCP / Skills / 记忆 / 跨 App |

---

## 0. 设计升级说明（v0.3 → v0.4）

v0.3 的问题：**半透明玻璃卡 + 渐变光晕 + 18px 大圆角 + 高饱和霓虹 → 仍有「塑料感 / AI 模板味」**。

v0.4 的四条根治原则：

1. **实体化表面**：彻底告别「半透明玻璃」。表面全部改为**实心色板**（bg / surface / surface-2 / surface-3），用**实底 + 细描边 + 内顶高光 + 投影**制造纵深，不再依赖 blur / 渐变玻璃。
2. **降圆角**：卡片圆角从 18px 降至 **12px**，小组件 8px，更「硬朗/工具感」，消除玩具感。
3. **降霓虹**：背景转为**近黑冷灰**（蓝调极淡）；主强调靛蓝紫降为 `#6e62ff`；青/薄荷仅作功能性状态点。**单强调色原则**。
4. **底部贴底导航**：底部导航从「浮动玻璃胶囊」改为**贴底实心栏**（`surface` 底 + 顶描边），回归原生 App 的稳定底栏心锚。

---

## 1. 设计愿景与原则

Prism 是**手机端个人 AI Agent 平台**。视觉语言传达「光穿过三棱镜折射出知识」的母题，**克制的深空 + 精准的光 + 真实的材质纵深**。

**四条不可妥协原则：**

1. **去 M3 默认**：所有组件自绘或深度覆写，杜绝 `NavigationBar` / `ListItem` / 默认 `Surface` 观感。
2. **实体表面分层**：表面分四级（bg / surface / surface-2 / surface-3），每级是**实心色板**，有明确 diff 值，拒绝平涂，也拒绝「全玻璃」。
3. **光即信息**：状态用「光」表达（连接灯晕、进度流光），但**光只出现在关键处**，不铺满；主视觉由靛蓝紫单一强调色主导。
4. **动效有物理**：全部交互用 spring 物理，入场瀑布，按压缩放，无线性缓动。

---

## 2. 色彩系统（去塑料感核心）

### 2.1 背景与表面（实体化四级，近黑冷灰）

| Token | 色值 | 用途 |
|---|---|---|
| `--bg` | `#0c0c11` | 屏底（最深层） |
| `--surface` | `#14141a` | L1 卡片 / 底栏 / 输入 |
| `--surface-2` | `#1a1a22` | L2 浮层 / hover / 图标底 |
| `--surface-3` | `#20202a` | L3 弹层 / 分段 thumb / 开关轨 |

背景纯度大幅降低：`--bg` 至 `#0c0c11`，蓝调极淡，接近中性冷灰，消除「深紫玩具感」。表面为**实心色板**，不再半透明。

### 2.2 品牌强调色（沉稳化，单强调色）

| Token | 色值 | 用途 |
|---|---|---|
| `--primary` | `#6e62ff` 靛蓝紫 | 主强调、AI 身份、激活、主按钮 |
| `--primary-strong` | `#5a4eff` | 主按钮按压 / 强调描边 |
| `--cyan` | `#22c7e0` 青 | MCP 运行 / 知识检索（功能性） |
| `--mint` | `#2fbf8f` 薄荷 | 成功 / 已连接 / 引用 |
| `--warning` | `#d99a2b` 琥珀 | 需配置 / 待处理 |
| `--danger` | `#e5484d` 玫红 | 错误 / 失败 |

主色从 `#7b6cff` 降为 `#6e62ff`（更沉稳）；青/薄荷饱和度进一步降低。**单强调色原则**：主视觉靛蓝紫主导，青/薄荷仅状态点缀。

### 2.3 语义色

| Token | 色值 | 用途 |
|---|---|---|
| `--text` | `#eaeaf0` | 主文本 |
| `--text-dim` | `#a0a0ac` | 次级文本 |
| `--text-faint` | `#6e6e7a` | 弱化 / 占位 |
| `--line` | `rgba(255,255,255,.07)` | 描边分隔 |
| `--line-strong` | `rgba(255,255,255,.12)` | 强调描边 / 输入框 |

> 对比度 `--text` on `--bg` ≈ 14:1，`--text-dim` ≈ 7:1，满足 WCAG AA。

---

## 3. 字体系统

- 中文 **Noto Sans CJK SC**；数字/拉丁 **Roboto**。
- 层级：Screen Title 17/700 → Card Title 13.5/600 → Body 14/400 → Meta 11/400。
- **字距**：标题 `-0.2px` 收紧，全大写标签 `+0.6px` 疏开，形成现代排版节奏。

| 层级 | 字号/字重/字距 | 用途 |
|---|---|---|
| Screen Title | 17 / 700 / -0.2px | 顶栏主标题 |
| Card Title | 13.5 / 600 / -0.2px | 卡片标题 |
| Body | 14 / 400 / L1.65 | 正文、气泡 |
| Meta | 11 / 400 / +0.2px | 摘要、时间、状态 |
| Label | 10 / 600 / +0.6px | 分组标签（uppercase 视觉） |

---

## 4. 圆角与间距

- 圆角：卡片 **12px**、小组件 **8px**、胶囊 999px、输入框 10px、底部弹层顶角 18px。
- 间距：`4 / 8 / 10 / 12 / 14 / 16 / 20 / 24` 节奏。
- 列表项内边距 `13px`，卡片网格 `12px`，弹层内部 `20px`。

---

## 5. 表面层级系统（v0.4 核心，实体化替代玻璃）

| 层级 | 定义 | 用途 | 视觉 |
|---|---|---|---|
| L0 背景 | `--bg` | 屏底 | 无边界 |
| L1 卡片 | `--surface` 实底 + 1px `--line` 描边 + 内顶高光 + 投影 | 列表/卡片/底栏 | 微升起的实体板 |
| L2 浮层 | `--surface-2` + 更强内高光 + 大投影 | 输入条/分段切换/图标底 | 悬浮 |
| L3 弹层 | `--surface-3` 实底 + 顶角 18px + 顶部手柄 | 配置 sheet / 菜单 | 模态 |

**卡片 L1 配方**（替代玻璃/渐变）：

```css
.card{
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--r); /* 12px */
  box-shadow: 0 1px 0 rgba(255,255,255,.03) inset, 0 8px 24px -16px rgba(0,0,0,.9);
}
.card:hover{ background: var(--surface-2); }
```

> v0.4 **不使用 blur、不使用渐变玻璃**。纵深完全由实心色板 + 内高光 + 投影构成，Compose 无需 `RenderEffect`，可直接实现。

---

## 6. 动效系统（spring）

| 场景 | 规格 |
|---|---|
| 列表入场 | 上浮 8px + 透明度，spring（stiffness 220 damping 24）瀑布错峰 |
| 详情页 push | 右滑入 + 淡入，`.3s`；返回右滑出 |
| 弹层 sheet | 底部上滑 + 遮罩淡入，spring |
| 状态光 | 连接灯呼吸 opacity `.3↔1`，1.6s 循环，仅运行中 |
| 按压 | 缩放 .98（触觉反馈） |
| Tab 切换 | 文字/图标明暗过渡 `.2s` |

> 性能红线：只动画 `transform` / `opacity`。

---

## 7. 信息架构（4 Tab + 能力中枢 + 配置详情页）

```
底部导航（4 主入口，贴底）
├─ 聊天     → 会话 + 顶部 Provider 选择
├─ 知识库   → 库列表 → 库详情页 / 导入流程（sheet）
├─ 能力中枢 → [MCP | Skills | 记忆]
│              ├─ MCP   → Server 配置面板（sheet）
│              └─ Skill → Skill 详情页
└─ 设置     → Provider 配置详情页 / API Key / 生物识别 / 档位 / 关于
```

**关键补充（v0.3+）**：每个能力都有对应的**配置详情界面**，而非仅展示列表。详情通过 push 页或底部 sheet 呈现。

---

## 8. 屏幕设计

### 8.1 聊天（Chat）

- 顶栏：主标题「Prism」+ Provider 选择胶囊（可点开切换）+ 新会话按钮。
- AI 头像：三棱镜 SVG（主色描边 + 薄荷折射光），34px。
- 气泡：AI 卡片层级（L1）/ 用户靛蓝纯色；引用薄荷胶囊。
- 输入区：L2 实心胶囊输入框 + 渐变发送钮。
- 运行时：三点呼吸 + 「正在调用 MCP 检索…」。

### 8.2 知识库（Knowledge · US-003）

- 列表：Bento 库卡片（L1）+ 最近导入。
- **库详情页（push）**：分片统计（总数/已索引/待分片，`statbox` 实心卡）+ 文档列表 + 检索设置（Top-K、相似度阈值开关）+ 清空/删除。
- **导入流程（sheet）**：选文件类型（PDF/DOCX/XLSX/MD/TXT）→ 分片进度（流光）→ 完成回调。

### 8.3 能力中枢（Capabilities）

**MCP 工具（US-002）**：

- 列表：本地内置 / 远程模板，状态光点 + 开关。
- **Server 配置面板（sheet，点击可配置项打开）**：连接信息（名称/icon）、传输类型（stdio/SSE→HTTP）、Base URL、Token 输入、Schema 校验状态、**测试连接按钮**、启用开关、删除。

**Skills（US-004）**：

- 列表：状态徽章 + 开关。
- **Skill 详情页（push）**：说明、来源（本地/远程）、安装参数、启用。

**记忆（US-005）**：三层卡片 L1/L2/L3。

### 8.4 设置（Settings）

- 分组：模型与端点 / 隐私与安全 / 设备档位 / 关于。
- **Provider 配置详情页（push）**：Provider 列表（OpenAI/Anthropic/Ollama/Moonshot…）→ 点击进入单项配置（endpoint、model、temperature、API key 密文输入、Keystore 加密徽标、测试）。

---

## 9. 组件规范索引（v0.4 实体化）

| 组件 | 外观 | 层级 |
|---|---|---|
| 卡片 | 实心色板 + 描边 + 内高光 + 投影 | L1 |
| 浮层条 | 输入条 / 分段切换 | L2 |
| 底部弹层 | 实心顶角圆 + 手柄 + 遮罩 | L3 |
| 状态光点 | 8px 圆 + 光晕 + 可选呼吸 | — |
| 开关 | 40×24 实心轨 + 白色滑块 | L2 |
| 分段切换 | surface 底 + surface-3 thumb | L2 |
| AI 头像 | 三棱镜 + 折射 SVG | — |
| 引用胶囊 | 薄荷 + 边框 | — |
| 进度条 | 实心主色流光 | — |
| 底部导航 | **贴底实心底栏** + surface-2 激活胶囊 | L1 |

### 9.1 底部导航（v0.4 关键变更）

```html
.nav{display:flex;background:var(--surface);border-top:1px solid var(--line)}
.navbar{flex:1;display:flex;flex-direction:column;align-items:center;gap:4px;padding:9px 0}
.navbar .nm{width:40px;height:26px;border-radius:13px;display:flex;align-items:center;justify-content:center}
.navbar.on .nm{background:var(--surface-2)}
.navbar.on svg{stroke:var(--primary)}
```

- 贴底实心栏：`surface` 底 + 顶部 1px 描边，不再浮动。
- 激活项：图标底 `surface-2` 胶囊 + 图标靛蓝紫 + 文字明暗过渡。
- 底部预留 `env(safe-area-inset-bottom)`（刘海屏安全区）。

### 9.2 状态光点

```css
.dot{width:8px;height:8px;border-radius:50%}
.dot.ok{background:var(--mint)} .dot.run{background:var(--cyan);animation:pulse 1.6s}
.dot.warn{background:var(--warning)} .dot.err{background:var(--danger)}
```

### 9.3 开关

40×24，轨 `surface-3` 实底 + 1px 描边；开启态轨 `primary` 实底；滑块 20px 白色 + spring 滑动。

### 9.4 分段切换

surface 底 + 1px 描边 + 12px 圆角；激活 thumb 为 `surface-3` 实底带内高光；文字明暗过渡。

### 9.5 按钮

| 变体 | 外观 |
|---|---|
| primary | `primary` 实底 + 白字，按压 scale .98 |
| ghost | `surface` 底 + `line` 描边 + `text-dim` 字 |
| danger | 玫红透明底 `rgba(229,72,77,.08)` + 玫红描边 + 玫红字 |

---

## 10. Compose 实现映射（后续 ADR-003）

- 主题：`PrismTheme` 扩展 `darkColorScheme`，新增扩展色 `bg / surface / surface2 / surface3 / lineStrong / warning`。
- 表面：`PrismCard`（L1 实体板）替代 `PrismGlassCard`；`PrismSheet`（L3）。不再依赖 blur。
- 组件：重做 `PrismNavBar`（贴底实心）、`PrismSegmented`（surface-3 thumb）、`PrismSwitch`（实心轨）；新增 `PrismButton`、`PrismField`、`PrismIcon`（SVG 图标集）。
- 动效：`AnimatedContent` 页面 push/shift、`animateFloatAsState` spring。
- 导航：4 Tab + 页面级二级导航（push 详情页）。

---

## 11. 待确认 / 后续

- [ ] 跨 App 调用展示（US-006）并入 MCP 或独立小卡。
- [ ] 浅色主题是否精修（当前聚焦深空深色）。
- [ ] 原型评审通过后转 ADR-003，分阶段落地 Compose。
