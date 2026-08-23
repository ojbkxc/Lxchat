package com.lxseek.chat.util

import java.io.File
import java.io.IOException

/**
 * Path sanitization utilities — Kotlin port of HyX core's `sanitize_relative_path`.
 *
 * Rejects path traversal (`..`), current-dir (`.`), absolute paths, Windows drive
 * prefixes / UNC roots, null bytes and other control characters, and symlink
 * escapes. Any path that came in over the wire or from untrusted user input
 * should be routed through these helpers before being joined under a trusted
 * base directory.
 *
 * The Rust original lives at `HyX/core/src/transfer_folder.rs::sanitize_relative_path`.
 * This Kotlin version adds two extra defenses the Rust one delegates to the OS:
 * null/control-character injection (rejected before any splitting) and symlink
 * escape detection (canonical-path containment check in [resolveSafe]).
 *
 * All rejection paths log a warning via [DebugLog] so callers don't need to
 * instrument every failure themselves.
 */
object PathSanitizer {
    private const val TAG = "PathSanitizer"

    /**
     * Sanitize a relative path supplied as a raw string.
     *
     * Returns the cleaned relative path using the platform separator, or `null`
     * if the input is rejected. Rejection reasons are logged via [DebugLog].
     *
     * Rejected inputs:
     *  - empty / blank input
     *  - absolute paths (leading `/` on Unix, leading `\` on Windows)
     *  - Windows drive prefixes (`C:`, `C:\foo`, `C:foo`) and UNC roots (`\\host\share`)
     *  - any `..` (parent) component
     *  - any `.` (current-dir) component
     *  - null bytes or other ASCII control characters (0x00–0x1F, 0x7F)
     *  - empty components from doubled separators (`a//b`) or trailing separators
     *
     * Accepted inputs are re-joined with the platform separator and contain only
     * plain `Normal` components — exactly the set Rust's `PathBuf::components()`
     * would yield after the same walk.
     */
    fun sanitizeRelativePath(input: String): String? {
        if (input.isEmpty()) {
            DebugLog.w(TAG, "rejecting empty path")
            return null
        }

        // Null byte / control char injection — reject before any further processing
        // so a payload like "safe.txt\0/../../etc/passwd" can't slip past splitters
        // that silently truncate at the null on some OSes.
        if (containsControlChars(input)) {
            DebugLog.w(TAG, "rejecting path containing null/control character")
            return null
        }

        // Reject absolute paths and Windows drive prefixes up front, matching the
        // `p.is_absolute()` and `Component::Prefix` / `Component::RootDir` arms.
        if (isAbsolutePath(input)) {
            DebugLog.w(TAG, "rejecting absolute path: $input")
            return null
        }
        if (isWindowsDriveOrUnc(input)) {
            DebugLog.w(TAG, "rejecting Windows drive/UNC path: $input")
            return null
        }

        // Normalize separators: treat both '/' and '\' as component separators so
        // Windows-style backslashes can't sneak past the splitter on a Unix host
        // (and vice versa).
        val parts = splitComponents(input)
        if (parts.isEmpty()) {
            DebugLog.w(TAG, "rejecting path that resolves to empty: $input")
            return null
        }

        val clean = ArrayList<String>(parts.size)
        for (part in parts) {
            when {
                part == ".." -> {
                    DebugLog.w(TAG, "rejecting '..' component in path: $input")
                    return null
                }
                part == "." -> {
                    DebugLog.w(TAG, "rejecting '.' component in path: $input")
                    return null
                }
                part.isEmpty() -> {
                    // `a//b` or trailing separator — reject, matches Rust's strict walk.
                    DebugLog.w(TAG, "rejecting empty component in path: $input")
                    return null
                }
                isWindowsDriveOrUnc(part) -> {
                    // Mid-path drive prefix like `foo/C:/bar` — the Rust `Component::Prefix`
                    // arm catches this on Windows; we catch it everywhere for safety.
                    DebugLog.w(TAG, "rejecting drive/root component in path: $input")
                    return null
                }
                else -> clean.add(part)
            }
        }

        if (clean.isEmpty()) {
            DebugLog.w(TAG, "rejecting path that resolves to empty after cleaning: $input")
            return null
        }

        return clean.joinToString(File.separator)
    }

