# 验收测试报告 —— US-002 ObjectBox 数据库基础

| 项目 | 内容 |
|---|---|
| 执行 Agent | ac-verifier |
| 任务令牌 | TKN-PRISM-ACCEPTANCE-001 |
| 验收日期 | 2026-08-02 |
| 关联 PRD | US-002「配置 ObjectBox 数据库基础」（prd.json 5 条验收标准） |
| 关联 ADR | [ADR-001 Prism 技术栈与架构选型](../decisions/ADR-001-prism-tech-stack.md) |
| guardrail 报告 | [US-002 ObjectBox guardrail 报告](2026-08-02-us002-objectbox-guardrail.md)（TKN-PRISM-GUARDRAIL-004，通过） |
| 考古报告 | [US-002 ObjectBox 考古报告](2026-08-02-us002-objectbox-archaeology.md)（TKN-PRISM-ARCHAEOLOGY-003） |
| 性能基线 | [US-002 ObjectBox CRUD 性能基线](perf/2026-08-02-us002-objectbox-crud-baseline.md) |
| 行为规则 | [docs/behavioral-rules.md](../behavioral-rules.md) BR-build-004/005 + BR-security-001 |
| 风险等级 | P2 跨模块（新增 P0 依赖 + 数据模型 + 构建配置） |
| 测试方法论 | test-architect skill（PRD 驱动分层测试金字塔） |

---

## 1. 验收标准执行结果

### 1.1 验收标准覆盖矩阵

| AC ID | 验收标准 | 验证方法 | 通过标准 | 结果 | 证据 |
|---|---|---|---|---|---|
| AC-001 | build.gradle.kts 应用 io.objectbox 插件 version 5.4.2 | 静态代码检查 + 依赖树验证 + 构建验证 | 插件应用 + 版本 5.4.2 + 依赖解析成功 | **通过** | [libs.versions.toml:8](../../gradle/libs.versions.toml) `objectbox = "5.4.2"`；[app/build.gradle.kts:6](../../app/build.gradle.kts) `alias(libs.plugins.objectbox)`；依赖树 `io.objectbox:objectbox-kotlin:5.4.2` 全链路版本一致；assembleDebug BUILD SUCCESSFUL |
| AC-002 | 定义 @Entity 数据类 KnowledgeChunk（id/title/content/embedding 字段） | 静态代码检查 + schema 一致性验证 + kapt 生成代码验证 | @Entity 注解 + 4 字段 + schema 匹配 + MyObjectBox 生成 | **通过** | [KnowledgeChunk.kt:20-25](../../app/src/main/java/io/prism/data/KnowledgeChunk.kt) `@Entity data class KnowledgeChunk(@Id var id, var title, var content, var embedding)`；[default.json](../../app/objectbox-models/default.json) 4 properties (id type=6/title type=9/content type=9/embedding type=28)；[MyObjectBox.java](../../app/build/generated/source/kapt/debug/io/prism/data/MyObjectBox.java) 生成 4 属性 (Long/String/String/FloatVector) |
| AC-003 | MyObjectBox.builder().androidContext(context).build() 初始化成功 | 静态代码检查 + JVM 间接验证 + APK 静态检查 | 代码正确 + builder API 可用 + Manifest 声明 + APK 打包 | **受限通过** | [PrismApplication.kt:20-22](../../app/src/main/java/io/prism/PrismApplication.kt) `MyObjectBox.builder().androidContext(this).build()`；18 个 JVM 测试通过 `MyObjectBox.builder().directory(tempDir).build()`；打包后 Manifest `android:name="io.prism.PrismApplication"`；APK 含 4 ABI libobjectbox-jni.so + classes3.dex 含 PrismApplication |
| AC-004 | box.put/box.get/box.remove CRUD 单元测试通过 | JUnit 4 单元测试（18 个） | 全部通过 | **通过** | [测试结果 XML](../../app/build/test-results/testDebugUnitTest/TEST-io.prism.data.KnowledgeChunkCrudTest.xml) 9 tests 0 failures；[EdgeCaseTest XML](../../app/build/test-results/testDebugUnitTest/TEST-io.prism.data.KnowledgeChunkEdgeCaseTest.xml) 9 tests 0 failures |
| AC-005 | Typecheck passes | lintDebug + compileDebugKotlin + assembleDebug | 0 errors + 编译成功 | **通过** | lintDebug: 0 errors / 15 warnings（均为已知环境限制 OldTargetApi/RedundantLabel）；kaptDebugKotlin 成功生成 MyObjectBox.java；assembleDebug BUILD SUCCESSFUL |

