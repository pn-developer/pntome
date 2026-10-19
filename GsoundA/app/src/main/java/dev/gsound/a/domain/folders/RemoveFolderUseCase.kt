package dev.gsound.a.domain.folders

import android.content.Context
import android.content.Intent
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.gsound.a.data.folders.FolderRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Outcome of removing a folder from the persisted list.
 *
 * The folder-list UI (task 4.2) uses this to confirm removal or surface a problem. Releasing
 * the persisted permission is best-effort: even if the system has already dropped the grant
 * (e.g. the backing storage was ejected), the row is still removed so the list stays
 * consistent — hence [Removed] rather than [Error] in that case.
 */
sealed interface RemoveFolderResult {
    /** The folder was removed from storage (and its permission released if still held). */
    data object Removed : RemoveFolderResult

    /** No folder with the given id was found; nothing was changed. */
    data object NotFound : RemoveFolderResult

    /** Removing the folder failed. */
    data class Error(val message: String, val cause: Throwable? = null) : RemoveFolderResult
}

/**
 * Removes a folder from the persisted list (Req 2.2).
 *
 * Mirrors [AddFolderUseCase]: given a folder id this use case
 * 1. looks up the folder so it can resolve the tree URI to release (and report [NotFound] when
 *    it is already gone),
 * 2. **releases the persistable read** permission taken in [AddFolderUseCase] so the app no
 *    longer holds access to the subtree (Req 2.2), and
 * 3. deletes the folder row (cached tracks cascade via the entity foreign key).
 *
 * Releasing the permission is best-effort — a [SecurityException] (the grant was already
 * dropped) does not prevent removal, so the stored list and the system's held grants converge.
 */
@Singleton
class RemoveFolderUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val folderRepository: FolderRepository,
) {

    /**
     * Remove the folder identified by [id].
     *
     * @param id the stable folder id (see [dev.gsound.a.domain.scan.FolderIdHasher]).
     * @return [RemoveFolderResult.Removed] on success, [RemoveFolderResult.NotFound] when no such
     *   folder exists, or [RemoveFolderResult.Error] when the delete itself fails.
     */
    suspend fun remove(id: String): RemoveFolderResult = withContext(Dispatchers.IO) {
        val folder = folderRepository.findFolder(id)
            ?: return@withContext RemoveFolderResult.NotFound

        // Req 2.2: give up our persisted read grant. Best-effort — the system may have already
        // revoked it, in which case the row is still worth removing.
        try {
            context.contentResolver.releasePersistableUriPermission(
                Uri.parse(folder.treeUri),
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (_: SecurityException) {
            // Grant already gone; proceed with removal.
        }

        try {
            folderRepository.removeFolder(id)
            RemoveFolderResult.Removed
        } catch (e: Exception) {
            RemoveFolderResult.Error("Could not remove the folder.", e)
        }
    }
}
