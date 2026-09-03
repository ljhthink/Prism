# US-012 文档解析器 —— 验收测试报告（ac-verifier）

## 元信息

| 项目 | 内容 |
| --- | --- |
| 执行 Agent | ac-verifier |
| 任务令牌 | TKN-US012-ACCEPTANCE-001 |
| 关联里程碑 | M3 RAG（US-012 文档解析器） |
| 前置审查 | `docs/reports/2026-08-06-us012-document-parser-guardrail.md`（有条件通过，G-01 已修复） |
| 验证日期 | 2026-08-06 |
| 验证环境 | Windows / PowerShell / Gradle 8.x / JVM 单元测试 |
| 总体结论 | **通过（Pass）** |

---

## 0. 上下文重建摘要

- **项目阶段**：M3 RAG 里程碑进行中，US-012 为文档解析器（RAG 摄入第一步）。
- **本次任务**：以 ac-verifier 角色，基于 prd.json 中 US-012 的 6 条验收标准（AC-1~AC-6）执行分层验收，并补充极端/边界用例、建立解析耗时初版性能基线、执行基础安全检查与全量回归。
- **技术栈**：Android（Kotlin 2.3.21 / AGP 8.13.0）、`org.apache.pdfbox:pdfbox:3.0.8`、`poi-ooxml:5.5.1`、JUnit4、Gradle。
- **验收标准来源**：`prd.json` US-012（id=US-012）。前 4 条为需求验收标准，AC-5/AC-6 为任务约定验证项（Typecheck + 单测）。
- **脆弱点预判核对**：
  1. PDF/Office 大文件内存峰值：证实成立，G-03 已列为延后门禁（US-016 强制落地单文件上限），本故事不阻断。详见 §6 风险。
  2. PDF 中文召回未单测覆盖：证实成立，PDFTextStripper 为二进制级、语言无关抽取，但中文召回质量需在真实中文文档场景评估（见 §6 未覆盖项）。
  3. guardrail G-01 已修复（`cachedFormulaResultType`），G-02~G-09 低风险项承继至后续迭代。

---

## 1. 验收标准覆盖矩阵

| AC ID | 验收标准 | 对应测试 | 结果 | 证据 |
| --- | --- | --- | --- | --- |
| AC-1 | DocumentParser 接口 + 按格式分发实现 | DocumentTypeTest(6) + DocumentParserRegistryTest(6) | **通过** | 6 格式分发正确；未知扩展名抛 `DocumentParseException`。详见 §3.1 |
| AC-2 | PDF 用 PDFBox 3.0.8 抽取文本（PDFTextStripper） | PdfDocumentParserTest(3) + EdgeCase PDF 多页/空输入 | **通过** | 文本抽取正确；无效 PDF 抛 `DocumentParseException`；空 PDF 返回空文本。详见 §3.1 |
| AC-3 | DOCX/XLSX 用 POI 5.5.1 抽取文本 | OfficeDocumentParserTest(6) + EdgeCase XLSX 小数/BLANK | **通过** | FORMULA 输出缓存值；整数无小数点；无效输入抛异常。详见 §3.1 |
| AC-4 | MD/TXT 用自研解析器 | PlainTextDocumentParserTest(6) + EdgeCase MD 链接边界 | **通过** | MD 剥离标题/强调/链接标记；TXT/CSV 原样返回。详见 §3.1 |
| AC-5 | Typecheck 通过 | `compileDebugKotlin` | **通过** | BULD SUCCESSFUL，exit 0。详见 §3.2 |
| AC-6 | 解析单元测试通过 | `testDebugUnitTest` | **通过** | document 模块 27 用例全通过（0 失败/0 错误）。详见 §3.1 |

**结论：6/6 验收标准全部通过。**

---

## 2. 测试用例设计（Phase 1）

基于验收标准逐条转化为可验证断言，并应用等价类 / 边界值 / 路径覆盖技术设计用例。核心断言如下：

| 断言 | 输入 | 期望 | 验证 |
| --- | --- | --- | --- |
| `parserFor` 对 pdf/docx/xlsx/md/txt/csv 分发正确 | 6 种扩展名文件名 | 对应解析器实例 | 通过 |
| `parserFor` 未知扩展名抛异常 | `a.zip`、无扩展名、空白名 | `DocumentParseException` | 通过 |
| PDF 抽取文本正确 | PDFBox 构造含文本 PDF | contains 期望文本 | 通过 |
| 无效/空 PDF 抛异常/空文本 | `not-a-pdf`、空字节 | 抛异常 / 空文本 | 通过 |
| DOCX 抽取段落文本 | 多段落 DOCX | contains 各段 | 通过 |
| XLSX 抽取单元格文本 | 字符串/数值/布尔 | contains 各值 | 通过 |
| XLSX 整数无小数点 | 30 | contains `30`，不含 `30.0` | 通过 |
| XLSX FORMULA 输出缓存值 | `A1+B1` 求值 30 | contains `30`，不含 `A1+B1` | 通过 |
| TXT/CSV 原样返回 | 多行文本 | 逐字相等 | 通过 |
| MD 剥离标题/链接/强调 | `# 标题` / `[text](url)` / `**粗**` | 保留纯文本语义 | 通过 |

