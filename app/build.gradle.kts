plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    // Build-time bytecode fix for the Android 15 removeFirst()/removeLast() crash (see build-logic).
    id("buildlogic.removefirstlast-fix")
}

import java.util.Properties

val keystoreProperties = Properties()
val keystorePropertiesFile = rootProject.file("local.properties")
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(keystorePropertiesFile.reader())
}

// ── 会员/支付密钥（安全修复 H2/H5）──────────────────────────────
// 真实密钥不入 git：优先读 gradle 属性（-P / gradle.properties，CI 用），
// 其次读 local.properties（本地开发用，已被 .gitignore 覆盖）。
// 两处都未配置时 BuildConfig 字段为空串 = 未配置，App 内对应本地验签能力
// 自动禁用并打 WARN（见 membership/MembershipSecrets.kt）。
val secretProps = Properties()
val secretPropsFile = rootProject.file("local.properties")
if (secretPropsFile.exists()) {
    secretProps.load(secretPropsFile.reader())
}

fun membershipSecret(key: String): String =
    ((project.findProperty(key) as String?) ?: secretProps.getProperty(key))
        ?.trim()
        .orEmpty()

fun membershipSecretLiteral(key: String): String =
    "\"" + membershipSecret(key).replace("\\", "\\\\").replace("\"", "\\\"") + "\""


