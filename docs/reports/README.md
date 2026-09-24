# docs/reports/ —— 运行时报告目录

> CLAUDE.md 5.3「动态报告目录化」规定的运行时报告存放目录。

## 目录性质

本目录存放 **一次性参考工件**，包括：

- `YYYY-MM-DD-<task>-archaeology.md` —— 源码考古报告（code-archaeologist）
- `YYYY-MM-DD-<task>-guardrail.md` —— 安全与质量审计报告（guardrail-enforcer）
- `YYYY-MM-DD-<task>-acceptance.md` —— 验收测试报告（ac-verifier）
- `YYYY-MM-DD-<task>-impact-selfcheck.md` —— 变更影响自检报告（主 Agent）
- `YYYY-MM-DD-<task>-debug.md` —— 调试证据报告（TRAE-debugger）
- `YYYY-MM-DD-<task>-audit.md` —— 一致性审计报告（主 Agent）
- `YYYY-MM-DD-<bug>-bug.md` —— Bug 报告存档
- `YYYY-MM-DD-<incident>-incident.md` —— 运维事件报告
- `YYYY-MM-DD-<incident>-postmortem.md` —— 事后复盘

## 提交策略（2026-08-09 用户指示）

- **本目录下的 `*.md` 一次性汇报文档不提交远程**（`.gitignore` 已配置忽略规则）
- **例外**：本 `README.md`（目录说明）+ `perf/` 子目录（性能基线长期参考）
- 已跟踪的历史文件保持现状，新文件通过 `.gitignore` 自动忽略

## 子目录

- [`perf/`](perf/) —— 性能基线报告（长期参考，提交远程）

## 检索入口（rules/04 §7.4 报告引用）

本目录文件量已达数百份，且均为**一次性工件**——为避免与项目进展文档重复维护（rules/03 §5.2 单一事实来源），
**具体批次/任务报告清单统一由 [docs/progress.md](../progress.md) 引用**（每条批次记录内附报告相对路径）。
本 README 只维护目录性质与命名规范，不逐份登记。

当前在用批次（2026-09-12，角色体验保真修复 TASK-20260912-001）
—— **以下文件仅本地存在，不入远程**（rules/05 §12.6 一次性工件禁令），远程仓库看不到这些链接属预期：

- [2026-09-12-rolepack-v6-archaeology.md](2026-09-12-rolepack-v6-archaeology.md) —— 源码考古（code-archaeologist）
- [2026-09-12-roleplay-fidelity-research.md](2026-09-12-roleplay-fidelity-research.md) —— 联网调研（tech-selection-researcher）
- [2026-09-12-rolepack-v6-guardrail.md](2026-09-12-rolepack-v6-guardrail.md) —— 审查与安全审计（guardrail-enforcer，R1 有条件通过 → R2 通过）
- [2026-09-12-rolepack-v6-realdevice-checklist.md](2026-09-12-rolepack-v6-realdevice-checklist.md) —— 真机手动测试清单（主 Agent）
- [2026-09-12-rolepack-v6-impact-selfcheck.md](2026-09-12-rolepack-v6-impact-selfcheck.md) —— 变更影响自检（主 Agent）
- [2026-09-12-rolepack-v6-acceptance.md](2026-09-12-rolepack-v6-acceptance.md) —— 验收测试（ac-verifier，有条件通过：AC 10 通过 / 0 失败 / 2 无法自动验证）
- [perf/2026-09-12-rolepack-v6-assembly-baseline.md](perf/2026-09-12-rolepack-v6-assembly-baseline.md) —— 装配链性能基线（**perf/ 子目录提交远程**）

当前在用批次（2026-09-19，端侧本地音色克隆 LVC-1 / TKN-LVC-001，ADR-053）
—— **以下文件仅本地存在，不入远程**（rules/05 §12.6 一次性工件禁令）：

