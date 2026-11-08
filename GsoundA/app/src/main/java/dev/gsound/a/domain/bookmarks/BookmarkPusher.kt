package dev.gsound.a.domain.bookmarks

import dev.gsound.a.data.folders.BookmarkDao
import dev.gsound.a.data.folders.FolderRepository
import dev.gsound.a.data.pairing.PairingStore
import dev.gsound.a.log.GsoundLog
import dev.gsound.a.net.garmin.ConnectionState
import dev.gsound.a.net.garmin.ControlChannel
import dev.gsound.a.net.garmin.messages.Bookmark
import dev.gsound.a.net.garmin.messages.ControlMessage
import dev.gsound.a.net.garmin.messages.Stats
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phone → watch bookmark push (spec gsound-player PL-5.1, PL-5.2, PL-6.4; design §7).
 *
 * [pushPending] sends, one at a time, a `BOOKMARK` for every audiobook bookmark that changed since
 * it was last exchanged with the watch ([BookmarkSyncState.needsSending]). Each success is
 * recorded with [BookmarkSyncState.markSent]; the first failure stops the chain so the rest go
 * next time. Never throws. A no-op without a pairing or while the watch is not reachable (PL-7.4).
 * Concurrent calls are collapsed: while a push runs, another call returns immediately.
 */
@Singleton
class BookmarkPusher internal constructor(
    private val controlChannel: ControlChannel,
    private val pairingStore: PairingStore,
    private val folders: FolderRepository,
    private val bookmarkDao: BookmarkDao,
    private val statsTimeoutMs: Long,
    private val quietPeriodMs: Long,
    private val maxWindowMs: Long,
) {
    @Inject
    constructor(
        controlChannel: ControlChannel,
        pairingStore: PairingStore,
        folders: FolderRepository,
        bookmarkDao: BookmarkDao,
    ) : this(controlChannel, pairingStore, folders, bookmarkDao, STATS_TIMEOUT_MS, QUIET_PERIOD_MS, MAX_WINDOW_MS)

    private val tag = "Gsound.Bookmark"
    private val mutex = Mutex()

    /** Send all pending phone bookmarks to the watch. Returns the number delivered. */
    suspend fun pushPending(): Int {
        if (!mutex.tryLock()) {
            GsoundLog.d(tag, "push skipped: already running")
            return 0
        }
        try {
            val pairingId = pairingStore.get()?.pairingId ?: return 0
            if (controlChannel.connectionState.value !is ConnectionState.Connected) {
                GsoundLog.d(tag, "push skipped: watch not reachable")
                return 0
            }
            var sentCount = 0
            for (entity in BookmarkSyncState.pending(bookmarkDao.getAllByRecency())) {
                val folder = folders.findFolder(entity.folderId)?.takeIf { it.isAudiobook } ?: continue
                val msg = Bookmark(
                    pairingId = pairingId,
                    folder = folder.displayName,
                    fid = folder.id,
                    trk = entity.trackId,
                    idx = entity.chapterIndex,
                    pos = entity.positionSec,
                    fin = entity.finished,
                    at = entity.updatedAt,
                )
                val result = controlChannel.send(msg)
                if (result.isFailure) {
                    GsoundLog.w(tag, "BOOKMARK push failed; remaining left for next time")
                    break
                }
                BookmarkSyncState.markSent(bookmarkDao, entity)
                sentCount++
            }
            return sentCount
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            GsoundLog.w(tag, "BOOKMARK push aborted", e)
            return 0
        } finally {
            mutex.unlock()
        }
    }

    /**
     * Refresh-stats flow (design §7): run [request] (the stats PING), wait for the watch's `STATS`,
     * then let its `BOOKMARK` messages land (quiet for [quietPeriodMs], at most [maxWindowMs]) so
     * the watch→phone merge in [BookmarkSync] happens before [pushPending]. Without a `STATS`
     * within [statsTimeoutMs] nothing is pushed.
     */
    suspend fun pushAfterStats(request: suspend () -> Unit): Int = coroutineScope {
        val events = Channel<ControlMessage>(Channel.UNLIMITED)
        // UNDISPATCHED: the subscription exists before the request goes out.
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            controlChannel.incoming.filter { it is Stats || it is Bookmark }.collect { events.trySend(it) }
        }
        try {
            request()
            val gotStats = withTimeoutOrNull(statsTimeoutMs) {
                while (events.receive() !is Stats) { /* keep waiting */ }
                true
            } ?: false
            if (!gotStats) {
                GsoundLog.d(tag, "no STATS after refresh; skipping bookmark push")
                return@coroutineScope 0
            }
            withTimeoutOrNull(maxWindowMs) {
                while (withTimeoutOrNull(quietPeriodMs) { events.receive() } != null) { /* extend */ }
            }
        } finally {
            collector.cancel()
        }
        pushPending()
    }

    companion object {
        const val STATS_TIMEOUT_MS = 8_000L
        const val QUIET_PERIOD_MS = 2_000L
        const val MAX_WINDOW_MS = 8_000L
    }
}
