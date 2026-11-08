package dev.pntome.app.domain.folders

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.pntome.app.data.folders.FolderRepository
import dev.pntome.app.domain.model.Folder
import dev.pntome.app.domain.scan.FolderIdHasher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Outcome of adding a folder selected via `ACTION_OPEN_DOCUMENT_TREE`.
 *
 * The folder-list UI (task 4.2) uses this to inform the user: a new entry on [Added], a
 * "already in your list" notice on [Duplicate] (Req 1.4), and an error message on [Error].
 */
sealed interface AddFolderResult {
    /** The folder was newly persisted. */
    data class Added(val folder: Folder) : AddFolderResult

    /** The selected folder was already in the list; nothing was changed (Req 1.4). */
    data class Duplicate(val existingId: String) : AddFolderResult

    /** Taking the permission or resolving the folder failed. */
    data class Error(val message: String, val cause: Throwable? = null) : AddFolderResult
}

/**
 * Adds a user-selected SAF folder to the persisted folder list (Req 1.2–1.4).
 *
 * Given the tree [Uri] returned by the system folder picker, this use case:
 * 1. takes a **persistable read** permission on the tree URI so access survives app and
 *    device restarts (Req 1.2),
 * 2. resolves a human-readable display name via [DocumentFile.fromTreeUri] (Req 2.1),
 * 3. builds a [Folder] whose stable id is [FolderIdHasher.hash] of the tree URI, and
 * 4. persists it via [FolderRepository.addFolderIfAbsent], deduping re-selections of the same
 *    folder (Req 1.3, 1.4).
 *
 * The Compose launcher only invokes this on a successful (non-null) result, so cancellation
 * leaves the list unchanged (Req 1.5) without any work here.
 */
@Singleton
class AddFolderUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val folderRepository: FolderRepository,
) {

    /**
     * Persist the folder behind [treeUri].
     *
     * @param treeUri the tree URI from `ACTION_OPEN_DOCUMENT_TREE`.
     * @param addedAt epoch-millis timestamp recorded on the new folder; defaults to now.
     * @return [AddFolderResult.Added] when newly inserted, [AddFolderResult.Duplicate] when the
     *   same folder is already persisted (Req 1.4), or [AddFolderResult.Error] on failure.
     */
    suspend fun add(
        treeUri: Uri,
        addedAt: Long = System.currentTimeMillis(),
    ): AddFolderResult = withContext(Dispatchers.IO) {
        try {
            // Req 1.2: persist read access so it survives restarts. Idempotent for re-selections.
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )

            val uriString = treeUri.toString()
            val id = FolderIdHasher.hash(uriString)

            // Short-circuit duplicates so re-selecting a folder reports a clear outcome and
            // preserves the original entry's metadata (Req 1.4).
            folderRepository.findFolder(id)?.let { return@withContext AddFolderResult.Duplicate(id) }

            val folder = Folder(
                id = id,
                treeUri = uriString,
                displayName = resolveDisplayName(treeUri),
                trackCount = 0,
                totalBytes = 0L,
                addedAt = addedAt,
            )

            // Insert-if-absent guards against a race between the check above and this write.
            if (folderRepository.addFolderIfAbsent(folder)) {
                AddFolderResult.Added(folder)
            } else {
                AddFolderResult.Duplicate(id)
            }
        } catch (e: SecurityException) {
            AddFolderResult.Error("Could not keep access to the selected folder.", e)
        } catch (e: Exception) {
            AddFolderResult.Error("Could not add the selected folder.", e)
        }
    }

    /**
     * Resolve a display name for the tree, falling back to the last path segment and finally a
     * generic label so a folder is always presentable (Req 2.1).
     */
    private fun resolveDisplayName(treeUri: Uri): String {
        val fromDocument = DocumentFile.fromTreeUri(context, treeUri)?.name?.takeIf { it.isNotBlank() }
        if (fromDocument != null) return fromDocument

        val lastSegment = treeUri.lastPathSegment
            ?.substringAfterLast('/')
            ?.substringAfterLast(':')
            ?.takeIf { it.isNotBlank() }
        return lastSegment ?: "Folder"
    }
}
