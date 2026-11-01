package dev.gsound.a.domain.model

/**
 * Live state of a sync operation, exposed by the `SyncOrchestrator` as a `Flow<SyncState>`
 * and rendered by the `SyncViewModel`/Sync screen (design §5.6, Req 6).
 *
 * Pure domain model — framework-free. Modeled as a sealed interface so exhaustive `when`
 * handling is enforced at compile time.
 */
sealed interface SyncState {

    /** No sync in progress; initial and post-cancel resting state. */
    data object Idle : SyncState

    /** Building the manifest and starting the local HTTP server before transfer begins (Req 6.1). */
    data object Preparing : SyncState

    /**
     * A sync is actively transferring. Progress is driven by `SYNC_STATUS` messages and/or
     * server request activity (Req 6.2).
     *
     * @property tracksDone number of tracks completed so far.
     * @property tracksTotal total number of tracks in the manifest.
     * @property bytesSent bytes served so far.
     * @property bytesTotal total bytes expected across all tracks, or null when not yet known.
     * @property currentTrackTitle title of the track currently transferring, or null.
     * @property speedBytesPerSec recent transfer rate in bytes/second observed on the local
     *   server, or null until it can be estimated.
     * @property serverUrl the LAN `http://ip:port` the music server is bound to, surfaced so the
     *   UI can display it for connectivity verification (Req 5.8); null when not yet known.
     */
    data class Running(
        val tracksDone: Int,
        val tracksTotal: Int,
        val bytesSent: Long,
        val bytesTotal: Long? = null,
        val currentTrackTitle: String? = null,
        val speedBytesPerSec: Long? = null,
        val serverUrl: String? = null
    ) : SyncState

    /** Sync finished successfully; carries the summary shown to the user (Req 6.3). */
    data class Completed(val summary: SyncSummary) : SyncState

    /** Sync failed (watch-reported error or control-channel drop); retryable (Req 6.4). */
    data class Error(val message: String) : SyncState

    /** Sync was cancelled by the user; the server has been stopped (Req 6.5). */
    data object Cancelled : SyncState
}
