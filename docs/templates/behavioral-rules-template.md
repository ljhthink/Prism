---
template: behavioral-rules
version: 1.0
date: 2026-08-02
related_adr: ADR-001
---

# 行为规则累积文件模板（behavioral-rules.md）

> 动态累积层：将 Bug 根因 / accepted review / postmortem 转为持久行为规则，
> 防止同类错误跨会话复发。借鉴 Microsoft 论文（arXiv:2607.13091），实证 0% 复发率。
> 本文件是 `CLAUDE.md`（静态核心层）的动态补充，分层避免核心规则膨胀。
> 关联流程见 [CLAUDE.md 第二十一~二十三节](../../CLAUDE.md) 第 5.3 节。

## 使用规范

- **任务启动前**（CLAUDE.md 第一节规划后）：主 Agent 必读相关类别规则。
- **提交前自检**（CLAUDE.md 第九节）：对照本文件逐条核对。
- **guardrail-enforcer 审查**：检查是否违反已有规则。
- **里程碑审计**：去重、合并、标注 deprecated。
- **新增规则**：需 `guardrail-enforcer` 确认非重复且可执行。

## 规则结构（每条规则字段）

| 字段 | 说明 |
| --- | --- |
| ID | BR-<category>-NNN |
| 类别 | naming / error-handling / security / concurrency / interface / ops / testing / docs |
| 规则 | 可执行的约束描述 |
| 反例 | 违反样例 |
| 正例 | 正确样例 |
| 来源 | Bug 根因 / accepted review / postmortem（附引用） |
| 添加日期 | YYYY-MM-DD |
| 适用场景 | dev / bugfix / ops / all |
| 状态 | active / deprecated |

---

## 命名（naming）

| ID | 规则 | 反例 | 正例 | 来源 | 日期 | 场景 | 状态 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| BR-naming-001 | _（待累积）_ |  |  |  |  |  |  |

## 错误处理（error-handling）

| ID | 规则 | 反例 | 正例 | 来源 | 日期 | 场景 | 状态 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| BR-error-001 | 不允许空 catch 块；所有异常必须记录或向上传播 | `catch (e) {}` | `catch (e) { logger.error(e); throw e; }` | _（待累积）_ |  | all | active |

## 安全（security）

| ID | 规则 | 反例 | 正例 | 来源 | 日期 | 场景 | 状态 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| BR-sec-001 | 禁止在日志输出密钥/令牌/完整 SQL | `logger.info(token)` | `logger.info({userId})` | _（待累积）_ |  | all | active |

## 并发（concurrency）

| ID | 规则 | 反例 | 正例 | 来源 | 日期 | 场景 | 状态 |
| --- | --- | --- | --- | --- | --- | --- | --- |

## 接口契约（interface）

| ID | 规则 | 反例 | 正例 | 来源 | 日期 | 场景 | 状态 |
| --- | --- | --- | --- | --- | --- | --- | --- |

## 运维（ops）

| ID | 规则 | 反例 | 正例 | 来源 | 日期 | 场景 | 状态 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| BR-ops-001 | 任何 L2/L3 运维操作必须人工 LGTM 后执行 | Agent 自主删除生产数据 | Agent 生成操作蓝图→等人工批准→执行 | ADR-001 | 2026-08-02 | ops | active |
| BR-ops-002 | 修复后必须检查下游服务健康度（防 goal lock） | 修完即关闭 | 健康检查通过 + 指标恢复阈值达成 | AI SRE 案例 | 2026-08-02 | ops | active |

## 测试（testing）

| ID | 规则 | 反例 | 正例 | 来源 | 日期 | 场景 | 状态 |
| --- | --- | --- | --- | --- | --- | --- | --- |

## 文档（docs）

| ID | 规则 | 反例 | 正例 | 来源 | 日期 | 场景 | 状态 |
| --- | --- | --- | --- | --- | --- | --- | --- |

---

## 审计记录

| 里程碑 | 审计日期 | 规则总数 | 新增 | 去重/合并 | deprecated | 审计人 |
| --- | --- | --- | --- | --- | --- | --- |
|  |  |  |  |  |  |  |