- [2026-09-19-local-voice-clone-feasibility.md](2026-09-19-local-voice-clone-feasibility.md) —— 上游可行性调研（方案 A/B/C/D/E/F 对比与推荐架构）
- [2026-09-19-local-voice-clone-research.md](2026-09-19-local-voice-clone-research.md) —— 联网调研（tech-selection-researcher：Pocket 中文支持复核、ZipVoice 参数与证据强度、v1.13.7 备选穷举）
- [2026-09-19-local-voice-clone-archaeology.md](2026-09-19-local-voice-clone-archaeology.md) —— 源码考古（code-archaeologist：枚举新增值受影响点、播报链不变量、降级链缺口）
- [2026-09-19-local-voice-clone-impact-selfcheck.md](2026-09-19-local-voice-clone-impact-selfcheck.md) —— 变更影响自检（主 Agent，含 `tryFallbackTts` 等价性论证）
- [2026-09-19-local-voice-clone-freeze.txt](2026-09-19-local-voice-clone-freeze.txt) —— 冻结指纹清单 R1（21 文件 SHA256）
- [2026-09-19-local-voice-clone-freeze-r2.txt](2026-09-19-local-voice-clone-freeze-r2.txt) —— 冻结指纹清单 R2（23 文件）
- [2026-09-19-local-voice-clone-freeze-r3.txt](2026-09-19-local-voice-clone-freeze-r3.txt) —— 冻结指纹清单 R3（23 文件）
- [2026-09-19-local-voice-clone-freeze-r4.txt](2026-09-19-local-voice-clone-freeze-r4.txt) —— 冻结指纹清单 R4（25 文件，封口轮）
- [2026-09-19-local-voice-clone-guardrail.md](2026-09-19-local-voice-clone-guardrail.md) —— 审查与安全审计（guardrail-enforcer，R1 有条件通过 → R2 有条件通过 → R3 有条件通过 → R4 封口）
- [2026-09-19-local-voice-clone-acceptance.md](2026-09-19-local-voice-clone-acceptance.md) —— 验收测试（ac-verifier）
- [2026-09-19-local-voice-clone-realdevice-checklist.md](2026-09-19-local-voice-clone-realdevice-checklist.md) —— 真机手动测试清单（主 Agent）

当前在用批次（2026-09-12，知识库自动注入范式撤除 TASK-20260912-002 / BUG-20260912-001，ADR-050）
—— **以下文件仅本地存在，不入远程**（rules/05 §12.6 一次性工件禁令）：

- [2026-09-12-kb-auto-injection-archaeology.md](2026-09-12-kb-auto-injection-archaeology.md) —— 源码考古（code-archaeologist，故障定位模式：11 处注入点与闸门失效）
- [2026-09-12-kb-injection-fix-history.md](2026-09-12-kb-injection-fix-history.md) —— 修复史与行为规则审计（7 轮修复时间线 + 5 条失败模式）
- [2026-09-12-kb-injection-policy-research.md](2026-09-12-kb-injection-policy-research.md) —— 联网调研（tech-selection-researcher：Agentic RAG vs 2-Step RAG、缓存语义、防污染 12 手段）
- [2026-09-12-kb-corpus-recovery.md](2026-09-12-kb-corpus-recovery.md) —— 真机知识库语料取证与阈值区分力实测
- [2026-09-12-kb-auto-injection-debug.md](2026-09-12-kb-auto-injection-debug.md) —— 复现与运行时取证（TRAE-debugger）
- [2026-09-12-kb-auto-injection-bug.md](2026-09-12-kb-auto-injection-bug.md) —— Bug 报告存档
- [2026-09-12-kb-auto-injection-impact-selfcheck.md](2026-09-12-kb-auto-injection-impact-selfcheck.md) —— 变更影响自检与 blast-radius（主 Agent）
- [2026-09-12-kb-auto-injection-guardrail.md](2026-09-12-kb-auto-injection-guardrail.md) —— 审查与安全审计（guardrail-enforcer）
- [2026-09-12-kb-auto-injection-acceptance.md](2026-09-12-kb-auto-injection-acceptance.md) —— 验收测试（ac-verifier）
- [2026-09-12-kb-auto-injection-realdevice-checklist.md](2026-09-12-kb-auto-injection-realdevice-checklist.md) —— 真机手动测试清单（主 Agent）

当前在用批次（2026-09-13，角色包桌宠 TKN-PET-*，ADR-051 / PRD 批次24）
—— **以下文件仅本地存在，不入远程**（rules/05 §12.6 一次性工件禁令）：