    /**
     * Check whether [path] is safe to use as a relative path under a trusted base.
     *
     * Equivalent to `sanitizeRelativePath(path) != null` but returns a boolean
     * directly — useful when the caller only needs a yes/no answer and doesn't
     * want to allocate the cleaned string. Rejections are logged the same way
     * [sanitizeRelativePath] logs them.
     */
    fun isSafePath(path: String): Boolean {
        if (path.isEmpty()) {
            DebugLog.w(TAG, "isSafePath: rejecting empty path")
            return false
        }
        if (containsControlChars(path)) {
            DebugLog.w(TAG, "isSafePath: rejecting path with null/control character")
            return false
        }
        if (isAbsolutePath(path)) {
            DebugLog.w(TAG, "isSafePath: rejecting absolute path: $path")
            return false
        }
        if (isWindowsDriveOrUnc(path)) {
            DebugLog.w(TAG, "isSafePath: rejecting Windows drive/UNC path: $path")
            return false
        }
        val parts = splitComponents(path)
        if (parts.isEmpty()) {
            DebugLog.w(TAG, "isSafePath: rejecting path that resolves to empty: $path")
            return false
        }
        for (part in parts) {
            if (part == "..") {
                DebugLog.w(TAG, "isSafePath: rejecting '..' component in path: $path")
                return false
            }
            if (part == "." || part.isEmpty()) {
                DebugLog.w(TAG, "isSafePath: rejecting '.'/empty component in path: $path")
                return false
            }
            if (isWindowsDriveOrUnc(part)) {
                DebugLog.w(TAG, "isSafePath: rejecting drive/root component in path: $path")
                return false
            }
        }
        return true
    }

    /**
     * Safely resolve [relativePath] under [baseDir].
     *
     * Returns the canonical [File] only if:
     *  1. [relativePath] passes [sanitizeRelativePath], and
     *  2. the resolved file's canonical path starts with [baseDir]'s canonical path.
     *
     * The canonical-path check defends against symlink escape: if any component
     * of `baseDir` is a symlink that points outside, or if `baseDir` itself
     * contains a symlink that the relative path traverses into, the resolved
     * target will no longer be a prefix of the base and the call returns `null`.
     *
     * Example escape blocked here:
     * ```
     * baseDir = /home/user/safe        (real dir)
     * /home/user/safe/link -> /etc     (symlink planted by an attacker)
     * relativePath = "link/passwd"
     * // candidate canonical = /etc/passwd  ->  not within /home/user/safe  ->  null
     * ```
     *
     * Note: canonicalization requires the paths to exist on disk. For base
     * directories that don't yet exist, the check falls back to a lexical
     * prefix comparison on normalized absolute paths — still safe against `..`
     * traversal (already rejected by [sanitizeRelativePath]) but unable to
     * detect symlinks in a non-existent base. Callers should ensure the base
     * exists before relying on the symlink defense.
     */
    fun resolveSafe(baseDir: File, relativePath: String): File? {
        val clean = sanitizeRelativePath(relativePath) ?: return null

        val baseFile = baseDir.absoluteFile
        val candidate = File(baseFile, clean)

        // Symlink-escape check via canonical paths. `canonicalFile` resolves
        // symlinks in every existing component; if the candidate lands outside
        // the base after resolution, we reject.
        val baseCanon: File = try {
            baseFile.canonicalFile
        } catch (_: IOException) {
            DebugLog.w(TAG, "canonical resolution failed for base: $baseFile")
            return null
        }

        val candidateCanon: File = try {
            candidate.canonicalFile
        } catch (_: IOException) {
            // Candidate may not exist yet (we're about to create it). Fall back
            // to the parent's canonical path + the relative tail so we still
            // resolve symlinks in the existing parent components.
            val parentCanon: File? = try {
                candidate.parentFile?.canonicalFile
            } catch (_: IOException) {
                null
            }
            if (parentCanon == null) {
                DebugLog.w(TAG, "canonical resolution failed for candidate: $candidate")
                return null
            }
            File(parentCanon, candidate.name)
        }

        if (!isWithin(candidateCanon, baseCanon)) {
            DebugLog.w(
                TAG,
                "resolved path escapes base: base=$baseCanon candidate=$candidateCanon"
            )
            return null
        }
        return candidateCanon
    }

