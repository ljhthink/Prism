# ADR-018: M8 集成与发布架构（US-044~US-047）

| 项目 | 内容 |
| --- | --- |
| 状态 | Accepted |
| 日期 | 2026-08-11 |
| 决策者 | 主 Agent |
| 关联文档 | [PRD M8](../PRD.md) / [ADR-001](ADR-001-prism-tech-stack.md) / [ADR-007](ADR-007-m3-rag-tech-stack.md) / [ADR-017](ADR-017-m7-device-adaptation.md) |
| 上游调研 | 本决策为发布工程决策，调研内嵌于本 ADR 4.x 小节 |
| 风险等级 | P3 重大（R8 全量混淆 + 签名密钥管理 + 公开发布，影响全局可逆性低） |

## 背景（Context）

PRD M8 要求 Prism 通过 `ac-verifier` + `functional-validation-auditor` 全量验收后，以自发布形式（GitHub Releases）分发 signed release APK。M0~M7 累计引入的第三方依赖（ObjectBox JNI / ONNX Runtime JNI / Apache POI / PDFBox / Tink / MCP SDK / SnakeYAML / Ktor）在 release 构建中首次面对 R8 全量压缩 + 混淆 + 优化，存在三个核心问题：

1. **R8 缺失类警告**：POI/PDFBox/commons-compress/log4j 引用了大量编译时可选依赖（Zstd/XZ/AWT/OSGi），Android 运行时不可用但 R8 会因找不到类阻断构建。
2. **签名密钥管理**：APK 公开发布必须 signed，密钥泄露等同私钥泄露，需要明确生成、存储、备份、`.gitignore` 规则。
3. **APK 体积**：ONNX Runtime Android AAR 默认含 4 个 ABI（arm64-v8a/armeabi-v7a/x86/x86_64），生产无用 ABI 占据约 40% 体积，需过滤。

未做本决策前，`./gradlew :app:assembleRelease` 因 R8 missing class 阻断，无法产出可发布 APK。

## 决策（Decision）

**方案 A：R8 全量启用 + ProGuard 安全网 + 单一 release keystore + 双 ABI 过滤 + GitHub Releases 自发布**

选择此方案的原因：**满足 PRD"自发布"非目标（不上 Google Play）的同时，最大化压缩 APK 体积、保护代码反编译、保留所有依赖运行时正确性，且密钥管理可复用、可备份。**

### 4.1 R8 全量启用（build.gradle.kts）

```kotlin
buildTypes {
    release {
        isMinifyEnabled = true        // R8 代码压缩 + 混淆 + 优化
        isShrinkResources = true     // 资源压缩（需 isMinifyEnabled=true）
        signingConfig = signingConfigs.getByName("release")  // 4.2 keystore
        proguardFiles(
            getDefaultProguardFile("proguard-android-optimize.txt"),
            "proguard-rules.pro"
        )
    }
}
```

### 4.2 签名密钥管理

- **生成**：`keytool -genkeypair -v -storetype pkcs12 -keystore prism-release.jks -keyalg RSA -keysize 2048 -sigalg SHA256withRSA -validity 10000`（RSA 2048，SHA256withRSA，有效期至 2053-12-28）。
  - **keysize 选择**：RSA 2048 在 2026 年仍为 NIST SP 800-57 推荐的最低安全级别（至 2030 年有效）。Prism 不上 Google Play（无 Play App Signing 强制 4096 要求），2048 位对自发布场景已足够，且构建速度优于 4096。**2030 年前应 rotate 至 RSA 3072+ 或 EC P-256**。
