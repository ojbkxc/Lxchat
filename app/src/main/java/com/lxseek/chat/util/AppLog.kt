package com.lxseek.chat.util

import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale

object AppLog {
    private const val MAX_ENTRIES = 800
    private val entries = Collections.synchronizedList(mutableListOf<LogEntry>())
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private data class LogEntry(val time: String, val level: String, val tag: String, val msg: String)

    fun d(tag: String, msg: String) { add("D", tag, msg); android.util.Log.d(tag, msg) }
    fun i(tag: String, msg: String) { add("I", tag, msg); android.util.Log.i(tag, msg) }
    fun w(tag: String, msg: String) { add("W", tag, msg); android.util.Log.w(tag, msg) }
    fun e(tag: String, msg: String) { add("E", tag, msg); android.util.Log.e(tag, msg) }
    fun e(tag: String, msg: String, tr: Throwable) { add("E", tag, "$msg | ${tr.javaClass.simpleName}: ${tr.message}"); android.util.Log.e(tag, msg, tr) }

    private fun add(level: String, tag: String, msg: String) {
        synchronized(entries) {
            entries.add(LogEntry(timeFormat.format(Date()), level, tag, msg))
            if (entries.size > MAX_ENTRIES) entries.removeAt(0)
        }
    }

    fun getText(): String {
        val sb = StringBuilder()
        synchronized(entries) {
            for (e in entries) sb.append("${e.time} ${e.level}/${e.tag}: ${e.msg}\n")
        }
        return sb.toString()
    }

    fun getFilteredText(tags: Set<String>, maxLines: Int = 50): String {
        if (tags.isEmpty() || maxLines <= 0) return ""
        val matched = synchronized(entries) {
            entries.filter { it.tag in tags }
        }
        if (matched.isEmpty()) return ""
        val tail = if (matched.size > maxLines) matched.takeLast(maxLines) else matched
        val sb = StringBuilder()
        for (e in tail) sb.append("${e.time} ${e.level}/${e.tag}: ${e.msg}\n")
        return sb.toString()
    }

    fun clear() { synchronized(entries) { entries.clear() } }
}
