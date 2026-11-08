package dev.pntome.app.net.server

import dev.pntome.app.log.PNTomeLog
import dev.pntome.app.log.Logger
import dev.pntome.app.net.garmin.messages.PNTomeJson
import dev.pntome.app.net.garmin.messages.Manifest
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.autohead.AutoHeadResponse
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.partialcontent.PartialContent
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import kotlinx.serialization.Serializable
import java.io.IOException

/** Logcat tag for HTTP data-channel diagnostics (filter: `adb logcat -s PNTome.HTTP`). */
private const val HTTP_TAG = "PNTome.HTTP"

/**
 * Installs the PNTome data-channel HTTP routes and their supporting plugins onto a Ktor
 * [Application] (task 8.2, design §5.5, Req 5.2, 5.4, 5.5, 10.1, 10.4).
 *
 * This is the routing "module" plugged into [PNTomeHttpServer] via the Hilt-multibound
 * `Set<Application.() -> Unit>` seam declared in `ServerModule`. The server stays agnostic of
 * the routes; everything endpoint-specific lives here and in [MediaSource].
 *
 * ## Endpoints
 * - `GET /health` → `200 {"ok":true}` — liveness probe, no session required.
 * - `GET /manifest` → `200` serialized [Manifest] from [MediaSource.manifest]; an inactive
 *   session serves an empty manifest (`{"tracks":[]}`), see [MediaSource].
 * - `GET /media/{trackId}` → streams the audio bytes with the track's `Content-Type`,
 *   `Accept-Ranges: bytes`, and `Range` support (Req 5.4); unknown/missing id → `404`
 *   (Req 5.5, 10.1).
 *
 * ## Plugins
 * - **ContentNegotiation** (kotlinx.serialization) using the shared [PNTomeJson] codec, so the
 *   `/manifest` and `/health` bodies serialize exactly like the control-channel types.
 * - **PartialContent** — turns a response carrying a known `Content-Length` into a
 *   `Range`-aware one: it emits `Accept-Ranges: bytes`, honors `Range`, and replies `206
 *   Partial Content` / `416` as appropriate (Req 5.4). We feed it an
 *   [OutgoingContent.ReadChannelContent] with the exact length so it can slice without us
 *   implementing range math.
 * - **AutoHeadResponse** — answers `HEAD` for the `GET` routes automatically, which lets a
 *   client probe size/headers (and `Accept-Ranges`) before downloading.
 *
 * ## Streaming (Req 10.4)
 * `/media` responds with a channel adapted from the [MediaSource]'s [java.io.InputStream] via
 * [toByteReadChannel], which pumps bytes in chunks — the file is never read fully into memory.
 *
 * ## Secret auth (task 8.3, Req 5.3)
 * An interceptor installed at the [ApplicationCallPipeline.Plugins] phase runs **before** route
 * resolution: for every path except the exempt `/health` it reads the `X-PNTome-Secret` header
 * and compares it (constant-time, [isSecretAuthorized]) against [SecretProvider.expectedSecret].
 * A missing or non-matching header short-circuits the pipeline with `401` via
 * [io.ktor.server.application.ApplicationCall.respond] + `finish()`, so the `/manifest` and
 * `/media/{trackId}` handlers only ever run for an authenticated request — the unknown-id and
 * missing-file `404` paths below are reached only after a valid secret. When no secret can be
 * determined (no active session and no pairing) the provider returns `null` and the request is
 * rejected with `401` (fail closed). The secret is never logged (Req 8.4).
 *
 * ### `/health` is unauthenticated (by design)
 * `/health` is deliberately exempt so the watch (and the user during setup) can liveness-check
 * LAN connectivity to the phone before any secret is in play — it reveals no music data, only
 * `{"ok":true}`. Every other route requires the secret.
 *
 * @param mediaSource the read-only view of the active sync session (manifest + openable tracks).
 * @param secretProvider supplies the current expected secret for the `X-PNTome-Secret` check.
 * @param logger sink for request diagnostics; defaults to [PNTomeLog] in production. The Ktor
 *   test host passes [dev.pntome.app.log.NoopLogger] so JVM tests don't touch `android.util.Log`.
 */