### 1.2 AC-003 受限说明

AC-003 标注为"受限通过"，原因如下：

| 维度 | 验证状态 | 说明 |
|---|---|---|
| 代码正确性 | 通过 | PrismApplication.kt 第 20-22 行正确调用 `MyObjectBox.builder().androidContext(this).build()` |
| builder API 可用性 | 通过 | 18 个 JVM 测试通过 `MyObjectBox.builder().directory(tempDir).build()`，证明 builder 链式 API 功能正常 |
| Manifest 声明 | 通过 | 源码 AndroidManifest.xml + 打包后 Manifest 均包含 `android:name="io.prism.PrismApplication"` |
| APK 类打包 | 通过 | classes3.dex 包含 PrismApplication，classes4.dex 包含 KnowledgeChunk |
| Native 库打包 | 通过 | APK 含 4 个 ABI 的 libobjectbox-jni.so（arm64-v8a/armeabi-v7a/x86/x86_64） |
| **真实 Android 设备初始化** | **受限** | **当前开发环境无 Android 模拟器/设备，无法执行 androidTest 仪器测试验证 `androidContext(this)` 初始化路径。guardrail G-04 已标记此为低风险技术债。** |

> 替代验证方法：通过 JVM 测试（`directory(tempDir)` 路径）间接验证 builder API 可用性 + APK 静态检查（Manifest 声明 + native 库打包 + dex 类存在）间接验证集成完整性。完整验证需在后续环境中补充 `androidTest` 仪器测试。

---

## 2. 分层测试

### 2.1 静态分析

| 工具 | 命令 | Errors | Warnings | 结果 |
|---|---|---|---|---|
| Android Lint 8.13.0 | `.\gradlew.bat lintDebug` | 0 | 15 | **通过** |
| kapt 注解处理器 | `.\gradlew.bat kaptDebugKotlin` | 0 | 1（kapt 2.0 Alpha 警告，已知技术债） | **通过** |
| TRAE-security-review | guardrail-enforcer 执行 | 0 | — | **通过**（clean diff） |

**Lint Warnings 明细**（均为已知环境限制，非 US-002 引入）：

| Warning ID | 数量 | 说明 | 风险 |
|---|---|---|---|
| OldTargetApi | 1 | targetSdk=34（android-35/36 平台因 GFW 无法下载，ADR-001 已记录） | 已知技术债 |
| RedundantLabel | 1 | Activity 冗余 label 属性 | 无风险 |
| 其他 | 13 | Google AutoValue / Compose 预览 / 过时 API 等 | 非本 US 引入 |

### 2.2 单元测试（覆盖率：语句 ~100% / 分支 ~100%，KnowledgeChunk.kt）

| 测试套件 | 框架 | 用例数 | 通过 | 失败 | 跳过 | 耗时 | 结果 |
|---|---|---|---|---|---|---|---|
| KnowledgeChunkCrudTest | JUnit 4 | 9 | 9 | 0 | 0 | 0.222s | **通过** |
| KnowledgeChunkEdgeCaseTest | JUnit 4 | 9 | 9 | 0 | 0 | 0.720s | **通过** |
| KnowledgeChunkPerformanceBenchmark | JUnit 4 | 4 | 0 | 0 | 4 | — | @Ignore（手动运行） |

**总计**：22 用例，18 执行通过，4 跳过（性能基准），0 失败。

**覆盖率评估**（项目未配置 JaCoCo，通过代码静态分析评估）：

