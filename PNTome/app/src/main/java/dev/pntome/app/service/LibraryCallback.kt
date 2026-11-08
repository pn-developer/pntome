package dev.gsound.a.service

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * `MediaLibrarySession.Callback` for Android Auto browsing (gsound-player design §3, task 6.2).
 *
 * Browse requests are answered by [tree] on [scope]. Extension points for later tasks:
 * `onSetMediaItems` (6.3), `onConnect` custom layout (6.4), `onSearch`/`onGetSearchResult` (6.5).
 *
 * @param onDisconnect called when a controller disconnects (final bookmark flush).
 */
class LibraryCallback(
    private val tree: BrowseTree,
    private val scope: CoroutineScope,
    private val resolver: MediaItemResolver? = null,
    private val onDisconnect: () -> Unit = {},
) : MediaLibrarySession.Callback {

    /**
     * The single place that decides who may browse. First version: any caller (personal use,
     * design §3). Tighten here (e.g. package allow-list for Android Auto / Assistant).
     */
    internal fun isCallerAllowed(controller: MediaSession.ControllerInfo): Boolean = true

    override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): MediaSession.ConnectionResult {
        val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
            .apply { BookCustomLayout.commands.forEach { add(it) } }
            .build()
        return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
            .setAvailableSessionCommands(commands)
            .setCustomLayout(BookCustomLayout.layoutFor(session.player.currentMediaItem))
            .build()
    }

    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle,
    ): ListenableFuture<SessionResult> =
        Futures.immediateFuture(
            BookCustomLayout.execute(customCommand, session.player)
                ?: SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED)
        )

    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> =
        if (isCallerAllowed(browser)) {
            immediate(LibraryResult.ofItem(tree.root(), params))
        } else {
            immediate(LibraryResult.ofError<MediaItem>(LibraryResult.RESULT_ERROR_PERMISSION_DENIED))
        }

    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
        async { tree.getChildren(parentId, page, pageSize) }

    override fun onGetItem(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> =
        async { tree.getItem(mediaId) }

    override fun onSetMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        val r = resolver ?: return Futures.immediateFuture(
            MediaSession.MediaItemsWithStartPosition(mediaItems, startIndex, startPositionMs)
        )
        return futureOf {
            r.setMediaItems(mediaItems, startIndex, startPositionMs)
                ?: throw IllegalArgumentException("No playable items for ${mediaItems.map { it.mediaId }}")
        }
    }

    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
    ): ListenableFuture<MutableList<MediaItem>> {
        val r = resolver ?: return Futures.immediateFuture(mediaItems)
        return futureOf { r.addMediaItems(mediaItems).toMutableList() }
    }

    /** Runs [block] on [scope]; any failure (incl. unknown ids) fails the future, never crashes. */
    private fun <T : Any> futureOf(block: suspend () -> T): ListenableFuture<T> {
        val future = SettableFuture.create<T>()
        scope.launch {
            try {
                future.set(block())
            } catch (e: CancellationException) {
                future.cancel(false)
                throw e
            } catch (e: Exception) {
                future.setException(e)
            }
        }
        return future
    }

    override fun onSearch(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<Void>> {
        val future = SettableFuture.create<LibraryResult<Void>>()
        scope.launch {
            try {
                val count = tree.search(query)
                future.set(LibraryResult.ofVoid())
                session.notifySearchResultChanged(browser, query, count, params)
            } catch (e: CancellationException) {
                future.cancel(false)
                throw e
            } catch (e: Exception) {
                future.set(LibraryResult.ofError<Void>(LibraryResult.RESULT_ERROR_UNKNOWN))
            }
        }
        return future
    }

    override fun onGetSearchResult(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
        async { tree.searchResults(query, page, pageSize) }

    override fun onDisconnected(session: MediaSession, controller: MediaSession.ControllerInfo) {
        onDisconnect()
    }

    private fun <T : Any> async(block: suspend () -> LibraryResult<T>): ListenableFuture<LibraryResult<T>> {
        val future = SettableFuture.create<LibraryResult<T>>()
        scope.launch {
            try {
                future.set(block())
            } catch (e: Exception) {
                future.set(LibraryResult.ofError<T>(LibraryResult.RESULT_ERROR_UNKNOWN))
            }
        }
        return future
    }

    private fun <T : Any> immediate(r: LibraryResult<T>): ListenableFuture<LibraryResult<T>> =
        SettableFuture.create<LibraryResult<T>>().also { it.set(r) }
}
