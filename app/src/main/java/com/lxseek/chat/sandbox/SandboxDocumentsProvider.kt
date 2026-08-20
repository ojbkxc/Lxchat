package com.lxseek.chat.sandbox

import com.lxseek.chat.R
import android.app.Activity
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Build
import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException
import java.util.Locale

/**
 * Exposes the sandbox filesystem via the Storage Access Framework.
 *
 * The Alpine rootfs lives under [Context.getFilesDir], while /home/lxchat is a
 * separate directory bind-mounted by proot. Keep that mount visible here too,
 * otherwise the file manager and commands running inside the sandbox would see
 * two different home directories.
 */
class SandboxDocumentsProvider : DocumentsProvider() {

    companion object {
        const val ROOT_ID = "lxchat_sandbox"
        const val ROOT_DOCUMENT_ID = "root"
        const val DEFAULT_AUTHORITY_SUFFIX = "documents"

        private const val LEGACY_HOME_DOCUMENT_ID = "home"
        private const val HOME_VIRTUAL_PATH = "home/lxchat"

        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY,
            Root.COLUMN_FLAGS,
            Root.COLUMN_ICON,
            Root.COLUMN_MIME_TYPES,
            Root.COLUMN_AVAILABLE_BYTES,
        )

        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_FLAGS,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
        )

    }

    private lateinit var rootDirectory: File
    private lateinit var canonicalRoot: File
    private lateinit var homeDirectory: File
    private lateinit var canonicalHome: File

    override fun onCreate(): Boolean {
        val appContext = context ?: return false
        rootDirectory = File(appContext.filesDir, "alpine-rootfs")
        canonicalRoot = rootDirectory.canonicalFile
        homeDirectory = File(appContext.filesDir, "sandbox-home")
        canonicalHome = homeDirectory.canonicalFile
        return true
    }

    // DocumentsProvider reports immediately writable space. Counting cache that could
    // theoretically be evicted overstates what callers can write without side effects.
    @SuppressLint("UsableSpace")
    override fun queryRoots(projection: Array<out String>?): Cursor {
        val columns = projection?.toList()?.toTypedArray() ?: DEFAULT_ROOT_PROJECTION
        return MatrixCursor(columns).apply {
            if (!rootDirectory.isDirectory) return@apply
            newRow().apply {
                add(Root.COLUMN_ROOT_ID, ROOT_ID)
                add(Root.COLUMN_DOCUMENT_ID, ROOT_DOCUMENT_ID)
                add(Root.COLUMN_TITLE, "LxChat")
                add(Root.COLUMN_SUMMARY, "Sandbox /")
                add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
                add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE or Root.FLAG_LOCAL_ONLY)
                add(Root.COLUMN_MIME_TYPES, "*/*")
                add(Root.COLUMN_AVAILABLE_BYTES, canonicalRoot.usableSpace)
            }
        }
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val columns = projection?.toList()?.toTypedArray() ?: DEFAULT_DOCUMENT_PROJECTION
        val virtualPath = virtualPathForDocumentId(documentId)
        val file = fileForVirtualPath(virtualPath)
        return MatrixCursor(columns).apply {
            includeFile(this, documentIdForVirtualPath(virtualPath), virtualPath, file)
        }
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val columns = projection?.toList()?.toTypedArray() ?: DEFAULT_DOCUMENT_PROJECTION
        val parentVirtualPath = virtualPathForDocumentId(parentDocumentId)
        val parent = fileForVirtualPath(parentVirtualPath)
        if (!parent.isDirectory) throw FileNotFoundException("Not a directory: $parentDocumentId")

        return MatrixCursor(columns).apply {
            parent.listFiles()
                ?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase(Locale.ROOT) })
                ?.forEach { child ->
                    // Following host-level symlinks could escape the virtual sandbox root.
                    // Do not expose an entry unless it resolves through our checked mapper.
                    if (runCatching { child.absoluteFile != child.canonicalFile }.getOrDefault(true)) {
                        return@forEach
                    }
                    val childVirtualPath = appendVirtualPath(parentVirtualPath, child.name)
                    runCatching {
                        val checkedChild = fileForVirtualPath(childVirtualPath)
                        includeFile(
                            this,
                            documentIdForVirtualPath(childVirtualPath),
                            childVirtualPath,
                            checkedChild
                        )
                    }
                }
        }
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        val file = fileForVirtualPath(virtualPathForDocumentId(documentId))
        if (file.isDirectory) throw FileNotFoundException("Cannot open directory as file")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }

    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String
    ): String {
        validateDisplayName(displayName)
        val parentVirtualPath = virtualPathForDocumentId(parentDocumentId)
        val parent = fileForVirtualPath(parentVirtualPath)
        if (!parent.isDirectory) throw FileNotFoundException("Parent is not a directory")

        val childVirtualPath = appendVirtualPath(parentVirtualPath, displayName)
        val child = fileForVirtualPath(childVirtualPath, requireExists = false)
        if (child.exists()) throw FileNotFoundException("File already exists: $displayName")

        val created = if (mimeType == Document.MIME_TYPE_DIR) child.mkdir() else child.createNewFile()
        if (!created) throw FileNotFoundException("Unable to create: $displayName")
        return documentIdForVirtualPath(childVirtualPath)
    }

    override fun deleteDocument(documentId: String) {
        val virtualPath = virtualPathForDocumentId(documentId)
        if (isProtectedRoot(virtualPath)) throw FileNotFoundException("Cannot delete sandbox root")
        val file = fileForVirtualPath(virtualPath)
        if (!file.deleteRecursively()) throw FileNotFoundException("Unable to delete: $documentId")
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        validateDisplayName(displayName)
        val sourceVirtualPath = virtualPathForDocumentId(documentId)
        if (isProtectedRoot(sourceVirtualPath)) throw FileNotFoundException("Cannot rename sandbox root")
        val source = fileForVirtualPath(sourceVirtualPath)

        val parentVirtualPath = sourceVirtualPath.substringBeforeLast('/', "")
        val targetVirtualPath = appendVirtualPath(parentVirtualPath, displayName)
        val target = fileForVirtualPath(targetVirtualPath, requireExists = false)
        if (target.exists() || !source.renameTo(target)) throw FileNotFoundException("Unable to rename document")
        return documentIdForVirtualPath(targetVirtualPath)
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        val parent = virtualPathForDocumentId(parentDocumentId)
        val child = virtualPathForDocumentId(documentId)
        if (child == parent) return false
        return if (parent.isEmpty()) child.isNotEmpty() else child.startsWith("$parent/")
    }

    // ── helpers ────────────────────────────────────────────────

    private fun includeFile(
        cursor: MatrixCursor,
        documentId: String,
        virtualPath: String,
        file: File
    ) {
        if (!file.exists()) throw FileNotFoundException("Document does not exist: $documentId")

        var flags = if (isProtectedRoot(virtualPath)) {
            0
        } else {
            Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
        }
        flags = if (file.isDirectory) flags or Document.FLAG_DIR_SUPPORTS_CREATE
                else flags or Document.FLAG_SUPPORTS_WRITE

        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, documentId)
            add(
                Document.COLUMN_DISPLAY_NAME,
                when (virtualPath) {
                    "" -> "/"
                    HOME_VIRTUAL_PATH -> "lxchat"
                    else -> virtualPath.substringAfterLast('/')
                }
            )
            add(Document.COLUMN_MIME_TYPE, mimeTypeFor(file))
            add(Document.COLUMN_FLAGS, flags)
            add(Document.COLUMN_SIZE, if (file.isFile) file.length() else null)
            add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
        }
    }

    private fun virtualPathForDocumentId(documentId: String): String {
        if (documentId == ROOT_DOCUMENT_ID) return ""
        if (documentId == LEGACY_HOME_DOCUMENT_ID) return HOME_VIRTUAL_PATH

        val rootPrefix = "$ROOT_DOCUMENT_ID:"
        if (documentId.startsWith(rootPrefix)) {
            return checkedVirtualPath(documentId.removePrefix(rootPrefix))
        }

        // Preserve old persisted tree/document grants. Before the root view was added,
        // "home" represented /home/lxchat and "home:<path>" represented its descendants.
        val legacyPrefix = "$LEGACY_HOME_DOCUMENT_ID:"
        if (documentId.startsWith(legacyPrefix)) {
            return appendVirtualPath(
                HOME_VIRTUAL_PATH,
                checkedVirtualPath(documentId.removePrefix(legacyPrefix))
            )
        }

        throw FileNotFoundException("Unknown document ID: $documentId")
    }

    private fun documentIdForVirtualPath(virtualPath: String): String {
        val checked = checkedVirtualPath(virtualPath)
        return if (checked.isEmpty()) ROOT_DOCUMENT_ID else "$ROOT_DOCUMENT_ID:$checked"
    }

    private fun fileForVirtualPath(virtualPath: String, requireExists: Boolean = true): File {
        val checkedPath = checkedVirtualPath(virtualPath)
        val inHomeMount = checkedPath == HOME_VIRTUAL_PATH ||
            checkedPath.startsWith("$HOME_VIRTUAL_PATH/")
        val boundary = if (inHomeMount) canonicalHome else canonicalRoot
        val relativePath = if (inHomeMount) {
            checkedPath.removePrefix(HOME_VIRTUAL_PATH).trimStart('/')
        } else {
            checkedPath
        }
        val file = checkedFile(File(boundary, relativePath), boundary)
        if (requireExists && !file.exists()) {
            throw FileNotFoundException("Document does not exist: $virtualPath")
        }
        return file
    }

    /** Prevent path traversal and host-level symlink escapes. */
    private fun checkedFile(file: File, boundary: File): File {
        val canonical = file.canonicalFile
        val inside = canonical == boundary ||
            canonical.path.startsWith(boundary.path + File.separator)
        if (!inside) throw FileNotFoundException("Path escapes sandbox root")
        return canonical
    }

    private fun checkedVirtualPath(path: String): String {
        if (path.isEmpty()) return ""
        if (path.startsWith('/') || path.startsWith('\\')) {
            throw FileNotFoundException("Absolute document path is not allowed")
        }
        val segments = path.replace('\\', '/').split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) {
            throw FileNotFoundException("Invalid document path: $path")
        }
        return segments.joinToString("/")
    }

    private fun appendVirtualPath(parent: String, child: String): String {
        val checkedChild = checkedVirtualPath(child)
        if (checkedChild.isEmpty()) return checkedVirtualPath(parent)
        return if (parent.isEmpty()) checkedChild else "${checkedVirtualPath(parent)}/$checkedChild"
    }

    private fun isProtectedRoot(virtualPath: String): Boolean =
        virtualPath.isEmpty() ||
            virtualPath == HOME_VIRTUAL_PATH.substringBefore('/') ||
            virtualPath == HOME_VIRTUAL_PATH

    private fun validateDisplayName(name: String) {
        if (name.isBlank() || name == "." || name == ".." || name.contains('/') ||
            name.contains('\\') || name.indexOf(' ') >= 0
        ) throw FileNotFoundException("Invalid file name: $name")
    }

    private fun mimeTypeFor(file: File): String {
        if (file.isDirectory) return Document.MIME_TYPE_DIR
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase(Locale.ROOT))
            ?: "application/octet-stream"
    }
}

