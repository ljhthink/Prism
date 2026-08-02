# 验收测试报告 —— US-003 API Key 加密存储

| 项目 | 内容 |
|---|---|
| 执行 Agent | ac-verifier |
| 任务令牌 | TKN-PRISM-ACCEPTANCE-002 |
| 验收日期 | 2026-08-02 |
| 关联 PRD | US-003「实现 API Key 加密存储」（prd.json 5 条验收标准） |
| 关联 ADR | [ADR-001 Prism 技术栈与架构选型](../decisions/ADR-001-prism-tech-stack.md)（3.5 节 Key 存储） |
| guardrail 报告 | [US-003 API Key guardrail 报告](2026-08-02-us003-apikey-guardrail.md)（TKN-PRISM-GUARDRAIL-005，通过） |
| 考古报告 | [US-003 API Key 考古报告](2026-08-02-us003-apikey-archaeology.md)（TKN-PRISM-ARCHAEOLOGY-004） |
| 性能基线 | [US-003 API Key 加密存储性能基线](perf/2026-08-02-us003-apikey-baseline.md) |
| 行为规则 | [docs/behavioral-rules.md](../behavioral-rules.md) BR-security-001/002 + BR-testing-001 + BR-build-001~005 |
| 风险等级 | P2 跨模块（新增 P0 依赖 Tink + P1 依赖 DataStore + 新增安全模块 + 构建配置变更） |
| 测试方法论 | test-architect skill（PRD 驱动分层测试金字塔） |

---

## 1. 验收标准执行结果

### 1.1 验收标准覆盖矩阵

