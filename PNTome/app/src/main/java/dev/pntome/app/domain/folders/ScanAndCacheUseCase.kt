package dev.pntome.app.domain.folders

import android.net.Uri
import dev.pntome.app.data.folders.FolderRepository
import dev.pntome.app.domain.model.AppSettings
import dev.pntome.app.domain.model.Folder
import dev.pntome.app.domain.scan.TrackReconciler
import dev.pntome.app.domain.scan.TrackScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Outcome of scanning a folder and caching the result (task 5.3).
 *
 * The folder-list/scan UI (task 4.2) and the later sync manifest builder use this to report how
 * a rescan changed the cache: how many eligible tracks are now cached, how many files were
 * skipped (Req 3.3), and how the cache was reconciled (added/removed) against the previous scan.
 */
sealed interface ScanAndCacheResult {
    /**
     * The folder was scanned and the cache reconciled.
     *
     * @property folderId the folder that was scanned.
     * @property trackCount eligible tracks now cached for the folder (Req 2.1).
     * @property totalBytes total size in bytes of the cached tracks (Req 2.1).
     * @property skippedCount files skipped because they were an unsupported/disabled format
     *   (Req 3.3).
     * @property addedCount tracks newly written for this folder (new or changed files).
     * @property removedCount previously-cached tracks removed (deleted or changed files).
     */
    data class Success(
        val folderId: String,
        val trackCount: Int,
        val totalBytes: Long,
        val skippedCount: Int,
        val addedCount: Int,
        val removedCount: Int
    ) : ScanAndCacheResult

    /** No folder with the given id is persisted; nothing was scanned. */
    data object FolderNotFound : ScanAndCacheResult

    /** Scanning or caching failed. */
    data class Error(val message: String, val cause: Throwable? = null) : ScanAndCacheResult
}

/**
 * Scans a persisted folder and caches the discovered tracks in Room, reconciling incrementally
 * against the previous scan and refreshing the owning folder's aggregates (task 5.3, Req 3.3,
 * 3.5, 8.1, 8.2; design §5.2/§7).
 *
 * This use case is the orchestration seam between the Android-coupled scanner (which owns SAF
 * enumeration + metadata extraction, injected via the [TrackScanner] interface so this logic is
 * testable without an emulator) and persistence ([FolderRepository]). Keeping it thin and
 * delegating the diff/aggregate maths to the pure [TrackReconciler] keeps the logic testable.
 *
 * Steps:
 * 1. Look up the folder so its tree URI and display name are available (report
 *    [ScanAndCacheResult.FolderNotFound] when it has been removed).
 * 2. Run [TrackScanner.scanToResult] to get the eligible tracks and the skipped count.
 * 3. Diff the fresh tracks against the currently-cached tracks by stable id
 *    ([TrackReconciler.reconcile]). Because [dev.pntome.app.domain.model.Track.id] encodes
 *    path/size/lastModified (Req 3.5), unchanged files keep their id (no churn), changed files
 *    get a new id (upsert new, remove stale), and deleted files are removed.
 * 4. Apply the diff: upsert the fresh tracks and delete the stale ids.
 * 5. Update the folder's [Folder.trackCount]/[Folder.totalBytes] so the folder list reflects
 *    accurate counts/size (Req 2.1).
 *
 * ### Incremental cost
 * The id-based reconcile is correctness-complete on its own: an unchanged folder yields a diff
 * with nothing to remove and an idempotent upsert. Metadata re-extraction still happens inside
 * the scanner on every pass; avoiding it for unchanged files is a possible later optimisation
 * (the id/lastModified data needed to detect "unchanged" already exists) but is not required for
 * correctness, which the task prioritises.
 */
@Singleton
class ScanAndCacheUseCase @Inject constructor(
    private val trackScanner: TrackScanner,
    private val folderRepository: FolderRepository
) {

    /**
     * Scan [folderId] and reconcile its cache.
     *
     * @param folderId the stable folder id (see [dev.pntome.app.domain.scan.FolderIdHasher]).
     * @param settings format flags gating optional formats; when null the scanner reads the
     *   current persisted [AppSettings].
     * @return [ScanAndCacheResult.Success] with the refreshed counts, [FolderNotFound] when the
     *   folder is gone, or [ScanAndCacheResult.Error] on failure.
     */
    suspend fun scanAndCache(
        folderId: String,
        settings: AppSettings? = null
    ): ScanAndCacheResult = withContext(Dispatchers.IO) {
        val folder = folderRepository.findFolder(folderId)
            ?: return@withContext ScanAndCacheResult.FolderNotFound

        try {
            val scan = trackScanner.scanToResult(
                treeUri = Uri.parse(folder.treeUri),
                folderId = folder.id,
                rootName = folder.displayName,
                settings = settings
            )

            val cached = folderRepository.getTracks(folder.id)
            val reconciliation = TrackReconciler.reconcile(cached = cached, scanned = scan.tracks)

            // Apply the diff: remove stale ids first so a changed file's old row never lingers,
            // then upsert the fresh tracks (new/changed by id, unchanged as idempotent writes).
            if (reconciliation.idsToRemove.isNotEmpty()) {
                folderRepository.removeTracks(reconciliation.idsToRemove)
            }
            folderRepository.upsertTracks(reconciliation.toUpsert)

            // Req 2.1: keep the folder's shown count/size in step with the cache. Aggregates
            // only — a full-row write of the pre-scan `folder` would clobber a flag (audiobook)
            // toggled while the scan ran.
            folderRepository.updateFolderAggregates(
                id = folder.id,
                trackCount = reconciliation.trackCount,
                totalBytes = reconciliation.totalBytes
            )

            ScanAndCacheResult.Success(
                folderId = folder.id,
                trackCount = reconciliation.trackCount,
                totalBytes = reconciliation.totalBytes,
                skippedCount = scan.skippedCount,
                addedCount = reconciliation.addedCount,
                removedCount = reconciliation.removedCount
            )
        } catch (e: Exception) {
            ScanAndCacheResult.Error("Could not scan the folder.", e)
        }
    }
}
