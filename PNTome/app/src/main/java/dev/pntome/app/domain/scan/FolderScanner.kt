package dev.pntome.app.domain.scan

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.pntome.app.data.settings.SettingsStore
import dev.pntome.app.domain.model.AppSettings
import dev.pntome.app.domain.model.ScanProgress
import dev.pntome.app.domain.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Recursively scans a SAF folder tree for watch-compatible audio and reports live progress
 * (Req 3).
 *
 * ## Enumeration (Req 3.1)
 * The tree is walked with [DocumentsContract.buildChildDocumentsUriUsingTree] bulk cursor
 * queries rather than `DocumentFile.listFiles`, which is markedly slower because it issues a
 * provider round-trip per child. Each directory is listed in one cursor query selecting only
 * the columns we need ([DocumentsContract.Document.COLUMN_DOCUMENT_ID], display name, MIME
 * type, size, last-modified); subdirectories ([DocumentsContract.Document.MIME_TYPE_DIR]) are
 * recursed into with the same technique using each child's document id. An explicit work stack
 * (rather than call recursion) keeps the walk shallow and cancellable.
 *
 * ## Filtering (Req 3.2, 3.3)
 * Each non-directory entry is tested with [FormatFilter]; ineligible files are skipped and
 * counted ([ScanResult.skippedCount]).
 *
 * ## Metadata (Req 3.4)
 * For eligible files, [MediaMetadataRetriever] reads title/artist/album/duration from a content
 * file descriptor opened through the resolver (no whole-file buffering — only metadata is read,
 * Req 10.4). The retriever is defensive: it can throw on malformed files, so failures degrade to
 * the filename fallback and `release()` always runs.
 *
 * ## Identity & grouping (Req 3.5)
 * Each track gets a stable id from [TrackIdHasher] over its relative path, size, and
 * last-modified, and a [Track.relativeFolder] derived from its subfolder path (see [ScanPaths]).
 *
 * ## Threading & progress (Req 3.6)
 * The whole walk runs on [Dispatchers.IO] and emits a [ScanProgress] stream so the UI stays
 * responsive and can show live feedback; the flow is cancellable (cancellation stops the walk).
 *
 * [ScanProgress] deliberately does not carry the resulting tracks, so callers that need the
 * discovered [Track]s use [scanToResult]; callers that only drive a progress UI use [scan].
 * Both share a single walk implementation ([walk]).
 */