/**
 * Open the sandbox root in the system file manager.
 * Uses the SAF root URI (ACTION_VIEW + buildRootUri) so DocumentsUI navigates
 * straight into the provider; falls back to ACTION_OPEN_DOCUMENT_TREE with a
 * tree URI as EXTRA_INITIAL_URI when the OEM file manager doesn't support
 * direct root browsing.
 */
fun Context.openSandboxRoot(authority: String = "$packageName.${SandboxDocumentsProvider.DEFAULT_AUTHORITY_SUFFIX}") {
    val rootUri = DocumentsContract.buildRootUri(authority, SandboxDocumentsProvider.ROOT_ID)
    val grantFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
        Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    val viewIntent = Intent(Intent.ACTION_VIEW).apply {
        // Root.MIME_TYPE_ITEM was only added to the SDK in API 26, but its wire value is
        // understood by DocumentsUI on every API LxChat supports.
        setDataAndType(rootUri, "vnd.android.document/root")
        addCategory(Intent.CATEGORY_DEFAULT)
        addFlags(grantFlags)
        if (this@openSandboxRoot !is Activity) {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    try {
        startActivity(viewIntent)
    } catch (_: Exception) {
        openSandboxRootPicker(authority)
    }
}

private fun Context.openSandboxRootPicker(authority: String) {
    val treeUri = DocumentsContract.buildTreeDocumentUri(authority, SandboxDocumentsProvider.ROOT_DOCUMENT_ID)
    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION

    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            putExtra(DocumentsContract.EXTRA_INITIAL_URI, treeUri)
        }
        addFlags(flags)
        if (this@openSandboxRootPicker !is Activity) {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    startActivity(intent)
}
