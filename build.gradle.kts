// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    id("buildlogic.kotlin-source-size")
    alias(libs.plugins.android.application) apply false
    // AGP 的 application 与 library 插件同源（同一 gradle 工件）；app 先申请 application
    // 后，library 若仅在子模块带版本申请会报 "already on the classpath with an
    // unknown version"，故必须在根构建统一 apply false 声明。
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}
