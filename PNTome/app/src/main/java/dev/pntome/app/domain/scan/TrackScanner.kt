package dev.gsound.a.domain.scan

import android.net.Uri
import dev.gsound.a.domain.model.AppSettings

/**
 * One-shot scan seam over [FolderScanner.scanToResult] (task 5.3).
 *
 * [FolderScanner] owns the Android-coupled SAF enumeration + metadata extraction and so can
 * only run on a device/emulator (its instrumented coverage is task 5.4). Depending on this thin
 * interface instead of the concrete scanner lets the orchestration/caching layer
 * ([dev.gsound.a.domain.folders.ScanAndCacheUseCase]) be exercised on the JVM with a fake scan
 * result, so the reconcile/aggregate behaviour is testable without an emulator.
 *
 * The progress-emitting [FolderScanner.scan] is intentionally not part of this seam — only the
 * terminal [ScanResult] matters to the cache.
 */
interface TrackScanner {

    /**
     * Scan [treeUri] to completion and return the discovered tracks plus the skipped count.
     *
     * @param treeUri the persisted SAF tree URI.
     * @param folderId the owning folder's id, stamped onto each track.
     * @param rootName the folder's display name, used to group root-level tracks.
     * @param settings format flags gating optional formats; when null the implementation reads
     *   the current persisted [AppSettings].
     */
    suspend fun scanToResult(
        treeUri: Uri,
        folderId: String,
        rootName: String,
        settings: AppSettings? = null
    ): ScanResult
}
