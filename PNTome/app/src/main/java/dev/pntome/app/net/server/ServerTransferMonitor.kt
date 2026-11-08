package dev.pntome.app.net.server

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Observed, server-side progress of the active sync (how much the watch has actually pulled),
 * published to the orchestrator so the UI can show real download progress and speed **even when
 * the watch never sends `SYNC_STATUS`** over the control channel.
 *
 * @property tracksTotal number of tracks in the active session's manifest.
 * @property tracksComplete number of tracks fully served (cumulative bytes ≥ the track's size).
 * @property bytesServed total distinct bytes served across all tracks so far.
 * @property bytesTotal total bytes expected across all tracks (sum of manifest sizes), or null
 *   when no session is active.
 * @property currentTrackTitle human-readable title of the track most recently being served, or
 *   null before the first byte flows.
 * @property speedBytesPerSec a smoothed recent transfer rate in bytes/second, or null until
 *   there is enough signal to estimate it.
 */
data class TransferProgress(
    val tracksTotal: Int,
    val tracksComplete: Int,
    val bytesServed: Long,
    val bytesTotal: Long?,
    val currentTrackTitle: String?,
    val speedBytesPerSec: Long?,
)

/**
 * Process-wide, thread-safe monitor of real HTTP byte activity for the active sync.
 *
 * ## Why this exists
 * Sync progress historically came only from `SYNC_STATUS` messages the watch pushes back. When
 * the watch does not report (observed in the field: manifest sent, server serving, but the UI
 * stuck on "Preparing"), there was no other progress signal. This monitor watches the bytes the
 * local HTTP server actually streams out of `/media/{trackId}` and turns them into progress the
 * orchestrator can project onto [dev.pntome.app.domain.model.SyncState.Running].
 *
 * ## How counting works (Range-safe)
 * The watch may issue multiple ranged `GET`s per file (resume/retry), each re-opening the stream.
 * Naively summing every byte read would overcount. Instead each media response reports, via
 * [reportTrackOffset], the **highest byte offset reached** for that `trackId`; the monitor keeps
 * the per-track maximum. Aggregate [TransferProgress.bytesServed] is the sum of those maxima, and
 * a track is "complete" once its max offset reaches its known size. This is monotonic and does not
 * double-count overlapping ranges. (It can under-count if the watch fetches sparse, non-contiguous
 * ranges and never the tail, but for a straight download it is exact, and completion is still
 * driven by reaching the size.)
 *
 * ## Lifecycle
 * [begin] is called by the orchestrator when a session is installed (seeding per-track sizes and
 * titles and resetting counters); [reset] clears everything on teardown. Both the route side
 * (through a counting stream) and the orchestrator hold the same `@Singleton`, mirroring
 * [SyncSessionHolder].
 */
@Singleton
class ServerTransferMonitor @Inject constructor() {

    /** Immutable per-track metadata captured at [begin]. */
    private data class TrackMeta(val sizeBytes: Long, val title: String)

    private val meta = ConcurrentHashMap<String, TrackMeta>()

    /** Highest byte offset observed per track id (monotonic; see class doc). */
    private val servedByTrack = ConcurrentHashMap<String, Long>()

    @Volatile
    private var currentTrackId: String? = null

    @Volatile
    private var bytesTotal: Long? = null

    // --- speed estimation (sliding window) --------------------------------------------------

    private val speedLock = Any()
    private var windowStartMillis: Long = 0L
    private var windowStartBytes: Long = 0L
    private var smoothedSpeed: Long? = null

    private val _progress = MutableStateFlow<TransferProgress?>(null)

    /** Live server-observed progress of the active sync, or null when no sync is active. */
    val progress: StateFlow<TransferProgress?> = _progress.asStateFlow()

    /**
     * Start monitoring a new sync. Seeds per-track sizes/titles from the session so completion and
     * totals can be computed, resets all counters, and emits a zeroed [TransferProgress].
     */
    fun begin(session: SyncSession) {
        meta.clear()
        servedByTrack.clear()
        session.manifest.tracks.forEach { track ->
            meta[track.id] = TrackMeta(sizeBytes = track.sizeBytes, title = track.title)
        }
        bytesTotal = session.manifest.tracks.sumOf { it.sizeBytes }.takeIf { it > 0 }
        currentTrackId = null
        synchronized(speedLock) {
            windowStartMillis = nowMillis()
            windowStartBytes = 0L
            smoothedSpeed = null
        }
        publish()
    }

    /** Stop monitoring and clear all state (sync completed/cancelled/errored). */
    fun reset() {
        meta.clear()
        servedByTrack.clear()
        currentTrackId = null
        bytesTotal = null
        synchronized(speedLock) {
            windowStartMillis = 0L
            windowStartBytes = 0L
            smoothedSpeed = null
        }
        _progress.value = null
    }

    /**
     * Report that [offset] bytes (from the start of the file) have now been served for [trackId].
     * Keeps the per-track maximum, marks [trackId] as the current track, recomputes the smoothed
     * speed, and publishes a fresh [TransferProgress]. Ignored for ids not in the active session.
     */
    fun reportTrackOffset(trackId: String, offset: Long) {
        if (!meta.containsKey(trackId)) return
        currentTrackId = trackId
        // Keep the maximum observed offset for this track (Range-safe, monotonic).
        servedByTrack.merge(trackId, offset) { prev, next -> if (next > prev) next else prev }
        recomputeSpeed()
        publish()
    }

    private fun recomputeSpeed() {
        val now = nowMillis()
        val total = servedByTrack.values.sum()
        synchronized(speedLock) {
            val elapsed = now - windowStartMillis
            // Update the estimate on a ~1s cadence so it reflects *recent* throughput, not the
            // whole-sync average, and is stable enough to read.
            if (elapsed >= SPEED_WINDOW_MILLIS) {
                val deltaBytes = (total - windowStartBytes).coerceAtLeast(0)
                val instantaneous = deltaBytes * 1000L / elapsed
                // Exponential smoothing to avoid a jumpy readout.
                smoothedSpeed = smoothedSpeed?.let { (it * 3 + instantaneous) / 4 } ?: instantaneous
                windowStartMillis = now
                windowStartBytes = total
            }
        }
    }

    private fun publish() {
        val metas = meta
        if (metas.isEmpty()) {
            _progress.value = null
            return
        }
        val served = servedByTrack
        // Clamp each track's served bytes to its known size so an over-read (or a client that
        // re-requests past EOF) can never push the aggregate above bytesTotal.
        val bytesServed = metas.entries.sumOf { (id, m) ->
            val offset = served[id] ?: 0L
            if (m.sizeBytes > 0) offset.coerceAtMost(m.sizeBytes) else offset
        }
        val complete = metas.count { (id, m) ->
            m.sizeBytes > 0 && (served[id] ?: 0L) >= m.sizeBytes
        }
        _progress.value = TransferProgress(
            tracksTotal = metas.size,
            tracksComplete = complete,
            bytesServed = bytesServed,
            bytesTotal = bytesTotal,
            currentTrackTitle = currentTrackId?.let { metas[it]?.title },
            speedBytesPerSec = synchronized(speedLock) { smoothedSpeed },
        )
    }

    /** Overridable clock seam kept trivial; wall-clock is fine for a human-readable rate. */
    private fun nowMillis(): Long = System.currentTimeMillis()

    private companion object {
        /** Minimum elapsed time between speed samples (ms). */
        const val SPEED_WINDOW_MILLIS = 1_000L
    }
}