android {
    namespace = "com.lxseek.chat"
    compileSdk {
        version = release(36)
    }

    ndkVersion = "29.0.14206865"

    // Version: prefer gradle properties (passed by CI from git tag) over hardcoded fallback.
    // LxChat is a fresh repo versioned from v1.0.0.
    val appVersionName: String = (project.findProperty("appVersionName") as String?)
        ?: "1.0.0"
    // versionCode must be a positive integer (Android rejects 0). Derive from
    // CI-provided appVersionCode, else bump to at least 1 from the last segment
    // (v1.0.0 -> 1, v1.0.1 -> 1, v1.5.3 -> 3). Fall back to 1 locally.
    val appVersionCode: Int = (project.findProperty("appVersionCode") as String?)?.toIntOrNull()
        ?: appVersionName.substringAfterLast(".").toIntOrNull()?.coerceAtLeast(1)
        ?: 1

    defaultConfig {
        applicationId = "com.lxseek.chat"
        minSdk = 24
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName

        // 会员/支付密钥注入（H2/H5）：空串 = 未配置（本地验签能力禁用 + WARN）。
        // 注意：共享 HMAC 密钥仍可被反编译提取，生产正确做法是服务器端
        // RSA 签名；本轮目标是"不随 APK 分发占位/假密钥"。
        buildConfigField("String", "LXCHAT_HMAC_SECRET", membershipSecretLiteral("LXCHAT_HMAC_SECRET"))
        buildConfigField("String", "LXCHAT_YIPAY_MERCHANT_KEY", membershipSecretLiteral("LXCHAT_YIPAY_MERCHANT_KEY"))

        // H1（安全铁律）：激活服务器证书 pin（SHA-256，形如 "sha256/BASE64="）。
        // 真实 pin 不入 git，读取顺序同上（gradle 属性 -P/gradle.properties →
        // local.properties）。未配置时为空串 → HttpClient.activationClient 降级为
        // 不校验 pin + WARN（fail-open，默认构建不被占位值拖垮）。
        buildConfigField("String", "ACTIVATION_PIN", membershipSecretLiteral("LXCHAT_ACTIVATION_PIN"))


        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += listOf("-DANDROID_STL=c++_shared")
                // lxchat_llama is a downloadable component now (see
                // app/src/main/cpp/CMakeLists.txt). It is built by CI as a
                // Release Asset, not packaged in the APK. Only the in-APK
                // stubs are listed here.
                targets += listOf("lxchat_proot", "redemption_native")
            }
        }
    }

    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
    }

    signingConfigs {
        create("release") {
            storeFile = file(keystoreProperties.getProperty("storeFile", "."))
            storePassword = keystoreProperties.getProperty("storePassword", "")
            keyAlias = keystoreProperties.getProperty("keyAlias", "")
            keyPassword = keystoreProperties.getProperty("keyPassword", "")
        }
    }

    val hasKeystore = keystoreProperties.getProperty("storeFile", ".").let { it != "." }
    val releaseSigning = if (hasKeystore) signingConfigs.getByName("release") else signingConfigs.getByName("debug")

    buildTypes {
        release {
            signingConfig = releaseSigning
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    flavorDimensions += "store"
    productFlavors {
        create("play") {
            dimension = "store"
        }
        create("fdroid") {
            dimension = "store"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    buildFeatures {
        compose = true
        buildConfig = true
        // AIDL is required by the Shizuku UserService (IShellService) interface.
        aidl = true
    }
    // The app switches locales at runtime without Play Feature Delivery. Keep every
    // packaged translation available instead of letting App Bundles split languages.
    bundle {
        language {
            enableSplit = false
        }
    }

    // ABI scope: ndk.abiFilters above restricts native compilation to arm64-v8a
    // only. We intentionally do NOT use splits.abi here because AGP forbids
    // setting the same ABI in both ndk.abiFilters and splits.abi. The result is
    // a single arm64-v8a APK per flavor with no universal fallback — LxChat
    // only targets 64-bit ARM devices.

    // Extract .so files to disk for ProcessBuilder exec (Kai approach)
    @Suppress("UnstableApiUsage")
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }
}

// Proot binaries (libproot_exec.so, libproot_loader.so, libtalloc.so) are
// built via GNUmakefile (see .build-proot/) and placed directly in
// app/src/fdroid/jniLibs/ (fdroid flavor only — PRoot sandbox is fdroid-exclusive).
// No CMake target is needed — the binaries are manually managed prebuilts.
// talloc is built with SONAME=libtalloc.so (no version) so AGP packaging works.

tasks.register<Copy>("copyPlayApk") {
    from("build/outputs/apk/play/release")
    into("release")
    include("*.apk")
}

tasks.register<Copy>("copyFdroidApk") {
    from("build/outputs/apk/fdroid/release")
    into("release")
    include("*.apk")
}

tasks.register<Copy>("copyPlayBundle") {
    from("build/outputs/bundle/playRelease")
    into("release")
    include("*.aab")
}

afterEvaluate {
    if (tasks.findByName("assemblePlayRelease") != null) {
        tasks.named("assemblePlayRelease") {
            finalizedBy("copyPlayApk")
        }
    }
    if (tasks.findByName("assembleFdroidRelease") != null) {
        tasks.named("assembleFdroidRelease") {
            finalizedBy("copyFdroidApk")
        }
    }
    if (tasks.findByName("bundlePlayRelease") != null) {
        tasks.named("bundlePlayRelease") {
            finalizedBy("copyPlayBundle")
        }
    }
}

dependencies {

    // 领域模型层（ChatMessage/RunState 等）与共享常量（Constants）。
    // :core:model 以 api 方式暴露 :core:util，故 app 可直接引用 Constants。
    implementation(project(":core:model"))
    implementation(project(":core:util"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.compose.foundation:foundation")
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.compose.markdown)
    implementation(libs.jetbrains.markdown)
    implementation(libs.coil.compose)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.okhttp)
    implementation(libs.material.color.utilities)

    implementation(libs.work.runtime.ktx)
    implementation(libs.jsch)
    implementation(libs.commons.compress)
    implementation(libs.zxing.core)
    implementation("com.alphacephei:vosk-android:0.3.47")
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Unit tests
    testImplementation("junit:junit:4.13.2")
    testImplementation("io.mockk:mockk:1.13.12")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("androidx.arch.core:core-testing:2.2.0")
}

// Baseline Profile (ArtProfile) 任务保持启用：AGP 会为 release 构建内置默认启动
// profile，冷启动/首帧可提速约 20-30%，APK 仅增大几十 KB。
// 之前禁用无记录原因，本次重新启用并通过 CI 验证。
tasks.whenTaskAdded {
    if (name.contains("StripDebugSymbols") || name.contains("MergeNativeDebugMetadata")) {
        enabled = false
    }
}
