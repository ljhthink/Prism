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
