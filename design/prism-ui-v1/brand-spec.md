# Prism · Brand Spec（UI v1）

> 采集日期：2026-11-20
> 资产来源：项目自有（`app/src/main/res/drawable/ic_launcher_foreground.xml`、`ui/theme/Color.kt`）+ 本次方向定义
> 资产完整度：部分（Logo 从应用图标矢量重绘为深色版本；无官方品牌手册）

## 核心资产

### Logo

- 母题：三棱镜剖面 + 入射白光 + 出射分光（现有 launcher 图标即此结构，属于真实品牌资产，保留）
- 本次处理：**只用三棱镜轮廓 + 一条出射折线**，单色描边，去掉分光三色（三色分光在 UI 里会变成第二个强调色）
- 文件中位置：`screens.js` 内 `ICONS.bolt` 为占位符，正式实现应提交 `ic_prism_mono.xml`（单色 24dp 矢量）
- 禁用：渐变球、发光、多彩分光、圆角方形渐变底

### 产品图 / UI 截图

- 实体产品：无（纯软件）
- UI 参考：`reference/current-settings.png`、`reference/current-chat.png`（现状快照，仅用于对照，不进入新界面）

## 色板（A · 暖石墨 + 琥珀，默认方向）

| Token | 值 | 对比度（on `#171816`） | 用途 |
| --- | --- | --- | --- |
| `bg` | `#111210` | — | 屏底 |
| `surface` | `#171816` | — | 卡片、输入框、底栏 |
| `surface-2` | `#1d1e1b` | — | 按下态、激活行 |
| `surface-3` | `#24261f` | — | 开关轨、弹层 |
| `text` | `#ecede6` | 15.1:1 | 主文本 |
| `dim` | `#a3a69b` | 7.2:1 | 次级文本 |
| `faint` | `#82867a` | 4.8:1 | 元信息（已调到 AA 下限以上） |
| `accent` | `#e0a45c` | 8.2:1 | 激活、运行、关键数字、危险区外唯一彩色 |
| `line` | `rgba(236,237,230,.08)` | — | 唯一分隔线 |
| `ok` | `#7fa98a` | 6.7:1 | 已连接 |
| `warn` | `#d6a94f` | 8.2:1 | 待配置 |
| `bad` | `#d96a5b` | 5.2:1 | 失败、删除 |

备选方向：B 冷石墨 `#8fb8c2`、C 墨黑暖白（`tokens.css` 中 `[data-theme]`）。

### 禁区

- 靛蓝紫 `#6E62FF` 及任何蓝紫作为主色
- 青 + 薄荷 + 紫三色并存于 chrome
- 渐变文字、彩色光晕、彩色投影、半透明玻璃叠加
- 第二个饱和色（除 `ok/warn/bad` 三个语义色外，全局只有一个 `accent`）

## 字型

- 界面：系统中文（PingFang SC / Noto Sans CJK SC / 思源黑体）
- 数字、耗时、路径、ID：`JetBrains Mono` → Android `FontFamily.Monospace`
- 字号：22 / 17 / 15 / 13 / 12 / 11；字重仅 400 与 500（品牌名 500）
- 不使用 700+、不使用宽字距全大写标签、小字不用负字距

## 签名细节

- **1px 折射线**：只出现在运行中的轨迹节点、激活 Tab 下沿、选中项（`accent` 1px 线或实心点）
- 每屏最多一个 `accent` 视觉焦点；其余全部中性阶梯

## 气质关键词

安静 · 精确 · 有一点温度（calm, exact, quietly warm）

## 反面参照（unslop 签名清单）

purple gradient / glow / emoji label / "New ✨" / 渐变文字 / 宽字距大写 / 卡片漂浮 / 循环呼吸动画 / 每屏一句口号 / 实现细节写进设置行。