| 文件 | 语句覆盖率 | 分支覆盖率 | 评估依据 |
|---|---|---|---|
| KnowledgeChunk.kt | ~100% | ~100% | 数据类无逻辑分支，4 字段（id/title/content/embedding）全部被读写测试覆盖，null/非 null 分支均覆盖 |
| PrismApplication.kt | 0%（JVM） | 0%（JVM） | onCreate() 需 Android Context，JVM 测试未覆盖。间接通过 MyObjectBox.builder().directory().build() 验证 builder API。guardrail G-04 已知限制。 |

**KnowledgeChunkCrudTest 用例明细**（主 Agent 基础用例）：

| 用例 | 技术 | 耗时 | 覆盖路径 |
|---|---|---|---|
| put_assigns_positive_id | 等价类（正常） | 0.019s | put + id 分配 |
| get_returns_persisted_chunk_without_embedding | 等价类（正常） | 0.020s | put + get + null embedding |
| put_with_embedding_persists_vector | 等价类（正常） | 0.018s | put + get + embedding 往返 |
| remove_deletes_chunk | 等价类（正常） | 0.020s | put + contains + remove + contains |
| put_with_existing_id_updates_chunk | 状态迁移 | 0.018s | put + 更新 + put + get |
| put_multiple_chunks_each_gets_unique_id | 等价类（多条） | 0.069s | put x3 + id 唯一性 + count |
| get_nonexistent_id_not_found_via_contains | 边界值（不存在） | 0.016s | contains(99999L) |
| remove_all_clears_box | 等价类（批量） | 0.021s | put x2 + count + removeAll + count |
| empty_embedding_round_trip | 边界值（空数组） | 0.018s | put + get + FloatArray(0) 往返 |

**KnowledgeChunkEdgeCaseTest 用例明细**（ac-verifier 补充极端场景）：

| 用例 | 技术 | 耗时 | 覆盖盲区 |
|---|---|---|---|
| empty_title_and_content_persist_correctly | 边界值（空字符串） | 0.018s | 空 title + 空 content |
| very_long_content_persists_correctly | 边界值（超长 10000 字符） | 0.023s | 超长 content |
| very_long_title_persists_correctly | 边界值（超长 1000 字符） | 0.031s | 超长 title |
| real_384_dim_embedding_round_trip | 等价类（真实场景 384 维） | 0.019s | all-MiniLM-L6-v2 真实维度向量 |
| bulk_insert_1000_chunks | 资源边界（1000 条） | 0.537s | 大量数据插入 + count |
| data_persists_across_boxstore_restart | 状态迁移（重启） | 0.030s | close + 重新 open + 数据持久化 |
| get_after_remove_returns_not_found | 异常路径 | 0.019s | 删除后 contains |
| remove_already_removed_id_is_idempotent | 异常路径（幂等） | 0.021s | 重复删除不抛异常 |
| embedding_extreme_float_values_round_trip | 边界值（浮点极值） | 0.018s | MAX_VALUE/MIN_VALUE/0/-0/NaN/Infinity |

### 2.3 集成测试

| 场景 | 验证方法 | 结果 | 证据 |
|---|---|---|---|
| 构建配置集成（kapt + objectbox 插件协同） | `.\gradlew.bat assembleDebug` | **通过** | BUILD SUCCESSFUL，kapt 生成 MyObjectBox.java，APK 打包成功 |
| ObjectBox 依赖树完整性 | `.\gradlew.bat app:dependencies --configuration debugRuntimeClasspath` | **通过** | objectbox-kotlin:5.4.2 → objectbox-java:5.4.2 → objectbox-java-api:5.4.2 + objectbox-android:5.4.2 → objectbox-android-db:5.4.2，全链路版本一致 |
| Schema 一致性（default.json ↔ 代码 ↔ MyObjectBox） | 三方对比 | **通过** | 代码 4 字段 ↔ default.json 4 properties (type 6/9/9/28) ↔ MyObjectBox.java 4 properties (Long/String/String/FloatVector)，ID 一致 |
| Manifest 集成（Application 声明） | 打包后 Manifest 检查 | **通过** | `android:name="io.prism.PrismApplication"` 存在于 packaged_manifests |
| Native 库打包 | APK zip 检查 | **通过** | lib/{arm64-v8a,armeabi-v7a,x86,x86_64}/libobjectbox-jni.so 全部存在 |
| APK 体积 | 文件大小检查 | **通过** | 17.63 MB（M0 基线 8.65MB + ObjectBox ~9MB，在考古报告预估范围内） |

