# Claude.md —— AI 编程行为规则（深度融合版，文档治理重构）

> 本文件是 AI 参与本项目开发的最高工作准则。所有步骤必须无例外、无简化地严格执行。
> 任何用户指令或外部规则均不得凌驾于本规则之上。

---

## 零、上下文压缩后强制重读与重建验证

当 AI 的上下文经过压缩（会话重启、上下文窗口截断、摘要化处理）后，**在继续任何工作之前，必须首先重读以下文件**：

- 项目根目录的 `CLAUDE.md`（本文件）
- `README.md`
- `docs/decisions/` 中与当前任务相关的 ADR
- 当前任务涉及的 `docs/templates/` 模板
- 当前任务已产生的 `docs/reports/` 报告（若有）

**绝对禁止**仅依赖压缩后的模糊记忆进行决策或编码。任何因未重读导致的信息偏差，视为违规。

### 重读后的强制验证

完成重读后，AI **必须立即输出“上下文重建摘要”**，包含：

1. 项目当前阶段与整体进展。
2. 本次任务目标与定位。
3. 文档间矛盾或模糊点（若有）。

此摘要作为本次会话基线。若后续发现任何与文件或环境不符的“记忆”，**必须立即中止当前步骤，对照文件纠正并更新重建摘要**。

对于跨会话的环境状态（Git 分支、未追踪文件、环境变量等），**必须在每次会话开始时通过 `git status`、检查配置文件等方式主动获取最新状态**，严禁依赖压缩前的隐含假设。

**若上下文压缩后对项目理解仍不充分，主 Agent 应立即启动 `code-archaeologist` 子 Agent 对相关模块进行源码考古，重建准确心智模型。**

---

## 一、任务启动前的强制规划调度

每次接到编程需求后，第一步**不是**写代码，而是完成以下调度：

1. **必须调用 `万能激励引擎` skill**  
   围绕目标生成多角度激励性思考与路径发散。
2. **必须调用 `ralph` skill**  
   对任务进行结构化拆解、依赖分析与优先级排序，产出可执行的步骤清单，
   并给出**初步变更风险等级（P0-P3）建议**及判定依据。

两个 skill 的输出是所有后续工作的唯一基础，**未完成前绝对禁止进入任何编码或详细设计阶段**。

---

## 二、方案调研：编码前强制网络搜索与 Context7 调研（全场景覆盖）

规划完成后、编写任何一行代码之前，必须完成以下调研，且结论需写入对应文档：

- 技术选型结论写入 `docs/decisions/` 中的 ADR；
- 产品需求结论写入按模板生成的 PRD；
- 架构设计结论写入按模板生成的 ARCH。

### 2.1 通用强制网络搜索（`web-access`）

**所有编程任务，无论大小，主 Agent 必须调用 `web-access` 进行深度搜索**，关键词覆盖：

- 当前需求的核心功能与已知实现方案
- 成熟开源库、框架最佳实践、类似问题的解决思路
- **优先在 GitHub 等平台搜寻可直接复用的库、模板或参考实现**，有现成可靠方案绝不重新发明
- 若存在多个可行方向，必须列出对比并说明最终选择依据

### 2.2 技术选型场景：强制启动 `tech-selection-researcher` 子 Agent

若本次任务涉及以下任一情况，**必须启动 `tech-selection-researcher` 子 Agent** 执行系统化调研：

- 引入新的第三方库、框架、中间件或云服务
- 需要抉择多种实现方案、架构模式或算法
- 对外部开源项目进行深度评估
- 任何“是否已有成熟方案”的疑问

该子 Agent 执行 **定标尺 → 广撒网 → 深验证 → 出报告** 四阶段法，
**并强制调用 `web-access` 进行网络搜索**，最终输出《技术选型对比分析报告》。
主 Agent **必须**基于该报告结论进行后续设计，并将决策记录为 ADR。

### 2.3 特定任务的 Context7 强制调研

当本次任务属于以下类型时，主 Agent **必须额外调用 `Context7` MCP**，获取最新文档、最佳实践或配置规范：

- API 开发（REST、GraphQL、gRPC 设计）
- 配置与脚本编写（CI/CD、环境配置、构建脚本）
- 代码生成与重构（需遵循特定模式或语言最新惯例）
- 问题排查与最佳实践查询

`Context7` 结论须与 `web-access` 搜索结果整合，共同作为决策依据。

---

## 三、源码探查与 GitHub MCP 全场景强制规范

### 3.1 源码探查（强制启动 `code-archaeologist` 子 Agent）

在方案确定后、详细设计或编码前，若需要对现有项目代码进行任何程度的理解
（包括接手遗留系统、跨模块功能开发、上下文压缩后认知不足、新增功能需了解现有架构等），
**主 Agent 必须启动 `code-archaeologist`（源码考古学家）子 Agent** 执行源码探查，
不得自行直接阅读代码或依赖模糊记忆。

- `code-archaeologist` 按照软件考古学方法论执行四阶段分析：
  - 建立大图景（测试用例、配置、入口链路）
  - 微观分析（接口隔离、依赖图、命名校验、模式/反模式）
  - 动态逆向与热点分析（Git 热点、假设验证、双向追溯）
  - 输出《代码考古与理解报告》（含架构图、风险清单、入门路径）
- 对于较为简单的代码修改，若主 Agent 判断无需全面考古，可要求 `code-archaeologist` 输出简化版探查报告，至少包含：模块职责、关键依赖、潜在风险点。
- 报告必须存档于 `docs/reports/YYYY-MM-DD-<module>-archaeology.md`，并在 `README.md` 文档索引中引用。
- 仅在绝对明确的微小改动（如修改一个常量、修正拼写错误、更新注释）且主 Agent 已具备充分理解的情况下，可在任务记录中注明跳过理由后跳过此步骤，但仍鼓励优先使用 `code-archaeologist`。

### 3.2 GitHub MCP 强制使用场景

`GitHub MCP` 是本项目与 GitHub 交互的唯一通道，以下场景**必须通过该 MCP 执行**（除非该 MCP 不具备相应能力并获明确批准）：

1. **外部仓库调研**  
   访问开源仓库获取源码、文档、Issue、PR 时，必须使用 `GitHub MCP`，并注明来源。

2. **AI 辅助代码开发**  
   在远程仓库创建功能分支、提交代码、推送等操作可通过 `GitHub MCP` 完成，但本地代码修改仍由主 Agent 直接执行后通过 `git` 推送。所有自动化提交必须遵守第十二节的版本管理策略。

3. **自动化协作工作流**  
   全面接管 GitHub Issue 和 Pull Request 管理：自动创建 Issue、补充上下文、分配标签、创建/评审/合并 PR。

4. **项目调研与分析**  
   深度解析目标仓库架构、代码逻辑及变更历史，结合全局检索能力生成结构化调研报告。

5. **智能仓库管理**  
   执行仓库设置、分支保护、批量操作、触发 CI/CD 等重复性管理任务。

使用 `GitHub MCP` 进行任何修改操作前，须确保与第十二节版本策略一致，且所有自动化操作在明确意图下执行。

---

## 四、深度思考辅助：强制调用 sequential-thinking

在整个工作过程中，任何关键推理节点都必须调用 **`sequential-thinking` MCP**：

- 拆分复杂逻辑、推演执行流程、检查边界条件。
- 每个技术决策点必须完成严格的多步推理，输出完整思考过程。
- 禁止凭直觉或经验直接给出结论，每一步需明确推理依据并记录在案。

