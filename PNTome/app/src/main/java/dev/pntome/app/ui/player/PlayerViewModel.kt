package dev.pntome.app.ui.player

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.Player
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pntome.app.data.folders.BookmarkRepository
import dev.pntome.app.data.folders.FolderRepository
import dev.pntome.app.service.PlaybackService
import dev.pntome.app.service.QueueBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

/**
 * Immutable UI state of the phone player (gsound-player PL-1.4), driven as a [StateFlow].
 *
 * @property hasMedia true when the service has a current item; false → nothing to show.
 * @property title current item title.
 * @property folder current item's folder (album) name.
 * @property positionMs playback position in ms.
 * @property durationMs item duration in ms, 0 while unknown.
 * @property isPlaying true while audio is actually playing.
 * @property isBook true when the current item belongs to an audiobook folder (shows ±30 s).
 * @property hasPrev whether a previous item exists in the queue.
 * @property hasNext whether a next item exists in the queue.
 * @property folderId id of the current item's folder, null when unknown.
 * @property cover cover picture of the current folder, null when none was found.
 * @property queue every item of the current queue, in play order.
 * @property currentIndex index of the current item in [queue], -1 when the queue is empty.
 * @property message a transient notice, cleared via [PlayerViewModel.onMessageShown].
 */
data class PlayerUiState(
    val hasMedia: Boolean = false,
    val title: String = "",
    val folder: String = "",
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val isPlaying: Boolean = false,
    val isBook: Boolean = false,
    val hasPrev: Boolean = false,
    val hasNext: Boolean = false,
    val folderId: String? = null,
    val cover: Bitmap? = null,
    val queue: List<QueueEntry> = emptyList(),
    val currentIndex: Int = -1,
    val message: String? = null,
)

/**
 * One row of the queue.
 *
 * @property done true when the chapter was already finished when it was queued.
 */
data class QueueEntry(
    val mediaId: String,
    val title: String,
    val folder: String,
    val folderId: String?,
    val isBook: Boolean,
    val done: Boolean,
)

/** A folder of the library as offered by the Player tab. */
data class PlayerFolder(
    val id: String,
    val name: String,
    val trackCount: Int,
    val isAudiobook: Boolean,
)

/**
 * Backs the player screen and mini-player (PL-1.1, 1.3, 1.4). It talks to [PlaybackService]
 * through a [Player] handed out by [PlayerConnector] (a `MediaController` in production).
 *
 * The controller is bound lazily (PL-7.1): nothing connects until playback is requested through
 * [playFolder]/[playTrack] or the UI calls [connect] to attach to playback already running. The
 * controller is released in [onCleared]; the service keeps playing.
 */
