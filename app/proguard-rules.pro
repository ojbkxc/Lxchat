# ── Attributes ─────────────────────────────────────────────
-keepattributes *Annotation*, InnerClasses, EnclosingMethod, Signature, Deprecated, SourceFile, LineNumberTable

# ── kotlinx.serialization ─────────────────────────────────
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
# Generated $$serializer for every @Serializable class in the app (data classes AND enums).
-keep,includedescriptorclasses class com.lxseek.chat.**$$serializer { *; }
-keepclassmembers class com.lxseek.chat.** { *** Companion; }
-keepclasseswithmembers class com.lxseek.chat.** { kotlinx.serialization.KSerializer serializer(...); }
# Custom KSerializer implementations referenced by @Serializable(with = ...) must survive.
-keep class com.lxseek.chat.** implements kotlinx.serialization.KSerializer { *; }

# ── Enum classes (valueOf / values reflection) ────────────
# Many enums are read back via Enum.valueOf(name) (ThemeMode, Participant,
# MessageStatus, RunStatus, RunEndReason, ScheduleType, …). R8 normally keeps
# these, but pin them explicitly so a future aggressive rule can never strip
# the constant fields or the synthetic valueOf/values methods.
-keepclassmembers enum com.lxseek.chat.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    <fields>;
}

# ── Room ──────────────────────────────────────────────────
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**
# Room reads entity fields by reflection at runtime.
-keep class com.lxseek.chat.data.local.*Entity { *; }
# TypeConverters are invoked by reflection.
-keep class com.lxseek.chat.data.local.MessageConverters { *; }
# Room-generated DAO implementations live as synthetic classes; keep the abstract DAO.
-keep class com.lxseek.chat.data.local.ChatDao { *; }
# Migration objects are anonymous inner classes referenced only from ChatDatabase.ALL_MIGRATIONS.
-keep class com.lxseek.chat.data.local.migration.** { *; }

# ── WorkManager Workers (instantiated by reflection) ──────
-keep class com.lxseek.chat.service.AutoBackupWorker { public <init>(android.content.Context, androidx.work.WorkerParameters); }
-keep class com.lxseek.chat.service.EmbeddingCacheWorker { public <init>(android.content.Context, androidx.work.WorkerParameters); }
-keep class com.lxseek.chat.service.LoopWorker { public <init>(android.content.Context, androidx.work.WorkerParameters); }
-keep class com.lxseek.chat.service.TaskWorker { public <init>(android.content.Context, androidx.work.WorkerParameters); }

# ── Sandbox (loaded via Class.forName + getDeclaredConstructor) ──
-keep class com.lxseek.chat.sandbox.SandboxManagerFactory { *; }
-keep class com.lxseek.chat.sandbox.SandboxDocumentsProvider { *; }
# Flavor-specific factories (only one is present per build, but both must be
# kept so the Class.forName fallback in AppContainer never throws).
-keep class com.lxseek.chat.sandbox.FdroidSandboxManagerFactory { *; }
-keep class com.lxseek.chat.sandbox.PlaySandboxManagerFactory { *; }

# ── Application entry point ───────────────────────────────
-keep class com.lxseek.chat.LxChatApplication { *; }

# ── OkHttp & Okio ─────────────────────────────────────────
-dontwarn okhttp3.**
-dontwarn okio.**

# ── DataStore ─────────────────────────────────────────────
-keepclassmembers class * extends androidx.datastore.preferences.protobuf.GeneratedMessageLite { <fields>; }

# ── JSch (SSH/SFTP) ───────────────────────────────────────
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**

# ── Compose ───────────────────────────────────────────────
-dontwarn androidx.compose.**

# ── JNI native methods (llama.cpp / proot) ────────────────
-keepclasseswithmembernames class * { native <methods>; }
-keep class com.lxseek.chat.api.LlamaEngine { *; }
-keep class com.lxseek.chat.api.LlamaChatEngine { *; }

# ── Coil ──────────────────────────────────────────────────
-dontwarn coil.**

# ── Media3 / ExoPlayer ────────────────────────────────────
-dontwarn androidx.media3.**