fun Application.installPNTomeRouting(
    mediaSource: MediaSource,
    secretProvider: SecretProvider,
    logger: Logger = PNTomeLog,
) {
    install(ContentNegotiation) {
        json(PNTomeJson)
    }
    install(PartialContent)
    install(AutoHeadResponse)

    // Request-level diagnostics (filter: `adb logcat -s PNTome.HTTP`). This is the single most
    // useful signal when a sync "hangs": it tells you definitively whether the WATCH ever reached
    // the phone's server over Wi-Fi, which endpoint it hit, and the status it got back —
    // independent of whether the watch sends SYNC_STATUS. One line is logged as each request
    // ENTERS (method + path + whether the secret header was present) and one as it RESOLVES
    // (status + any Range). The secret VALUE is never logged, only its presence (Req 8.4).
    intercept(ApplicationCallPipeline.Setup) {
        val method = call.request.httpMethod.value
        val path = call.request.path()
        val hasSecret = !call.request.headers[SECRET_HEADER].isNullOrBlank()
        val range = call.request.headers[HttpHeaders.Range]
        logger.i(
            HTTP_TAG,
            "--> $method $path secret=${if (hasSecret) "present" else "absent"}" +
                (range?.let { " range=$it" } ?: "")
        )
        try {
            proceed()
        } finally {
            val status = call.response.status()?.value?.toString() ?: "no-response"
            logger.i(HTTP_TAG, "<-- $method $path -> $status")
        }
    }

    // Secret-auth gate (Req 5.3). Runs before routing so unauthenticated requests to any route
    // other than /health never reach a handler. /health stays open for liveness checks.
    intercept(ApplicationCallPipeline.Plugins) {
        if (call.request.path() == "/health") return@intercept

        val presented = call.request.headers[SECRET_HEADER]
        val expected = secretProvider.expectedSecret()
        if (!isSecretAuthorized(presented = presented, expected = expected)) {
            // Most common "sync hangs" cause after the watch connects: a stale/missing secret.
            // Log the cause (not the value) so it's obvious in logcat (Req 8.4).
            logger.w(
                HTTP_TAG,
                "401 ${call.request.path()}: secret ${if (presented.isNullOrBlank()) "absent" else "mismatch"}" +
                    (if (expected.isNullOrBlank()) " (no active session secret)" else "")
            )
            call.respond(HttpStatusCode.Unauthorized)
            finish()
        }
    }

    routing {
        get("/health") {
            call.respond(HealthResponse(ok = true))
        }

        get("/manifest") {
            // No active session → empty manifest (200), a valid "nothing to sync" answer.
            val manifest = mediaSource.manifest() ?: Manifest(tracks = emptyList())
            logger.i(HTTP_TAG, "/manifest served: ${manifest.tracks.size} track(s)")
            call.respond(manifest)
        }

        get("/media/{trackId}") {
            val trackId = call.parameters["trackId"]

            // Validate/sanitize the untrusted id before any lookup (design §8). A syntactically
            // invalid id — empty, over-long, or carrying a path separator/traversal/whitespace —
            // can never be a legitimate opaque hash, so reject it as 404 up front without
            // touching the manifest or storage. The lookup below is itself traversal-safe (the id
            // is only a map key), but this makes the no-traversal guarantee explicit (Req 5.5).
            if (trackId == null || !TrackId.isValidTrackId(trackId)) {
                logger.w(HTTP_TAG, "/media 404: invalid trackId")
                call.respond(HttpStatusCode.NotFound)
                return@get
            }

            // Unknown id (not in current manifest) → 404 (Req 5.5).
            val media = mediaSource.openTrack(trackId) ?: run {
                logger.w(HTTP_TAG, "/media/$trackId 404: not in active session")
                call.respond(HttpStatusCode.NotFound)
                return@get
            }

            // Open the stream now; a file that vanished mid-sync → 404 and continue (Req 10.1).
            val stream = try {
                media.open()
            } catch (_: IOException) {
                logger.w(HTTP_TAG, "/media/$trackId 404: media no longer accessible")
                call.respond(HttpStatusCode.NotFound)
                return@get
            }

            logger.i(HTTP_TAG, "/media/$trackId streaming ${media.length} bytes (${media.contentType})")

            // Explicitly advertise range support (PartialContent also sets this, but we assert
            // it for Req 5.4 regardless of plugin internals).
            call.response.header(HttpHeaders.AcceptRanges, "bytes")

            // Stream via a ReadChannelContent with a known length so PartialContent can satisfy
            // Range requests. The channel pumps the InputStream in chunks (Req 10.4: never buffer
            // the whole file). The channel adapter closes the underlying stream when drained.
            call.respond(
                object : OutgoingContent.ReadChannelContent() {
                    override val contentType: ContentType =
                        ContentType.parse(media.contentType)
                    override val contentLength: Long = media.length
                    override fun readFrom(): ByteReadChannel = stream.toByteReadChannel()
                }
            )
        }
    }
}

/**
 * Body of `GET /health`: `{"ok":true}` (overview §5.2). A tiny serializable type so the response
 * goes through the same kotlinx.serialization path as the rest of the API.
 */
@Serializable
private data class HealthResponse(val ok: Boolean)
