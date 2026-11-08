package dev.gsound.a.domain.model

/**
 * Summary of the most recent sync, shown on the Sync screen and persisted (Req 6.3, 8.1).
 *
 * Pure domain model — no framework/persistence annotations (Proto DataStore schema lives in
 * the data layer, see task 3).
 *
 * @property syncedCount number of tracks successfully synced to the watch.
 * @property skippedCount number of tracks skipped (already present / unsupported).
 * @property removedCount number of tracks removed from the watch during reconciliation.
 * @property lastSyncAt epoch millis of the last completed sync.
 * @property error user-facing error message when the last sync failed, or null on success.
 */
data class SyncSummary(
    val syncedCount: Int,
    val skippedCount: Int,
    val removedCount: Int,
    val lastSyncAt: Long,
    val error: String?
)