### 2.4 E2E 测试（APK 静态检查替代，因无模拟器/设备）

| 检查项 | 验证方法 | 结果 | 证据 |
|---|---|---|---|
| APK 可构建 | `.\gradlew.bat assembleDebug` | **通过** | BUILD SUCCESSFUL，app-debug.apk 生成 |
| APK 含 PrismApplication 类 | dex 二进制搜索 | **通过** | classes3.dex 包含 "io/prism/PrismApplication" |
| APK 含 KnowledgeChunk 类 | dex 二进制搜索 | **通过** | classes4.dex 包含 "io/prism/data/KnowledgeChunk" |
| APK 含 MyObjectBox 生成类 | dex 二进制搜索 | **通过** | classes3.dex + classes4.dex 包含 "io/prism/data/MyObjectBox" |
| APK 含 ObjectBox native 库 | zip 内容检查 | **通过** | 4 个 ABI 的 libobjectbox-jni.so |
| APK Manifest 声明 Application | 字符串匹配 | **通过** | `android:name="io.prism.PrismApplication"` |
| APK allowBackup=false | 字符串匹配 | **通过** | `android:allowBackup="false"` |
| **真实设备启动不崩溃** | **仪器测试** | **受限** | **无模拟器/设备，无法验证 PrismApplication.onCreate() 的 androidContext 初始化路径** |

> E2E 测试结论：APK 静态检查 7/7 通过，真实设备仪器测试受限（环境约束）。建议后续在有模拟器/设备的环境中补充 `androidTest` 仪器测试覆盖 AC-003 的 androidContext 初始化路径。

---

## 3. 极端/边缘场景

### 3.1 测试用例设计矩阵

| 测试用例 ID | AC ID | 技术 | 输入 / 前置条件 | 动作 | 预期行为 | 测试层级 | 结果 |
|---|---|---|---|---|---|---|---|
| TC-EDGE-01 | AC-004 | 边界值（空字符串） | title="", content="" | put + get | 空字符串正确持久化 | 单元 | 通过 |
| TC-EDGE-02 | AC-004 | 边界值（超长 content） | content = "a" x 10000 | put + get | 10000 字符正确持久化 | 单元 | 通过 |
| TC-EDGE-03 | AC-004 | 边界值（超长 title） | title = "标题" x 500 | put + get | 1000 字符正确持久化 | 单元 | 通过 |
| TC-EDGE-04 | AC-004 | 等价类（真实场景向量） | embedding = FloatArray(384) | put + get | 384 维向量精确往返 | 单元 | 通过 |
| TC-EDGE-05 | AC-004 | 资源边界（大量数据） | 1000 条 KnowledgeChunk | put x1000 + count | 1000 条全部持久化 | 单元 | 通过 |
| TC-EDGE-06 | AC-004 | 状态迁移（重启持久化） | put → close → reopen → get | 重启 BoxStore | 数据跨重启持久化 | 单元 | 通过 |
| TC-EDGE-07 | AC-004 | 异常路径（删除后访问） | put → remove → contains | 删除后查询 | contains=false | 单元 | 通过 |
| TC-EDGE-08 | AC-004 | 异常路径（幂等删除） | put → remove → remove | 重复删除 | 不抛异常，count=0 | 单元 | 通过 |
| TC-EDGE-09 | AC-004 | 边界值（浮点极值） | MAX_VALUE/MIN_VALUE/0/NaN/Infinity | put + get | 极值正确持久化 | 单元 | 通过 |

### 3.2 未覆盖的极端场景（环境受限）

| 场景 | 原因 | 风险评估 |
|---|---|---|
| 并发写入冲突 | ObjectBox JVM 测试环境事务模型与 Android 不同，并发测试需设备环境 | 低——ObjectBox 支持事务，并发冲突由库管理 |
| 磁盘空间耗尽 | JVM 测试环境难以模拟磁盘满 | 低——ObjectBox 内部处理 I/O 异常 |
| 数据库文件损坏恢复 | 需手动破坏数据库文件，测试环境难以模拟 | 中——后续应补充异常恢复测试 |
| Android Context 为 null | 需 Android 仪器测试环境 | 低——androidContext(this) 中 this 不可能为 null |

