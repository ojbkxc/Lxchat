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

# ── Shizuku user service ─────────────────────────────────
# ShellUserService is instantiated reflectively by the Shizuku server in an
# isolated privileged process (by fully qualified class name), so it must keep
# its public constructors and not be renamed/stripped. IShellService is the
# AIDL interface used to build the client-side proxy.
-keep,allowobfuscation class com.lxseek.chat.adb.IShellService { *; }
-keep class com.lxseek.chat.adb.IShellService$* { *; }
-keep class com.lxseek.chat.adb.ShellUserService { *; }

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

# ── Shizuku (IPC + reflection) ────────────────────────────
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }
-dontwarn rikka.shizuku.**
-dontwarn moe.shizuku.**

# ── IM IllegalAccessError 根因修复 ───────────────────────
# 根因：ImRuntimeState 的 companion object 被声明为 private，导致
# kotlinx.serialization 生成的 $$serializer 通过 synthetic accessor 访问
# Companion 字段时，R8 优化内联了 accessor，跨类直接访问 private 字段
# 抛出 IllegalAccessError。修复：去掉 private，让 Companion 字段为 public。

# ── Plugin / ToolProvider interfaces (reflective lookup) ──
# PluginHost discovers plugins and tool providers by reflection; the
# interfaces themselves and their implementing classes must survive
# obfuscation so Class.forName / getDeclaredConstructor keep working.
-keep interface com.lxseek.chat.plugin.Plugin { *; }
-keep interface com.lxseek.chat.tool.ToolProvider { *; }
-keep class com.lxseek.chat.membership.** { *; }
-keep class com.lxseek.chat.skill.** { *; }

# ── JNI native methods (redemption_native + llama + proot) ─
# Pin every native method on every class so R8 never strips the JNI link.
-keepclassmembers class * { native <methods>; }