- [2026-09-13-rolepack-pet-archaeology.md](2026-09-13-rolepack-pet-archaeology.md) —— 源码考古（code-archaeologist：RolePack 现状 / 悬浮窗零基础 / ADR 硬约束）
- [2026-09-13-pet-live2d-mobile-research.md](2026-09-13-pet-live2d-mobile-research.md) —— 技术选型调研（tech-selection-researcher：Android Live2D 三方案量化对比 + 悬浮窗工程实践 + 模型合规）
- [2026-09-13-pet-integration-points.md](2026-09-13-pet-integration-points.md) —— 集成点逐字取证（code-archaeologist：工具挂载点 / TTS 起止可观测点 / 三个"不存在"）
- [2026-09-12-android-overlay-desktop-pet-research.md](2026-09-12-android-overlay-desktop-pet-research.md) —— 悬浮窗桌宠补充取证（结论已并入选型报告 §4）
- [2026-09-13-pet-impact-selfcheck.md](2026-09-13-pet-impact-selfcheck.md) —— 变更影响自检（主 Agent）
- [2026-09-13-pet-cyrene-asset.md](2026-09-13-pet-cyrene-asset.md) —— 昔涟形象资产交付说明（取数通道 / 授权核实 / `pet.json` 设计依据 / 8 项自校验）
- [2026-09-13-rolepack-pet-guardrail.md](2026-09-13-rolepack-pet-guardrail.md) —— 审查与安全审计（guardrail-enforcer）
- [2026-09-13-rolepack-pet-acceptance.md](2026-09-13-rolepack-pet-acceptance.md) —— 验收测试（ac-verifier）
- [2026-09-13-rolepack-pet-realdevice-checklist.md](2026-09-13-rolepack-pet-realdevice-checklist.md) —— 真机手动测试清单（主 Agent）

当前在用批次（2026-09-13，昔涟角色卡 v7 TASK-20260913-002，ADR-052 / PRD 批次26）
—— **以下文件仅本地存在，不入远程**（rules/05 §12.6 一次性工件禁令）：

- [2026-09-13-cyrene-rolecard-v7-sourcing.md](2026-09-13-cyrene-rolecard-v7-sourcing.md) —— 取证与根因定性（主 Agent：真机角色包/选中项/日志三路取证 + 上游仓库一手核验）
- [2026-09-13-cyrene-rolecard-v7-impact-selfcheck.md](2026-09-13-cyrene-rolecard-v7-impact-selfcheck.md) —— 变更影响自检（主 Agent）
- [2026-09-13-cyrene-rolecard-v7-guardrail.md](2026-09-13-cyrene-rolecard-v7-guardrail.md) —— 审查与安全审计（guardrail-enforcer，R1 有条件通过 → R2 通过 → R3 通过 → R4 有条件通过 → **R5 通过**）
- [2026-09-13-cyrene-rolecard-v7-acceptance.md](2026-09-13-cyrene-rolecard-v7-acceptance.md) —— 验收测试（ac-verifier，首验通过 → R5 有条件通过 → **R6 通过**；冻结 15/15 MATCH）
- [2026-09-13-cyrene-rolecard-v7-realdevice-checklist.md](2026-09-13-cyrene-rolecard-v7-realdevice-checklist.md) —— 真机手动测试清单（主 Agent；A 可自动验证 / B 仅真机 / C 已登记未实现）
- 评测台（gitignored，`tmp/rolecard-eval/`）：`build-prompt.js`（角色轮角色层子集等效复刻）、`run-eval.js`、`tools-probe`、`rescore.js`（口径修正后重算）、`out/results-{v5,v6,v7,v5-flash,v7-flash}.{json,md}`、`out/tools-probe-{v5,v6,v7}.json`
- [perf/2026-09-13-pet-baseline.md](perf/2026-09-13-pet-baseline.md) —— 桌宠静态资源与性能基线（**perf/ 子目录提交远程**；真机项标"未验证"）

当前在用批次（2026-09-24，批次28 端侧音色质量 / 设置页音色包 UI / 资产与临时文件治理，ADR-058/059/060；BUG-20260924-001/002/003）
—— **以下文件仅本地存在，不入远程**（rules/05 §12.6 一次性工件禁令）：

- [2026-09-24-voice-quality-ui-and-asset-tidy-proposal.md](2026-09-24-voice-quality-ui-and-asset-tidy-proposal.md) —— 方案与既有取证（主 Agent：真机产物一致性、客观测量口径、`voicebox` 路线裁决；用户逐项裁决 Q1~Q6）
- [2026-09-24-batch28-realdevice-checklist.md](2026-09-24-batch28-realdevice-checklist.md) —— 真机手动测试清单（主 Agent；A 设置页 UI / B 音色包切换 / C VITS 听感 / D 性能 / E 回归）
- 19 臂扫参产物（gitignored，`tmp/voice/sweep-20260924/`）：`README.md`（人读）、`sweep-report.json`（全数字）、`determinism-probe.json`、19 个 wav；脚本 `voice-clone-kit/tools/sweep-vits-arms.py`、`probe-vits-determinism.py`
- 调研与取证（gitignored，`tmp/research-20260924/`）：`R1-voicebox-evaluation.md`、`R2-zipvoice-quality-speed.md`、`R3-vits-quality-diagnosis.md`、`T-tidy-completion.md`、`T-tidy-completion-r2.md`

