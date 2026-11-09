
package dev.pntome.app.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import dagger.hilt.android.AndroidEntryPoint
import dev.pntome.app.data.folders.BookmarkDao
import dev.pntome.app.data.folders.BookmarkRepository
import dev.pntome.app.data.folders.FolderRepository
import dev.pntome.app.di.PlayerModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import javax.inject.Inject

/**
 * Phone player service (gsound-player design §2, task 2.1 spike for risk R1).
 *
 * A [MediaLibraryService] that owns one [ExoPlayer] and exposes it through a
 * [MediaLibrarySession]. Media3 posts the media notification and promotes the service to a
 * `mediaPlayback` foreground service by itself while playback is active.
 *
 * ## Spike scope (task 2.1)
 * Prove that ExoPlayer can play a SAF `content://` track (a document URI under a persisted tree
 * grant) from this service in the background. ExoPlayer's default data source reads
 * `content://` URIs through `ContentResolver`; the persisted tree permission belongs to the app
 * process, which is the process that plays, so no extra grant is needed.
 *
 * Entry point for the spike is [play], which starts the service with [ACTION_PLAY_URI]. The real
 * queue/controller path (tasks 2.2, 3.x) replaces it; the library-browse callbacks (task 6) are
 * served by [LibraryCallback] (browse only; onSetMediaItems etc. come in 6.3-6.5).
 *
 * ## R1 fallback
 * If ExoPlayer cannot open the SAF URI directly, swap the data source: build the player with a
 * `DefaultMediaSourceFactory` whose `DataSource.Factory` opens
 * `ContentResolver.openFileDescriptor(uri, "r")` and serves it through a `DataSource` over the
 * `ParcelFileDescriptor` (design §9 R1). Nothing else in this class changes.
 */
@AndroidEntryPoint
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackService : MediaLibraryService() {

    /** Injected for the queue/controller path (tasks 3.x, 6.x); unused by the 2.1 spike entry. */
    @Inject internal lateinit var folderRepository: FolderRepository
    @Inject internal lateinit var queueBuilder: QueueBuilder
    @Inject internal lateinit var bookmarkDao: BookmarkDao
    @Inject internal lateinit var bookmarkRepository: BookmarkRepository

    private val tickerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    // Not cancelled in onDestroy: the recorder closes its channel and this drains the final write.
    private val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    internal var recorder: BookmarkRecorder? = null
        private set

    internal var player: ExoPlayer? = null
        private set
    private var session: MediaLibrarySession? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        val exo = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        player = exo
        exo.addListener(UnreadableFileSkipper(exo) { showMessage(it) })
        exo.addListener(QueueResumeListener(exo))
        recorder = BookmarkRecorder(exo, bookmarkDao, tickerScope, writeScope)
        val seekPlayer = BookSeekPlayer(exo)
        session = MediaLibrarySession.Builder(
            this, seekPlayer,
            LibraryCallback(
                BrowseTree(folderRepository, bookmarkDao, queueBuilder, PlayerModule.defaultArtworkUri(this)),
                tickerScope,
                MediaItemResolver(folderRepository, bookmarkRepository, queueBuilder),
            ) { recorder?.flush() }
        ).build().also { s ->
            BookLayoutUpdater(exo) { s.setCustomLayout(it) }.also { exo.addListener(it) }.apply()
        }
    }

    private fun showMessage(text: String) {
        Toast.makeText(applicationContext, text, Toast.LENGTH_SHORT).show()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
        session

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PLAY_URI) {
            val uri = intent.data
            val exo = player
            if (uri != null && exo != null) {
                val item = MediaItem.Builder()
                    .setMediaId(uri.toString())
                    .setUri(uri)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(intent.getStringExtra(EXTRA_TITLE) ?: uri.lastPathSegment)
                            .build()
                    )
                    .build()
                exo.setMediaItem(item)
                exo.prepare()
                exo.play()
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    /** Stop when the user swipes the app away and nothing is playing (Media3 convention). */
    override fun onTaskRemoved(rootIntent: Intent?) {
        recorder?.flush()
        val exo = player
        if (exo == null || !exo.playWhenReady || exo.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        recorder?.release()
        recorder = null
        tickerScope.cancel()
        session?.release()
        session = null
        player?.release()
        player = null
        running = false
        super.onDestroy()
    }

    companion object {
        /** True while a service instance exists in this process (PL-7.1: attach only if so). */
        @Volatile
        var running: Boolean = false
            private set

        /** Seek increment for audiobook items (PL-1.4, design §3). */
        const val BOOK_SEEK_MS = 30_000L

        /** Spike action: play the SAF document URI in [Intent.getData]. */
        const val ACTION_PLAY_URI = "dev.pntome.app.action.PLAY_URI"
        const val EXTRA_TITLE = "dev.pntome.app.extra.TITLE"

        /** Starts the service and plays a single [uri] (task 2.1 spike entry point). */
        fun play(context: Context, uri: Uri, title: String? = null) {
            val intent = Intent(context, PlaybackService::class.java)
                .setAction(ACTION_PLAY_URI)
                .setData(uri)
            if (title != null) intent.putExtra(EXTRA_TITLE, title)
            context.startService(intent)
        }
    }
}

/**
 * Exposes 30 s seek back/forward increments for audiobook items and the default 15 s for music
 * (the increment is a builder-only setting on ExoPlayer, so it is applied per current item here).
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class BookSeekPlayer(player: Player) : ForwardingPlayer(player) {
    private fun isBook() = BookCustomLayout.isBook(currentMediaItem)

    private fun increment() =
        if (isBook()) PlaybackService.BOOK_SEEK_MS else DEFAULT_MS

    override fun getSeekBackIncrement(): Long = increment()
    override fun getSeekForwardIncrement(): Long = increment()

    // ForwardingPlayer delegates seekBack()/seekForward() to ExoPlayer, which would use its own
    // fixed 15 s increment, so apply the per-item increment here.
    override fun seekBack() = seekTo((currentPosition - increment()).coerceAtLeast(0L))

    override fun seekForward() {
        val target = currentPosition + increment()
        val duration = duration
        seekTo(if (duration != C.TIME_UNSET) minOf(target, duration) else target)
    }

    private companion object {
        const val DEFAULT_MS = 15_000L
    }
}

/**
 * PL-1.7: when an item cannot be read (revoked permission, removed file) skip to the next one and
 * report a short message instead of stopping or crashing. On the last item, the player is left
 * idle after the message.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class UnreadableFileSkipper(
    private val player: Player,
    private val message: (String) -> Unit,
) : Player.Listener {
    override fun onPlayerError(error: PlaybackException) {
        val title = player.currentMediaItem?.mediaMetadata?.title?.toString()
        message(if (title.isNullOrBlank()) MESSAGE else "$MESSAGE: $title")
        if (player.hasNextMediaItem()) {
            player.seekToNextMediaItem()
            player.prepare()
            player.play()
        }
    }

    companion object {
        const val MESSAGE = "Can't play file, skipped"
    }
}
