# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.lxseek.chat.**$$serializer { *; }
-keepclassmembers class com.lxseek.chat.** { *** Companion; }
-keepclasseswithmembers class com.lxseek.chat.** { kotlinx.serialization.KSerializer serializer(...); }

# Room
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# OkHttp & Okio
-dontwarn okhttp3.**
-dontwarn okio.**

# DataStore
-keepclassmembers class * extends androidx.datastore.preferences.protobuf.GeneratedMessageLite { <fields>; }

# JSch (SSH/SFTP)
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**

# Compose
-dontwarn androidx.compose.**

# JNI native methods (llama.cpp / proot)
-keepclasseswithmembernames class * { native <methods>; }
-keep class com.lxseek.chat.api.LlamaEngine { *; }
-keep class com.lxseek.chat.api.LlamaChatEngine { *; }


# Coil
-dontwarn coil.**

# Media3 / ExoPlayer
-dontwarn androidx.media3.**


# TTS — prevent R8 from obfuscating UtteranceProgressListener callbacks
-keep class com.lxseek.chat.util.TtsManager { *; }
-keep class com.lxseek.chat.util.TtsManager$* { *; }

# JNA (required for Vosk native binding)
-keep class com.sun.jna.** { *; }
-dontwarn com.sun.jna.**
-keep class * extends com.sun.jna.Callback { *; }
-keep class * extends com.sun.jna.PointerType { *; }
-keep class * implements com.sun.jna.Library { *; }
-keepattributes Signature,InnerClasses,EnclosingMethod,Annotation

# Vosk
-keep class org.vosk.** { *; }
-dontwarn org.vosk.**
