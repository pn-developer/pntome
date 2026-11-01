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
    private val sessionHolder: SyncSessionHolder,
    private val transferMonitor: ServerTransferMonitor,
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
                // Wrapped so every byte the watch actually pulls is reported to the transfer
                // monitor, giving real server-side progress/speed even without watch SYNC_STATUS.
                CountingInputStream(openStreamOrThrow(uri)) { offset ->
                    transferMonitor.reportTrackOffset(trackId, offset)
                }
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

/**
 * An [InputStream] decorator that reports cumulative bytes read through [onProgress] as a running
 * offset from the start of the underlying stream. Used to observe how much of a track the watch
 * has actually pulled over HTTP (see [ServerTransferMonitor]). Reporting is best-effort and must
 * never disturb the transfer, so [onProgress] exceptions are swallowed.
 */
private class CountingInputStream(
    private val delegate: InputStream,
    private val onProgress: (offset: Long) -> Unit,
) : InputStream() {

    private var count: Long = 0L

    override fun read(): Int {
        val b = delegate.read()
        if (b != -1) {
            count += 1
            report()
        }
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = delegate.read(b, off, len)
        if (n > 0) {
            count += n
            report()
        }
        return n
    }

    override fun skip(n: Long): Long {
        val skipped = delegate.skip(n)
        if (skipped > 0) {
            count += skipped
            report()
        }
        return skipped
    }

    override fun available(): Int = delegate.available()

    override fun close() = delegate.close()

    private fun report() {
        try {
            onProgress(count)
        } catch (_: Throwable) {
            // Monitoring must never break the actual download.
        }
    }
}
