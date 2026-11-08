package dev.pntome.app.domain.scan

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Point
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import java.io.File

/**
 * A hermetic, file-backed [DocumentsProvider] used only by instrumented tests (task 5.4).
 *
 * It exposes a seeded on-disk directory tree through the real Storage Access Framework so that
 * [FolderScanner] can be exercised against genuine [DocumentsContract] cursor queries and real
 * [android.media.MediaMetadataRetriever] file descriptors — the Android-coupled path the JVM
 * unit tests deliberately stub with a fake scanner.
 *
 * ## Document ids
 * The document id of a node is its path relative to [rootDir] using `/` separators; the root
 * document id is the empty string (represented as [ROOT_DOC_ID]). This makes
 * [DocumentsContract.buildChildDocumentsUriUsingTree] / [buildTreeDocumentUri] round-trip
 * naturally and keeps id → file resolution a simple path join. All resolved files are confined
 * to [rootDir] (no `..` traversal).
 *
 * ## Lifecycle
 * The backing directory is static so a test can seed files *before* obtaining a tree URI and the
 * provider (instantiated by the platform in the test process) can read the same location. Tests
 * must call [install] with a seeded directory and [reset] when done.
 */
class SeededDocumentsProvider : DocumentsProvider() {

    override fun onCreate(): Boolean = true

    // --- Roots ---------------------------------------------------------------------------------

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        val dir = rootDir ?: return cursor
        cursor.newRow().apply {
            add(Root.COLUMN_ROOT_ID, ROOT_ID)
            add(Root.COLUMN_DOCUMENT_ID, ROOT_DOC_ID)
            add(Root.COLUMN_TITLE, "Seeded")
            add(Root.COLUMN_FLAGS, 0)
            add(Root.COLUMN_ICON, 0)
            add(Root.COLUMN_SUMMARY, dir.absolutePath)
        }
        return cursor
    }

    // --- Documents -----------------------------------------------------------------------------

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        addDocumentRow(cursor, documentId, fileFor(documentId))
        return cursor
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val parent = fileFor(parentDocumentId)
        val children = parent.listFiles()?.sortedBy { it.name } ?: emptyList()
        for (child in children) {
            addDocumentRow(cursor, childDocumentId(parentDocumentId, child.name), child)
        }
        return cursor
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        val file = fileFor(documentId)
        val pfdMode = ParcelFileDescriptor.parseMode(mode)
        return ParcelFileDescriptor.open(file, pfdMode)
    }

    override fun openDocumentThumbnail(
        documentId: String?,
        sizeHint: Point?,
        signal: CancellationSignal?
    ): android.content.res.AssetFileDescriptor? = null

    // --- Row building --------------------------------------------------------------------------

    private fun addDocumentRow(cursor: MatrixCursor, documentId: String, file: File) {
        val isDir = file.isDirectory
        val mime = if (isDir) Document.MIME_TYPE_DIR else mimeOf(file.name)
        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, documentId)
            add(Document.COLUMN_DISPLAY_NAME, if (documentId.isEmpty()) ROOT_ID else file.name)
            add(Document.COLUMN_MIME_TYPE, mime)
            add(Document.COLUMN_SIZE, if (isDir) 0L else file.length())
            add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
            add(Document.COLUMN_FLAGS, 0)
        }
    }

    // --- Path/id resolution --------------------------------------------------------------------

    private fun fileFor(documentId: String): File {
        val base = requireNotNull(rootDir) { "SeededDocumentsProvider not installed" }
        if (documentId.isEmpty() || documentId == ROOT_DOC_ID) return base
        val resolved = File(base, documentId).canonicalFile
        require(resolved.path.startsWith(base.canonicalFile.path)) {
            "Document id escapes root: $documentId"
        }
        return resolved
    }

    private fun childDocumentId(parentDocumentId: String, name: String): String =
        if (parentDocumentId.isEmpty()) name else "$parentDocumentId/$name"

    private fun mimeOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "aac" -> "audio/aac"
            "wav" -> "audio/wav"
            "flac" -> "audio/flac"
            "txt" -> "text/plain"
            "ogg" -> "audio/ogg"
            else -> "application/octet-stream"
        }
    }

    companion object {
        /** Authority declared in the androidTest manifest. */
        const val AUTHORITY = "dev.pntome.app.test.seededdocs"

        private const val ROOT_ID = "root"

        /** Document id of the tree root (empty path). */
        const val ROOT_DOC_ID = ""

        @Volatile
        private var rootDir: File? = null

        /** The tree URI tests pass to [FolderScanner], rooted at the installed directory. */
        fun treeUri(): Uri = DocumentsContract.buildTreeDocumentUri(AUTHORITY, ROOT_DOC_ID)

        /**
         * Point the provider at [dir] (the seeded directory tree). Must be called before the
         * provider services any query, i.e. before the test touches [treeUri].
         */
        fun install(dir: File) {
            rootDir = dir
        }

        /** Create (if needed) a fresh, empty seed directory under the test cache and install it. */
        fun freshRoot(context: Context, name: String = "seeded_tree"): File {
            val dir = File(context.cacheDir, name)
            dir.deleteRecursively()
            dir.mkdirs()
            install(dir)
            return dir
        }

        /** Detach and delete the seeded directory. */
        fun reset() {
            rootDir?.deleteRecursively()
            rootDir = null
        }

        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_TITLE,
            Root.COLUMN_FLAGS,
            Root.COLUMN_ICON,
            Root.COLUMN_SUMMARY
        )

        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS
        )
    }
}
