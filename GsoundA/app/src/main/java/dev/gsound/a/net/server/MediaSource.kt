package dev.gsound.a.net.server

import dev.gsound.a.net.garmin.messages.Manifest
import java.io.InputStream

/**
 * Read-only view the HTTP routes (task 8.2) use to answer `/manifest` and `/media/{trackId}`
 * without knowing anything about SAF, the Android [android.content.ContentResolver], or the
 * sync orchestration that produces the data (design §5.5, Req 5.2, 5.4, 5.5, 10.4).
 *
 * ## Why an interface
 * The routes must be testable with a fake (task 8.4 drives the Ktor test host against an
 * in-memory implementation), and the real audio bytes come from SAF document URIs opened via
 * the Android content resolver. Hiding both behind this one small interface keeps the Android
 * coupling out of the route code and lets the whole routing module be exercised on the JVM.
 *
 * ## Streaming contract (Req 10.4)
 * [openTrack] returns an [OpenMedia] that exposes the content type, the exact byte length, and a
 * **factory** that opens a fresh [InputStream] on demand. It deliberately does **not** hand back
 * a byte array: the route copies from the stream to the response in fixed-size chunks so a large
 * file is never loaded into memory. The length lets Ktor's `PartialContent` plugin satisfy
 * `Range` requests (Req 5.4) and set `Content-Length`.
 *
 * ## No active session
 * When no sync session is active (before a sync starts or after it stops), [manifest] returns
 * `null` and [openTrack] returns `null` for every id. The routes translate those into an empty
 * manifest / `404` respectively (see [dev.gsound.a.net.server.GsoundRouting]).
 */
interface MediaSource {

    /**
     * The manifest for the current sync session, or `null` when no session is active.
     *
     * The route serves `null` as an empty [Manifest] (`{"tracks":[]}`) with `200` rather than an
     * error: `/manifest` is a safe, idempotent read and an empty list is a valid "nothing to
     * sync yet" answer, which is friendlier to a polling watch than a `404`.
     */
    fun manifest(): Manifest?

    /**
     * Open the audio for [trackId], or `null` when the id is not in the current manifest (Req
     * 5.5 → `404`) or the backing file has gone missing mid-sync (Req 10.1 → `404`, continue
     * serving others).
     *
     * The returned [OpenMedia] has not opened any stream yet; the route decides when (and how
     * many times, e.g. for a `Range` retry) to call [OpenMedia.open].
     *
     * @param trackId the opaque track id from the `/media/{trackId}` path (already a stable hash;
     *   callers should still treat it as untrusted — the implementation looks it up in the
     *   manifest rather than mapping it to a filesystem path, so path traversal is impossible).
     *   The route additionally rejects syntactically invalid ids via
     *   [dev.gsound.a.net.server.TrackId.isValidTrackId] before calling this (design §8), so a
     *   traversal-looking id never reaches here.
     */
    fun openTrack(trackId: String): OpenMedia?
}

/**
 * A resolved, openable track: everything the HTTP route needs to stream the bytes with correct
 * headers, without buffering the whole file (Req 5.4, 10.4).
 *
 * @property contentType the MIME type for the `Content-Type` header (e.g. `audio/mpeg`), taken
 *   from the manifest track's `mime`.
 * @property length the exact size in bytes. Needed so `PartialContent` can compute `Range`
 *   slices and `Content-Length`; it comes from the manifest's `sizeBytes`.
 * @property open opens a **fresh** [InputStream] positioned at the start of the file. It may be
 *   called more than once (a new stream per response/attempt); each returned stream is owned and
 *   closed by the caller. Implementations throw [java.io.IOException] if the file is no longer
 *   available, which the route maps to `404` (Req 10.1).
 */
class OpenMedia(
    val contentType: String,
    val length: Long,
    val open: () -> InputStream
)