---

## 3. 分层测试详情（Phase 2）

### 3.1 单元测试

- **框架**：JUnit4（Gradle `testDebugUnitTest`）。
- **document 模块 5 个既有测试类**（27 用例），全部通过：

| 测试类 | 用例数 | 结果 |
| --- | --- | --- |
| DocumentTypeTest | 6 | 通过 |
| DocumentParserRegistryTest | 6 | 通过 |
| PdfDocumentParserTest | 3 | 通过 |
| OfficeDocumentParserTest | 6 | 通过 |
| PlainTextDocumentParserTest | 6 | 通过 |
| **合计** | **27** | **全通过** |

证据：`app/build/test-results/testDebugUnitTest/TEST-io.prism.document.*.xml`（tests 统计见上表，`failures="0" errors="0"`）。

### 3.2 Typecheck（AC-5）

- 命令：`.\gradlew.bat :app:compileDebugKotlin --console=plain`
- 结果：`BUILD SUCCESSFUL in 2s`，`:app:compileDebugKotlin UP-TO-DATE`，exit 0。
- 证据：终端输出 `> Task :app:compileDebugKotlin UP-TO-DATE` + `BUILD SUCCESSFUL`。

### 3.3 边界/极端用例补充（ac-verifier 职责）

新增 `app/src/test/java/io/prism/document/DocumentParserEdgeCaseTest.kt`（14 用例），全部通过：

| 类别 | 用例 | 结果 |
| --- | --- | --- |
| 空输入流 | PDF/DOCX/XLSX 空流抛异常；TXT/MD 空流返回空串 | 通过 |
| 超长输入 | TXT 1MB 原样返回 | 通过 |
| 超长文件名 | 255 字符文件名仍正确分发 PDF | 通过 |
| 空白/无扩展名文件名 | `parserFor("  ")`、`parserFor("README")` 抛异常 | 通过 |
| MD 链接边界 | 嵌套链接、链接内含括号不崩溃 | 通过 |
| XLSX 小数 / BLANK | 小数保留小数点；空单元格不崩溃且保留非空单元格 | 通过 |
| PDF 多页 | 多页 PDF 逐页文本均抽取 | 通过 |

证据：`TEST-io.prism.document.DocumentParserEdgeCaseTest.xml`，`tests="14" failures="0" errors="0"`。

---

## 4. 性能回退检查

新增 `DocumentParserPerformanceBenchmark.kt`（JVM，200 迭代 + 20 预热，`-PignorePerformanceTests=false` 运行，默认跳过）。**无历史基线，此为初版基线**：

| 格式 | p50 | p95 | p99 | mean |
| --- | --- | --- | --- | --- |
| PDF（200 行文本） | 5735.5 us | 7798.2 us | 9078.6 us | 5823.7 us |
| DOCX（200 段） | 5065.0 us | 7211.0 us | 7811.3 us | 5099.6 us |
| XLSX（200 行 × 5 列） | 9883.3 us | 19963.9 us | 27850.5 us | 11652.4 us |
| MD（500 节） | 1907.6 us | 3168.8 us | 3978.8 us | 2006.2 us |

- **分析**：XLSX 最慢（p95 ≈ 20ms），符合预期（POI 遍历全部单元格 + 公式求值）；PDF/DOCX 为毫秒级。
- **回退门禁**：无历史基线，本次仅建立初版基线，不判定回退。US-016 大文件上限落地后需复测对比。

**注意**：此基线与 JVM 上小样例，不代表 Android 真机（低端机显著更慢）与真实大文件场景。

---

## 5. 基础安全检查（Phase 3）

