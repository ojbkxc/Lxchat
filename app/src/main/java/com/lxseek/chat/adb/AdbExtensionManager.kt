package com.lxseek.chat.adb

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Manages downloading and installing the standalone ADB binary as a downloadable extension.
 *
 * Root users do not need this — they go through `su -c` directly. Non-root users on
 * Android 11+ download a prebuilt adb binary (arm64-v8a) from a GitHub Release, mark it
 * executable, and then pair once via wireless debugging. After pairing the connection
 * survives reboots.
 *
 * States: [NOT_DOWNLOADED] → [DOWNLOADING] → [INSTALLED] (or [FAILED]).
 */
class AdbExtensionManager(private val context: Context) {

    companion object {
        private const val TAG = "AdbExtensionManager"
        private const val PREFS_NAME = "adb_extension_prefs"
        private const val KEY_INSTALLED = "installed"
        private const val KEY_LAST_ERROR = "last_error"

        /** GitHub Release URL for the prebuilt adb binary (arm64-v8a, ~4.9 MB). */
        const val DOWNLOAD_URL =
            "https://github.com/ojbkxc/Lxchat/releases/download/v1.0.0/adb-arm64-v8a.bin"
        const val DOWNLOAD_SIZE_HINT = "4.9 MB"
        private const val BINARY_NAME = "adb"
        private const val TMP_NAME = "adb.tmp"
    }

    sealed class State {
        object NotDownloaded : State()
        data class Downloading(val progress: Int) : State()   // 0..100
        object Installed : State()
        data class Failed(val message: String) : State()
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** The final on-disk path of the adb binary once installed. */
    val targetPath: String = "${context.filesDir.absolutePath}/$BINARY_NAME"

    /** Temp path used while streaming the download; renamed to [targetPath] on success. */
    private val tmpPath: String = "${context.filesDir.absolutePath}/$TMP_NAME"

    /** Returns the adb binary path if installed, null otherwise. */
    fun getAdbPath(): String? = if (isInstalled()) targetPath else null

    /** True if the binary is installed and the file actually exists on disk. */
    fun isInstalled(): Boolean {
        if (!prefs.getBoolean(KEY_INSTALLED, false)) return false
        val f = File(targetPath)
        return f.exists() && f.canExecute()
    }

    /** Returns the last persisted failure message, or null. */
    fun lastError(): String? = prefs.getString(KEY_LAST_ERROR, null)

    /**
     * Downloads the adb binary from [DOWNLOAD_URL] to [targetPath].
     * [onProgress] receives values 0..100 and is invoked from the network thread.
     * Returns true on success, false on failure (see [lastError]).
     */
    fun download(onProgress: (Int) -> Unit): Boolean {
        val tmpFile = File(tmpPath)
        try {
            // Clean up any stale temp file from a previous failed attempt.
            if (tmpFile.exists()) tmpFile.delete()

            val request = Request.Builder().url(DOWNLOAD_URL).build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                val msg = "Download failed: HTTP ${response.code}"
                prefs.edit().putString(KEY_LAST_ERROR, msg).apply()
                return false
            }
            val body = response.body ?: run {
                val msg = "Download failed: empty response body"
                prefs.edit().putString(KEY_LAST_ERROR, msg).apply()
                return false
            }
            val totalBytes = body.contentLength()
            FileOutputStream(tmpFile).use { out ->
                val input = body.byteStream()
                val buffer = ByteArray(8 * 1024)
                var downloaded = 0L
                var lastReported = -1
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    out.write(buffer, 0, read)
                    downloaded += read
                    if (totalBytes > 0) {
                        val pct = (downloaded * 100 / totalBytes).toInt().coerceIn(0, 100)
                        if (pct != lastReported) {
                            lastReported = pct
                            onProgress(pct)
                        }
                    }
                }
                out.flush()
            }
            response.close()

            // Rename temp file to final name and mark executable.
            val targetFile = File(targetPath)
            if (targetFile.exists()) targetFile.delete()
            if (!tmpFile.renameTo(targetFile)) {
                val msg = "Failed to rename temp file to $targetPath"
                prefs.edit().putString(KEY_LAST_ERROR, msg).apply()
                return false
            }
            // chmod 755
            val chmod = Runtime.getRuntime().exec(arrayOf("chmod", "755", targetPath))
            chmod.waitFor()
            if (chmod.exitValue() != 0) {
                Log.w(TAG, "chmod 755 returned ${chmod.exitValue()}")
            }
            // Verify executable bit took effect; if not, try Java's setExecutable fallback.
            if (!targetFile.canExecute()) {
                targetFile.setExecutable(true, false)
            }

            prefs.edit()
                .putBoolean(KEY_INSTALLED, true)
                .remove(KEY_LAST_ERROR)
                .apply()
            onProgress(100)
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Download error", e)
            prefs.edit().putString(KEY_LAST_ERROR, e.message ?: "Unknown download error").apply()
            // Clean up partial temp file.
            if (tmpFile.exists()) tmpFile.delete()
            return false
        }
    }

    /** Removes the downloaded binary and resets state to [State.NotDownloaded]. */
    fun uninstall() {
        val f = File(targetPath)
        if (f.exists()) f.delete()
        val tmp = File(tmpPath)
        if (tmp.exists()) tmp.delete()
        prefs.edit().clear().apply()
    }
}