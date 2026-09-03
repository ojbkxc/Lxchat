plugins {
    id("buildlogic.android-application")
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    // Build-time bytecode fix for the Android 15 removeFirst()/removeLast() crash (see build-logic).
    id("buildlogic.removefirstlast-fix")
}

import java.util.Properties
import java.net.HttpURLConnection
import java.net.URI

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(localPropertiesFile.reader())
}

// ── 会员/支付密钥（安全修复 H2/H5）──────────────────────────────
// 真实密钥不入 git：优先读 gradle 属性（-P / gradle.properties，CI 用），
// 其次读 local.properties（本地开发用，已被 .gitignore 覆盖）。
// 两处都未配置时 BuildConfig 字段为空串 = 未配置，App 内对应本地验签能力
// 自动禁用并打 WARN（见 membership/MembershipSecrets.kt）。
fun localValue(key: String): String? =
    (project.findProperty(key) as String?) ?: localProperties.getProperty(key)

fun secretLiteral(key: String): String =
    "\"" + (localValue(key)?.trim().orEmpty())
        .replace("\\", "\\\\")
        .replace("\"", "\\\"") + "\""


android {
    namespace = "com.lxseek.chat"

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
        versionCode = appVersionCode
        versionName = appVersionName

        // 会员/支付密钥注入（H2/H5）：空串 = 未配置（本地验签能力禁用 + WARN）。
        // 注意：共享 HMAC 密钥仍可被反编译提取，生产正确做法是服务器端
        // RSA 签名；本轮目标是"不随 APK 分发占位/假密钥"。
        buildConfigField("String", "LXCHAT_HMAC_SECRET", secretLiteral("LXCHAT_HMAC_SECRET"))
        buildConfigField("String", "LXCHAT_YIPAY_MERCHANT_KEY", secretLiteral("LXCHAT_YIPAY_MERCHANT_KEY"))

        // H1（安全铁律）：激活服务器证书 pin（SHA-256，形如 "sha256/BASE64="）。
        // 真实 pin 不入 git，读取顺序同上（gradle 属性 -P/gradle.properties →
        // local.properties）。未配置时为空串 → HttpClient.activationClient 降级为
        // 不校验 pin + WARN（fail-open，默认构建不被占位值拖垮）。
        buildConfigField("String", "ACTIVATION_PIN", secretLiteral("LXCHAT_ACTIVATION_PIN"))


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
            storeFile = file(localValue("storeFile") ?: ".")
            storePassword = localValue("storePassword") ?: ""
            keyAlias = localValue("keyAlias") ?: ""
            keyPassword = localValue("keyPassword") ?: ""
        }
    }

    val hasKeystore = (localValue("storeFile") ?: ".").let { it != "." }
    val releaseSigning = if (hasKeystore) signingConfigs.getByName("release") else signingConfigs.getByName("debug")

    // 单元测试可用 classpath 资源（InfantCryNet 黄金样本 JSON）与 Android 内置 org.json。
    testOptions {
        unitTests {
            isIncludeAndroidResources = false
        }
    }

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

    // 维度顺序：store 在前 → 变体名为 fdroidOnline / fdroidFull / playOnline / playFull，
    // 即 assembleFdroidOnlineRelease 等，保持与原 fdroid 前缀一致的命名习惯。
    flavorDimensions += listOf("store", "dist")
    productFlavors {
        // 模型分发维度：online = 运行时下载 YAMNet 模型（默认，APK 小）；
        // full = 把 YAMNet 模型内置进 assets（免下载，APK 增大 ~16MB）。
        create("online") {
            dimension = "dist"
        }
        create("full") {
            dimension = "dist"
        }
        create("play") {
            dimension = "store"
        }
        create("fdroid") {
            dimension = "store"
        }
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

// ── Bundled YAMNet 模型（full 变体）────────────────────────────────────
// online 变体运行时下载模型；full 变体把模型内置进 src/full/assets 以免下载。
// 该目录在 .gitignore 中排除（不入 git），仅在 CI/full 构建时由本任务拉取。
val bundledYamnetFile = file("src/full/assets/baby_monitor/yamnet.tflite")

val downloadBundledYamnet = tasks.register("downloadBundledYamnet") {
    description = "Download YAMNet tflite into src/full/assets (bundled-model flavor)."
    // 有意不声明 outputs.file：模型文件位于 full 变体的 assets 输入目录，若声明为输出，
    // 会触发 Gradle "其它任务（如 Lint）读取了本任务输出却未声明依赖" 的校验报错。
    // 本任务幂等（已存在即早退），无需 up-to-date 推断；打包顺序由
    // afterEvaluate 中 merge*Full*Assets -> downloadBundledYamnet 的 dependsOn 保证。
    doLast {
        val out = bundledYamnetFile
        if (out.isFile && out.length() > 0L) {
            logger.lifecycle("Bundled YAMNet already present ({} bytes)", out.length())
            return@doLast
        }
        out.parentFile.mkdirs()
        val urls = listOf(
            "https://huggingface.co/thelou1s/yamnet/resolve/main/lite-model_yamnet_tflite_1.tflite",
            "https://hf-mirror.com/thelou1s/yamnet/resolve/main/lite-model_yamnet_tflite_1.tflite",
        )
        var lastError: Exception? = null
        for (url in urls) {
            try {
                val conn = URI(url).toURL().openConnection() as HttpURLConnection
                conn.connectTimeout = 30_000
                conn.readTimeout = 120_000
                conn.instanceFollowRedirects = true
                conn.inputStream.use { input -> out.outputStream().use { output -> input.copyTo(output) } }
                if (!(out.isFile && out.length() > 0L)) throw RuntimeException("empty download from $url")
                logger.lifecycle("Bundled YAMNet downloaded from $url ({} bytes)", out.length())
                return@doLast
            } catch (e: Exception) {
                lastError = e
                logger.warn("bundled YAMNet download failed from $url: {}", e.message)
            }
        }
        throw GradleException("Failed to download bundled YAMNet model", lastError)
    }
}

tasks.register<Copy>("copyPlayApk") {
    from("build/outputs/apk/play")
    include("*/release/*.apk")
    into("release")
}

tasks.register<Copy>("copyFdroidApk") {
    from("build/outputs/apk/fdroid")
    include("*/release/*.apk")
    into("release")
}

tasks.register<Copy>("copyPlayBundle") {
    from("build/outputs/bundle/playRelease")
    into("release")
    include("*.aab")
}

afterEvaluate {
    tasks.configureEach {
        // 让 full 变体的 assets 合并/打包依赖模型下载任务，保证内置模型就位。
        if (name.startsWith("merge") && name.contains("Full") && name.endsWith("Assets")) {
            dependsOn(downloadBundledYamnet)
        }
    }
    if (tasks.findByName("assemblePlayOnlineRelease") != null) {
        tasks.named("assemblePlayOnlineRelease") { finalizedBy("copyPlayApk") }
        tasks.named("assemblePlayFullRelease") { finalizedBy("copyPlayApk") }
    }
    if (tasks.findByName("assembleFdroidOnlineRelease") != null) {
        tasks.named("assembleFdroidOnlineRelease") { finalizedBy("copyFdroidApk") }
        tasks.named("assembleFdroidFullRelease") { finalizedBy("copyFdroidApk") }
    }
    if (tasks.findByName("bundlePlayRelease") != null) {
        tasks.named("bundlePlayRelease") { finalizedBy("copyPlayBundle") }
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
    // Baby cry monitor: on-device YAMNet audio classification (AudioSet 521 classes).
    implementation("org.tensorflow:tensorflow-lite:2.14.0")
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Unit tests
    testImplementation("junit:junit:4.13.2")
    testImplementation("io.mockk:mockk:1.13.12")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("androidx.arch.core:core-testing:2.2.0")
    // InfantCryNet 黄金样本对齐测试需要解析 JSON（主代码用 Android 内置 org.json）。
    testImplementation("org.json:json:20240303")
}

// Baseline Profile (ArtProfile) 任务保持启用：AGP 会为 release 构建内置默认启动
// profile，冷启动/首帧可提速约 20-30%，APK 仅增大几十 KB。
// 之前禁用无记录原因，本次重新启用并通过 CI 验证。
tasks.whenTaskAdded {
    if (name.contains("StripDebugSymbols") || name.contains("MergeNativeDebugMetadata")) {
        enabled = false
    }
}