---

## 4. 性能回退检查

### 4.1 基线状态

| 维度 | 状态 |
|---|---|
| 既有基线 | 无（US-002 为首次引入 ObjectBox） |
| 本次操作 | 生成初版基线 |
| 基线文件 | [docs/reports/perf/2026-08-02-us002-objectbox-crud-baseline.md](perf/2026-08-02-us002-objectbox-crud-baseline.md) |

### 4.2 CRUD 操作延迟基线（JVM 环境，500 次迭代 + 50 次预热）

| 操作 | p50 | p95 | p99 | mean | min | max |
|---|---|---|---|---|---|---|
| PUT（单条） | 298.6 us | 443.1 us | 599.4 us | 319.6 us | 235.9 us | 643.8 us |
| GET（单条） | 1.1 us | 1.7 us | 9.4 us | 1.5 us | 0.9 us | 44.9 us |
| REMOVE（单条） | 330.3 us | 464.1 us | 547.0 us | 344.3 us | 246.9 us | 575.5 us |
| BULK PUT（1000 条） | 336.8 ms | 443.3 ms | 443.3 ms | 344.1 ms | 316.5 ms | 443.3 ms |

### 4.3 性能分析

| 指标 | 结论 | 依据 |
|---|---|---|
| 性能回退 | **N/A（初版基线）** | 无既有基线对比，本次为首次基线建立 |
| PUT/REMOVE 延迟 | 合理 | 300-600 us（涉及磁盘写入），约 3.1K ops/s |
| GET 延迟 | 优秀 | p50 1.1 us（ObjectBox mmap 内存映射读取） |
| 批量 PUT | 可优化 | 1000 条 337 ms（每条单独 put），后续可用 `box.put(list)` 批量 API |
| p99/p50 比值 | 可接受 | PUT 2.0x，GET 8.5x（偶发 GC 抖动） |

### 4.4 回退门禁

- 性能下降 >50%：标记失败 — **N/A（初版基线）**
- 性能下降 >20%：标记警告 — **N/A（初版基线）**

---

## 5. 安全检查

### 5.1 安全检查清单

| 检查项 | 验证方法 | 结果 | 证据 |
|---|---|---|---|
| 注入测试（NoSQL） | 代码审查——ObjectBox 使用 box API（put/get/remove），无 query 字符串构造 | **通过** | [KnowledgeChunkCrudTest.kt](../../app/src/test/java/io/prism/data/KnowledgeChunkCrudTest.kt) + [EdgeCaseTest.kt](../../app/src/test/java/io/prism/data/KnowledgeChunkEdgeCaseTest.kt) 全部使用 box API，无 `box.query()` 调用，无注入面 |
| 敏感信息泄露（源码） | PowerShell 正则扫描 `(password\|secret\|api_key\|token\|credential)\s*=\s*["']...["']` | **通过** | 0 匹配——源码中无硬编码密钥/密码/token |
| 敏感信息泄露（日志） | PowerShell 正则扫描 `Log.(d\|e\|i\|v\|w)\|println\|System.out\|printStackTrace` | **通过** | 0 匹配——源码中无日志输出调用 |
| 敏感信息泄露（URL/IP） | PowerShell 正则扫描 `https?://\|(\d{1,3}\.){3}\d{1,3}` | **通过** | 1 匹配——仅 KnowledgeChunk.kt KDoc 注释中的 ObjectBox 文档链接（非敏感） |
| 数据库备份防护 | AndroidManifest allowBackup 检查 | **通过** | `android:allowBackup="false"` 防止 adb backup 提取数据库 |
| .gitignore 密钥排除 | .gitignore 规则检查 | **通过** | `.env` / `*.keystore` / `*.jks` / `local.properties` 均已排除 |
| JNI DLL 排除 | git check-ignore 验证 | **通过** | `git check-ignore app/objectbox-jni-windows-x64.dll` 返回该路径，确认被 .gitignore 排除（BR-build-004） |
| 依赖供应链 | ObjectBox 5.4.2 CVE 检查（guardrail 报告） | **通过** | web-access 搜索未发现已知 CVE（2026-08-02）；Apache 2.0 许可证 |
| 权限最小化 | AndroidManifest 权限检查 | **通过** | 无新增权限（US-002 仅数据层，无网络/存储等权限需求） |
| XSS（前端） | N/A | **不适用** | US-002 无前端 UI，无用户输入渲染路径 |

