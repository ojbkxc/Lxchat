package com.lxseek.chat.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileWriter

/**
 * Serialized append-only file writer, adapted from MediaCrawler's `AsyncFileWriter`.
 *
 * Guards against interleaved writes: every physical write for a given [file] goes through a single
 * [Mutex], so concurrent callers never corrupt a line (JSONL / CSV / plain log). IO runs on
 * [Dispatchers.IO], so the caller thread is never blocked. The parent directory is created on the
 * first write.
 */
class AppendWriter(private val file: File) {

    private val mutex = Mutex()

    /** Append a single [line] plus a trailing newline. */
    suspend fun writeLine(line: String) = writeLines(listOf(line))

    /** Append [lines], each with a trailing newline, as one serialized batch. */
    suspend fun writeLines(lines: Iterable<String>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            file.parentFile?.mkdirs()
            FileWriter(file, true).use { writer ->
                for (line in lines) {
                    writer.write(line)
                    writer.write(LINE_SEPARATOR)
                }
            }
        }
    }

    private companion object {
        private const val LINE_SEPARATOR = "\n"
    }
}