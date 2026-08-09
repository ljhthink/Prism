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
