package dev.pntome.app.ui.player

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import dev.pntome.app.service.PlaybackService
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Seam between [PlayerViewModel] and the playback service (gsound-player design §2): hands out a
 * [Player] bound to the service's session. JVM tests substitute a fake; production uses
 * [MediaControllerConnector].
 */
interface PlayerConnector {
    /** Binds to the service (starting it if needed) and returns the connected player. */
    suspend fun connect(): Player

    /** Releases a player previously returned by [connect]. */
    fun release(player: Player)
}

/** Production [PlayerConnector]: a Media3 [MediaController] bound to [PlaybackService]. */
class MediaControllerConnector(private val context: Context) : PlayerConnector {

    override suspend fun connect(): Player {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        return suspendCancellableCoroutine { cont ->
            future.addListener(
                {
                    try {
                        cont.resume(future.get())
                    } catch (e: Exception) {
                        cont.resumeWithException(e)
                    }
                },
                Runnable::run,
            )
            cont.invokeOnCancellation { MediaController.releaseFuture(future) }
        }
    }

    override fun release(player: Player) {
        (player as? MediaController)?.release()
    }
}