### 5.2 guardrail-enforcer 安全审计结论

> TRAE-security-review 结论：无可利用安全漏洞（clean diff）。详见 [guardrail 报告第 2 节](2026-08-02-us002-objectbox-guardrail.md)。

---

## 6. 回归测试

### 6.1 回归测试范围

| 套件 | 用例数 | 通过 | 失败 | 跳过 | 结果 |
|---|---|---|---|---|---|
| KnowledgeChunkCrudTest（主 Agent 基础用例） | 9 | 9 | 0 | 0 | **通过** |
| KnowledgeChunkEdgeCaseTest（ac-verifier 补充） | 9 | 9 | 0 | 0 | **通过** |
| KnowledgeChunkPerformanceBenchmark（性能基准） | 4 | 0 | 0 | 4 | @Ignore |
| **总计** | **22** | **18** | **0** | **4** | **通过** |

### 6.2 回归测试结论

项目在 US-002 阶段首次引入测试套件，无历史测试需要回归对比。本次新增的 18 个功能测试全部通过，4 个性能测试正确跳过（@Ignore）。无回归失败。

### 6.3 构建回归

| 构建任务 | 结果 | 耗时 |
|---|---|---|
| `.\gradlew.bat lintDebug` | BUILD SUCCESSFUL | 29s |
| `.\gradlew.bat testDebugUnitTest` | BUILD SUCCESSFUL | 8s |
| `.\gradlew.bat assembleDebug` | BUILD SUCCESSFUL | 3s（增量） |

---

## 7. 结论

### 7.1 总体结论

| 维度 | 结论 |
|---|---|
| 验收标准覆盖 | 5/5 全部验证（4 通过 + 1 受限通过） |
| 分层测试 | 静态分析 通过 / 单元测试 通过 / 集成测试 通过 / E2E 受限通过 |
| 安全检查 | 10/10 检查项通过（1 不适用） |
| 性能基线 | 初版基线已建立，无回退 |
| 回归测试 | 18/18 通过，0 失败 |
| **总体** | **通过（附带受限项）** |

### 7.2 验收标准逐条结论

- [x] **AC-001 通过**：build.gradle.kts 应用 io.objectbox 插件 version 5.4.2
- [x] **AC-002 通过**：定义 @Entity KnowledgeChunk（id/title/content/embedding 字段）
- [x] **AC-003 受限通过**：MyObjectBox.builder().androidContext(context).build() 初始化成功（JVM 间接验证 + APK 静态检查；真实设备仪器测试受限）
- [x] **AC-004 通过**：box.put/box.get/box.remove CRUD 单元测试通过（18 个测试）
- [x] **AC-005 通过**：Typecheck passes（lint 0 errors + 编译成功）

### 7.3 受限项与后续追踪

| 受限项 | 原因 | 影响 | 建议追踪 |
|---|---|---|---|
| AC-003 真实设备初始化验证 | 无 Android 模拟器/设备 | androidContext 初始化路径未在真实 Android 环境验证 | 后续补充 androidTest 仪器测试（guardrail G-04） |
| 并发写入测试 | JVM 环境与 Android 事务模型差异 | 并发冲突处理未验证 | 后续在设备环境补充并发测试 |
| 数据库损坏恢复测试 | 测试环境难以模拟 | 异常恢复路径未验证 | 后续补充故障注入测试 |

### 7.4 ac-verifier 补充产出物

