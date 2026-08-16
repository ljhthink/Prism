# ProGuard / R8 rules for Prism release builds (M8 ADR-018).
#
# Compose / AndroidX / OkHttp / kotlinx.coroutines / kotlinx.serialization
# 均自带 consumer-rules.pro，R8 自动合并。本文件仅补充：
# 1. 无 consumer-rules 的第三方依赖（ObjectBox / ONNX Runtime / POI / PDFBox / Tink / MCP SDK / SnakeYAML）
# 2. Prism 应用自身反射/JNI 入口点
# 3. Kotlin 元数据保留（协程/反射/序列化依赖）
#
# 参考来源：
# - Android 官方 R8 keep 规则指南
# - ObjectBox FAQ: ProGuard rules
# - ONNX Runtime Android README
# - Apache POI / PDFBox ProGuard recommendations
# - Tink Android ProGuard rules
# - kotlinx.serialization 编译时代码生成（无需反射规则）

# =============================================================================
# 1. Kotlin 元数据（协程 + 反射 + 序列化依赖）
# =============================================================================

# 保留 Kotlin Metadata 注解（协程状态机 + KClass 反射 + @Serializable 编译器生成的 $serializer 需要读取 metadata）
-keep class kotlin.Metadata { *; }
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,RuntimeVisibleTypeAnnotations

# 保留 Kotlin 协程 continuation 字段（协程状态机通过字段名恢复挂起点）
-keepclassmembers class kotlin.coroutines.experimental.** { volatile <fields>; }
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }

# 保留 @Serializable 注解的类及其 $serializer companion（kotlinx.serialization 编译器生成）
# kotlinx.serialization 不使用运行时反射，但 R8 需要保留 $serializer 类的无参构造与 deserialize 方法
-keepattributes *Annotation*
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}

# =============================================================================
# 2. ObjectBox（M1/M3/M5 数据层 —— JNI + 代码生成）
# =============================================================================

# ObjectBox 代码生成的实体类 + Cursor + MyObjectBox（反射加载）
-keep class io.prism.data.model.** { *; }
-keep class io.prism.data.**_ { *; }
-keep class io.prism.data.MyObjectBox { *; }
-keep class io.prism.data.*Cursor { *; }
-keep class io.objectbox.** { *; }
-dontwarn io.objectbox.**
# ObjectBox 内部使用 flatbuffers + JNI，保留 native 方法
-keepclasseswithmembernames class * {
    native <methods>;
}

# =============================================================================
# 3. ONNX Runtime（M3 端侧嵌入引擎 —— JNI native 库）
# =============================================================================

# ONNX Runtime 通过 JNI 调用 native 库，保留 native 方法声明 + OrtSession 回调
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**
# ONNX Runtime 内部使用反射加载 OrtProvider
-keep class ai.onnxruntime.OrtProvider { *; }
-keep class ai.onnxruntime.OrtProviders { *; }

# =============================================================================
# 4. Apache POI（M3 文档解析 —— 反射密集 XML 序列化）
# =============================================================================

# POI 使用反射加载 XML bean 映射（XSSFRequest 等）
-keep class org.apache.poi.** { *; }
-keep class org.apache.poi.ss.** { *; }
-keep class org.apache.poi.xssf.** { *; }
-keep class org.apache.poi.xwpf.** { *; }
-dontwarn org.apache.poi.**
-keep class org.openxmlformats.schemas.** { *; }
-dontwarn org.openxmlformats.schemas.**
# POI 依赖的 XmlBeans 反射
-keep class org.apache.xmlbeans.** { *; }
-dontwarn org.apache.xmlbeans.**
-keep class schemaorg_apache_xmlbeans.** { *; }
-dontwarn schemaorg_apache_xmlbeans.**

# =============================================================================
# 5. Apache PDFBox（M3 PDF 解析 —— 反射加载字体/CMap）
# =============================================================================