@Singleton
class FolderScanner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsStore: SettingsStore
) : TrackScanner {

    /**
     * Scan [treeUri], emitting a [ScanProgress] stream for the UI (Req 3.6).
     *
     * The final emission has [ScanProgress.done] set. The discovered tracks are not carried on
     * the progress stream (see [ScanProgress]); use [scanToResult] when you need them.
     *
     * @param treeUri the persisted SAF tree URI (`ACTION_OPEN_DOCUMENT_TREE` result).
     * @param folderId the owning folder's id (see [FolderIdHasher]), stamped onto each [Track].
     * @param rootName the folder's display name, used as the group name for tracks that sit
     *   directly under the root (see [ScanPaths.relativeFolder]).
     * @param settings format flags gating optional formats; when null the current persisted
     *   [AppSettings] are read from [SettingsStore].
     */
    fun scan(
        treeUri: Uri,
        folderId: String,
        rootName: String,
        settings: AppSettings? = null
    ): Flow<ScanProgress> = flow {
        val effective = settings ?: settingsStore.get()
        walk(treeUri, folderId, rootName, effective) { progress -> emit(progress) }
    }.flowOn(Dispatchers.IO)

    /**
     * Scan [treeUri] to completion and return the discovered [Track]s plus the skipped count
     * (Req 3.2, 3.3), discarding intermediate progress.
     *
     * Shares the exact enumeration/metadata path with [scan]; useful for the Room cache
     * (task 5.3) and sync manifest building where only the final result matters.
     */
    override suspend fun scanToResult(
        treeUri: Uri,
        folderId: String,
        rootName: String,
        settings: AppSettings?
    ): ScanResult = withContext(Dispatchers.IO) {
        val effective = settings ?: settingsStore.get()
        val tracks = mutableListOf<Track>()
        var skipped = 0
        walk(treeUri, folderId, rootName, effective, onTrack = { tracks += it }) { progress ->
            skipped = progress.skippedCount
        }
        ScanResult(tracks, skipped)
    }

    // --- Internal walk ---------------------------------------------------------------------

    /**
     * The single shared tree walk. Enumerates [treeUri] depth-first via bulk cursors, builds a
     * [Track] for each eligible file (reporting it through [onTrack]), counts skipped files, and
     * reports a [ScanProgress] after every file plus a terminal `done` emission through
     * [onProgress]. Both [onProgress] and [onTrack] may suspend; the caller supplies them.
     */
    private suspend fun walk(
        treeUri: Uri,
        folderId: String,
        rootName: String,
        settings: AppSettings,
        onTrack: suspend (Track) -> Unit = {},
        onProgress: suspend (ScanProgress) -> Unit
    ) {
        val resolver = context.contentResolver
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)

        var filesScanned = 0
        var tracksFound = 0
        var skipped = 0

        // Explicit stack avoids deep call recursion and keeps the walk cancellable.
        val stack = ArrayDeque<DirFrame>()
        stack.addLast(DirFrame(rootDocId, emptyList()))

        while (stack.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val frame = stack.removeLast()
            val childrenUri =
                DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, frame.docId)

            // Read this directory's children in one cursor pass (non-suspending), then process
            // them — so the suspending metadata/progress work happens outside the open cursor.
            val dirs = mutableListOf<DirFrame>()
            val files = mutableListOf<ChildEntry>()
            queryChildren(resolver, childrenUri) { child ->
                if (child.isDirectory) {
                    dirs += DirFrame(child.documentId, frame.segments + child.displayName)
                } else {
                    files += child
                }
            }
            dirs.forEach { stack.addLast(it) }

            for (child in files) {
                currentCoroutineContext().ensureActive()
                filesScanned++
                if (FormatFilter.isEligible(child.displayName, child.mimeType, settings)) {
                    val track = buildTrack(treeUri, folderId, rootName, frame.segments, child)
                    tracksFound++
                    onTrack(track)
                } else {
                    skipped++ // Req 3.3
                }
                onProgress(
                    ScanProgress(
                        filesScanned = filesScanned,
                        tracksFound = tracksFound,
                        skippedCount = skipped,
                        currentFile = child.displayName,
                        done = false
                    )
                )
            }
        }

        onProgress(
            ScanProgress(
                filesScanned = filesScanned,
                tracksFound = tracksFound,
                skippedCount = skipped,
                currentFile = null,
                done = true
            )
        )
    }

    /** One child row from the bulk cursor. */
    private data class ChildEntry(
        val documentId: String,
        val displayName: String,
        val mimeType: String?,
        val sizeBytes: Long,
        val lastModified: Long
    ) {
        val isDirectory: Boolean
            get() = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
    }

    private data class DirFrame(val docId: String, val segments: List<String>)

    /**
     * Query the children of a directory in one bulk cursor and invoke [onChild] per row.
     * Columns are read defensively (missing columns tolerated) and the cursor is always closed.
     */
    private inline fun queryChildren(
        resolver: ContentResolver,
        childrenUri: Uri,
        onChild: (ChildEntry) -> Unit
    ) {
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        val cursor: Cursor =
            resolver.query(childrenUri, projection, null, null, null) ?: return
        cursor.use { c ->
            val idIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            val modIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            if (idIdx < 0 || nameIdx < 0) return@use
            while (c.moveToNext()) {
                val docId = c.getString(idIdx) ?: continue
                val name = c.getString(nameIdx) ?: continue
                val mime = if (mimeIdx >= 0 && !c.isNull(mimeIdx)) c.getString(mimeIdx) else null
                val size = if (sizeIdx >= 0 && !c.isNull(sizeIdx)) c.getLong(sizeIdx) else 0L
                val mod = if (modIdx >= 0 && !c.isNull(modIdx)) c.getLong(modIdx) else 0L
                onChild(ChildEntry(docId, name, mime, size, mod))
            }
        }
    }

    /** Build a [Track] for an eligible file, extracting metadata with a filename fallback. */
    private fun buildTrack(
        treeUri: Uri,
        folderId: String,
        rootName: String,
        parentSegments: List<String>,
        child: ChildEntry
    ): Track {
        val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, child.documentId)
        val relativePath = ScanPaths.relativePath(parentSegments, child.displayName)
        val relativeFolder = ScanPaths.relativeFolder(parentSegments, rootName)
        val id = TrackIdHasher.hash(relativePath, child.sizeBytes, child.lastModified)
        val meta = readMetadata(documentUri)

        val title = meta.title?.takeIf { it.isNotBlank() }
            ?: ScanPaths.titleFromFileName(child.displayName) // Req 3.4

        return Track(
            id = id,
            folderId = folderId,
            documentUri = documentUri.toString(),
            relativeFolder = relativeFolder,
            title = title,
            artist = meta.artist?.takeIf { it.isNotBlank() },
            album = meta.album?.takeIf { it.isNotBlank() },
            durationMs = meta.durationMs,
            sizeBytes = child.sizeBytes,
            mime = child.mimeType ?: "application/octet-stream",
            fileName = child.displayName // AB-2.1: natural ordering + audiobook chapter titles
        )
    }

    private data class Metadata(
        val title: String?,
        val artist: String?,
        val album: String?,
        val durationMs: Long
    )

    /**
     * Read audio metadata via [MediaMetadataRetriever] from a content file descriptor. Only
     * metadata is read — the audio bytes are never buffered (Req 10.4). The retriever can throw
     * on malformed/unreadable files, so this degrades gracefully to empty metadata (the caller
     * then applies the filename fallback) and always releases its resources.
     */
    private fun readMetadata(documentUri: Uri): Metadata {
        val retriever = MediaMetadataRetriever()
        return try {
            context.contentResolver.openFileDescriptor(documentUri, "r").use { pfd ->
                if (pfd == null) return Metadata(null, null, null, 0L)
                retriever.setDataSource(pfd.fileDescriptor)
                val title =
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                val artist =
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                val album =
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                val durationMs =
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull() ?: 0L
                Metadata(title, artist, album, durationMs)
            }
        } catch (t: Throwable) {
            // Defensive: unreadable/corrupt file or unsupported source — fall back to filename.
            Metadata(null, null, null, 0L)
        } finally {
            try {
                retriever.release()
            } catch (_: Throwable) {
                // ignore release failures
            }
        }
    }
}
