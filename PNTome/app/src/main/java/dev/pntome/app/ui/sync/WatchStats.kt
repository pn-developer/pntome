package dev.gsound.a.ui.sync

import dev.gsound.a.net.garmin.messages.Stats

/**
 * Render-ready projection of the watch's reported stats (Req 7.1).
 *
 * A small, framework-free UI model holding the last `STATS` the watch reported for the current
 * pairing: how many files and folders it currently holds, and when it last synced. Mapped from
 * the wire [Stats] message by [from].
 *
 * ## "unknown" vs. stale (Req 7.3)
 * The *absence* of this model (a `null` [WatchStats] on [SyncUiState.watchStats]) means **unknown**
 * — the screen renders "unknown" rather than any numbers. The app never shows stale counts without
 * saying so, which it enforces structurally by collapsing to `null` whenever the counts can no
 * longer be trusted:
 * - no `STATS` has been received yet → `null` (unknown),
 * - the pairing goes away (unpaired) → reset to `null` (the previous watch's counts are not this
 *   state), and
 * - only a `STATS` whose `pairingId` matches the *current* pairing is accepted (a message for a
 *   different/old pairing never becomes the displayed value).
 *
 * Note that a received value is intentionally *retained* across a transient control-channel drop:
 * the last known counts remain shown (they are the last truth the watch reported) and the user can
 * ask for fresh stats via [SyncViewModel.onRefreshStatsClicked]. "Unknown" is reserved for "we have
 * nothing trustworthy to show", not "the link blipped".
 *
 * @property fileCount number of files currently on the watch (Req 7.1).
 * @property folderCount number of folders currently on the watch (Req 7.1).
 * @property lastSyncAt epoch-millis of the watch's last successful sync, or null if it has never
 *   synced (distinct from the whole model being null/unknown).
 */
data class WatchStats(
    val fileCount: Int,
    val folderCount: Int,
    val lastSyncAt: Long?,
) {
    companion object {
        /** Project a validated wire [Stats] message into the UI model (Req 7.1). */
        fun from(stats: Stats): WatchStats = WatchStats(
            fileCount = stats.fileCount,
            folderCount = stats.folderCount,
            lastSyncAt = stats.lastSyncAt,
        )
    }
}
