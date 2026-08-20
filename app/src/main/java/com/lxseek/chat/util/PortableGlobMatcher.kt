package com.lxseek.chat.util

/**
 * Small path-glob matcher that works on every supported Android API level.
 *
 * Paths are normalized to '/', `*` stays within one segment, `**` crosses directories, `?`
 * matches one non-separator character, and ordinary character classes are supported.
 */
internal object PortableGlobMatcher {
    fun matches(pattern: String, path: String): Boolean {
        val normalizedPattern = pattern.replace('\\', '/')
        val normalizedPath = path.replace('\\', '/')
        return runCatching {
            Regex("^${globBodyToRegex(normalizedPattern)}$").matches(normalizedPath)
        }.getOrDefault(false)
    }

    private fun globBodyToRegex(glob: String): String = buildString {
        var index = 0
        while (index < glob.length) {
            when (val char = glob[index]) {
                '*' -> {
                    if (glob.getOrNull(index + 1) == '*') {
                        index += 2
                        if (glob.getOrNull(index) == '/') {
                            append("(?:.*/)?")
                            index++
                        } else {
                            append(".*")
                        }
                        continue
                    }
                    append("[^/]*")
                }
                '?' -> append("[^/]")
                '[' -> {
                    val end = glob.indexOf(']', startIndex = index + 1)
                    if (end < 0) {
                        append("\\[")
                    } else {
                        val source = glob.substring(index + 1, end)
                        append('[')
                        if (source.startsWith("!")) {
                            append('^')
                            append(escapeCharacterClass(source.drop(1)))
                        } else {
                            append(escapeCharacterClass(source))
                        }
                        append(']')
                        index = end
                    }
                }
                else -> append(Regex.escape(char.toString()))
            }
            index++
        }
    }

    private fun escapeCharacterClass(source: String): String = buildString {
        source.forEachIndexed { index, char ->
            when (char) {
                '\\', ']' -> append('\\').append(char)
                '^' -> if (index == 0) append("\\^") else append(char)
                else -> append(char)
            }
        }
    }
}
