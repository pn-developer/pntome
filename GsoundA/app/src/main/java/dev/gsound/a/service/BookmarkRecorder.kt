package dev.gsound.a.service

import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import dev.gsound.a.data.folders.BookmarkDao
import dev.gsound.a.data.folders.BookmarkEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Records the audiobook listening position on the phone (gsound-player PL-3, design §4.2).
 *
 * A [Player.Listener] plus a ticker:
 *
 * | Trigger | Write |
 * |---|---|
 * | pause / stop / `ENDED` | current chapter + position |
 * | media item transition (not a playlist change) | the new chapter at its current position |
 * | seek | the new position |
 * | ticker, every [tickMs] while playing | current chapter + position |
 * | [flush] / [release] (destroy, task removal, disconnect) | final write |
 *
 * Only items that carry [QueueBuilder.EXTRA_AUDIOBOOK] and [QueueBuilder.EXTRA_FOLDER_ID] are
 * recorded, so music is ignored. When the last chapter ends the bookmark is written with
 * `finished = true`; a chapter that ends earlier is followed by the transition event, which
 * writes the next chapter at its start (PL-3.3).
 *
 * Writes never block the player thread (PL-3.5): the snapshot is taken on the calling (player)
 * thread and stored as the latest pending snapshot of its folder (conflation is per folder), then a
 * conflated [Channel] wakes a single consumer on [writeScope] (use an IO dispatcher). A failing
 * DAO write is logged and skipped; the writer keeps running.
 * A snapshot equal to the last stored `(trackId, positionSec, finished)` is skipped.
 *
 * @param player the player to observe; all calls happen on its application thread.
 * @param dao bookmark storage.
 * @param tickerScope scope for the ticker; must run on the player's thread (e.g. Main).
 * @param writeScope scope for the single writer coroutine; it outlives [tickerScope] so the final
 *   write of [release] still completes after the service has been torn down.
 * @param clock epoch millis.
 */
class BookmarkRecorder(
    private val player: Player,
    private val dao: BookmarkDao,
    private val tickerScope: CoroutineScope,
    writeScope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val tickMs: Long = TICK_MS,
) : Player.Listener {

    private data class Key(val trackId: String, val positionSec: Int, val finished: Boolean)

    private data class Snapshot(
        val folderId: String,
        val folderName: String,
        val trackId: String,
        val chapterIndex: Int,
        val positionSec: Int,
        val finished: Boolean,
        val updatedAt: Long,
    ) {
        val key get() = Key(trackId, positionSec, finished)
    }

    /**
     * Conflation is per folder: [pending] keeps the latest snapshot of every folder, so a snapshot
     * of book A is not dropped when book B is enqueued before the writer drains. [channel] is
     * only a conflated wake-up signal for the writer.
     */
    private val pending = LinkedHashMap<String, Snapshot>()
    private val channel = Channel<Unit>(Channel.CONFLATED)
    private val lastWritten = HashMap<String, Key>()
    private var ticker: Job? = null
    private var released = false

    /**
     * Folder -> trackId of a book that finished (last chapter `ENDED`). While set, later snapshots
     * of that same chapter stay `finished = true` even though the player has left `ENDED`
     * (e.g. `stop()` resets the state to IDLE before the destroy/disconnect flush). Cleared only
     * by an actual restart: play, seek or a real chapter transition.
     */
    private val finishedBooks = HashMap<String, String>()

    init {
        writeScope.launch {
            // Closing the channel still delivers a buffered signal, so the final write is drained.
            for (signal in channel) {
                val batch = synchronized(pending) {
                    pending.values.toList().also { pending.clear() }
                }
                for (s in batch) {
                    try {
                        write(s)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // A failing write must not kill the loop; log the type only (no data).
                        Log.w(TAG, "Bookmark write failed: ${e.javaClass.simpleName}")
                    }
                }
            }
        }
        player.addListener(this)
        if (player.isPlaying) startTicker()
    }

    private suspend fun write(s: Snapshot) {
        // Always read the stored row: the upsert below replaces the whole row, so the sync
        // bookkeeping `lastSentAt` (PL-6.4) must be carried over from it, not reset to 0.
        val existing = dao.get(s.folderId)
        val known = lastWritten[s.folderId]
            ?: existing?.let { Key(it.trackId, it.positionSec, it.finished) }
        if (known == s.key) {
            lastWritten[s.folderId] = s.key
            return
        }
        dao.upsert(
            BookmarkEntity(
                folderId = s.folderId,
                folderName = s.folderName,
                trackId = s.trackId,
                chapterIndex = s.chapterIndex,
                positionSec = s.positionSec,
                finished = s.finished,
                updatedAt = s.updatedAt,
                source = SOURCE,
                lastSentAt = existing?.lastSentAt ?: 0L,
            )
        )
        lastWritten[s.folderId] = s.key
    }

    /** Takes a snapshot of the current book position and queues it; no-op for music/no item. */
    fun flush() {
        if (released) return
        enqueue()
    }

    /** Final write, then detach. Safe to call more than once. */
    fun release() {
        if (released) return
        enqueue()
        released = true
        player.removeListener(this)
        ticker?.cancel()
        ticker = null
        channel.close()
    }

    private fun enqueue(atChapterStart: Boolean = false) {
        val item = player.currentMediaItem ?: return
        val folderId = bookFolderId(item) ?: return
        val ended = player.playbackState == Player.STATE_ENDED
        val finished = if (ended) {
            (!player.hasNextMediaItem()).also { last ->
                if (last) finishedBooks[folderId] = item.mediaId
            }
        } else {
            finishedBooks[folderId] == item.mediaId
        }
        val snapshot = Snapshot(
                folderId = folderId,
                folderName = item.mediaMetadata.albumTitle?.toString().orEmpty(),
                trackId = item.mediaId,
                chapterIndex = player.currentMediaItemIndex,
                // A chapter that completed on its own always hands over at 0 (PL-3.3).
                positionSec = if (atChapterStart) 0
                else (player.currentPosition.coerceAtLeast(0L) / 1000L).toInt(),
                finished = finished,
                updatedAt = clock() / 1000L,
        )
        synchronized(pending) { pending[folderId] = snapshot }
        channel.trySend(Unit)
    }

    private fun bookFolderId(item: MediaItem): String? {
        val extras = item.mediaMetadata.extras ?: return null
        if (!extras.getBoolean(QueueBuilder.EXTRA_AUDIOBOOK)) return null
        return extras.getString(QueueBuilder.EXTRA_FOLDER_ID)?.takeIf { it.isNotEmpty() }
    }

    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = tickerScope.launch {
            while (isActive) {
                delay(tickMs)
                if (player.isPlaying) enqueue()
            }
        }
    }

    // --- Player.Listener ---

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) {
            finishedBooks.clear()
            startTicker()
        } else {
            ticker?.cancel()
            ticker = null
            enqueue()
        }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_ENDED) enqueue()
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        // PLAYLIST_CHANGED is a queue being loaded, not listening progress.
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) return
        finishedBooks.clear()
        enqueue(atChapterStart = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK ||
            reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT
        ) {
            finishedBooks.clear()
            enqueue()
        }
    }

    companion object {
        const val TICK_MS = 15_000L
        const val SOURCE = "phone"
        private const val TAG = "BookmarkRecorder"
    }
}