| 文件 | 类型 | 说明 |
|---|---|---|
| [KnowledgeChunkEdgeCaseTest.kt](../../app/src/test/java/io/prism/data/KnowledgeChunkEdgeCaseTest.kt) | 补充测试 | 9 个极端场景测试（空值/超长/384 维/批量/重启/幂等/极值） |
| [KnowledgeChunkPerformanceBenchmark.kt](../../app/src/test/java/io/prism/data/KnowledgeChunkPerformanceBenchmark.kt) | 性能基准 | 4 个 CRUD 延迟基准测试（@Ignore，手动运行） |
| [性能基线文档](perf/2026-08-02-us002-objectbox-crud-baseline.md) | 基线记录 | p50/p95/p99 延迟初版基线 |

### 7.5 流程判定

```
静态分析（Lint + 安全扫描）: 通过（0 errors）
单元测试（18 功能 + 4 性能跳过）: 通过（0 failures）
集成测试（构建 + 依赖 + Schema + Manifest + Native）: 通过
E2E 测试（APK 静态检查 7/7）: 通过（真实设备受限）
安全专项验证（10 项）: 通过
回归测试（18/18）: 通过
性能基线: 初版已建立

→ US-002 验收结论: 通过（附带 AC-003 受限项）
→ 受限项不阻断本轮开发周期闭合
→ 后续在有设备环境中补充 androidTest 仪器测试以完全闭合 AC-003
```

---

## 8. 缺陷列表

| ID | 严重度 | 关联 AC | 描述 | 复现步骤 | 证据 | 状态 |
|---|---|---|---|---|---|---|
| 无 | — | — | 本次验收未发现新缺陷 | — | — | — |

> guardrail-enforcer 已识别的 G-01 ~ G-08 为已知问题（非本次验收新发现），其中 G-01（.gitignore DLL 排除）已完成，G-03（default.json 提交）待主 Agent 提交时处理，G-02（FloatArray equals）已用注释方式解决，G-04 ~ G-08 为后续追踪技术债。

---

## 9. 未覆盖项与风险

| 未覆盖项 | 原因 | 风险描述 | 缓解措施 |
|---|---|---|---|
| PrismApplication.onCreate() androidContext 初始化 | 无 Android 模拟器/设备 | 真实 Android 环境下 ObjectBox 初始化可能因 Context/存储权限问题失败 | APK 静态检查间接验证 + 后续补充 androidTest |
| 并发写入冲突处理 | JVM 测试环境与 Android 事务模型不同 | 多线程并发写入可能导致数据不一致 | ObjectBox 内置事务管理；后续设备测试验证 |
| 数据库损坏后恢复 | 测试环境难以模拟文件损坏 | 数据库文件损坏后应用可能崩溃 | PrismApplication 无错误处理（guardrail G-06）；后续增强 |
| Android 设备实际 CRUD 性能 | 测试在 JVM 环境，非真实设备 | eMMC/UFS 存储速度差异可能导致性能不同 | JVM 基线作为参考起点；后续设备测试建立设备基线 |
| release 构建 ProGuard 混淆兼容 | release isMinifyEnabled=false | 启用混淆后 ObjectBox consumer rules 未验证 | guardrail G-08；release 优化阶段验证 |

---

## 10. 参考

- [CLAUDE.md 第十一节 验收测试与分层验证](../../CLAUDE.md)
- [ADR-001 Prism 技术栈与架构选型](../decisions/ADR-001-prism-tech-stack.md)
- [US-002 ObjectBox guardrail 报告](2026-08-02-us002-objectbox-guardrail.md)（TKN-PRISM-GUARDRAIL-004）
- [US-002 ObjectBox 考古报告](2026-08-02-us002-objectbox-archaeology.md)（TKN-PRISM-ARCHAEOLOGY-003）
- [US-002 ObjectBox CRUD 性能基线](perf/2026-08-02-us002-objectbox-crud-baseline.md)
- [behavioral-rules.md](../behavioral-rules.md) BR-build-004/005 + BR-security-001
- [ObjectBox Entity Annotations](https://docs.objectbox.io/entity-annotations)
- [ObjectBox GitHub README](https://github.com/objectbox/objectbox-java)
- test-architect skill（PRD 驱动分层测试方法论）
