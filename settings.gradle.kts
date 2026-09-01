pluginManagement {
    includeBuild("build-logic")
    repositories {
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
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "LxChat"

// ── 模块注册顺序说明 ─────────────────────────────────────────
// 多模块抽取批次（modularization phase 1+）：
//   :core:util  — 纯常量（Constants），无 Android/业务依赖，位于最底层。
//   :core:model — 领域模型，依赖 :core:util（消息前缀、Provider 名、持久化上限）。
//   :app        — 宿主应用，依赖上述 core 模块。
// 注意：util 中依赖 R 资源或 app 内类型（如 SelectedAttachment）的文件仍留在
// :app，待去耦后再逐批迁入 :core:util，避免出现 core ↔ app 循环依赖。
include(":core:util")
include(":core:model")
include(":app")
