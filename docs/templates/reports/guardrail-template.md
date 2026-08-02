# 安全与质量审计报告（模板）

> 从本模板复制新建，存于 `docs/reports/YYYY-MM-DD-<feature>-guardrail.md`。
> 由 guardrail-enforcer 子 Agent 生成。依 CLAUDE.md 第十节。

| 项目 | 内容 |
|---|---|
| 执行 Agent | guardrail-enforcer |
| 任务令牌 | TKN-XXX-NNN |
| 审计日期 | YYYY-MM-DD |
| 关联 ADR | |
| 关联代码变更 | |

## 1. 代码质量审查（TRAE-code-review）

- Karpathy Guidelines 符合性
- 逻辑错误 / 性能隐患 / 可维护性
- 跨模块影响识别
- 测试框架与基础用例充分性

## 2. 安全漏洞扫描（TRAE-security-review）

### 2.1 输入与边界审计
### 2.2 执行安全审计（注入 / 最小权限 / 输出编码）
### 2.3 密钥与配置安全
### 2.4 依赖与供应链风险

## 3. OWASP / CWE 发现

| 编号 | 等级 | 位置 | 修复建议 |
|---|---|---|---|

## 4. 结论

- [ ] 通过（可进入测试阶段）
- [ ] 阻断（存在严重缺陷或高危漏洞，回退编码阶段）

## 5. 规则提议（accepted review → behavioral-rules）

将本次审查中接受的 review comment 转为规则提议，追加到 `docs/behavioral-rules.md`。
