package dev.gsound.a.domain.folders

import dev.gsound.a.data.folders.FolderRepository
import dev.gsound.a.log.GsoundLog
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Re-scans every configured folder so the Room track cache matches what is on disk right now.
 *
 * Called by the sync orchestrator before it builds a manifest. Without it a sync used whatever was
 * cached the last time the Folders tab was opened, so deleted/replaced files could still be sent to
 * the watch (or new files missed). An interface so the orchestrator stays testable without SAF.
 */
interface LibraryRefresher {
    /** Re-scan all folders; returns how many folders failed to scan (their cache is kept as-is). */
    suspend fun refreshAll(): Int
}

/** Production [LibraryRefresher]: runs [ScanAndCacheUseCase] for each persisted folder. */
@Singleton
class ScanLibraryRefresher @Inject constructor(
    private val folderRepository: FolderRepository,
    private val scanAndCacheUseCase: ScanAndCacheUseCase,
) : LibraryRefresher {

    private val tag = "Gsound.Sync"

    override suspend fun refreshAll(): Int {
        var failed = 0
        for (folder in folderRepository.getFolders()) {
            when (val result = scanAndCacheUseCase.scanAndCache(folder.id)) {
                is ScanAndCacheResult.Success -> GsoundLog.i(
                    tag,
                    "rescan '${folder.displayName}': ${result.trackCount} track(s) " +
                        "(+${result.addedCount} -${result.removedCount}, skipped ${result.skippedCount})",
                )
                is ScanAndCacheResult.Error -> {
                    // Keep the cached tracks rather than treating a failed scan as "empty":
                    // that would make the watch delete the folder's music on a transient error.
                    failed++
                    GsoundLog.w(tag, "rescan '${folder.displayName}' FAILED (cache kept): ${result.message}", result.cause)
                }
                ScanAndCacheResult.FolderNotFound -> Unit
            }
        }
        return failed
    }
}
