pluginManagement {
    repositories {
        // 阿里云镜像（中国开发环境加速；CI 与国际贡献者自动回退到官方源）
        maven {
            url = uri("https://maven.aliyun.com/repository/gradle-plugin")
        }
        maven {
            url = uri("https://maven.aliyun.com/repository/google")
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        // 官方源（fallback）
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven {
            url = uri("https://maven.aliyun.com/repository/google")
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        maven {
            url = uri("https://maven.aliyun.com/repository/public")
            // BR-build-003：public 聚合镜像排除 AndroidX 组，确保从 google 镜像获取，降低交叉投毒风险
            content {
                excludeGroupByRegex("com\\.android.*")
                excludeGroupByRegex("androidx.*")
            }
        }
        google {
            // BR-build-003：官方 google 源同样限定 Android 组，避免 io.ktor / io.modelcontextprotocol
            // 等非 Android 依赖被发往不可达的 dl.google.com 导致解析超时（ADR-001 环境适配修订）
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "Prism"
include(":app")