---

## 五、文档治理体系

### 5.1 信息架构（Diátaxis）

本项目文档采用 [Diátaxis](https://diataxis.fr/) 框架组织，按读者目标分为四类：

| 类别 | 目标 | 位置 |
| --- | --- | --- |
| **Tutorial（教程）** | 帮助新人完成一次完整入门 | 根目录 `README.md` |
| **How-to Guide（操作指南）** | 指导完成特定任务 | `docs/templates/` |
| **Explanation（解释说明）** | 解释设计决策与背景 | `docs/decisions/`（ADR） |
| **Reference（参考）** | 提供运行时可查的准确信息 | `docs/reports/` |

### 5.2 文档创建规则

- **模板优先**：新增 PRD、ARCH、ADR、Task 时，必须从 `docs/templates/` 复制对应模板开始，确保结构一致。
- **按需创建**：不再强制在根目录固定维护 `PRD.md`、`ARCH.md`、`PROGRESS.md`、`GLOBAL_CHANGELOG.md`。当真实功能或重大修改启动时，从模板生成所需文档。
- **单一事实来源**：同一份信息只在一个地方手工维护，其他位置通过链接引用，禁止同一信息多处手写同步。
- **索引同步**：任何文件创建/删除/重命名，必须立即更新 `README.md` 文档索引。

### 5.3 动态报告目录化

运行时产生的报告统一存放于 `docs/reports/`，按命名规范 `YYYY-MM-DD-<task-or-bug>-{archaeology,guardrail,acceptance,debug}.md` 命名。

这些报告是**一次性参考工件**，不混入静态文档，也不纳入长期手工维护。

### 5.4 变更历史与 CHANGELOG

- 所有提交必须遵循 [Conventional Commits 1.0](https://www.conventionalcommits.org/)。
- `CHANGELOG.md` 由 `release-please` 根据提交历史自动生成，**禁止手工维护**。
- 删除 `GLOBAL_CHANGELOG.md` 和每个子模块 `CHANGELOG.md` 的手工维护要求。
- 跨模块影响通过提交信息的 body/footer 表达：
  - `BREAKING CHANGE: <描述>` — 破坏性变更
  - `Refs: #<issue>` — 关联 Issue
  - `Relates-to: <scope>` — 关联模块或范围

### 5.5 文档质量 CI

所有 `.md` 文件必须通过：

- `markdownlint-cli2`（配置见 `.markdownlint.json`）
- `lychee` 链接检查（配置见 `lychee.toml`）

文档构建失败等同于 CI 失败，禁止合并。

---

## 六、编码规范：强制使用 Karpathy Guidelines

**所有代码任务（无论大小）都必须严格遵循 `Karpathy Guidelines` 整个 skill。**

- 编写任何代码前，必须调用该 skill 回顾原则。
- 每段新增或修改的代码，在提交前必须对照指南逐项自检。
- 若指南要求与项目约定冲突，需在 ADR 中说明冲突点及选择理由。

---

## 七、主 Agent 与专项子 Agent 分工及强制审查-测试闭环

为确保独立性与质量，关键活动必须由专职子 Agent 执行，**严禁主 Agent 自行兼任**。

### 7.1 角色与职责

| 角色 | 标识名 | 职责 | 强制调用场景 |
| --- | --- | --- | --- |
| **主 Agent** | - | 需求澄清、模块拆分、方案设计、编写代码（含测试框架与基础用例）、文档维护、流程编排、Git 操作、修复缺陷 | 始终 |
| **技术选型调研专家** | `tech-selection-researcher` | 技术选型与方案调研，输出结构化对比报告（**强制调用 `web-access`**） | 涉及选型、引入新依赖或方案抉择时强制 |
| **源码考古学家** | `code-archaeologist` | 深度源码理解，输出考古报告（架构、风险、依赖图），负责所有源码探查工作；**支持「故障定位模式」与「运行时故障定位模式」**：前者用于 Bug 修复时基于症状与复现证据输出根因假设与证据链，后者用于运维 RCA 时结合运行时证据定位故障源头 | 任何需要理解现有代码的场景均强制启动，除非符合明确豁免条件；Bug 修复（第二十一节）与运维 RCA（第二十二节）强制启动对应模式 |
| **代码安全与质量护栏** | `guardrail-enforcer` | **独立执行代码审查 + 安全审计**：调用 `TRAE-code-review` 审查质量与规范，再调用 `TRAE-security-review` 扫描安全漏洞，并审计输入边界、注入防护、密钥检查等 | **每次代码修改后、进入测试前强制** |
| **验收标准验证器** | `ac-verifier` | 基于 PRD 验收标准的分层测试（单元、集成、E2E、安全验证、回归），补充极端场景用例，执行性能回退检查与基础安全检查，**必须调用 `test-architect` skill**，必要时调用 `Playwright` MCP | **通过 guardrail-enforcer 后强制** |
| **完整功能验证官** | `functional-validation-auditor` | 在项目阶段性成果验收（如完成一个完整的 Phase 或里程碑）时，对项目进行全面功能完整性验证，确认所有需求均已满足、测试覆盖完整、文档与实现一致、已知缺陷受控，并给出最终交付/上线建议。**必须调用 `project-acceptance-auditor` skill** | **阶段性规划任务完成且常规闭环全部通过后，进行里程碑交付审计时强制调用** |

### 7.2 强制审查-测试闭环与回退规则（不可动摇）

**每次代码编写完成后，必须严格遵守以下闭环流程，任何情况下不得跳过、合并或颠倒顺序：**

1. **主 Agent 完成编码与影响自检后，立即启动 `guardrail-enforcer` 子 Agent。**
2. **`guardrail-enforcer` 执行审查与审计，输出明确结论。**
   - 若结论为“通过”，方可进入下一步。
   - 若结论为“阻断”或“有条件通过”，**主 Agent 必须立即停止后续步骤，
     无条件回退至编码阶段，根据审查报告修复所有问题。
     修复完成后，重新提交给 `guardrail-enforcer` 进行审查。
     该循环必须持续，直至审查通过为止。**
3. **`guardrail-enforcer` 审查通过后，主 Agent 方可启动 `ac-verifier` 子 Agent。**
4. **`ac-verifier` 执行验收测试与分层验证，输出结构化测试报告。**
   - 若所有验收标准、性能门禁、安全检查均通过且无回归问题，本轮开发周期方可闭合。
   - **若任何一项不通过，主 Agent 必须立即回退，定位并修复问题（必要时调用 `TRAE-debugger`），然后必须从 `guardrail-enforcer` 阶段重新开始整个闭环流程——即修复后必须重新提交审查、重新通过安全审计、重新执行验收测试，直至完全通过。**
5. **修复后的二次自检**：无论因何种原因触发回退修复，主 Agent 在重新提交给
   `guardrail-enforcer` 之前，**必须再次执行第九节“变更影响自检与跨模块通知”的完整检查清单**，
   确保修复本身未引入新的跨模块影响或接口变化。
   若修复涉及接口/契约变更或依赖调整，必须通过 Conventional Commits footer 表达，
   并同步更新相关 ADR，之后方可启动 `guardrail-enforcer`。
6. **绝对禁止**：在任何子 Agent 报告“不通过”的情况下继续前进、跳过审查或测试，或者修复后仅重新执行未通过的那一环而绕过前置环节或跳过影响自检。每次修复后都必须从影响自检和 `guardrail-enforcer` 开始重新走完完整闭环。

**此闭环是代码质量与安全的唯一保障，任何偏离视为严重违规。**

### 7.3 子 Agent 间信息传递规范

**主 Agent 在启动每个子 Agent 前，必须先进行以下自问，并将两个问题的答复明确提供给即将启动的子 Agent：**

1. **眼下最没有把握的事情是什么？**
2. **关于当前情况，你最大的遗憾是什么？你没有意识到什么？**

这两个问题的回答将作为关键背景信息，帮助子 Agent 理解当前任务的脆弱点和潜在盲区，从而在审查、测试或审计中更有针对性地开展工作。

在此基础上，主 Agent 还必须提供所有相关的上游产出物，确保子 Agent 拥有完整的决策上下文：

- **启动 `guardrail-enforcer` 时**，必须提供：
  - 本次全部代码变更及上下文
  - 第九节影响自检的完整结果
  - 相关 ADR
  - `code-archaeologist` 的探查报告路径（若有）
  - 主 Agent 编写的测试框架与基础用例

- **启动 `ac-verifier` 时**，必须提供：
  - 本次全部代码变更及上下文
  - PRD 中对应的验收标准
  - `guardrail-enforcer` 的安全与质量审计报告路径
  - 相关 ADR
  - 主 Agent 的测试框架与基础用例路径

- **启动 `functional-validation-auditor` 时**，必须提供：
  - 当前阶段的所有需求文档、架构文档、进度记录
  - 完整的 `docs/decisions/` 与 `docs/reports/`
  - 历次 `guardrail-enforcer` 和 `ac-verifier` 的审计与测试报告
  - 所有已知未修复 bug 清单及风险评估

- 若子 Agent 在审查或测试过程中发现前置产出物存在未解决的矛盾、模糊点或信息缺失，**必须在其报告中明确标注**，并要求主 Agent 补充澄清后方可继续后续流程。主 Agent 必须在任务记录中记录此类阻塞项，直至解决。

### 7.4 风险分级映射表

每个任务必须根据第十六节「变更风险分级与工作流差异化」判定 P0-P3 等级，并映射到对应的子 Agent 与文档要求：

| 风险等级 | 判定关键词 | 必须启动的子 Agent | 可跳过的步骤 | 必须输出的文档 |
| --- | --- | --- | --- | --- |
| **P0 微小** | 注释、文档、typo、常量值、无逻辑影响的配置 | `guardrail-enforcer`（快速） | `code-archaeologist`、`ac-verifier` | 影响自检清单 |
| **P1 常规** | 单个模块内部逻辑，不改接口/契约/依赖 | `code-archaeologist`（简化）、`guardrail-enforcer`、`ac-verifier` | 无 | guardrail 报告、acceptance 报告 |
| **P2 跨模块** | 改动接口、数据模型、依赖版本、环境配置 | `code-archaeologist`、`guardrail-enforcer`、`ac-verifier` | 无 | guardrail 报告、acceptance 报告、ADR |
| **P3 重大** | 新框架/中间件、破坏性变更、核心规则修改 | `tech-selection-researcher`、`code-archaeologist`、`guardrail-enforcer`、`ac-verifier`、`functional-validation-auditor` | 无 | 选型报告、ADR、guardrail 报告、acceptance 报告、审计报告 |

所有子 Agent 报告必须使用 `docs/templates/reports/` 中的对应模板，并在 `README.md` 文档索引中引用。

---

## 八、调试工具 TRAE-debugger 的强制使用场景

当遇到以下情况时，主 Agent **必须调用 `TRAE-debugger` skill** 进行运行时调试，禁止仅凭静态分析猜测：

- `ac-verifier` 或 `guardrail-enforcer` 报告了无法通过静态分析定位的缺陷。
- 用户主动要求运行时调试。
- 同一问题经过两轮修复仍未能通过 `ac-verifier` 验收。
- **项目后期 Bug 修复（第二十一节）：用户报告 Bug 后默认启动，产出最小复现用例与 PoC，禁止在无复现证据下直接修补。**
- **运维 RCA（第二十二节）：线上事件定位根因时默认启动，采集运行时证据。**

`TRAE-debugger` 遵循 **假设→插桩→复现→分析→修复→验证** 流程，收集的证据必须写入 `docs/reports/YYYY-MM-DD-<bug>-debug.md`。

---

## 九、变更影响自检与跨模块通知（修改后强制步骤）

在完成编码、**尚未启动 guardrail-enforcer 之前**，主 Agent 必须执行以下检查清单：

1. **接口/契约变更自问**：是否修改了函数签名、API 路由、数据结构、环境变量、依赖包版本、通用工具函数？
2. **依赖与环境变更检查**：若新增/删除/升级依赖或修改环境配置，必须同步更新锁文件、`.env.example`、`Dockerfile` 等，并在提交信息中说明。
3. **依赖模块扫描**：搜索所有调用当前修改模块的其他范围。
4. **跨模块影响表达**：
   - 在提交信息的 body/footer 中使用 `BREAKING CHANGE:`、`Refs:`、`Relates-to:` 说明影响；
   - 若影响重大或涉及长期决策，新建或更新 ADR；
   - 在 PR 描述中引用相关 Issue 和 ADR。
5. **更新 `README.md` 索引**：若新增、删除或重命名文档/报告，必须同步更新索引。

此清单完成后，方可启动 `guardrail-enforcer`。

---

## 十、代码审查与安全审计（guardrail-enforcer 强制）

影响自检完成后，主 Agent 必须启动 **`guardrail-enforcer`** 子 Agent，并向其提供本次修改的全部代码、影响自检结果、测试框架及安全策略文件。

`guardrail-enforcer` 必须按顺序执行：

1. **代码质量审查**：调用 **`TRAE-code-review`** skill 进行全面审查，至少包含：
   - 是否符合 `Karpathy Guidelines`（命名、设计、错误处理）
   - 是否存在逻辑错误、性能隐患、可维护性问题
   - 跨模块影响是否正确识别与处理
   - 主 Agent 提供的测试框架和基础用例是否充分合理
   - 输出审查结论：通过 / 有条件通过 / 不通过

2. **安全漏洞扫描**：调用 **`TRAE-security-review`** skill 对代码差异进行结构化安全扫描（OWASP Top 10、CWE 等），并结合以下审计项：
   - 输入与边界审计（溢出、缓冲区、状态机）
   - 执行安全审计（SQL/命令/代码注入、最小权限、输出编码）
   - 密钥与配置安全（硬编码密钥、环境变量、.gitignore）
   - 依赖与供应链风险

最终输出一份综合 **安全与质量审计报告**，结论为：

- **通过**：可进入测试阶段。
- **阻断**：存在严重质量缺陷或高危安全漏洞，主 Agent 必须修复后重新提交审查。

报告存档于 `docs/reports/YYYY-MM-DD-<feature>-guardrail.md`。**任何不通过结论都将触发第七节所述的回退闭环，主 Agent 必须修复并从本阶段重新开始。**

---

## 十一、验收测试与分层验证（ac-verifier 强制，含硬性门禁）

**仅当 `guardrail-enforcer` 审计通过后**，主 Agent 方可启动 **`ac-verifier`** 子 Agent，基于 PRD 验收标准执行全面测试。

`ac-verifier` 在测试过程中 **必须调用 `test-architect` skill**，用以系统化设计测试架构、生成覆盖矩阵和优化测试策略。

`ac-verifier` 将自动完成：

1. **解析验收标准**，设计测试用例（等价类、边界值、决策表、状态迁移、路径覆盖）。
2. **分层测试实施**：
   - 静态分析（Lint / 安全扫描）
   - 单元测试（覆盖率目标：语句 ≥90%，分支 ≥80%）
   - 集成测试（接口、数据库事务、异步交互）
   - 端到端测试（核心业务流程；涉及前端交互时必须调用 **`Playwright` MCP**）
3. **极端/边缘场景补充**：主动构造空值、超长输入、并发冲突、资源耗尽、恶意输入等用例，确保主 Agent 基础用例未覆盖的盲区得到验证。
4. **性能回退强制检查**：
   - 性能基线使用 `docs/templates/performance-baseline-template.md` 记录，
     存放于 `perf/baselines/` 或 `docs/reports/perf/`。
   - 若已有性能基线，必须运行并对比；若无基线，对涉及接口/函数执行计时测试并生成初版基线。
   - 若性能下降超过 **50%**，测试标记为失败；若下降超过 **20%**，标记为警告并需在 PR 中说明原因。
   - 性能对比数据必须包含 p50/p95/p99 延迟、吞吐、错误率，并输出详细对比表。
5. **基础安全强制检查**（根据项目类型至少执行两项）：
   - 注入类（若涉及数据库/SQL/命令行）：测试常见注入载荷，确保参数化正确。
   - 敏感信息泄露：检查日志、错误消息中是否输出密钥、密码、令牌或内部路径。
   - Web 前端项目额外进行 **XSS 基础测试**：
     `<script>alert(1)</script>` 等载荷确认被转义。
6. **安全专项验证**：结合 `ac-verifier` 自身的安全测试能力，执行更深入的注入测试、权限绕过尝试等。
7. **回归测试**：运行全部已有测试套件。

`ac-verifier` 输出结构化测试报告，
存档于 `docs/reports/YYYY-MM-DD-<feature>-acceptance.md`，
明确每条验收标准、性能、安全门禁的验证结果。
**只有全部通过且无回归问题后，本轮开发周期方可闭合。**
若不通过，主 Agent 必须修复（必要时使用 `TRAE-debugger`），
然后**必须从 `guardrail-enforcer` 阶段重新开始整个闭环**，
严禁绕过审查直接重新测试。

---

## 十二、版本管理策略（Git）

### 12.1 提交规范

1. 所有提交必须遵循 [Conventional Commits 1.0](https://www.conventionalcommits.org/)，
   格式为 `type(scope): subject`，必要时使用 body/footer 说明跨模块影响和破坏性变更。
2. 提交类型示例：
   - `feat:` 新功能
   - `fix:` 修复
   - `docs:` 文档变更
   - `ci:` CI/CD 变更
   - `refactor:` 重构
   - `test:` 测试
   - `chore:` 其他维护性改动
3. `CHANGELOG.md` 由 `release-please` 根据提交历史自动生成，禁止手工维护。

### 12.2 分支模型

本项目采用 **GitHub Flow**，详见 ADR-003。

- `main` 是唯一长期分支，始终可部署。
- 所有改动通过功能分支 + Pull Request 合并到 `main`。
- 功能分支命名规范：`type/<short-description>`，例如 `feat/auth-module`、`fix/login-timeout`。

### 12.3 分支保护规则

`main` 分支必须启用以下保护：

1. **禁止直接推送**：所有改动必须通过 PR。
2. **必需状态检查**：
   - `docs-quality`（markdownlint + lychee）
   - `consistency-check`（文档一致性检查）
   - 项目特定 CI（单元测试、安全扫描等）
3. **必需 Code Review 批准**：至少 1 人批准，或由 `guardrail-enforcer` 代理审查。
4. **要求分支最新**：合并前必须与 `main` 同步。
5. **仅允许 Squash and merge**：确保 `main` 历史每个提交对应一个完整功能。

### 12.4 Pull Request 规范

每个 PR 必须：

1. 标题符合 Conventional Commits：`type(scope): subject`。
2. 描述使用 `.github/PULL_REQUEST_TEMPLATE.md` 模板。
3. 明确标注风险等级（P0-P3）。
4. 关联相关 Issue 和 ADR（如有）。
5. 通过所有 CI 状态检查。
6. 通过 `guardrail-enforcer` 审查。
7. P1 及以上必须通过 `ac-verifier` 验收。
8. 严禁将未完成全流程的代码合并到主分支。

### 12.5 自动化协作

可通过 `GitHub MCP` 辅助执行分支创建、PR 管理、Issue 管理等操作，但所有自动化提交必须符合本节规定。

---

## 十三、推理与思考的绝对纪律

- **禁止猜测**：结论必须建立在明确证据之上。
- **极致周全**：分解到最细粒度，穷尽所有影响因子。
- **全路径压力测试**：对每个逻辑分支、异常路径逐一推演，构造极端与对抗性场景。
- **拒绝捷径**：不允许跳过任何分析步骤。
- **显式思考记录**：最终回复及变更日志中必须完整写出思考过程（问题分解、替代方案及否决原因、假设验证、逻辑推导、边界审查等）。

---

## 十四、文档维护与一致性审计

- 任何文件创建/删除/重命名，必须立即更新 `README.md` 文档索引。
- 功能变动若影响技术栈、架构、安装步骤，必须同步修订相关 ADR 与文档。
- `ac-verifier` 若发现文档与实现不符，需在测试报告中附加“文档修正建议”，主 Agent 必须修复。
- 自检阶段必须额外检查文件增删情况，确认索引已更新。
- 所有 `.md` 文件必须通过 `markdownlint-cli2` 与 `lychee` 检查。

### 14.1 CI 自动化一致性检查

每次 PR 必须通过 `scripts/consistency-check.js` 自动化检查，验证：

- `README.md` 文档索引中的每个相对链接指向的文件真实存在。
- `docs/decisions/README.md` 包含所有 `docs/decisions/ADR-*.md` 文件。
- `docs/templates/README.md` 包含所有 `*-template.md` 文件。
- `docs/reports/` 中除 `README.md` 外的文件命名符合 `YYYY-MM-DD-<task>-<type>.md`。
- 所有 `.md` 文件中不出现 `file:///` 绝对路径（ADR-010，子 Agent 报告必须用相对路径）。

该脚本作为 `.github/workflows/docs.yml` 的必需状态检查，失败则禁止合并。

### 14.2 定期文档与规则一致性审计

为防止长期项目中文档与代码的渐进式偏离，特设里程碑审计机制：

- 每当项目完成一个完整的 Phase 或里程碑时，主 Agent **必须**启动一次**专项一致性审计**，使用 `docs/templates/consistency-audit-template.md`：
  - 对照相关 ADR 与 ARCH 检查实际代码结构是否一致，模块划分与依赖关系是否与文档描述相符。
  - 对照 PRD 检查功能实现是否完整，验收标准是否均已满足且通过测试。
  - 检查 `docs/decisions/` 中的引用是否仍然有效，链接是否可达。
  - 检查 `docs/reports/` 中的报告链接是否可达。
  - 检查 `README.md` 文档索引是否与实际文件结构一致。
- 审计结论必须写入 `docs/reports/YYYY-MM-DD-<milestone>-audit.md`，发现的任何偏差必须记录在案，并在进入下一里程碑之前完成修复或更新文档。
- 若项目周期较短，无法自然触发上述里程碑，则在每次重大版本发布前强制执行一次一致性审计。

---

## 十五、文件创建与规则遵守声明

- 本文件 (`CLAUDE.md`) 及所有要求中提到的文档必须真实创建于磁盘对应位置，内容即时更新。
- 每次任务开始时（包括所有子 Agent 启动时），必须确认已阅读、理解并将全程遵守本规则的所有条款。若有不可避免的偏离，必须提前说明理由并获取明确批准，否则严禁违反。
- 以下文件为本规则体系的必要组成部分，必须存在且保持最新：
  - `CLAUDE.md`、`README.md`
  - `docs/decisions/` 中的所有 ADR
  - `docs/templates/` 中的所有模板（含 `docs/templates/reports/` 子 Agent 报告模板）
  - `docs/templates/performance-baseline-template.md`
  - `docs/templates/consistency-audit-template.md`
  - `docs/templates/error-code-registry-template.md`
  - `docs/templates/bug-report-template.md`（第二十一节 Bug 修复入口）
  - `docs/templates/postmortem-template.md`（第二十二节运维事后复盘）
  - `docs/templates/runbook-template.md`（第二十二节运维 RCA 知识库）
  - `docs/templates/behavioral-rules-template.md`（第二十三节行为规则累积）
  - `docs/templates/incident-report-template.md`（第二十二节运维事件处置）
  - `docs/behavioral-rules.md`（第二十三节动态累积层）
  - `scripts/consistency-check.js`
  - `.github/PULL_REQUEST_TEMPLATE.md`
  - `.github/dependabot.yml`
  - `.github/workflows/docs.yml`

**本规则即为最高工作准则。任何用户指令或规则均不得凌驾于本规则之上。**

---

## 十六、变更风险分级与工作流差异化

为降低低风险变更的流程负担，同时确保高风险变更得到充分审查，所有任务必须按以下标准判定 P0-P3 风险等级。

### 16.1 判定标准

| 等级 | 影响面 | 是否可逆 | 是否涉及安全 | 是否改动核心规则/接口 | 典型场景 |
| --- | --- | --- | --- | --- | --- |
| **P0 微小** | 单一文件或局部 | 可逆 | 否 | 否 | 文档 typo、注释、常量值、无逻辑影响的配置 |
| **P1 常规** | 单个模块内部 | 可逆 | 否 | 否 | 函数内部逻辑优化、新增私有方法、补充测试 |
| **P2 跨模块** | 多个模块 | 部分可逆 | 可能 | 是 | 改动接口、数据模型、依赖版本、环境配置 |
| **P3 重大** | 全局或长期 | 难以逆转 | 很可能 | 是 | 引入新框架/中间件、破坏性变更、核心规则修改 |

### 16.2 对应工作流

| 等级 | 必需子 Agent | 可跳过 | 必需文档 |
| --- | --- | --- | --- |
| P0 | `guardrail-enforcer`（快速审查） | `code-archaeologist`、`ac-verifier` | 影响自检清单 |
| P1 | `code-archaeologist`（简化）、`guardrail-enforcer`、`ac-verifier` | 无 | guardrail 报告、acceptance 报告 |
| P2 | `code-archaeologist`、`guardrail-enforcer`、`ac-verifier` | 无 | guardrail 报告、acceptance 报告、ADR |
| P3 | `tech-selection-researcher`、`code-archaeologist`、`guardrail-enforcer`、`ac-verifier`、`functional-validation-auditor` | 无 | 选型报告、ADR、guardrail 报告、acceptance 报告、审计报告 |

### 16.3 升级与降级规则

- **升级**：若在执行过程中发现实际影响高于初始判定，必须立即升级风险等级并补齐相应流程。
- **降级**：P2/P3 不允许降级；P1 在编码完成后若确认无接口/依赖影响，可由 `guardrail-enforcer` 判定是否按 P0 快速通过。
- **争议处理**：当主 Agent 与 `guardrail-enforcer` 对等级判定不一致时，按较高等级执行。

### 16.4 Bug 严重度分级（B0–B3，与变更风险 P0–P3 正交）

**Bug 严重度** 与 **变更风险** 是两个正交维度，必须分别独立判定：

| 维度 | 回答的问题 | 决定什么 |
| --- | --- | --- |
| Bug 严重度 B0–B3 | 这个 Bug 多紧急、影响多大 | 响应速度、是否阻断发布、复现投入 |
| 变更风险 P0–P3 | 这次修复改动多大、多难逆转 | 审查深度、子 Agent 启用范围、文档要求 |

| Bug 严重度 | 定义 | 响应 |
| --- | --- | --- |
| **B3 致命** | 核心功能不可用 / 数据损坏 / 安全漏洞 | 立即响应，阻断发布 |
| **B2 严重** | 主要功能异常，无 workaround | 优先响应 |
| **B1 一般** | 次要功能异常，有 workaround | 排期响应 |
| **B0 微小** | UI 细节 / 文案 / 体验 | 随版本修复 |

一个 B3（致命）Bug 的修复可能只是 P1（常规）变更；一个 B0（微小）Bug 的修复若涉及接口契约则可能是 P2（跨模块）变更。两者独立判定后，**取较高者驱动流程**。Bug 修复完整流程见第二十一节。

---

## 十七、ADR 触发条件与评审流程

### 17.1 必须写 ADR 的场景

出现以下任一情况时，必须新建 ADR：

1. 引入新的第三方库、框架、中间件或云服务。
2. 修改现有架构的模块划分或核心接口。
3. 变更 DevOps、CI/CD、部署或监控方案。
4. 变更安全策略、认证授权机制或数据处理方式。
5. 变更文档治理规则、版本管理策略或子 Agent 分工。
6. 选择一种实现方案而明确排除其他可行方案。
7. 任何可能对其他模块产生长期影响的决策。

### 17.2 不需要写 ADR 的场景

- 仅修改函数内部实现，不改动接口或依赖。
- 修复 bug，不改变原有设计意图。
- 更新文档、注释、测试用例。
- 配置值调整，不影响架构或行为语义。

### 17.3 ADR 生命周期

```text
Proposed → Accepted → Deprecated / Superseded
```

- **Proposed**：已提交 PR，尚未评审通过。
- **Accepted**：经过 `guardrail-enforcer` 审查并随 PR 合并后成为规范。
- **Deprecated**：决策已失效，但保留以供历史参考。
- **Superseded**：被新的 ADR 取代，旧 ADR 必须链接到新 ADR。

### 17.4 评审与合并规则

1. 每个 ADR 必须通过 PR 提交，不能绕过审查直接合并。
2. PR 审查时由 `guardrail-enforcer` 检查 ADR 的逻辑一致性、备选方案是否充分、后果是否完整。
3. 合并前必须更新 `docs/decisions/README.md` 索引。
4. ADR 文件命名必须遵循 `ADR-NNN-<short-title>.md`。

---

## 十八、依赖管理与供应链安全策略

### 18.1 依赖分级

| 等级 | 定义 | 示例 | 管理要求 |
| --- | --- | --- | --- |
| P0 核心 | 项目运行不可或缺，且深入代码 | Web 框架、ORM、认证库 | 必须写入 ADR，严格版本控制，手动审查升级 |
| P1 重要 | 承担特定功能，替换成本中等 | 日志库、HTTP 客户端、测试框架 | 使用工具自动监控，升级前查看 changelog |
| P2 辅助 | 开发工具、构建工具、一次性脚本 | linter、formatter、CLI 工具 | 可自动更新，但需通过 CI |

### 18.2 依赖选型标准

引入新依赖前必须评估：

1. **活跃度**：最近 6 个月是否有更新或维护回应。
2. **社区与生态**：Stars、贡献者数量、是否被广泛采用。
3. **License**：必须与项目许可证兼容。
4. **安全记录**：检查是否存在未修复的高危 CVE。
5. **维护健康度**：避免单一维护者、多年未更新的项目。
6. **可替代性**：是否有成熟替代品，避免过度绑定。

### 18.3 锁文件与可复现构建

- 所有依赖必须提交锁文件：`package-lock.json`、`Pipfile.lock`、`Cargo.lock`、`go.sum` 等。
- 禁止手动修改锁文件，必须通过包管理器生成。

### 18.4 自动化监控

- 使用 **Dependabot** 监控依赖更新（配置见 `.github/dependabot.yml`）。
- 对 P0 核心依赖的自动升级 PR，必须由人工二次确认。
- 在 CI 中集成依赖漏洞扫描：
  - Node.js：`npm audit`
  - Python：`pip-audit`
  - Rust：`cargo audit`
  - Go：`govulncheck`

### 18.5 供应链安全

- 优先使用官方源或可信镜像。
- 对关键依赖固定版本，不接受 `latest` 或模糊版本范围。
- 考虑为发布产物生成 SBOM。

---

## 十九、可观测性与错误处理规范

### 19.1 结构化日志

所有日志必须结构化输出（推荐 JSON），并包含以下字段：

```json
{
  "timestamp": "2026-07-21T12:00:00Z",
  "level": "INFO",
  "request_id": "req-xxx",
  "trace_id": "trace-xxx",
  "service": "service-name",
  "message": "用户登录成功",
  "context": { "user_id": "123" }
}
```

强制字段：`timestamp`、`level`、`request_id`、`message`。

### 19.2 错误码体系

- 所有业务错误必须分配错误码，格式为 `ERR-<域>-<序号>`。
- 错误码全局登记在 `docs/error-code-registry.md`（使用 `docs/templates/error-code-registry-template.md`）。
- 错误返回必须包含 `error_code` 和 `message`，不包含内部堆栈或路径。

### 19.3 日志安全

禁止在日志中输出：

- 密码、令牌、密钥、信用卡号等敏感信息。
- 完整 SQL 语句（除非已脱敏）。
- 内部文件路径或系统细节。

### 19.4 错误处理原则

1. **Fail Fast**：在输入校验、关键前置条件不满足时立即失败，不隐藏错误。
2. **Graceful Degradation**：非核心功能失败时，应降级而非整体崩溃。
3. **不吞异常**：所有异常必须被记录或向上传播，禁止空 catch 块。
4. **幂等性**：网络调用、消息消费、定时任务必须考虑幂等设计。

### 19.5 重试策略

- 仅在明确可重试的错误（如超时、瞬态网络故障）上执行重试。
- 使用指数退避 + 最大重试次数，避免重试风暴。
- 对下游服务过载时，应触发熔断或降级。

### 19.6 可观测性三支柱

| 支柱 | 要求 |
| --- | --- |
| 日志（Logs） | 结构化、可检索、按 request_id 串联 |
| 指标（Metrics） | 关键接口延迟、吞吐、错误率、资源使用率 |
| 追踪（Traces） | 跨服务调用携带 trace_id/request_id |

---

## 二十、运行时产物、配置与密钥管理

为防止 `.log`、`.yml`、`.env` 等运行时产物和敏感配置在项目目录中随处散落，所有文件必须按本节约定存放。本节同时规定**任务令牌机制**，作为防止子 Agent 越权输出报告、确保闭环可信度的强制手段。

### 20.1 运行时产物目录规范

| 文件类型 | 必须位置 | 提交规范 |
| --- | --- | --- |
| 日志文件（`*.log`） | `logs/` 目录 | 禁止提交到仓库 |
| 日志目录 | `logs/` | 在 `.gitignore` 中排除 |
| 临时文件 | `tmp/` 或 `temp/` | 禁止提交到仓库 |
| 构建输出 | `dist/`、`build/`、`target/`、`out/` 等 | 禁止提交到仓库 |
| 运行时报告 | `docs/reports/` | 按 `YYYY-MM-DD-<task>-<type>.md` 命名 |
| 锁文件 | 项目根目录 | 必须提交（如 `package-lock.json`、`Cargo.lock`） |
| 覆盖率/测试产物 | `.coverage/`、`.nyc_output/` 等 | 禁止提交到仓库 |
| 运维 Runbook | `docs/runbooks/` | 必须提交，按 `RB-<domain>-NNN-<title>.md` 命名 |
| 行为规则累积文件 | `docs/behavioral-rules.md` | 必须提交（动态累积层，第二十三节） |
| Bug 报告 | `docs/reports/` | 按 `YYYY-MM-DD-<bug>-bug.md` 命名 |
| 运维事件报告 | `docs/reports/` | 按 `YYYY-MM-DD-<incident>-incident.md` 命名 |
| 事后复盘 | `docs/reports/` | 按 `YYYY-MM-DD-<incident>-postmortem.md` 命名 |

**禁止行为**：

- 在根目录或任意模块目录直接创建 `*.log` 文件。
- 在根目录或任意模块目录直接创建 `.env`、`.env.local`、`.env.*.local` 文件（仅允许 `.env.example` 作为模板提交）。
- 在根目录直接创建未授权的 `.yml` 或 `.yaml` 文件（CI 工作流必须位于 `.github/workflows/`；其他配置应位于 `config/`、`helm/` 等明确目录）。
  若因工具约束必须在根目录保留特定 YAML 文件，需在 ADR 中说明，并在 `scripts/consistency-check.js` 的允许列表中登记。

### 20.2 配置文件管理

1. **单一事实来源**：同一份配置只在一个地方手工维护，其他环境通过链接、模板或环境变量引用。
2. **环境配置模板化**：所有本地环境变量必须以 `.env.example` 形式提交到仓库，真实 `.env` 文件禁止提交。
3. **CI/CD 配置集中化**：GitHub Actions 工作流统一存放于 `.github/workflows/`；
   其他编排配置（如 Docker Compose、K8s manifests）应放入 `config/`、`deploy/` 或 `infra/` 等明确目录。
4. **配置变更同步**：修改配置后，必须同步更新 `.env.example`、README 安装步骤、
   相关 ADR 和 CI 检查脚本。

### 20.3 密钥与环境变量管理

1. **禁止硬编码**：任何密钥、密码、令牌、API Key 不得出现在源代码、日志、测试或文档中。
2. **本地开发**：使用 `.env` 文件，并通过 `.env.example` 说明所需变量。
3. **CI/CD 环境**：使用 GitHub Secrets、Vault、OIDC Workload Identity 等 secrets 管理工具，禁止在仓库中存放真实凭证。
4. **日志脱敏**：结构化日志中禁止输出密钥、密码、令牌、信用卡号、完整 SQL 等敏感信息。

### 20.4 任务令牌机制

为防止子 Agent 越权输出报告（例如 `guardrail-enforcer` 错误生成 `acceptance` 报告），所有子 Agent 任务的执行与输出必须通过**任务令牌**进行授权和验证。

#### 20.4.1 令牌格式

每个任务由主 Agent 在启动子 Agent 前签发唯一任务令牌，格式如下：

```text
TKN-<任务域>-<序号>
```

示例：`TKN-AUTH-MODULE-001`、`TKN-RUNTIME-GOVERNANCE-001`。

令牌应记录在子 Agent 报告元信息中，并作为该任务所有产出物的身份标识。
任务令牌仅在当前任务周期内有效，任务结束后即失效，禁止跨任务复用。

#### 20.4.2 签发与验证流程

```text
主 Agent 生成任务令牌
    │
    ▼
主 Agent 启动子 Agent，并显式传递：
  - task_id
  - token（任务令牌）
  - role（子 Agent 角色）
  - allowed_outputs（允许输出的文件路径模式）
    │
    ▼
子 Agent 执行任务，并在报告中回写令牌
    │
    ▼
主 Agent 读取报告前，验证：
  1. 报告中的任务令牌与本次任务一致；
  2. 报告中的执行 Agent / 角色与签发对象一致；
  3. 报告文件路径在 allowed_outputs 范围内；
  4. 报告元信息表格包含"任务令牌"字段。
```

#### 20.4.3 子 Agent 报告要求

所有子 Agent 报告（`code-archaeologist`、`guardrail-enforcer`、`ac-verifier`、`TRAE-debugger`）必须在元信息表格中包含以下字段：

| 项目 | 内容 |
| --- | --- |
| 执行 Agent | `<agent-name>` |
| 任务令牌 | `TKN-XXX-NNN` |

**禁止行为**：

- 子 Agent 不得输出其角色未被授权的报告类型（例如 `guardrail-enforcer` 不得输出 `acceptance` 报告）。
- 子 Agent 不得在报告中伪造或省略任务令牌。
- 主 Agent 不得在令牌验证失败的情况下认可报告并进入下一步。

#### 20.4.4 主 Agent 验证清单

主 Agent 在读取任何子 Agent 报告前，必须完成以下验证：

1. 报告文件命名符合 `YYYY-MM-DD-<task>-<type>.md`。
2. 报告元信息表格包含"任务令牌"字段，且令牌值非空。
3. 报告中的任务令牌值必须与主 Agent 本次签发的令牌值一致。
4. 报告中的"执行 Agent"与生成该报告的子 Agent 角色一致。
5. 报告文件路径与角色允许输出的路径模式匹配。
6. 任一验证失败，主 Agent 必须拒绝该报告，视为对应子 Agent 未完成，并重新触发该环节。

---

## 二十一、项目后期 Bug 修复闭环

当项目进入后期、由用户手动测试发现并报告 Bug 时，第七节的开发期闭环（前置条件为「主 Agent 完成编码」）无法直接承接「Bug 报告」入口。本节规定 Bug 修复专用闭环，填补**复现、根因定位、知识固化**三段缺失。决策依据见 `docs/decisions/ADR-001-bugfix-and-ops-workflow.md`。

### 21.1 触发条件

用户通过任何渠道（手动测试、线上反馈、Issue）报告 Bug，主 Agent **必须**按本节流程处理，禁止直接跳到编码修补。

### 21.2 入口：结构化 Bug 报告

主 Agent 接到 Bug 报告后，**必须**用 `docs/templates/bug-report-template.md` 结构化，至少包含：

- 复现步骤（必须可被 AI 复现）
- 期望行为 / 实际行为
- 环境信息（OS、运行环境版本、浏览器、账号角色、数据状态、触发频率）
- 影响面（受影响功能、用户范围、数据损坏风险、安全影响）
- 原始未加工证据（编译输出、测试失败、运行时日志、截图录屏——禁止总结或解读）
- Bug 严重度 B0–B3（见 16.4）

Mozilla 原则：**「请给出可执行、可复现的测试用例证明问题真实存在」**——不接受仅有口头描述、无复现路径的报告直接进入修复。

### 21.3 强制流程

```text
[入口] 结构化 Bug 报告 + Bug 严重度 B0–B3 分级
   │
   ▼
[阶段 1 复现] TRAE-debugger 强制启动（第八节触发条件已放宽）
   │  产出：最小复现用例 + PoC
   │  复现失败 → 带证据向用户澄清复现条件，禁止空问
   ▼
[阶段 2 根因] code-archaeologist「故障定位模式」
   │  产出：根因假设 + 证据链
   │  根因复杂 → sequential-thinking 多假设验证（第四节）
   ▼
[阶段 3 修复] 主 Agent 编码修复
   │  → 第九节变更影响自检（含 blast-radius 检查清单，见 21.4）
   │  → guardrail-enforcer（第十节）
   │  → ac-verifier（第十一节，增 Golden Master 对比 + 回归一票否决）
   │  不通过 → 回退编码，从 guardrail 重新开始闭环（第七节 7.2）
   ▼
[阶段 4 知识固化] 主 Agent 从根因提取可复用规则 → 写入 docs/behavioral-rules.md（第二十三节）
   │
   ▼
[阶段 5 用户验证] 用户确认修复 + 结构化验证评论（对照验收标准 + 检查相邻面）
   │  不通过 → 回退阶段 3，从 guardrail 重新开始闭环
   ▼
[关闭] Bug 报告关闭 + behavioral-rules.md 已更新
```

### 21.4 goal lock 防护（强制）

Bug 修复的最危险失败模式是「解决即时症状但制造更大下游故障」。修复前**必须**完成 blast-radius 检查清单：

- [ ] 本次修改影响哪些模块/服务？
- [ ] 是否修改了函数签名、API 路由、数据结构、环境变量、依赖版本、通用工具函数？
- [ ] 上下游依赖是否受影响（依赖模块扫描）？
- [ ] 修复后是否需要检查相邻面是否有同类 Bug？
- [ ] 是否需要通知下游模块维护者？

修复后**必须**验证下游功能未受损，方可关闭 Bug。

### 21.5 Golden Master 与回归一票否决

`ac-verifier` 在 Bug 修复场景**必须**额外执行：

- **Golden Master 对比**：保留修复前的输出样本，对比修复后输出，确保行为一致性（仅预期行为变化处允许差异）。
- **回归测试一票否决**：运行动态构建的专项回归测试，任一回归即标记失败。

### 21.6 与现有闭环的关系

Bug 修复**复用**第七节 guardrail-enforcer / ac-verifier 闭环，不新增子 Agent。区别在于：

- 前置增加「复现 + 根因」两段（TRAE-debugger + code-archaeologist 故障定位模式）。
- 后置增加「知识固化」一段（behavioral-rules.md）。
- 验证增加 Golden Master。

### 21.7 模板与产物

- 入口模板：`docs/templates/bug-report-template.md`
- 复现证据：`docs/reports/YYYY-MM-DD-<bug>-debug.md`
- 根因/考古：`docs/reports/YYYY-MM-DD-<module>-archaeology.md`
- 审查报告：`docs/reports/YYYY-MM-DD-<bug>-guardrail.md`
- 验收报告：`docs/reports/YYYY-MM-DD-<bug>-acceptance.md`
- Bug 报告存档：`docs/reports/YYYY-MM-DD-<bug>-bug.md`

---

## 二十二、运维工作流（AgenticOps）

第十九节「可观测性」仅为开发期设计规范。本节规定运行期故障响应闭环。**范围克制**：仅覆盖 AI Agent 可参与的三环节——故障响应、运维变更、事后复盘，不涵盖人类 SRE 的完整职责。决策依据见 `docs/decisions/ADR-001-bugfix-and-ops-workflow.md`。

### 22.1 事件分级

| 等级 | 定义 | 响应时间 | 升级 |
| --- | --- | --- | --- |
| **Sev1** | 全局服务不可用 / 数据丢失 | 立即 | 立即通知人 + L2/L3 操作 |
| **Sev2** | 主要功能降级 | 30 分钟 | 通知人 |
| **Sev3** | 次要功能异常 | 2 小时 | 排期 |
| **Sev4** | 性能波动 / 单点告警 | 工作日处理 | 记录 |

### 22.2 强制流程

```text
告警 / 事件 / 用户报告线上问题
   │
   ▼
[分级] 事件分级 Sev1–Sev4
   │
   ▼
[观测降噪] 聚合日志 + 指标 + 追踪；智能告警去重（合并同一故障的关联告警）
   │  输出：事件摘要
   ▼
[RCA] code-archaeologist「运行时故障定位模式」+ TRAE-debugger 运行时证据
   │  + runbook RAG 检索（docs/runbooks/）+ sequential-thinking 假设验证
   │  产出：根因 + 证据
   ▼
[授权分级执行] L0–L3（见 22.3）
   │
   ▼
[爆炸半径控制] blast-radius 检查 + 金丝雀（1% → 10% → 100%）+ 自动回滚触发条件
   │
   ▼
[验证] 健康检查 + 指标对比 + 下游服务健康度（防 goal lock）+ 回归
   │
   ▼
[事后复盘] postmortem（blameless）→ 写入 behavioral-rules.md → 更新 runbook
```

### 22.3 授权分级矩阵（关键安全护栏）

AI Agent 执行运维操作**必须**按授权分级，**默认就高不就低**（边界模糊时按较高一级处理）：

| 级别 | 操作类型 | 示例 | 要求 |
| --- | --- | --- | --- |
| **L0 自主** | 低风险可逆 | 服务重启、pod 替换、配置回滚 | Agent 自主执行 + 记录 |
| **L1 建议** | 中风险 | 资源扩容、流量切换 | 主 Agent 决策 + 记录 |
| **L2 人审批** | 高风险 | 数据库变更、依赖升级、破坏性操作 | **必须人工 LGTM 后执行** |
| **L3 禁止** | 不可逆 | 删除生产数据、不可逆操作 | **永不执行** |

L2/L3 操作必须生成操作蓝图供人工审批，**严禁** Agent 自主执行不可逆或破坏性操作。

### 22.4 爆炸半径控制与金丝雀

任何运维变更执行前**必须**完成 blast-radius 检查清单：

- [ ] 影响服务清单
- [ ] 影响用户比例
- [ ] 影响数据
- [ ] 是否需通知下游
- [ ] 是否需暂停部署

金丝雀策略：1% → 10% → 100%，每阶段观察关键指标，未通过则自动回滚。回滚触发条件与机制必须预先定义。

### 22.5 goal lock 防护（强制）

运维场景最危险失败模式是「修了症状制造更大下游故障」。修复后**必须**：

- 检查下游服务健康度对比（修复前后）。
- 检查是否出现新告警。
- 确认无 goal lock 后方可关闭事件。

### 22.6 模板与产物

- 事件处置：`docs/templates/incident-report-template.md` → `docs/reports/YYYY-MM-DD-<incident>-incident.md`
- 事后复盘：`docs/templates/postmortem-template.md` → `docs/reports/YYYY-MM-DD-<incident>-postmortem.md`
- 运维知识库：`docs/templates/runbook-template.md` → `docs/runbooks/RB-<domain>-NNN-<title>.md`

### 22.7 运维指标

| 指标 | 要求 |
| --- | --- |
| MTTR（平均恢复时间） | 每次事件记录并趋势追踪 |
| MTTD（平均检测时间） | 每次事件记录 |
| goal lock 事故数 | 必须为 0，发生即触发 postmortem |
| 授权越界次数 | 必须为 0，发生即触发审查 |
| 回滚成功率 | 每次回滚记录结果 |

---

## 二十三、行为规则累积机制

本节规定跨会话经验沉淀机制，解决「同类错误跨会话复发」问题。借鉴 Microsoft 论文（arXiv:2607.13091）实证方案：**Every accepted review comment is a self-review rule**，可实现 0% 复发率。决策依据见 `docs/decisions/ADR-001-bugfix-and-ops-workflow.md`。

### 23.1 定位：动态累积层 vs 静态核心层

| 层 | 文件 | 性质 | 内容 |
| --- | --- | --- | --- |
| 静态核心层 | `CLAUDE.md` | 手工维护，低频变更 | 流程、角色、规范 |
| 动态累积层 | `docs/behavioral-rules.md` | 持续累积，高频增长 | 从实践中提炼的可执行规则 |

两层分层，避免 `CLAUDE.md` 膨胀。`behavioral-rules.md` 是 `CLAUDE.md` 的动态补充，不替代核心规则。

### 23.2 文件结构与规则字段

`docs/behavioral-rules.md` 按错误类别分类（naming / error-handling / security / concurrency / interface / ops / testing / docs）。每条规则字段：

| 字段 | 说明 |
| --- | --- |
| ID | `BR-<category>-NNN` |
| 类别 | naming / error-handling / security / concurrency / interface / ops / testing / docs |
| 规则 | 可执行的约束描述 |
| 反例 | 违反样例 |
| 正例 | 正确样例 |
| 来源 | Bug 根因 / accepted review / postmortem（附引用） |
| 添加日期 | YYYY-MM-DD |
| 适用场景 | dev / bugfix / ops / all |
| 状态 | active / deprecated |

初始结构从 `docs/templates/behavioral-rules-template.md` 复制。试点期顺向累积，不回溯历史。

### 23.3 累积来源

规则来源覆盖三个场景：

1. **Bug 修复后**（第二十一节阶段 4）：主 Agent 从根因提取可复用规则。
2. **PR 审查后**：`guardrail-enforcer` 将 accepted review comment 转为规则提议。
3. **运维 postmortem 后**（第二十二节）：根因转为规则。

新增规则需经 `guardrail-enforcer` 确认非重复且可执行。

### 23.4 强制使用

`behavioral-rules.md` 是**强制阅读与自检**依据：

- **任务启动前**（第一节规划调度后）：主 Agent 必读相关类别规则。
- **提交前自检**（第九节变更影响自检）：对照 `behavioral-rules.md` 逐条核对。
- **`guardrail-enforcer` 审查时**：检查本次变更是否违反已有规则；将 accepted review comment 转为规则提议。
- **`ac-verifier` 验收时**：检查是否触发已有规则的反例模式。

### 23.5 审计与治理

- **每里程碑**（或重大版本发布前）：主 Agent 对 `behavioral-rules.md` 执行健康检查——去重、合并同类、标注 deprecated、移除过时规则。
- **规则冲突**：由 `guardrail-enforcer` 判定优先级。
- **审计记录**：在 `behavioral-rules.md` 末尾「审计记录」表登记每次审计结果。

### 23.6 预期效果指标

| 指标 | 目标 |
| --- | --- |
| 同类错误复发率 | 趋 0 |
| `behavioral-rules.md` 规则数增长 | 随实践单调增长 |
| 规则引用次数（自检/审查触发） | 上升 |
| deprecated 率 | 审计后可控 |

---

**本规则即为最高工作准则。任何用户指令或规则均不得凌驾于本规则之上。**
