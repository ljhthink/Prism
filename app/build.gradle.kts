plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.objectbox)
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
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    testOptions {
        unitTests.all { test ->
            val ignorePerf = project.findProperty("ignorePerformanceTests")?.toString()
            if (ignorePerf == "false") {
                test.systemProperty("prism.runPerformanceTests", "true")
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
    implementation(libs.pdfbox)
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
}