@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val connector: PlayerConnector,
    private val folderRepository: FolderRepository,
    private val queueBuilder: QueueBuilder,
    private val bookmarkRepository: BookmarkRepository,
    private val coverArt: CoverArtSource,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    /** The folders that can be added to the queue, in repository order. */
    val folders: StateFlow<List<PlayerFolder>> = folderRepository.observeFolders()
        .map { list ->
            list.map { PlayerFolder(it.id, it.displayName, it.trackCount, it.isAudiobook) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    private var coverFolderId: String? = null
    private var coverJob: Job? = null

    private var player: Player? = null
    private val connectLock = Mutex()
    private var ticker: Job? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = refresh(player)
    }

    /** Attach to the service so the state reflects playback already running. Idempotent. */
    fun connect() {
        viewModelScope.launch { controller() }
    }

    /**
     * Attach only when the service is already running (PL-7.1), so merely visiting the Folders
     * screen never starts or binds the service. [isServiceRunning] is injectable for tests.
     */
    fun connectIfRunning(isServiceRunning: () -> Boolean = { PlaybackService.running }) {
        if (isServiceRunning()) connect()
    }

    /**
     * Replace the queue with [folderId] and play. Audiobooks continue at their bookmark, with the
     * finished chapters marked done.
     */
    fun playFolder(folderId: String) = start(folderId, trackId = null)

    /**
     * Append [folderId] to the queue. Into an empty queue it is loaded ready to play (not
     * started), an audiobook selected at its next chapter and position.
     */
    fun addFolder(folderId: String) {
        viewModelScope.launch {
            try {
                val resume = bookmarkRepository.resolve(folderId)
                val items = queueBuilder.build(folderRepository, folderId, resume)
                if (items.isEmpty()) {
                    notify("Nothing to play in this folder.")
                    return@launch
                }
                val p = controller()
                if (p.mediaItemCount == 0) {
                    p.setMediaItems(items, resume?.index ?: 0, resume?.positionMs ?: 0L)
                    p.prepare()
                } else {
                    p.addMediaItems(items)
                }
                refresh(p)
                val name = items.first().mediaMetadata.albumTitle?.toString().orEmpty()
                notify("Added \"$name\" to the queue.")
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                notify("Couldn't add to the queue: ${e.message ?: "error"}")
            }
        }
    }

    /** Stop playback and empty the queue. */
    fun clearQueue() = withPlayer {
        // Pause first so the audiobook position is recorded before the item disappears.
        it.pause()
        it.clearMediaItems()
    }

    /** Jump to queue item [index] and play it. */
    fun playQueueItem(index: Int) = withPlayer {
        if (index in 0 until it.mediaItemCount) {
            it.seekTo(index, 0L)
            it.play()
        }
    }

    /** Play the folder's queue starting at [trackId]. */
    fun playTrack(folderId: String, trackId: String) = start(folderId, trackId)

    fun togglePlayPause() = withPlayer { if (it.isPlaying) it.pause() else it.play() }

    fun play() = withPlayer { it.play() }

    fun pause() = withPlayer { it.pause() }

    fun previous() = withPlayer { it.seekToPreviousMediaItem() }

    fun next() = withPlayer { it.seekToNextMediaItem() }

    fun seekTo(positionMs: Long) = withPlayer { it.seekTo(positionMs.coerceAtLeast(0)) }

    /** Audiobook skip back 30 s, clamped at the start of the item. */
    fun skipBack30() = skipBy(-PlaybackService.BOOK_SEEK_MS)

    /** Audiobook skip forward 30 s, clamped at the end of the item. */
    fun skipForward30() = skipBy(PlaybackService.BOOK_SEEK_MS)

    fun onMessageShown() {
        _uiState.update { it.copy(message = null) }
    }

    private fun skipBy(deltaMs: Long) = withPlayer { p ->
        val duration = p.duration.takeIf { it != C.TIME_UNSET }
        var target = (p.currentPosition + deltaMs).coerceAtLeast(0)
        if (duration != null) target = target.coerceAtMost(duration)
        p.seekTo(target)
    }

    private fun start(folderId: String, trackId: String?) {
        viewModelScope.launch {
            try {
                val resume = if (trackId == null) bookmarkRepository.resolve(folderId) else null
                val items = queueBuilder.build(folderRepository, folderId, resume)
                if (items.isEmpty()) {
                    notify("Nothing to play in this folder.")
                    return@launch
                }
                val index = trackId?.let { id -> items.indexOfFirst { it.mediaId == id } }
                    ?.takeIf { it >= 0 } ?: resume?.index ?: 0
                val p = controller()
                p.setMediaItems(items, index, if (trackId == null) resume?.positionMs ?: 0L else 0L)
                p.prepare()
                p.play()
                refresh(p)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                notify("Couldn't start playback: ${e.message ?: "error"}")
            }
        }
    }

    /** Runs [block] on the connected player; no-op until a controller exists (never connects). */
    private fun withPlayer(block: (Player) -> Unit) {
        val p = player ?: return
        block(p)
        refresh(p)
    }

    private suspend fun controller(): Player = connectLock.withLock {
        player ?: connector.connect().also { p ->
            player = p
            p.addListener(listener)
            refresh(p)
            ticker = viewModelScope.launch {
                while (true) {
                    delay(POSITION_POLL_MS)
                    if (p.isPlaying) refresh(p, rebuildQueue = false)
                }
            }
        }
    }

    private fun notify(text: String) {
        _uiState.update { it.copy(message = text) }
    }

    private fun refresh(p: Player, rebuildQueue: Boolean = true) {
        val item = p.currentMediaItem
        val meta = item?.mediaMetadata
        val duration = p.duration.takeIf { it != C.TIME_UNSET && it >= 0 } ?: 0L
        val folderId = meta?.extras?.getString(QueueBuilder.EXTRA_FOLDER_ID)
        val queue = if (rebuildQueue) queueOf(p) else null
        _uiState.update {
            it.copy(
                hasMedia = item != null,
                title = meta?.title?.toString().orEmpty(),
                folder = meta?.albumTitle?.toString().orEmpty(),
                positionMs = p.currentPosition.coerceAtLeast(0),
                durationMs = duration,
                isPlaying = p.isPlaying,
                isBook = meta?.extras?.getBoolean(QueueBuilder.EXTRA_AUDIOBOOK) == true,
                hasPrev = p.hasPreviousMediaItem(),
                hasNext = p.hasNextMediaItem(),
                folderId = folderId,
                queue = queue ?: it.queue,
                currentIndex = if (item == null) -1 else p.currentMediaItemIndex,
            )
        }
        updateCover(folderId)
    }

    private fun queueOf(p: Player): List<QueueEntry> = (0 until p.mediaItemCount).map { i ->
        val item = p.getMediaItemAt(i)
        val meta = item.mediaMetadata
        val extras = meta.extras
        QueueEntry(
            mediaId = item.mediaId,
            title = meta.title?.toString().orEmpty(),
            folder = meta.albumTitle?.toString().orEmpty(),
            folderId = extras?.getString(QueueBuilder.EXTRA_FOLDER_ID),
            isBook = extras?.getBoolean(QueueBuilder.EXTRA_AUDIOBOOK) == true,
            done = extras?.getBoolean(QueueBuilder.EXTRA_DONE) == true,
        )
    }

    /** Loads the cover when the current folder changes; clears it while loading / when none. */
    private fun updateCover(folderId: String?) {
        if (folderId == coverFolderId) return
        coverFolderId = folderId
        coverJob?.cancel()
        _uiState.update { it.copy(cover = null) }
        if (folderId == null) return
        coverJob = viewModelScope.launch {
            val bitmap = coverArt.load(folderId)
            if (coverFolderId == folderId) _uiState.update { it.copy(cover = bitmap) }
        }
    }

    override fun onCleared() {
        ticker?.cancel()
        player?.let {
            it.removeListener(listener)
            connector.release(it)
        }
        player = null
        super.onCleared()
    }

    private companion object {
        const val POSITION_POLL_MS = 500L
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