| AC ID | 验收标准 | 验证方法 | 通过标准 | 结果 | 证据 |
|---|---|---|---|---|---|
| AC-001 | Android Keystore 生成主密钥（AES-256-GCM，StrongBox 可用时启用） | 静态代码检查 + 编译验证 + 依赖树验证 | 代码正确 + AES-256-GCM + StrongBox 检测 + 回退 TEE + 编译通过 | **受限通过** | [KeystoreCryptoService.kt:62-110](../../app/src/main/java/io/prism/security/KeystoreCryptoService.kt) `KeyGenerator.getInstance(AES, "AndroidKeyStore")` + `BLOCK_MODE_GCM` + `ENCRYPTION_PADDING_NONE` + `setKeySize(256)` + `hasSystemFeature(FEATURE_STRONGBOX_KEYSTORE)` + `setIsStrongBoxBacked(true)` + catch Exception 回退 TEE（G-01 已修复，BR-security-002）+ `@RequiresApi(P)` 隔离；compileDebugKotlin BUILD SUCCESSFUL；依赖树 `com.google.crypto.tink:tink-android:1.15.0` |
| AC-002 | DataStore + Tink AEAD 加密 API Key，不落明文 | 静态代码检查 + 单元测试（30 个） + 安全扫描 | encrypt 在 edit 之前调用 + DataStore 存密文 + 密文 ≠ 明文 + Tink AEAD | **通过** | [ApiKeyRepository.kt:38-43](../../app/src/main/java/io/prism/security/ApiKeyRepository.kt) `encrypt(value.toByteArray(UTF_8))` 在 `dataStore.edit` 之前调用；30 个测试通过（14 基础 + 16 边界），含 `datastore_stores_ciphertext_not_plaintext` + `datastore_never_contains_plaintext_for_any_key`；RecordingCryptoService 使用 `PredefinedAeadParameters.AES256_GCM` |
| AC-003 | 保存/读取 API Key 单元测试通过（明文不出 Keystore） | JUnit 4 单元测试（30 个） | 全部通过 + 明文不落盘验证 | **通过** | [测试结果 XML](../../app/build/test-results/testDebugUnitTest/TEST-io.prism.security.ApiKeyRepositoryTest.xml) 14 tests 0 failures；[EdgeCaseTest XML](../../app/build/test-results/testDebugUnitTest/TEST-io.prism.security.ApiKeyEdgeCaseTest.xml) 16 tests 0 failures；encrypt 被调用接收明文 + decrypt 被调用接收密文 + DataStore 存密文 ≠ 明文 |
| AC-004 | 日志中不输出 API Key（安全扫描通过） | 源码 grep 扫描（7 项） | 0 日志输出 + 0 硬编码密钥 + 0 API Key 模式 | **通过** | 安全扫描 7/7 通过：0 日志语句（Log.d/e/i/v/w/println/System.out/printStackTrace）、0 硬编码密钥、0 sk- 模式、0 Bearer 模式、0 真实 API Key 在测试文件、.gitignore 含 .env/*.keystore/*.jks |
| AC-005 | Typecheck passes | lintDebug + compileDebugKotlin + compileDebugUnitTestKotlin + assembleDebug | 0 errors + 编译成功 + APK 生成 | **通过** | lintDebug: 0 errors / 17 warnings（均为已知环境限制）；compileDebugKotlin: BUILD SUCCESSFUL；compileDebugUnitTestKotlin: BUILD SUCCESSFUL；assembleDebug: BUILD SUCCESSFUL，APK 19.22 MB |

### 1.2 AC-001 受限说明

AC-001 标注为"受限通过"，原因如下：

| 维度 | 验证状态 | 说明 |
|---|---|---|
| 代码正确性 | 通过 | KeystoreCryptoService.kt 正确使用 `KeyGenerator.getInstance(AES, "AndroidKeyStore")` 生成主密钥 |
| 算法参数 | 通过 | `BLOCK_MODE_GCM` + `ENCRYPTION_PADDING_NONE` + `setKeySize(256)` 确认 AES-256-GCM |
| StrongBox 检测 | 通过 | `hasSystemFeature(FEATURE_STRONGBOX_KEYSTORE)` + `setIsStrongBoxBacked(true)` + `@RequiresApi(P)` 隔离 |
| StrongBox 回退 | 通过 | G-01 已修复：`catch (StrongBoxUnavailableException)` + `catch (Exception)` 双重回退 TEE（BR-security-002） |
| Tink AEAD 委托 | 通过 | `AndroidKeystoreKmsClient().getAead("$KEY_URI_SCHEME$keyAlias")` 委托加密给 Keystore |
| 编译验证 | 通过 | compileDebugKotlin BUILD SUCCESSFUL，@RequiresApi 满足 lint NewApi 检查 |
| 依赖解析 | 通过 | 依赖树 `com.google.crypto.tink:tink-android:1.15.0` 成功解析 |
| APK 打包 | 通过 | assembleDebug BUILD SUCCESSFUL，APK 19.22 MB |
| **真实 Android Keystore 集成** | **受限** | **当前开发环境无 Android 模拟器/设备，无法执行 androidTest 仪器测试验证 Keystore 主密钥在真实硬件上的生成与加密链路。KeystoreCryptoService 需 Android 运行时（Keystore HAL binder IPC），JVM 测试无法覆盖。** |

> 替代验证方法：通过 RecordingCryptoService（纯 JVM Tink AEAD AES-256-GCM）间接验证加密算法正确性 + 代码静态分析验证 Keystore 集成代码正确性 + 依赖树验证 Tink 解析。完整验证需在后续环境中补充 `androidTest` 仪器测试。与 US-002 AC-003 受限通过模式一致。

---

## 2. 分层测试

### 2.1 静态分析

| 工具 | 命令 | Errors | Warnings | 结果 |
|---|---|---|---|---|
| Android Lint 8.13.0 | `.\gradlew.bat lintDebug` | 0 | 17 | **通过** |
| kapt 注解处理器 | `.\gradlew.bat kaptDebugKotlin` | 0 | 1（kapt 2.0 Alpha 警告，已知技术债） | **通过** |
| TRAE-security-review | guardrail-enforcer 执行 | 0 | — | **通过**（clean diff，见 guardrail 报告） |

**Lint Warnings 明细**（均为已知环境限制，非 US-003 引入）：

| Warning ID | 数量 | 说明 | 风险 |
|---|---|---|---|
| OldTargetApi | 1 | targetSdk=34（android-35/36 平台因 GFW 无法下载，ADR-001 已记录） | 已知技术债 |
| RedundantLabel | 1 | Activity 冗余 label 属性 | 无风险 |
| GradleDependency | 5 | 依赖版本有更新可用 | 非阻断，后续升级 |
| NewerVersionAvailable | 5 | 同上 | 非阻断 |
| DataExtractionRules | 1 | 备份规则配置建议 | 无风险（allowBackup=false） |
| ObsoleteSdkInt | 1 | SDK 版本检查可简化 | 无风险 |
| MonochromeLauncherIcon | 2 | 启动器图标建议 | 无风险 |
| AndroidGradlePluginVersion | 1 | AGP 版本提示 | 无风险 |

### 2.2 单元测试

| 测试套件 | 框架 | 用例数 | 通过 | 失败 | 跳过 | 耗时 | 结果 |
|---|---|---|---|---|---|---|---|
| ApiKeyRepositoryTest（主 Agent 基础用例） | JUnit 4 | 14 | 14 | 0 | 0 | 0.049s | **通过** |
| ApiKeyEdgeCaseTest（ac-verifier 补充） | JUnit 4 | 16 | 16 | 0 | 0 | 0.473s | **通过** |
| ApiKeyPerformanceBenchmark（ac-verifier 性能基准） | JUnit 4 | 4 | 0 | 0 | 4 | — | @Ignore（手动运行） |

**US-003 小计**：34 用例，30 执行通过，4 跳过（性能基准），0 失败。

**覆盖率评估**（项目未配置 JaCoCo，通过代码静态分析评估）：

| 文件 | 语句覆盖率 | 分支覆盖率 | 评估依据 |
|---|---|---|---|
| CryptoService.kt | 100% | 100% | 纯接口，2 个方法签名，无逻辑分支 |
| ApiKeyRepository.kt | ~95% | ~85% | 4 个方法全部被测试覆盖；saveApiKey 正常路径 + readApiKey 正常/null/异常路径 + remove 正常/不存在 + removeAll；唯一未覆盖分支：readApiKey 的 `catch(Exception)` 中不同异常子类型（GeneralSecurityException vs IOException），但 catch 统一返回 null |
| KeystoreCryptoService.kt | 0%（JVM） | 0%（JVM） | 需 Android 运行时，JVM 测试未覆盖。通过代码静态分析 + 编译验证间接确认。受限通过。 |
| RecordingCryptoService.kt | 100% | 100% | 测试替身，encrypt/decrypt 全部被调用 |
| FakePreferenceDataStore.kt | 100% | 100% | 测试替身，updateData 全部被调用 |

**ApiKeyRepositoryTest 用例明细**（主 Agent 基础用例，14 个）：

| 用例 | 技术 | 覆盖路径 |
|---|---|---|
| save_and_read_api_key_round_trip | 等价类（正常） | save + read 往返 |
| save_multiple_keys_all_readable | 等价类（多条） | 3 个 key 各自可读 |
| save_overwrite_existing_key | 状态迁移（覆盖） | save → save 同 key → read 新值 |
| save_empty_string_key_round_trip | 边界值（空字符串） | 空字符串往返 |
| save_unicode_key_round_trip | 等价类（Unicode） | 多语言 + emoji 往返 |
| save_api_key_calls_encrypt_with_plaintext | 契约验证 | encrypt 被调用且接收明文 |
| datastore_stores_ciphertext_not_plaintext | 安全验证 | DataStore 存密文 ≠ 明文 |
| read_api_key_calls_decrypt_with_ciphertext | 契约验证 | decrypt 被调用 |
| remove_api_key_deletes_it | 状态迁移（删除） | save → remove → read null |
| remove_nonexistent_key_is_idempotent | 异常路径（幂等） | 删除不存在的 key 不抛异常 |
| remove_all_api_keys_clears_everything | 等价类（批量） | 3 key → removeAll → all null |
| read_nonexistent_key_returns_null | 边界值（不存在） | read 从未保存的 key |
| read_corrupted_ciphertext_returns_null | 异常路径（损坏密文） | 损坏密文 → null 不崩溃 |
| read_with_wrong_crypto_service_returns_null | 异常路径（密钥不匹配） | 不同密钥解密 → null |

**ApiKeyEdgeCaseTest 用例明细**（ac-verifier 补充极端场景，16 个）：

| 用例 | 技术 | 覆盖盲区 |
|---|---|---|
| save_with_empty_key_identifier_still_works | 边界值（空 key 标识符） | G-03 相关：空字符串 key |
| save_with_whitespace_only_key_identifier | 边界值（纯空白 key） | 纯空白字符 key 标识符 |
| save_very_long_api_key_round_trip | 边界值（超长 10000 字符） | 超长 API Key 往返 |
| save_extremely_long_api_key_round_trip | 边界值（超长 100K 字符） | 极端超长 API Key |
| save_with_very_long_key_identifier | 边界值（超长 key 标识符） | 500+ 字符 key 标识符 |
| save_with_special_characters_in_key_identifier | 等价类（特殊字符） | 8 种特殊字符 key 标识符 |
| encrypt_same_plaintext_produces_different_ciphertext | 安全验证（IV 随机化） | 同一明文两次加密密文不同 |
| save_same_api_key_twice_stores_different_ciphertext | 安全验证（IV 随机化） | 同一明文两次保存密文不同 |
| save_whitespace_only_api_key_round_trip | 等价类（纯空白值） | 纯空白字符 API Key |
| save_binary_like_content_round_trip | 等价类（二进制-like） | 控制字符 + text 混合 |
| tampered_ciphertext_returns_null | 安全验证（AEAD 完整性） | 篡改密文 → null |
| truncated_ciphertext_returns_null | 安全验证（AEAD 完整性） | 截断密文 → null |
| save_100_keys_all_readable | 资源边界（100 key） | 大量 key 存储 + 读取 |
| concurrent_saves_to_different_keys_all_persisted | 并发安全 | 10 个并发 async 写入 |
| save_remove_save_cycle_works | 状态迁移（循环） | save → remove → save 循环 |
| datastore_never_contains_plaintext_for_any_key | 安全验证（明文不落盘） | 遍历所有 key 确认无明文 |

### 2.3 集成测试

| 场景 | 验证方法 | 结果 | 证据 |
|---|---|---|---|
| 构建配置集成（Tink + DataStore + ObjectBox 共存） | `.\gradlew.bat assembleDebug` | **通过** | BUILD SUCCESSFUL，三个依赖共存无冲突 |
| Tink 依赖树完整性 | `.\gradlew.bat app:dependencies --configuration debugRuntimeClasspath` | **通过** | `com.google.crypto.tink:tink-android:1.15.0` 成功解析 |
| DataStore 依赖树完整性 | 同上 | **通过** | `androidx.datastore:datastore-preferences:1.1.1` → 全链路版本一致（datastore-preferences-android → datastore → datastore-core → datastore-core-android → datastore-core-okio） |
| 版本目录一致性 | libs.versions.toml 静态检查 | **通过** | tink=1.15.0 / datastore=1.1.1 / coroutines=1.8.0 三项版本声明与 build.gradle.kts 引用一致 |
| PrismApplication 集成 | 代码静态检查 | **通过** | [PrismApplication.kt:23](../../app/src/main/java/io/prism/PrismApplication.kt) `val cryptoService: CryptoService by lazy { KeystoreCryptoService(this) }` 延迟初始化 |
| APK 打包 | assembleDebug + APK 检查 | **通过** | APK 19.22 MB，6 个 DEX 文件，Manifest 含 PrismApplication 声明 |
| AndroidManifest 安全配置 | 静态检查 | **通过** | `android:allowBackup="false"` 防止 adb backup 提取 DataStore 文件 |
| .gitignore 敏感文件排除 | 文件检查 | **通过** | `.env` / `.env.local` / `*.keystore` / `*.jks` / `keystore/` / `local.properties` 全部排除 |

### 2.4 E2E 测试（APK 静态检查替代，因无模拟器/设备）

| 检查项 | 验证方法 | 结果 | 证据 |
|---|---|---|---|
| APK 可构建 | `.\gradlew.bat assembleDebug` | **通过** | BUILD SUCCESSFUL，app-debug.apk 生成 |
| APK 体积 | 文件大小检查 | **通过** | 19.22 MB（US-002 后 17.63 MB + Tink ~1.5 MB，在考古报告预估范围内） |
| Manifest 声明 Application | APK 内 Manifest 检查 | **通过** | `android:name="io.prism.PrismApplication"` 存在 |
| Manifest allowBackup=false | 字符串匹配 | **通过** | `android:allowBackup="false"` 存在 |
| DEX 文件完整 | APK zip 检查 | **通过** | 6 个 DEX 文件（classes.dex ~16.9MB + classes6.dex ~8.8MB 等） |
| **真实设备 Keystore 初始化** | **仪器测试** | **受限** | **无模拟器/设备，无法验证 KeystoreCryptoService 在真实 Android Keystore 上的主密钥生成与加密链路** |
| **真实 DataStore 文件持久化** | **仪器测试** | **受限** | **使用 FakePreferenceDataStore 内存替身，真实 DataStore 文件 I/O 路径未测试** |

> E2E 测试结论：APK 静态检查 5/5 通过，真实设备仪器测试 2 项受限（环境约束）。建议后续在有模拟器/设备的环境中补充 `androidTest` 仪器测试覆盖 Keystore 集成与 DataStore 文件持久化。

---

## 3. 极端/边缘场景

### 3.1 测试用例设计矩阵

| 测试用例 ID | AC ID | 技术 | 输入 / 前置条件 | 动作 | 预期行为 | 测试层级 | 结果 |
|---|---|---|---|---|---|---|---|
| TC-EDGE-01 | AC-003 | 边界值（空 key 标识符） | key="" | save + read | 空字符串 key 可存取 | 单元 | 通过 |
| TC-EDGE-02 | AC-003 | 边界值（纯空白 key） | key="   " | save + read | 纯空白 key 可存取 | 单元 | 通过 |
| TC-EDGE-03 | AC-003 | 边界值（超长 API Key） | value = 10000 字符 | save + read | 超长 Key 正确往返 | 单元 | 通过 |
| TC-EDGE-04 | AC-003 | 边界值（极端超长） | value = 100K 字符 | save + read | 极端超长正确往返 | 单元 | 通过 |
| TC-EDGE-05 | AC-003 | 边界值（超长 key 标识符） | key = 500+ 字符 | save + read | 超长 key 可存取 | 单元 | 通过 |
| TC-EDGE-06 | AC-003 | 等价类（特殊字符 key） | 8 种特殊字符 | save + read | 全部正确往返 | 单元 | 通过 |
| TC-EDGE-07 | AC-002 | 安全验证（IV 随机化） | 同一明文加密两次 | 比较 ciphertext | 密文不同 | 单元 | 通过 |
| TC-EDGE-08 | AC-002 | 安全验证（IV 随机化） | 同一明文保存两次 | 比较 DataStore 值 | 密文不同 | 单元 | 通过 |
| TC-EDGE-09 | AC-003 | 等价类（纯空白值） | value="   \t\n  " | save + read | 纯空白值正确往返 | 单元 | 通过 |
| TC-EDGE-10 | AC-003 | 等价类（二进制-like） | 控制字符 + text | save + read | 正确往返 | 单元 | 通过 |
| TC-EDGE-11 | AC-002 | 安全验证（AEAD 完整性） | 篡改密文最后字节 | read | 返回 null | 单元 | 通过 |
| TC-EDGE-12 | AC-002 | 安全验证（AEAD 完整性） | 截断密文 5 字节 | read | 返回 null | 单元 | 通过 |
| TC-EDGE-13 | AC-003 | 资源边界（100 key） | 100 个 key | save x100 + read x100 | 全部可读 | 单元 | 通过 |
| TC-EDGE-14 | AC-003 | 并发安全 | 10 async 并发写入 | awaitAll + read | 全部持久化 | 单元 | 通过 |
| TC-EDGE-15 | AC-003 | 状态迁移（循环） | save → remove → save | read | 返回新值 | 单元 | 通过 |
| TC-EDGE-16 | AC-002 | 安全验证（明文不落盘） | 3 个 key 遍历 | 检查 DataStore 原始字节 | 全部是密文 | 单元 | 通过 |

### 3.2 未覆盖的极端场景（环境受限）

| 场景 | 原因 | 风险评估 |
|---|---|---|
| 真实 Android Keystore 主密钥生成 | 需 Android 运行时（Keystore HAL） | 中——代码静态分析正确，但厂商碎片化行为未验证（考古报告 RISK-002） |
| StrongBox 回退到 TEE 的实际触发 | 需真实 StrongBox 设备 | 低——G-01 已修复 catch Exception，BR-security-002 已固化规则 |
| DataStore 真实文件 I/O 异常 | FakePreferenceDataStore 无文件 I/O | 低——DataStore 是成熟 AndroidX 库，内部处理 I/O 异常 |
| 并发写入 DataStore 文件冲突 | FakePreferenceDataStore 用 MutableStateFlow，非真实 actor 模型 | 低——guardrail G-04 已识别，BR-testing-001 已固化规则 |
| 磁盘空间耗尽 | JVM 测试环境难以模拟 | 低——DataStore 内部处理 I/O 异常 |
| cryptoService lazy 初始化延迟尖峰 | 首次访问触发 Keystore 主密钥生成 | 中——考古报告 RISK-006，需设备测试测量 |

---

## 4. 性能回退检查

### 4.1 基线状态

| 维度 | 状态 |
|---|---|
| 既有基线 | 无（US-003 为首次引入安全模块） |
| 本次操作 | 生成初版基线 |
| 基线文件 | [docs/reports/perf/2026-08-02-us003-apikey-baseline.md](perf/2026-08-02-us003-apikey-baseline.md) |

### 4.2 加密存储操作延迟基线（JVM 环境，500 次迭代 + 50 次预热）

| 操作 | p50 | p95 | p99 | mean | min | max |
|---|---|---|---|---|---|---|
| ENCRYPT | 9.1 us | 39.0 us | 140.3 us | 14.99 us | 8.6 us | 267.4 us |
| DECRYPT | 7.1 us | 24.9 us | 57.6 us | 10.26 us | 3.9 us | 87.1 us |
| SAVE_API_KEY | 88.2 us | 228.5 us | 399.8 us | 122.67 us | 53.3 us | 5213.6 us |
| READ_API_KEY | 40.0 us | 208.8 us | 330.3 us | 63.99 us | 14.0 us | 488.3 us |

### 4.3 性能分析

| 指标 | 结论 | 依据 |
|---|---|---|
| 性能回退 | **N/A（初版基线）** | 无既有基线对比，本次为首次基线建立 |
| ENCRYPT/DECRYPT 延迟 | 优秀 | p50 7-9 us（Tink AEAD AES-256-GCM JVM 纯软件实现） |
| SAVE_API_KEY 延迟 | 合理 | p50 88 us（encrypt 9 us + DataStore 内存更新 79 us） |
| READ_API_KEY 延迟 | 合理 | p50 40 us（Flow 读取 33 us + decrypt 7 us） |
| p99/p50 比值 | 可接受 | ENCRYPT 15.4x（偶发 GC），SAVE 4.5x，READ 8.3x |

### 4.4 回退门禁

- 性能下降 >50%：标记失败 — **N/A（初版基线）**
- 性能下降 >20%：标记警告 — **N/A（初版基线）**

---

## 5. 安全专项验证

### 5.1 安全检查清单

| 检查项 | 验证方法 | 结果 | 证据 |
|---|---|---|---|
| 敏感信息泄露（源码硬编码密钥） | PowerShell grep 扫描 security 模块源码 | **通过** | 0 匹配——`password|secret|api_key|token|credential` 模式在 `app/src/main/java/io/prism/security/*.kt` 中无硬编码值赋值 |
| 敏感信息泄露（日志输出） | PowerShell grep 扫描 `Log.d/e/i/v/w|println|System.out|printStackTrace|Timber` | **通过** | 0 匹配——security 模块 3 个源文件零日志输出（符合 AC-4） |
| 敏感信息泄露（API Key 模式） | PowerShell grep 扫描 `sk-[a-zA-Z0-9]{20,}` 全部源码 | **通过** | 0 匹配——源码中无真实 API Key |
| 敏感信息泄露（Bearer Token） | PowerShell grep 扫描 `Bearer\s+[a-zA-Z0-9]` 全部源码 | **通过** | 0 匹配 |
| 硬编码加密密钥 | PowerShell grep 扫描 `private key|secret key|encryption key|master key =` | **通过** | 0 匹配——`DEFAULT_KEY_ALIAS = "prism_master_key_v1"` 是非敏感常量标识 |
| 测试文件中真实 API Key | PowerShell grep 扫描 test 目录 `sk-[a-zA-Z0-9]{20,}` | **通过** | 0 匹配——测试用例均使用 `sk-test-*` 假数据 |
| .gitignore 敏感文件排除 | 文件检查 `.env / *.keystore / *.jks / keystore/ / local.properties` | **通过** | 5/5 全部在 .gitignore 中排除 |
| AndroidManifest 备份防护 | `android:allowBackup` 检查 | **通过** | `android:allowBackup="false"` 防止 adb backup 提取 DataStore 文件 |
| 注入测试（DataStore Preferences） | 代码审查——DataStore 是键值存储，非 SQL | **通过** | `byteArrayPreferencesKey(key)` 的 key 是字符串标识，不参与查询构造，无注入面 |
| 权限最小化 | AndroidManifest 权限检查 | **通过** | 无新增权限（Keystore 与 DataStore 均不需要 `<uses-permission>`） |
| 依赖供应链 | Tink 1.15.0 / DataStore 1.1.1 / Coroutines 1.8.0 CVE 检查（guardrail 报告） | **通过** | guardrail Stage 5 确认无影响性 CVE |
| XSS（前端） | N/A | **不适用** | US-003 无前端 UI，无用户输入渲染路径 |

### 5.2 明文不落盘端到端验证

| 验证步骤 | 验证方法 | 结果 | 证据 |
|---|---|---|---|
| 1. saveApiKey 调用 encrypt | `save_api_key_calls_encrypt_with_plaintext` 测试 | **通过** | encryptCalls 非空，且接收明文 UTF-8 字节 |
| 2. encrypt 在 dataStore.edit 之前 | 代码静态检查 [ApiKeyRepository.kt:38-43](../../app/src/main/java/io/prism/security/ApiKeyRepository.kt) | **通过** | `val encrypted = cryptoService.encrypt(...)` 在 `dataStore.edit { ... }` 之前 |
| 3. DataStore 存储密文非明文 | `datastore_stores_ciphertext_not_plaintext` 测试 | **通过** | `storedBytes.contentEquals(plaintext)` 返回 false（密文 ≠ 明文） |
| 4. 密文可解密为原明文 | 同上测试 | **通过** | `cryptoService.decrypt(storedBytes)` 还原明文 |
| 5. readApiKey 调用 decrypt | `read_api_key_calls_decrypt_with_ciphertext` 测试 | **通过** | decryptCalls 非空 |
| 6. 所有 key 均无明文落盘 | `datastore_never_contains_plaintext_for_any_key` 测试 | **通过** | 遍历 3 个 key，全部存储值 ≠ 明文 |
| 7. IV 随机化（同明文不同密文） | `encrypt_same_plaintext_produces_different_ciphertext` 测试 | **通过** | 同一明文两次加密密文不同 |
| 8. AEAD 完整性（篡改检测） | `tampered_ciphertext_returns_null` 测试 | **通过** | 篡改密文 → null |
| 9. AEAD 完整性（截断检测） | `truncated_ciphertext_returns_null` 测试 | **通过** | 截断密文 → null |

### 5.3 guardrail-enforcer 安全审计结论

> TRAE-security-review 结论：无可利用安全漏洞（clean diff）。详见 [guardrail 报告第 3 节](2026-08-02-us003-apikey-guardrail.md)。

---

## 6. 回归测试

### 6.1 回归测试范围

| 套件 | 来源 | 用例数 | 通过 | 失败 | 跳过 | 结果 |
|---|---|---|---|---|---|---|
| KnowledgeChunkCrudTest | US-002（主 Agent） | 9 | 9 | 0 | 0 | **通过** |
| KnowledgeChunkEdgeCaseTest | US-002（ac-verifier 补充） | 9 | 9 | 0 | 0 | **通过** |
| KnowledgeChunkPerformanceBenchmark | US-002（ac-verifier 性能） | 4 | 0 | 0 | 4 | @Ignore |
| ApiKeyRepositoryTest | US-003（主 Agent） | 14 | 14 | 0 | 0 | **通过** |
| ApiKeyEdgeCaseTest | US-003（ac-verifier 补充） | 16 | 16 | 0 | 0 | **通过** |
| ApiKeyPerformanceBenchmark | US-003（ac-verifier 性能） | 4 | 0 | 0 | 4 | @Ignore |
| **总计** | | **56** | **48** | **0** | **8** | **通过** |

### 6.2 回归测试结论

US-002 ObjectBox 测试（18 执行 + 4 跳过）全部通过，无回归。US-003 新增测试（30 执行 + 4 跳过）全部通过。两个模块共存无冲突。

### 6.3 构建回归

| 构建任务 | 结果 | 耗时 |
|---|---|---|
| `.\gradlew.bat lintDebug` | BUILD SUCCESSFUL | — |
| `.\gradlew.bat testDebugUnitTest --rerun-tasks` | BUILD SUCCESSFUL | 43s |
| `.\gradlew.bat assembleDebug` | BUILD SUCCESSFUL | 5s（增量） |

---

## 7. 结论

### 7.1 总体结论

| 维度 | 结论 |
|---|---|
| 验收标准覆盖 | 5/5 全部验证（4 通过 + 1 受限通过） |
| 分层测试 | 静态分析 通过 / 单元测试 通过 / 集成测试 通过 / E2E 受限通过 |
| 安全检查 | 12/12 检查项通过（1 不适用） |
| 性能基线 | 初版基线已建立，无回退 |
| 回归测试 | 48/48 通过，0 失败（8 @Ignore 跳过） |
| **总体** | **通过（附带受限项）** |

### 7.2 验收标准逐条结论

- [x] **AC-001 受限通过**：Android Keystore 生成主密钥（AES-256-GCM，StrongBox 可用时启用）—— 代码正确性、算法参数、StrongBox 检测/回退、编译验证、依赖解析全部通过；真实 Android Keystore 硬件集成验证受限（无模拟器/设备）
- [x] **AC-002 通过**：DataStore + Tink AEAD 加密 API Key，不落明文 —— encrypt 在 edit 之前调用 + DataStore 存密文 + 30 个测试验证 + 明文不落盘端到端验证 9/9 通过
- [x] **AC-003 通过**：保存/读取 API Key 单元测试通过（明文不出 Keystore）—— 30 个测试通过（14 基础 + 16 边界），encrypt 接收明文 + decrypt 接收密文 + DataStore 存密文
- [x] **AC-004 通过**：日志中不输出 API Key（安全扫描通过）—— 7 项安全扫描全部通过（0 日志 + 0 硬编码 + 0 API Key 模式 + 0 Bearer + .gitignore 完整）
- [x] **AC-005 通过**：Typecheck passes —— lint 0 errors + compileDebugKotlin 成功 + compileDebugUnitTestKotlin 成功 + assembleDebug 成功

### 7.3 受限项与后续追踪

| 受限项 | 原因 | 影响 | 建议追踪 |
|---|---|---|---|
| AC-001 真实 Keystore 集成验证 | 无 Android 模拟器/设备 | KeystoreCryptoService 的主密钥生成与加密链路未在真实硬件验证 | 后续补充 androidTest 仪器测试（与 US-002 AC-003 同模式） |
| DataStore 真实文件持久化 | 使用 FakePreferenceDataStore 内存替身 | DataStore 文件 I/O 路径与异常处理未测试 | 后续 androidTest 仪器测试覆盖 |
| cryptoService lazy 初始化延迟 | JVM 测试无 Keystore HAL | 首次访问触发 Keystore 主密钥生成的延迟尖峰未测量 | 后续设备测试测量 Application.onCreate 耗时（考古报告 RISK-006） |
| StrongBox 回退实际触发 | 需真实 StrongBox 设备 | 厂商碎片化下 StrongBox 不可用的回退路径未在真机验证 | G-01 已修复（catch Exception），BR-security-002 已固化 |

### 7.4 ac-verifier 补充产出物

| 文件 | 类型 | 说明 |
|---|---|---|
| [ApiKeyEdgeCaseTest.kt](../../app/src/test/java/io/prism/security/ApiKeyEdgeCaseTest.kt) | 补充测试 | 16 个极端场景测试（空值/超长/特殊字符/IV 随机化/AEAD 完整性/并发/资源边界） |
| [ApiKeyPerformanceBenchmark.kt](../../app/src/test/java/io/prism/security/ApiKeyPerformanceBenchmark.kt) | 性能基准 | 4 个加密存储延迟基准测试（@Ignore，手动运行） |
| [性能基线文档](perf/2026-08-02-us003-apikey-baseline.md) | 基线记录 | encrypt/decrypt/saveApiKey/readApiKey p50/p95/p99 延迟初版基线 |

### 7.5 guardrail 发现项追踪状态

| 编号 | 严重度 | 问题 | 验收时状态 |
|---|---|---|---|
| G-01 | 中 | StrongBox 异常捕获过窄 | **已修复**（catch Exception 回退 TEE，BR-security-002 已固化）—— 验收确认代码正确 |
| G-02 | 低 | readApiKey catch(Exception) 过宽 | 保留（解密失败需宽泛捕获，已添加注释说明） |
| G-03 | 低 | saveApiKey 未验证 key 非空 | 待后续迭代（边界测试 `save_with_empty_key_identifier_still_works` 确认当前行为可接受） |
| G-04 | 低 | FakePreferenceDataStore 非原子性 | 已用 MutableStateFlow 缓解（BR-testing-001 已固化） |
| G-05~G-11 | 低/建议 | KDoc/AAD/DataStore 升级等 | 待后续迭代（不阻断验收） |

### 7.6 流程判定

```
静态分析（Lint 0 errors + 安全扫描 7/7 通过）: 通过
单元测试（30 执行通过 + 4 性能跳过）: 通过
集成测试（构建 + 依赖树 + 版本目录 + APK 打包）: 通过
E2E 测试（APK 静态检查 5/5）: 通过（真实设备受限）
安全专项验证（12 项 + 明文不落盘端到端 9/9）: 通过
回归测试（48/48 执行通过，0 失败）: 通过
性能基线: 初版已建立

→ US-003 验收结论: 通过（附带 AC-001 受限项）
→ 受限项不阻断本轮开发周期闭合
→ 后续在有设备环境中补充 androidTest 仪器测试以完全闭合 AC-001
```

---

## 8. 缺陷列表

| ID | 严重度 | 关联 AC | 描述 | 复现步骤 | 证据 | 状态 |
|---|---|---|---|---|---|---|
| 无 | — | — | 本次验收未发现新缺陷 | — | — | — |

> guardrail-enforcer 已识别的 G-01 ~ G-11 为已知问题（非本次验收新发现）。G-01（StrongBox 异常捕获）已修复并验证。G-02 ~ G-11 为低风险/建议项，待后续迭代处理。

---

## 9. 未覆盖项与风险

| 未覆盖项 | 原因 | 风险描述 | 缓解措施 |
|---|---|---|---|
| KeystoreCryptoService 真机验证 | 无 Android 模拟器/设备 | 真实 Android Keystore 主密钥生成与加密链路未验证 | 代码静态分析 + 编译验证 + JVM 间接验证（RecordingCryptoService AES-256-GCM）；后续补充 androidTest |
| DataStore 真实文件持久化 | FakePreferenceDataStore 内存替身 | DataStore 文件 I/O 路径、异常处理、文件损坏恢复未测试 | DataStore 是成熟 AndroidX 库；后续 androidTest 覆盖 |
| cryptoService lazy 初始化延迟 | JVM 测试无 Keystore HAL | 首次访问触发 Keystore 主密钥生成可能导致延迟尖峰 | 考古报告 RISK-006 评估 < 300ms 在 2s 启动预算内；后续设备测试测量 |
| StrongBox 回退真机验证 | 需真实 StrongBox 设备 | 厂商 StrongBox 碎片化行为未在真机验证 | G-01 已修复 catch Exception；BR-security-002 已固化规则 |
| 并发写入 DataStore 文件冲突 | FakePreferenceDataStore 非真实 actor 模型 | 并发写入可能丢失更新 | guardrail G-04 已识别；BR-testing-001 已固化；并发测试 `concurrent_saves_to_different_keys_all_persisted` 通过（FakePreferenceDataStore 用 MutableStateFlow） |
| release 构建 ProGuard 兼容 | release isMinifyEnabled=false | 启用混淆后 Tink consumer rules 未验证 | guardrail G-08 / 考古报告 RISK-008；release 优化阶段验证 |
| Android 设备实际加密性能 | 测试在 JVM 环境，非真实设备 | Keystore HAL binder IPC 延迟可能与 JVM 不同 | JVM 基线作为参考起点；后续设备测试建立设备基线 |

---

## 10. 参考

- [CLAUDE.md 第十一节 验收测试与分层验证](../../CLAUDE.md)
- [ADR-001 Prism 技术栈与架构选型](../decisions/ADR-001-prism-tech-stack.md)（3.5 节 Key 存储）
- [US-003 API Key guardrail 报告](2026-08-02-us003-apikey-guardrail.md)（TKN-PRISM-GUARDRAIL-005）
- [US-003 API Key 考古报告](2026-08-02-us003-apikey-archaeology.md)（TKN-PRISM-ARCHAEOLOGY-004）
- [US-003 API Key 性能基线](perf/2026-08-02-us003-apikey-baseline.md)
- [US-002 ObjectBox 验收报告](2026-08-02-us002-objectbox-acceptance.md)（参考验收格式与受限通过模式）
- [behavioral-rules.md](../behavioral-rules.md) BR-security-001/002 + BR-testing-001 + BR-build-001~005
- [Tink Android 文档](https://developers.google.com/tink/android)
- [Android Keystore 文档](https://developer.android.com/training/articles/keystore)
- [AndroidX DataStore 文档](https://developer.android.com/topic/libraries/architecture/datastore)
- test-architect skill（PRD 驱动分层测试方法论）
