package com.lxseek.chat.adb

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.lxseek.chat.util.DebugLog

/**
 * 进程内 ADB Shell 诊断日志源（Shizuku / root 检测 / 命令执行共用）。
 *
 * 所有发生在 [ShizukuManager]、[RootDetector]、[AdbShellBackend] 关键路径的步骤都会写入这里，
 * 供设置页"日志"统一页（[com.lxseek.chat.ui.settings.SettingsLogsPage]）的 ADB Shell 段实时展示——
 * 用户无需抓 logcat 即可看到 Shizuku 状态检查 / root 检测 / 命令执行的完整过程。容量有上限，超限丢弃最旧条目。
 */
object AdbLog {

    private const val TAG = "AdbLog"
    private const val MAX_ENTRIES = 400

    /** 时间格式：HH:mm:ss.SSS */
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val _entries = MutableStateFlow<List<String>>(emptyList())
    val entries: StateFlow<List<String>> = _entries

    /** 追加一条日志（时间戳前缀），同时镜像到 logcat 便于抓包排查。 */
    fun log(msg: String) {
        val line = buildString {
            append(timeFormat.format(Date()))
            append("  ")
            append(msg)
        }
        DebugLog.i(TAG, msg)
        _entries.value = (_entries.value + line).takeLast(MAX_ENTRIES)
    }

    /** 清空日志。 */
    fun clear() {
        _entries.value = emptyList()
    }
}