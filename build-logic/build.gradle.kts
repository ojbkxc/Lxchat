plugins {
    `kotlin-dsl`
}

dependencies {
    // AGP 必须在 implementation 上：android-application / android-library convention plugin
    // 运行时负责 pluginManager.apply("com.android.application"/"com.android.library")，
    // 消费模块的 plugins 块已不再直接 alias AGP，故此处 compileOnly 会在构建期报
    // "Plugin [id: 'com.android.application'] was not found"。版本须与 libs.versions.toml 的 AGP 保持一致。
    implementation("com.android.tools.build:gradle:9.2.1")
    // ASM is used to rewrite the offending bytecode at compile time.
    implementation("org.ow2.asm:asm:9.7")
    implementation("org.ow2.asm:asm-commons:9.7")
    testImplementation("junit:junit:4.13.2")
}

gradlePlugin {
    plugins {
        create("removeFirstLastFix") {
            id = "buildlogic.removefirstlast-fix"
            implementationClass = "buildlogic.RemoveFirstLastFixPlugin"
        }
        create("kotlinSourceSize") {
            id = "buildlogic.kotlin-source-size"
            implementationClass = "buildlogic.KotlinSourceSizePlugin"
        }
        create("androidApplication") {
            id = "buildlogic.android-application"
            implementationClass = "buildlogic.AndroidApplicationConventionPlugin"
        }
        create("androidLibrary") {
            id = "buildlogic.android-library"
            implementationClass = "buildlogic.AndroidLibraryConventionPlugin"
        }
    }
}