-keep class org.apache.pdfbox.** { *; }
-dontwarn org.apache.pdfbox.**
-keep class org.apache.fontbox.** { *; }
-dontwarn org.apache.fontbox.**
# PDFBox 通过 ServiceLoader 加载字体引擎
-keepdirectories org/apache/pdfbox/resources/**

# =============================================================================
# 6. Google Tink（M1 安全层 —— 加密 Provider 反射加载）
# =============================================================================

# Tink 使用反射注册 KeyManager / PrimitiveConstructor
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**
-keep class com.google.crypto.tink.subtle.** { *; }
# Tink Provider 注册（ServiceLoader）
-keep class com.google.crypto.tink.config.TinkFips { *; }
-keep class com.google.crypto.tink.aead.AeadConfig { *; }
-keep class com.google.crypto.tink.aead.AesGcmJce { *; }
-keep class com.google.crypto.tink.aead.AesGcmHkdfStreamingKeyManager { *; }

# =============================================================================
# 7. MCP Kotlin SDK（M2 —— 相对较新，可能无 consumer-rules）
# =============================================================================

-keep class io.modelcontextprotocol.** { *; }
-dontwarn io.modelcontextprotocol.**
# MCP SDK 使用 kotlinx.serialization 序列化 JSON-RPC 消息
-keepclassmembers class io.modelcontextprotocol.** {
    *** Companion;
    *** serializer(...);
}

# =============================================================================
# 8. SnakeYAML Engine（M4 SKILL.md frontmatter 解析）
# =============================================================================

-keep class org.snakeyaml.engine.** { *; }
-dontwarn org.snakeyaml.engine.**
# SnakeYAML 使用反射实例化 Java 对象（若使用 construct 模式）
# Prism 仅用 LoadSettings（安全模式，无 construct），但保留以防内部依赖

# =============================================================================
# 9. Ktor（M2 SSE 流式 + M5 非流式 chatCompletion）
# =============================================================================

# Ktor 多数模块自带 consumer-rules，此处为安全网补充
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**
-keep class kotlinx.coroutines.debug.** { *; }
-dontwarn kotlinx.coroutines.debug.**

# =============================================================================
# 9.1 markdown-renderer（UX-001 问题 3，ADR-021/022）
# =============================================================================

# mikepenz/multiplatform-markdown-renderer 无 consumer proguard rules（guardrail
# TKN-UXR2-GUARDRAIL-001 实证：0.26.0 的 m3/core AAR 均不含 consumer-rules.pro）。
# R8 可能混淆库内部 @Composable 组件类，导致运行期反射/组件解析失败。
# 保留整个 com.mikepenz.markdown 包（含 Kotlin Metadata，供 Compose 组件查找）。
-keep class com.mikepenz.markdown.** { *; }
-dontwarn com.mikepenz.markdown.**
-keep class org.intellij.markdown.** { *; }
-dontwarn org.intellij.markdown.**

# =============================================================================
# 10. Ktor CIO / OkHttp 引擎（网络层 JNI / TLS）
# =============================================================================

-keep class okhttp3.** { *; }
-dontwarn okhttp3.**
-keep class okio.** { *; }
-dontwarn okio.**

# =============================================================================
# 11. Prism 应用自身入口点
# =============================================================================

# Application / Activity / ViewModel（Android 组件入口，R8 默认保留，但显式声明安全网）
-keep class io.prism.PrismApplication { *; }
-keep class io.prism.MainActivity { *; }
-keep class * extends androidx.lifecycle.ViewModel { *; }
-keep class * extends androidx.lifecycle.AndroidViewModel { *; }

# Compose Composable 函数（R8 默认保留 @Composable，此处安全网）
-keepclassmembers @androidx.compose.runtime.Composable class * {
    *;
}

# AndroidManifest 声明的组件
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider

# =============================================================================
# 12. ServiceLoader（各依赖通过 META-INF/services 加载 Provider）
# =============================================================================

-keepdirectories META-INF/services/**
-keep class javax.annotation.** { *; }

# =============================================================================
# 13. 调试支持（保留源文件名 + 行号用于崩溃日志反混淆）
# =============================================================================

-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# =============================================================================
# 14. 安全网：不警告未解析的引用（第三方 jar 可能引用编译时不可见的类）
# =============================================================================

-dontwarn javax.lang.model.**
-dontwarn org.w3c.dom.**

# =============================================================================
# 15. R8 自动检测的缺失类（missing_rules.txt）
# 这些类是 POI / PDFBox / commons-compress / log4j 的可选依赖，
# 在 Android 运行时不可用但也不被使用，-dontwarn 抑制 R8 缺失类错误。
# =============================================================================

# log4j OSGi 注解（Android 无 OSGi，运行时不加载）
-dontwarn aQute.bnd.annotation.baseline.BaselineIgnore
-dontwarn aQute.bnd.annotation.spi.ServiceConsumer
-dontwarn aQute.bnd.annotation.spi.ServiceProvider

# commons-compress Zstd 压缩（Prism 不使用 Zstd）
-dontwarn com.github.luben.zstd.ZstdInputStream

# log4j FindBugs 注解（编译时注解，运行时不需要）
-dontwarn edu.umd.cs.findbugs.annotations.Nullable

# Java AWT（Android 无 AWT，POI 绘图引用但 Prism 不用绘图 API）
-dontwarn java.awt.Shape

# log4j JSpecify 注解
-dontwarn org.jspecify.annotations.NullMarked

# log4j OSGi 框架类（Android 无 OSGi）
-dontwarn org.osgi.framework.Bundle
-dontwarn org.osgi.framework.BundleContext
-dontwarn org.osgi.framework.FrameworkUtil
-dontwarn org.osgi.framework.ServiceReference
-dontwarn org.osgi.framework.wiring.BundleRevision

# commons-compress XZ 压缩（Prism 不使用 XZ 压缩）
-dontwarn org.tukaani.xz.MemoryLimitException
-dontwarn org.tukaani.xz.SingleXZInputStream
-dontwarn org.tukaani.xz.XZInputStream
