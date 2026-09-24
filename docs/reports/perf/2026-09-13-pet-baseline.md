# 批次24 桌宠 · 性能与资源基线（初版）

| 项目 | 内容 |
| --- | --- |
| 报告路径 | docs/reports/perf/2026-09-13-pet-baseline.md |
| 执行 Agent | 主 Agent（deepseek_harness / default-agent） |
| 任务令牌 | TKN-PET-PERF-001 |
| 日期 | 2026-09-13 |
| 关联 | [ADR-051](../../decisions/ADR-051-rolepack-pet-live2d.md) · [PRD §4 非功能需求](../../prd-v1-b24-rolepack-pet.md) |
| 基线类型 | **初版**（本批次首次建立；无历史基线可比） |

> 本文件位于 `docs/reports/perf/` —— 该子目录**提交远程**（`.gitignore` 只忽略 `docs/reports/*.md`，
> 不递归匹配子目录），与其它一次性报告不同。

---

## 1. 可在本环境测量的项（已测，可复现）

| 指标 | 值 | 测量方法 |
| --- | --- | --- |
| 新增 APK 静态资源（未压缩） | **812,363 B**（约 0.78 MB） | `pixi.min.js` 456,150 + `live2dcubismcore.min.js` 207,155 + `pixi-live2d-display-cubism4.min.js` 145,809 + `pet-renderer.js` 24,590 + `index.html` 1,606 + 2 份 LICENSE 2,153 |
| 形象包体积（昔涟） | **8,281,422 B**（约 7.9 MB） | `cyrene.petpack`（zip/deflate，23 条目，含 9,007,035 B 纹理） |
| 形象包解压后体积 | **9,479,993 B** | 23 个条目原始字节合计 |
| 桌宠关闭时常驻开销 | **0**（无服务、无 WebView、无定时器） | 代码路径核对：`PetOverlayService` 仅在 `enabled=true` 时启动；`PetManager` 只持有状态流；`PetActionBus.emit` 在无订阅者时为 buffer 写入后被丢弃 |

**可复现命令**

```powershell
# 静态资源体积
Get-ChildItem app\src\main\assets\pet -Recurse -File | Measure-Object -Property Length -Sum
# APK 内是否确实包含且仅包含这些
Add-Type -AssemblyName System.IO.Compression.FileSystem
([System.IO.Compression.ZipFile]::OpenRead("app\build\outputs\apk\debug\app-debug.apk")).Entries |
  Where-Object { $_.FullName -like 'assets/pet/*' } | Select-Object FullName, Length
```

---

## 2. 只能真机测量、**当前未验证**的项（显式登记，不得当作已达标）

| 指标 | PRD 目标 | 当前状态 |
| --- | --- | --- |
| 悬浮窗创建 → 首帧可见 | ≤ 1500 ms | **未验证**（无 Android 运行时） |
| 就绪超时阈值是否合理 | 8 s（[PetOverlayService.READY_TIMEOUT_MS]） | **未验证**。8 s 是"冷启动 WebView + 9 MB 纹理解码"的经验估计，真机若稳定 > 8 s 会误报失败，届时需上调 |
| 拖动/动画帧率 | 60 fps（低功耗 15 fps 上限） | **未验证** |
| WebView 常驻内存（含 9 MB 纹理） | 无明确门禁；业界无公开量化数据 | **未验证** |
| 长时间显示（10 min）的耗电与温升 | 无明显异常 | **未验证** |
| 桌宠关闭时的实际进程内存增量 | ≈ 0 | **未验证**（代码路径上为 0，但未实测） |

> PRD §4 的性能门禁（"性能下降超过 50% 判失败"）在本批次**不适用**：
> 本批次是**新增功能**，未修改任何既有热路径；唯一被触碰的既有链路是
> `ConversationViewModel.markCompleted`（1 次非挂起 `tryEmit`）与
> `VoiceSessionController` 的 3 处 `tryEmit`（其中 1 处在 TTS 回调线程做一次
> 字符串长度统计）。这三处的开销上界都是 O(1)~O(文本长度)，且**桌宠关闭时无订阅者**，
> 不构成性能回退。全量回归 2927 用例的耗时与基线（2829 用例）同量级，可作为粗略旁证。

---

## 3. 后续测量建议（真机）

1. `adb shell dumpsys meminfo io.prism | grep TOTAL` 在「桌宠关闭 / 开启 Live2D / 开启图片形象」三态各取一次。
2. `adb shell dumpsys gfxinfo io.prism framestats` 拖动 10 秒取 p50/p95 帧时长。
3. 首帧可见：`PetOverlayService` 的 `pet renderer ready` 日志时间戳 − `showOverlay()` 开始时间戳。
   （当前日志已含 ready 行与诊断字段，无需额外插桩。）
4. 耗电：`adb shell dumpsys batterystats --charged io.prism` 对照同长度不使用桌宠的时段。

---

## 4. 静态已验证项（补充，非性能）

| 项 | 结论 | 证据 |
| --- | --- | --- |
| **PIXI 的资源路径解析语义** | **URL 语义（非 path 语义）** ⇒ 模型内以**相对路径**书写的引用（如 `motions/动作%236_0.motion3.json`）会被正确拼到模型 URL 所在目录下 | 反编译 vendored `pixi.min.js`：`url` 对象的 `get resolve(){return ku}`，而 `ku=us.resolve=Ku`，`function Ku(i,t){return fs(i,!1,!0).resolve(t)}` 即 Node `url.resolve` 实现（`resolveObject`）。**这决定了"引用不必写成根相对路径"这一前提成立**——否则全部 motion/expression/纹理都会 404 |
| **`#` 必须百分号编码** | 成立 | 同一 bundle 内存在 `removeUrlParams = url.split("?")[0].split("#")[0]`；`动作#6_0.motion3.json` 若不以 `%23` 编码，后缀会被整段吃掉 ⇒ 4/5 个 motion 静默 404。安装期 `encodeModelReferences` 已处理，且 `PetContractTest` 有回归 |
| **虚拟源路由与引用编码的一致性** | 成立 | `PetAssetRoutes.resolvePetFile` 用自实现的 `percentDecode`（**不把 `+` 当空格**）解码后再净化 + canonicalPath 双校验；`PetContractTest`「含井号的资产名可被路由命中」覆盖 |

