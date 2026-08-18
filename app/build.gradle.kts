import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.objectbox)
}

// M8 发布配置（ADR-018）：读取 keystore/keystore.properties 配置 release 签名。
// 文件位于 keystore/ 目录（.gitignore 排除），不存在时 release 构建降级为无签名（CI 可用）。
// 使用 inputStream().use { } 确保文件句柄释放（guardrail CR-1 修复）。
val keystorePropertiesFile = rootProject.file("keystore/keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystorePropertiesFile.inputStream().use { keystoreProperties.load(it) }
}

android {
    namespace = "io.prism"
    compileSdk = 34
    buildToolsVersion = "36.1.0"

    defaultConfig {
        applicationId = "io.prism"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        // M3 引入 Apache POI（poi-ooxml）后方法数/引用数可能超限，启用 Multidex（ADR-007 5.3）
        multiDexEnabled = true
        // M7 设备适配（ADR-017 4.8）：仅打包 arm64-v8a + armeabi-v7a 两个 ABI，
        // 排除 x86 / x86_64（模拟器用，生产无用），减小 APK 体积（ONNX Runtime Android AAR
        // 含这两个 ABI 的 native 库，排除其他 ABI 可减约 40%）。
        // 开发者如需在 x86 模拟器调试，可用 arm64 模拟器或加 -Pprism.includeX86 覆盖 abiFilters。
        ndk {
            abiFilters += if (project.hasProperty("prism.includeX86")) {
                listOf("arm64-v8a", "armeabi-v7a", "x86_64")
            } else {
                listOf("arm64-v8a", "armeabi-v7a")
            }
        }
    }

    // M8 发布签名配置（ADR-018）：keystore.properties 存在时创建 release 签名配置
    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // M8 ADR-018：启用 R8 代码压缩 + 混淆 + 优化（proguard-rules.pro 补充所有依赖 keep 规则）
            isMinifyEnabled = true
            // M8 ADR-018：启用资源压缩（移除未引用资源，需 isMinifyEnabled=true）
            isShrinkResources = true
            // M8 ADR-018：keystore.properties 存在时签名 release，否则无签名（CI 可用）
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
    }

    // 性能基准可复跑（DEF-02）：默认跳过；传 -PignorePerformanceTests=false 时注入系统属性使基准运行。
    // US-022 AC-5 补强（2026-08-09）：isReturnDefaultValues = true 让 android.util.Log 等 stub
    // 静态方法在纯 JVM 单元测试中返回默认值（0/null）而非抛 "not mocked" RuntimeException，
    // 使 SkillRegistry 等含 Log 调用的纯逻辑可在不引入 Robolectric/Mockito 的情况下测试。
    testOptions {
        unitTests {
            isReturnDefaultValues = true
            // R1（ADR-032）：Robolectric 需要加载 Android resources（pdfbox-android 测试）
            isIncludeAndroidResources = true
            all { test ->
                val ignorePerf = project.findProperty("ignorePerformanceTests")?.toString()
                if (ignorePerf == "false") {
                    test.systemProperty("prism.runPerformanceTests", "true")
                }
            }
        }
    }

    // 临时配置：禁用 lint 崩溃检测器（已知工具链问题，保留以维持 Typecheck）。
    // 背景：Kotlin 2.3.21 产 metadata v2.3.0，lint 内置 kotlinx-metadata-jvm 无法读取更高版本 metadata，
    // 导致多个 UAST 检测器解析含协程/StateFlow 的源码时抛 throwIfNotCompatible 崩溃（非业务代码缺陷）。
    // US-008 guardrail L1 实测（2026-08-06 lintDebug）：Kotlin 2.3.21 下 CoroutineCreationDuringComposition、
    // StateFlowValueCalledInComposition、FlowOperatorInvokedInComposition 均崩溃，故一并禁用。
    lint {
        disable += "CoroutineCreationDuringComposition"
        disable += "StateFlowValueCalledInComposition"
        disable += "FlowOperatorInvokedInComposition"
    }

    // M3 审计 M3-001 修复（TKN-M3-MILESTONE-AUDIT-001 阻断项）：
    // US-014 引入 PDFBox 3.0.8 后，pdfbox / fontbox / pdfbox-io / log4j-api 四个 jar 都含
    // META-INF/DEPENDENCIES 文件，AGP mergeDebugJavaResource 阶段因路径冲突打包失败。
    // 排除该重复资源文件即可（META-INF/DEPENDENCIES 仅是 jar 元数据，运行时不需要）。
    // 修复前：`gradlew assembleDebug` 在 mergeDebugJavaResource 失败，APK 无法打包。
    // 修复后：APK 正常打包，US-014~US-019 真机可验证。
    packaging {
        resources {
            excludes += "META-INF/DEPENDENCIES"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.tink.android)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.lottie.compose)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.mcp.kotlin.sdk.client)
    // 内置 Filesystem MCP Server（US-009）：本地 Server 承载进程内工具（ADR-006 5.1）
    implementation(libs.mcp.kotlin.sdk.server)
    implementation(libs.androidx.documentfile)
    // M3 RAG（ADR-007）：端侧嵌入运行时（onnxruntime-android，MIT）+ 文档解析（Apach POI，Apache 2.0）
    implementation(libs.onnxruntime.android)
    implementation(libs.poi.ooxml)
    // R1（UXR10 真机修复）：桌面 PDFBox 3.0.8 依赖 java.awt（Android 无此包）→ 真机解析 PDF 崩溃。
    // 生产切换 pdfbox-android（Apache PDFBox 2.0.27 的 Android 移植，ADR-032）；桌面 pdfbox 仅 test 供夹具。
    implementation(libs.pdfbox.android)
    // M4 Skills（ADR-013 5.2）：SKILL.md frontmatter YAML 解析（snakeyaml-engine-kmp，Apache 2.0）
    implementation(libs.snakeyaml.engine.kmp)
    implementation(libs.markdown.renderer.m3)
    debugImplementation(libs.androidx.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)
    // 真实 SSE 服务器集成测试（ADR-004 4.7：MockEngine 不支持 SSECapability）
    testImplementation(libs.ktor.server.core)
    testImplementation(libs.ktor.server.netty)
    testImplementation(libs.ktor.server.sse)
    // US-014：JVM 单测用 onnxruntime（纯 JVM 版，含桌面原生库）替代 onnxruntime-android AAR
    testImplementation(libs.onnxruntime)
    // R1（ADR-032）：桌面 pdfbox 仅供测试夹具生成 PDF（TestDocumentFactory/DocumentParserEdgeCaseTest）
    testImplementation(libs.pdfbox)
    // R1（ADR-032）：Robolectric 在 JVM 单测中提供 android.graphics，使 pdfbox-android 可测
    testImplementation(libs.robolectric)
}
