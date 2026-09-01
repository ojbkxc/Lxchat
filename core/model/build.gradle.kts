plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.lxseek.chat.core.model"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // model 依赖 util.Constants（消息前缀、Provider 名、持久化上限常量）。
    // util 与 model 同批抽取，见 settings.gradle.kts 的模块注册顺序说明。
    api(project(":core:util"))

    implementation(libs.kotlinx.serialization.json)
    // ChatMessage/TokenUsage 上的 @Immutable 注解。
    implementation("androidx.compose.runtime:runtime:1.9.4")
}
