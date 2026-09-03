# ADR-042: 分支模型落地（feat/m0-scaffold → main + GitHub Flow PR 流程）

> 落实 CLAUDE.md 第十二节版本管理策略：仓库此前唯一集成分支为 `feat/m0-scaffold`（批次1~15
> 全部直接提交于此，远程无 main），与 12.2「main 是唯一长期分支、所有改动经功能分支 + PR
> 合并」的要求不符。本 ADR 建立 main 分支、切换默认分支、配置分支保护，并以 ADR-042 自身的
> PR 作为新流程的首次演练。

| 项目 | 内容 |
| --- | --- |
| 状态 | Accepted |
| 日期 | 2026-09-03 |
| 决策者 | 主 Agent + 用户指令（「先建立 main 分支和 PR 流程」） |
| 关联文档 | [CLAUDE.md](../../CLAUDE.md) 第十二节、[ADR-003](ADR-003-mvvm-architecture.md)（GitHub Flow 引用） |
| 风险等级 | P2（DevOps/CI 变更，CLAUDE.md 17.1 第 3/5 条强制 ADR） |

## 背景（Context）

- 仓库 `ljhthink/Prism` 远程分支仅有 `feat/m0-scaffold` 与若干 dependabot 分支，**无 main**；
  默认分支为 `feat/m0-scaffold`，与 CLAUDE.md 12.2/12.3 约定不一致。
- `.github/workflows/docs.yml`（workflow 名 `docs-quality`，job 名 `docs-quality`）已具备
  markdownlint + `scripts/consistency-check.js` + lychee 三项检查，触发条件已按 main 设计
  （`pull_request` 路径过滤 + `push: branches: [main]`），仅缺 main 分支本身。
- 12.3 要求的「项目特定 CI（单元测试、安全扫描）」工作流尚不存在（Android 单测需 runner 配置
  Android SDK，成本较高），本期不设为必需检查，列为后续迭代（见「后果」）。

## 决策（Decision）

### 子决策 A：main 分支从 feat/m0-scaffold 当前 tip 创建

- `git push origin feat/m0-scaffold:refs/heads/main`：main 起点包含批次1~15.1 全部提交
  （批次1~7、批次8~15 两笔合并提交），保证 main「始终可部署」（最近的构建与真机验证均在此
  tip 上完成）；
- `gh repo edit --default-branch main`：默认分支切至 main；
- `feat/m0-scaffold` 保留为历史集成分支（只读参考，不再接收新提交）。

### 子决策 B：分支保护规则（对应 CLAUDE.md 12.3）

| 规则 | 配置 | 与 12.3 的差异说明 |
| --- | --- | --- |
| 禁止直接推送 | `required_pull_request_reviews` 以「要求 PR」形式落地（allow_force_pushes/deletions=false） | — |
| 必需状态检查 | `docs-quality`（strict up-to-date） | 12.3 的 `consistency-check` 已并入 docs-quality job（同工作流内步骤），检查名合一 |
| 项目 CI（单测/安全扫描） | **暂不设为必需** | 单测工作流未建立；设置不存在的检查名会永久 pending 卡死 PR，列后续迭代 |
| 必需 Code Review | **不强制人工批准**（solo 开发者无法自批） | 按 12.3 的替代条款执行：「由 guardrail-enforcer 代理审查」——每个 PR 对应批次均已通过 guardrail-enforcer + ac-verifier 闭环，PR 描述引用对应报告结论 |
| 合并方式 | 仅允许 Squash and merge（allow_merge_commit/rebase=false） | 与 12.3 一致 |
| 其他 | 禁止 force push / 禁止删除分支 | — |

### 子决策 C：以本 ADR 的 PR 作为新流程首次演练

- 功能分支 `docs/adr-042-branching-flow` → PR → main，走 docs-quality 检查 → squash merge；
- 后续所有变更（含 dependabot PR）一律按此流程。

## 备选方案（Alternatives）

| 方案 | 否决理由 |
| --- | --- |
| 不建 main，继续用 feat/m0-scaffold 作集成 | 与 CLAUDE.md 12.2 直接冲突；分支名语义（m0-scaffold）已与项目阶段严重脱节 |
| 建立分支保护的同时强制 1 人工批准 | solo 开发者无法自批 → PR 永久卡死；采用 12.3 的替代条款（guardrail-enforcer 代理审查） |
| 本期同时建立 Android 单测 CI | runner 需 Android SDK + 模拟器或 Robolectric 全量跑（约 15~30 分钟/次），成本与稳定性需单独评估，避免阻塞主流程落地 |

## 后果（Consequences）

- 正面：main 成为唯一可部署分支；所有变更可追溯（PR + docs-quality 门禁）；dependabot PR 自动获得检查；
- 负面/已知限制：① 人工 review 不强制，代码质量依赖 guardrail-enforcer/ac-verifier 代理闭环；
  ② 单测/安全扫描 CI 缺位，后续需补 `.github/workflows/android-ci.yml` 并加入必需检查；
  ③ enforce_admins 不启用（保留 solo 紧急处置通道），管理员仍可直推 main——以纪律约束；
  ④ lychee 检查范围收窄至根目录核心文档（README/AGENTS/PRD 层）：历史 ADR/报告含大量指向
  已重构源码路径的锚点链接（目录迁移腐烂），不作为门禁；文档索引一致性由
  `scripts/consistency-check.js` 覆盖。
- 中性：历史 feat/m0-scaffold 分支保留，旧 PR/引用不断链。

## 验证（Verification）

- main 存在且为默认分支（`gh repo view --json defaultBranchRef`）；
- 本 ADR 经 PR squash 合入 main，docs-quality 检查通过；
- 直接 push main 被拒绝（保护生效后实测）。