- **签名 scheme**：apksigner 实测启用 **APK Signature Scheme v2**（Android 7.0+），覆盖 Prism minSdk 26（Android 8.0）的全部目标设备。v1（JAR signing）已废弃不启用，v3（Android 9.0+）与 v4（Android 11+）未启用（v2 已兼容所有目标 API，无需更高 scheme）。
- **存储**：`keystore/` 目录（`.gitignore` 已排除），含 `prism-release.jks` + `keystore.properties` + `prism-release-credentials.txt`。
- **加载**：`build.gradle.kts` 顶部 `val keystorePropertiesFile = rootProject.file("keystore/keystore.properties")`，`exists()` 时创建 `signingConfigs.release`，否则降级无签名（CI 可用）。
- **凭据备份**：`prism-release-credentials.txt` 记录 store/key 密码（PKCS12 强制两者相同）、alias、DN、算法、有效期、Gradle 配置示例。文件本地保存，不提交。
- **安全约束**：`.gitignore` 第 10-12 行已排除 `keystore/`、`*.keystore`、`*.jks`。
- **证书指纹**（实测，用于发布身份校验）：
  - SHA-256: `7C:EA:D7:FC:DC:32:CA:F5:23:7F:02:F8:D0:83:79:74:C2:9F:43:3F:DF:5A:29:FF:D8:0B:CC:2A:0C:3D:3D:84`
  - SHA-1: `1A:21:0B:F9:E0:45:CD:78:DC:69:1C:24:D2:3A:19:B9:3D:3B:E6:C8`
  - DN: `CN=Prism, OU=Dev, O=Prism, L=Beijing, ST=Beijing, C=CN`

### 4.3 ProGuard/R8 规则（proguard-rules.pro）

按依赖分 15 个章节，核心规则：

| 依赖 | 规则要点 | 原因 |
|---|---|---|
| Kotlin Metadata | `-keep class kotlin.Metadata { *; }` | 协程状态机 + @Serializable $serializer 依赖 metadata |
| ObjectBox | `-keep class io.prism.data.model.** { *; }` + `io.objectbox.**` | JNI + 代码生成反射加载 |
| ONNX Runtime | `-keep class ai.onnxruntime.** { *; }` | JNI native 方法 + OrtProvider 反射 |
| Apache POI | `-keep class org.apache.poi.**` + XmlBeans | XML bean 反射密集 |
| Apache PDFBox | `-keep class org.apache.pdfbox.**` + fontbox | 字体/CMap ServiceLoader |
| Google Tink | `-keep class com.google.crypto.tink.**` | KeyManager 反射注册 |
| MCP Kotlin SDK | `-keep class io.modelcontextprotocol.**` | 较新 SDK 无 consumer-rules |
| SnakeYAML | `-keep class org.snakeyaml.engine.**` | 内部反射实例化 |
| Ktor / OkHttp | `-keep class io.ktor.**` + `okhttp3.**` + `okio.**` | 安全网（多数模块自带 consumer-rules） |
| Prism 入口点 | `-keep class io.prism.PrismApplication/MainActivity` | Application/Activity/ViewModel |
| ServiceLoader | `-keepdirectories META-INF/services/**` | 各依赖 Provider 加载 |
| 调试支持 | `-keepattributes SourceFile,LineNumberTable` | 崩溃日志反混淆 |
| R8 缺失类 | `-dontwarn aQute.bnd.**` 等 15 条（第 15 章节） | 抑制 POI/PDFBox/commons-compress/log4j 可选依赖警告 |

### 4.4 ABI 过滤（M7 ADR-017 4.8 延续）

```kotlin
ndk {
    abiFilters += listOf("arm64-v8a", "armeabi-v7a")
}
```

排除 x86 / x86_64（模拟器用，生产无用），ONNX Runtime AAR 体积减少约 40%。

### 4.5 发布渠道：GitHub Releases

- **触发**：`git tag v0.1.0` → `gh release create v0.1.0 app-release.apk --title ... --notes ...`
- **资产**：signed `app-release.apk` + `mapping.txt`（R8 混淆映射，供崩溃日志反混淆）
- **不发布**：debug APK、未签名 APK、keystore

### 4.6 任务令牌

- US-044 Phase A: `TKN-M8-RELEASE-001`
- US-045 Phase B: `TKN-M8-RELEASE-002`
- US-046 Phase C: `TKN-M8-RELEASE-003`
- US-047 Phase D: `TKN-M8-RELEASE-004`

## 备选方案（Alternatives）