# ── TTS — prevent R8 from obfuscating UtteranceProgressListener callbacks ──
-keep class com.lxseek.chat.util.TtsManager { *; }
-keep class com.lxseek.chat.util.TtsManager$* { *; }

# ── JNA (required for Vosk native binding) ────────────────
-keep class com.sun.jna.** { *; }
-dontwarn com.sun.jna.**
-keep class * extends com.sun.jna.Callback { *; }
-keep class * extends com.sun.jna.PointerType { *; }
-keep class * implements com.sun.jna.Library { *; }

# ── Vosk ──────────────────────────────────────────────────
-keep class org.vosk.** { *; }
-dontwarn org.vosk.**

# ── commons-compress (tar/zip streaming used by sandbox extraction) ──
-dontwarn org.apache.commons.compress.**
-keep class org.apache.commons.compress.** { *; }

# ── MaterialKolor (palette inference via reflection) ──────
-keep class com.materialkolor.** { *; }
-dontwarn com.materialkolor.**

# ── IM gateway (IllegalAccessError 防护) ──────────────────
# 线上日志：feedInboundBatch 处理入站消息时抛 java.lang.IllegalAccessError
#   at com.lxseek.chat.im.b.c(...)
#   at com.lxseek.chat.im.b.a(...)
#   at vr5.b(...) at ur5.k(...) at si1.invokeSuspend(...)
# App 启动时抛一次，收到入站消息后 feedInboundBatch 再抛一次，每次收到消息都抛，
# 导致 pollLoop 永久退出。
#
# 根因：R8 混淆破坏了 com.lxseek.chat.im 包下类的成员访问。高风险点：
#  1. ImGatewayStore.kt:15 `internal val Context.imGatewayDataStore by preferencesDataStore(...)`
#     —— internal 顶层属性 + inline 委托函数，混淆后跨类访问可能失败。
#  2. im 包下多个 `private companion object`（ImPollingReceiver / ImCommandProcessor /
#     ProactiveMessagingService / ImRuntimeState），R8 混淆 private companion 时可能
#     破坏成员访问。
#  3. kotlinx.serialization 的 inline reified decodeFromString/encodeToString 在
#     ImGatewayStore 中大量使用，混淆后访问 $$serializer 可能出错。
#
# CI 未上传 mapping.txt（build.yml/prerelease.yml 只上传 APK），无法精确反混淆
# com.lxseek.chat.im.b 到具体类。保守 keep 整个 IM 包树，防止成员访问被破坏。
# IM 包代码量小，APK 体积影响可忽略。
-keep class com.lxseek.chat.im.** { *; }
-keepclassmembers class com.lxseek.chat.im.** { *; }

# ── SecretCrypto (IllegalAccessError 防护) ────────────────
# ImGatewayStore (被 keep) 跨包调用 SecretCrypto.encrypt/decrypt，
# R8 混淆 SecretCrypto 方法可见性后跨包访问抛 IllegalAccessError。
-keep class com.lxseek.chat.util.SecretCrypto { *; }
-keepclassmembers class com.lxseek.chat.util.SecretCrypto { *; }

# ── 全 app keep（IllegalAccessError 根治） ────────────────
# im 包被 keep 后跨包调用 DebugLog/ConversationRepository/HttpClient 等，
# R8 混淆这些类方法可见性后跨包访问抛 IllegalAccessError。
# 根治方案：keep 整个 com.lxseek.chat.**，禁止 R8 混淆任何 app 代码。
# APK 体积增加可接受，功能稳定性优先。
-keep class com.lxseek.chat.** { *; }
-keepclassmembers class com.lxseek.chat.** { *; }

# ── kotlinx 库 keep（IllegalAccessError 根治） ───────────
# ImGatewayStore inline map 调用 kotlinx.coroutines.flow 内部 emit，
# R8 混淆库方法可见性后抛 IllegalAccessError。
# kotlinx.coroutines 的 @PublishedApi internal 函数被 inline 函数跨包调用，
# R8 优化时可能改变可见性。keep 整个库树防止此问题。
-keep class kotlinx.coroutines.** { *; }
-keepclassmembers class kotlinx.coroutines.** { *; }
# kotlinx.serialization 的 reified decodeFromString/encodeToString 访问 $$serializer
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class kotlinx.serialization.** { *; }
