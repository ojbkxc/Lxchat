package com.lxseek.chat.plugin

import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * Verifier for external plugin packages (ZIP files).
 *
 * Runs a defensive pipeline before a package is handed to [com.lxseek.chat.plugin.adapters.ToolPkgLoader]:
 *  1. Package size limit (default 50 MB) — rejects oversized / zip-bomb payloads early.
 *  2. ZIP structural integrity — opens and closes the archive to detect corruption.
 *  3. `manifest.json` presence and SHA-256 fingerprint — the manifest is the trust root
 *     for the plugin identity and capability declarations.
 *  4. Optional `manifest.sig` signature file:
 *       - When present, the signer fingerprint (SHA-256 of the signature bytes) must be
 *         listed in [trustedSignatures]; otherwise the package is rejected.
 *       - When absent, the package is treated as unsigned and rejected unless
 *         [allowUnsigned] is true (useful for local development imports).
 *  5. Zip-slip (path traversal) guard over every entry — ensures no entry escapes the
 *     extraction root at load time.
 *
 * This is a framework-grade verifier: the signature model is a trusted-fingerprint
 * allow-list (SHA-256 of `manifest.sig`). Production hardening can swap step 4 for
 * asymmetric signature verification (RSA/EC) without changing the public API.
 */
class PluginPackageVerifier(
    private val trustedSignatures: Set<String> = emptySet(), // SHA-256 fingerprints of trusted signing keys
    private val maxPackageSizeBytes: Long = 50 * 1024 * 1024, // 50 MB limit
    private val allowUnsigned: Boolean = false,
) {
    /** Result of verifying a plugin package. */
    data class VerificationResult(
        val isValid: Boolean,
        val manifestHash: String? = null,
        val error: String? = null,
    )

    /** Verify a plugin ZIP package before loading. */
    fun verify(zipFile: File): VerificationResult {
        // 1. Check file size limit (also guards against missing/empty files).
        val size = zipFile.length()
        if (size <= 0L) {
            return VerificationResult(isValid = false, error = "Package file is empty or missing")
        }
        if (size > maxPackageSizeBytes) {
            return VerificationResult(
                isValid = false,
                error = "Package size $size bytes exceeds limit $maxPackageSizeBytes bytes",
            )
        }

        // 2. Open ZIP + structural integrity.
        val zip = try {
            ZipFile(zipFile)
        } catch (e: Exception) {
            return VerificationResult(isValid = false, error = "Invalid ZIP archive: ${e.message}")
        }

        zip.use { z ->
            // 3. Read manifest.json — the trust root for plugin identity.
            val manifestEntry = z.getEntry(MANIFEST_ENTRY)
                ?: return VerificationResult(isValid = false, error = "Missing $MANIFEST_ENTRY")
            val manifestBytes = try {
                z.getInputStream(manifestEntry).use { it.readBytes() }
            } catch (e: Exception) {
                return VerificationResult(isValid = false, error = "Failed to read manifest: ${e.message}")
            }
            if (manifestBytes.isEmpty()) {
                return VerificationResult(isValid = false, error = "Empty manifest")
            }

            // 4. Compute SHA-256 of manifest — used as the integrity fingerprint.
            val manifestHash = sha256(manifestBytes)

            // 5. Check signature file (manifest.sig) if present.
            val sigEntry = z.getEntry(SIGNATURE_ENTRY)
            if (sigEntry != null) {
                val sigBytes = try {
                    z.getInputStream(sigEntry).use { it.readBytes() }
                } catch (e: Exception) {
                    return VerificationResult(
                        isValid = false,
                        manifestHash = manifestHash,
                        error = "Failed to read signature: ${e.message}",
                    )
                }
                val signerFingerprint = sha256(sigBytes)
                if (trustedSignatures.isEmpty()) {
                    return VerificationResult(
                        isValid = false,
                        manifestHash = manifestHash,
                        error = "Package is signed but no trusted signatures are configured",
                    )
                }
                if (signerFingerprint !in trustedSignatures) {
                    return VerificationResult(
                        isValid = false,
                        manifestHash = manifestHash,
                        error = "Untrusted signer fingerprint: $signerFingerprint",
                    )
                }
            } else if (!allowUnsigned) {
                return VerificationResult(
                    isValid = false,
                    manifestHash = manifestHash,
                    error = "Unsigned package rejected (missing $SIGNATURE_ENTRY)",
                )
            }

            // 6. Check for path traversal attacks (zip slip) on every entry.
            //    The target dir is the zip's parent — extraction is always rooted
            //    at or below this location (see ToolPkgLoader cache layout).
            val targetDir = zipFile.parentFile ?: File(".")
            val entries = z.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                if (!isSafePath(entry.name, targetDir)) {
                    return VerificationResult(
                        isValid = false,
                        manifestHash = manifestHash,
                        error = "Unsafe entry path (zip slip): ${entry.name}",
                    )
                }
            }

            // 7. All checks passed.
            return VerificationResult(isValid = true, manifestHash = manifestHash)
        }
    }

    /** Compute SHA-256 hash of a byte array, returned as lowercase hex. */
    private fun sha256(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data).joinToString("") { "%02x".format(it) }
    }

    /** Check for zip slip (path traversal) vulnerability.
     *  Returns true only when [entryPath] resolves inside [targetDir]. */
    private fun isSafePath(entryPath: String, targetDir: File): Boolean {
        val targetPath = File(targetDir, entryPath).canonicalPath
        return targetPath.startsWith(targetDir.canonicalPath)
    }

    private companion object {
        const val MANIFEST_ENTRY = "manifest.json"
        const val SIGNATURE_ENTRY = "manifest.sig"
    }
}