    // ---- internals -------------------------------------------------------

    /**
     * Reject null bytes and other ASCII control characters (0x00–0x1F, 0x7F).
     * Tab and newline are also rejected — they shouldn't appear in path
     * components and are a classic injection vector on systems that strip
     * them silently or render them as separators in logs.
     */
    private fun containsControlChars(s: String): Boolean {
        for (i in s.indices) {
            val c = s[i]
            // c < ' '  covers 0x00 (null) through 0x1F; 0x7F is DEL.
            if (c < ' ' || c.code == 0x7F) return true
        }
        return false
    }

    /** Leading `/` (Unix absolute) or leading `\` (Windows absolute / UNC). */
    private fun isAbsolutePath(s: String): Boolean =
        s.startsWith('/') || s.startsWith('\\')

    /**
     * Windows drive letter prefix (`C:`, `C:\foo`, `C:foo`) or UNC root
     * (`\\host\share`, `//host/share`). Also detects a bare drive-letter
     * component like `C:` appearing mid-path — that's how the Rust
     * `Component::Prefix` arm catches it on Windows; we catch it on every
     * platform so a `foo/C:/bar` payload can't bypass the check on Unix.
     *
     * `C:foo` (drive-relative on Windows) is rejected conservatively even
     * though it's a legal filename on Unix — the cross-platform safety win
     * outweighs the rare false positive on files literally named `C:foo`.
     */
    private fun isWindowsDriveOrUnc(s: String): Boolean {
        // UNC: `\\server\share` (two leading backslashes) or tolerant `//host/share`.
        if (s.startsWith("\\\\") || s.startsWith("//")) return true
        // Drive letter: `X:` where X is a letter — covers `C:`, `C:\foo`, `C:foo`.
        if (s.length >= 2 && s[0].isLetter() && s[1] == ':') return true
        return false
    }

    /**
     * Split on both `/` and `\`, preserving order. Empty segments are kept
     * so the caller can reject `a//b` and trailing separators — matches the
     * strictness of Rust's `Path::components()` walk, which yields a
     * `RootDir`/`CurDir` for those rather than silently dropping them.
     */
    private fun splitComponents(s: String): List<String> {
        if (s.isEmpty()) return emptyList()
        val out = ArrayList<String>(4)
        val sb = StringBuilder()
        for (i in s.indices) {
            val c = s[i]
            if (c == '/' || c == '\\') {
                out.add(sb.toString())
                sb.setLength(0)
            } else {
                sb.append(c)
            }
        }
        out.add(sb.toString())
        return out
    }

    /**
     * Lexical containment check: is [child] inside [parent]?
     * Both must be canonical/absolute. A trailing separator is appended to
     * [parent] so `/foo` doesn't match `/foobar` — only `/foo/...` does.
     */
    private fun isWithin(child: File, parent: File): Boolean {
        val parentPath = parent.absolutePath
        val childPath = child.absolutePath
        if (childPath == parentPath) return true
        val sep = File.separator
        val parentWithSep = if (parentPath.endsWith(sep)) parentPath else parentPath + sep
        return childPath.startsWith(parentWithSep)
    }
}