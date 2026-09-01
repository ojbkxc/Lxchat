plugins {
    id("buildlogic.android-library")
}

android {
    namespace = "com.lxseek.chat.core.util"
}

dependencies {
    // 目前仅承载无 Android 依赖的纯常量（Constants.kt），供 :core:model 与 :app 共享。
    // 其余 util 文件仍留在 app 模块（部分依赖 R 资源 / app 内类型，后续批次再抽取）。
}