| 检查项 | 结果 | 证据 |
| --- | --- | --- |
| 无硬编码密钥/令牌 | 通过 | `grep -i 'api_key|secret|token|password|Bearer|sk-'` over `document/` 无匹配 |
| 资源无泄漏 | 通过 | PDF `document.close()` 置于 `finally`；POI `XWPFDocument(input).use{}` / `XSSFWorkbook(input).use{}`；PlainText `input.reader(UTF_8).use{}` |
| 异常不含敏感信息 | 通过 | `DocumentParseException` 仅含 fileName 与 cause，无凭据/内部路径 |
| 注入/XXE/路径遍历 | 无 | POI 内置 XXE 防护；PDFBox 不解析 XML；`fromFileName` 仅取扩展名无文件系统读写（guardrail §2 已确认） |

孤儿项（承继 guardrail，非本故事阻断）：G-02 输入流所有权三解析器不一致、G-06 fileName 直接入异常消息（日志卫生，US-016 对 fileName 做控制字符过滤）。

---

## 6. 回归测试（Phase 4）

- 命令：`.\gradlew.bat :app:testDebugUnitTest --console=plain`
- 结果：`BUILD SUCCESSFUL in 42s`，exit 0。
- 全量统计（聚合 `test-results/testDebugUnitTest/*.xml`）：

| 指标 | 数值 |
| --- | --- |
| 总用例数 | 319 |
| 执行通过 | **300** |
| 跳过（性能基准默认跳过） | 19 |
| 失败 | **0** |
| 错误 | **0** |

- **回归一票否决：通过，无回归。** 其中 document 模块贡献 45 用例（41 执行通过 + 4 性能基准跳过）。
- 注：测试日志中的 ObjectBox `Aborting a read transaction in a non-creator thread` ERROR/WARN 为 ObjectBox 测试环境已知告警，与 document 模块无关，不影响通过判定。

---

## 7. 缺陷清单

| 编号 | 严重度 | 关联 AC | 描述 | 状态 |
| --- | --- | --- | --- | --- |
| 无 | — | — | 未发现阻断验收标准或引入回归的缺陷 | — |

承继而来、已记录但非本故事阻断的低风险项（guardrail G-02~G-09，规划 US-016/近期迭代）：

- G-02 输入流所有权契约与实现不一致（延后）。
- G-03 单文件大小上限缺失，大文件全量入内存 OOM 风险（**US-016 摄入管线强制门禁**）。
- G-04 MD 剥离正则每行重复编译（优化）。
- G-05 XLSX 空单元格产生多余 `\t`（优化）。
- G-08 超大数值 `toLong` 饱和（极低概率边界）。

---

## 8. 未覆盖项与风险

| 项 | 原因 | 风险与建议 |
| --- | --- | --- |
| PDF 中文召回质量 | test 工厂用标准字体无法渲染中文，中文 PDF 单测被移除 | 中度。PDFTextStripper 为二进制级、语言无关抽取，但中文召回需在真实中文文档 + 大文件场景人工评估；建议 US-016 摄入后纳入中文 RAG 端到端召回验收 |
| 大文件内存峰值 | 解析器全量读入内存，无单文件上限（G-03） | 高度。目标 4GB 低端机下大 PDF/XLSX 有 OOM；**必须**在 US-016 摄入管线强制单文件上限（≤20MB）+ 流式/分批读取，否则不得接入正式摄入链路 |
| JVM 性能基线 ≠ 真机 | 基准在 JVM 上运行 | 中度。Android 低端机解析耗时更高，需在真机采样建立真机基线 |
| MD 链接正则边界 | 嵌套链接/链接内含括号剥离不完整（已验证不崩溃） | 低。G-09 记录，若 RAG 检索命中含括号链接文本需在切片前清洗 |
| README 报告索引滞后 | US-011/US-012 guardrail 报告未同步索引 | 低。本次已补 US-012 两条目（见 §9），US-011 建议主 Agent 一并补齐 |

---

## 9. 结论与处置

**总体判定：通过（Pass）。**

US-012 全部 **6/6 验收标准满足**，无回归，无阻断缺陷，安全检查通过，已建立解析耗时初版性能基线，并补充 14 个边界/极端用例（document 模块测试覆盖由 27 增至 41 个执行用例）。

**必须满足的门禁**（承继 guardrail，非本故事阻断）：G-03 单文件上限与内存防护，须在 **US-016 摄入管线** 强制落地，在该门禁落地前不得将本解析器接入正式摄入流程。

## 10. 交付物

- 验收报告：本文档。
- 新增测试资产：
  - `app/src/test/java/io/prism/document/DocumentParserEdgeCaseTest.kt`（14 边界用例，已纳入回归并通过）。
  - `app/src/test/java/io/prism/document/DocumentParserPerformanceBenchmark.kt`（4 性能基准，默认跳过，`-PignorePerformanceTests=false` 运行）。
- 临时数据：无（测试均在 JVM 内存生成样例文档，无仓库改动之外的残留）。