| 方案 | 优点 | 缺点 / 否决理由 |
|---|---|---|
| **B. 关闭 R8（isMinifyEnabled=false）** | 构建简单，无 missing class 问题 | APK 体积膨胀约 60%、代码无混淆易被反编译、违反 PRD 性能/安全要求 |
| **C. 仅 debug 签名 + 不发布** | 无密钥管理负担 | 不满足 PRD M8 "自发布" 验收标准 |
| **D. 分 ABI 发布（arm64-v8a.apk + armeabi-v7a.apk）** | 单 APK 更小 | GitHub Releases 资产管理复杂、用户需选对 ABI、收益边际（已通过 ABI 过滤减 40%） |
| **E. 上架 Google Play** | 自动签名 + 分发 + OBB | PRD 2.非目标明确"不做 Google Play 上架（自发布）" |
| **F. 使用 AAB（Android App Bundle）** | Google Play 动态分发 | 不上 Google Play 时 AAB 无法直接安装，需 bundletool 转 APK，复杂度高 |

## 后果（Consequences）

- **正面后果**：
  - APK 体积通过 R8 + 资源压缩 + ABI 过滤三重优化，预期 <80MB（实测 78.44MB，见 US-045 验收报告）。
  - 代码经 R8 混淆 + 优化，反编译成本显著提升，符合 PRD 4.安全要求。
  - RSA 2048 + SHA256withRSA 签名密钥，有效期至 2053-12-28（约 27 年），覆盖 Prism v0.x ~ v1.x 生命周期。
  - `keystore.properties` 缺失时 release 构建降级无签名，CI 可用，本地发布时存在即签名。
- **负面后果 / 代价**：
  - R8 全量启用后，新增依赖必须同步补 ProGuard 规则，否则 release 构建可能运行时 ClassNotFound。
  - keystore 丢失等同发布身份丢失，无法发布同包名升级版（需凭据备份文档恢复或重新生成导致升级断裂）。
  - R8 混淆后崩溃日志需 mapping.txt 反混淆，发布时必须同步归档 mapping。
- **需要同步更新的文档或代码**：
  - `app/build.gradle.kts`：签名配置 + R8 启用 + ABI 过滤
  - `app/proguard-rules.pro`：15 章节依赖规则
  - `keystore/keystore.properties` + `keystore/prism-release-credentials.txt`（本地，不入库）
  - `docs/decisions/README.md`：索引追加 ADR-018
  - `README.md`：发布说明引用 ADR-018
  - `prd.json`：追加 US-044~US-047
  - `.gitignore`：已排除 `keystore/`、`*.keystore`、`*.jks`（无需变更）

## 风险与缓解

| 风险 | 等级 | 缓解措施 |
|---|---|---|
| R8 剥离关键类导致运行时崩溃 | 高 | proguard-rules.pro 15 章节逐依赖 keep + US-045 全量回归（1497+ 测试）一票否决 |
| keystore 密钥泄露 | 高 | `.gitignore` 排除 + 本地凭据备份 + 不在日志输出密码 + RSA 2048 + NIST 2030 前 rotate |
| keystore 丢失 | 中 | `prism-release-credentials.txt` 记录完整凭据 + 多重本地备份建议 |
| APK 体积超预期 | 中 | R8 + 资源压缩 + ABI 过滤三重优化，实测 78.44MB，符合 <80MB 预期 |
| GitHub Release 资产上传失败 | 低 | `gh release create` 失败时重试，APK + mapping.txt 本地存档 |
| 30 年后密钥过期 | 极低 | 2053-12-28 前 rotate 至 RSA 3072+ 或 EC P-256（NIST 2030 前建议 rotate，可结合 v2.x 版本升级） |

## 参考

- [Android Shrink your code and resources](https://developer.android.com/build/shrink-code)
- [R8 keep rules guide](https://developer.android.com/build/shrink-code#keep-code)
- [ObjectBox ProGuard rules](https://docs.objectbox.io/advanced#proguard)
- [ONNX Runtime Android README](https://github.com/microsoft/onnxruntime-inference-examples/blob/main/quickstart/mobile/android/README.md)
- [Apache POI ProGuard recommendations](https://poi.apache.org/components/poi-faq.html)
- [Tink Android ProGuard](https://github.com/tink-crypto/tink-java/blob/main/README.md)
- [kotlinx serialization ProGuard](https://github.com/Kotlin/kotlinx.serialization/blob/master/docs/security.md)
- ADR-001 Prism 技术栈 / ADR-007 M3 RAG / ADR-017 M7 设备适配
