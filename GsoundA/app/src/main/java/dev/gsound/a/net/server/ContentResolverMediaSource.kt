package dev.gsound.a.net.server

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.gsound.a.net.garmin.messages.Manifest
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android [MediaSource] that serves the active [SyncSession] (from [SyncSessionHolder]) and
 * opens track bytes from SAF document URIs via the [ContentResolver] (design §5.5, Req 5.2,
 * 5.4, 5.5, 10.1, 10.4).
 *
 * ## Where the data comes from
 * [manifest] and [openTrack] both read the *current* session from [sessionHolder] on each call,
 * so the server always reflects the sync that is in progress (and serves nothing once the
 * session is cleared on sync completion, Req 5.6).
 *
 * ## Streaming, not buffering (Req 10.4)
 * [openTrack] returns an [OpenMedia] whose `open` lambda calls
 * [ContentResolver.openInputStream] on demand. The resolver returns a stream backed by the
 * document's file descriptor; the route copies it to the response in fixed-size chunks, so the
 * audio bytes are never materialized in memory. The length comes from the session's
 * `sizeBytes` (recorded at scan time) rather than re-probing the file, avoiding a second open.
 *
 * ## Missing files (Req 10.1)
 * If the id is not in the current session the method returns `null` (→ `404`, Req 5.5). If the
 * id is known but the resolver cannot open the document (file moved/deleted mid-sync), the
 * `open` lambda throws [IOException], which the route catches and maps to `404` while continuing
 * to serve other tracks.
 *
 * ## Security
 * The incoming `trackId` is only ever used as a map key into the session's track table — it is
 * never concatenated into a path — so a hostile/traversal-looking id simply misses and yields a
 * `404` (design §8).
 */
@Singleton
class ContentResolverMediaSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessionHolder: SyncSessionHolder
) : MediaSource {

    private val contentResolver: ContentResolver get() = context.contentResolver

    override fun manifest(): Manifest? = sessionHolder.current?.manifest

    override fun openTrack(trackId: String): OpenMedia? {
        val ref = sessionHolder.current?.tracks?.get(trackId) ?: return null
        val uri = Uri.parse(ref.documentUri)
        return OpenMedia(
            contentType = ref.contentType,
            length = ref.sizeBytes,
            open = {
                // Opened lazily and freshly per call so PartialContent can re-open for a Range
                // retry; the caller owns and closes the returned stream. A null/opening failure
                // means the file vanished mid-sync (Req 10.1) → surfaced as IOException → 404.
                openStreamOrThrow(uri)
            }
        )
    }

    private fun openStreamOrThrow(uri: Uri): InputStream =
        try {
            contentResolver.openInputStream(uri)
                ?: throw IOException("Could not open media stream")
        } catch (e: SecurityException) {
            // Lost/never-granted URI permission is, from the server's view, an unavailable file.
            throw IOException("Media no longer accessible", e)
        }
}
