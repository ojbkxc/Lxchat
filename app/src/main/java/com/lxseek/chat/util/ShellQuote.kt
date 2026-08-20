package com.lxseek.chat.util

/**
 * Shell quoting utilities that prevent injection attacks when constructing commands from
 * user/AI-supplied arguments. Inspired by ZorvAI's QuroShellQuote.
 *
 * The core technique: wrap each argument in single quotes, and escape any embedded single
 * quotes by closing the quote, inserting an escaped literal quote, and reopening. Single
 * quotes in POSIX shells prevent ALL metacharacter interpretation, making this the safest
 * quoting strategy.
 *
 * Example: `quote("it's a test")` → `'it'\''s a test'`
 */
object ShellQuote {

    /**
     * Safely quotes a single shell argument. Empty strings are quoted as ''.
     * Already-safe characters (alphanumeric, common punctuation) are left unquoted
     * for readability of the resulting command.
     */
    fun quote(arg: String): String {
        if (arg.isEmpty()) return "''"
        if (isSafeUnquoted(arg)) return arg
        return "'" + arg.replace("'", "'\\''") + "'"
    }

    /**
     * Builds a command string from a base command and arguments, with each argument
     * safely quoted. Example: `buildCommand("ssh", "user@host", "ls -la /tmp")`
     * → `ssh user@host 'ls -la /tmp'`
     */
    fun buildCommand(base: String, vararg args: String): String {
        if (args.isEmpty()) return base
        return base + " " + args.joinToString(" ") { quote(it) }
    }

    /**
     * Builds a command string from a list of parts. The first element is the base
     * command, the rest are arguments.
     */
    fun buildCommand(parts: List<String>): String {
        if (parts.isEmpty()) return ""
        if (parts.size == 1) return parts[0]
        return parts[0] + " " + parts.drop(1).joinToString(" ") { quote(it) }
    }

    /**
     * Sanitizes a string for safe inclusion in a shell command context. Removes
     * null bytes and control characters that could confuse shell parsing.
     * Does NOT add quotes — use [quote] for that.
     */
    fun sanitize(input: String): String {
        return input
            .replace("\u0000", "")
            .replace(Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]"), "")
            .trim()
    }

    /**
     * Checks if a string is safe to use unquoted in a shell command.
     * Safe characters: alphanumeric, dash, underscore, dot, slash, colon, at, plus, equals.
     */
    private fun isSafeUnquoted(s: String): Boolean {
        if (s.isEmpty()) return false
        for (c in s) {
            val safe = c in 'a'..'z' ||
                c in 'A'..'Z' ||
                c in '0'..'9' ||
                c == '-' || c == '_' || c == '.' || c == '/' ||
                c == ':' || c == '@' || c == '+' || c == '='
            if (!safe) return false
        }
        return true
    }
}
