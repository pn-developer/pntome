package dev.pntome.app.net.server

import dev.pntome.app.log.NoopLogger
import dev.pntome.app.net.garmin.messages.PNTomeJson
import dev.pntome.app.net.garmin.messages.Manifest
import dev.pntome.app.net.garmin.messages.ManifestTrack
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Ktor test-host coverage for [installPNTomeRouting] (task 8.4).
 *
 * These exercise the full routing module — the secret-auth interceptor, ContentNegotiation,
 * PartialContent, AutoHeadResponse, and the three endpoints — over the in-memory Ktor test host
 * driven by fakes, so no Android `Context`, SAF, or running sync is involved. Covers:
 *
 * - `/health` is unauthenticated (Req 5.2).
 * - Missing/wrong/`null`-expected secret → `401` on protected routes; correct secret proceeds
 *   (Req 5.3, fail-closed).
 * - `/manifest` serializes the [Manifest] via [PNTomeJson]; empty when the source has none
 *   (Req 5.2).
 * - `/media` full download: `Content-Type`, `Accept-Ranges: bytes`, `Content-Length`, exact bytes
 *   (Req 5.4).
 * - `/media` `Range` → `206` + correct `Content-Range` and sliced body; unsatisfiable → `416`
 *   (Req 5.4).
 * - `/media` unknown id and a mid-sync-missing file (`open()` throws) → `404` (Req 5.5, 10.1).
 * - `HEAD /media` via AutoHeadResponse returns headers without a body (Req 5.4, 10.4).
 *
 * The pure comparison rule itself is pinned separately in `SecretAuthTest`.
 */
class PNTomeRoutingTest {

    private companion object {
        const val SECRET = "s3cr3t-value"
        const val TRACK_ID = "track-abc"
        const val MIME = "audio/mpeg"

        // Deterministic non-trivial payload so range slices are easy to assert.
        val AUDIO_BYTES: ByteArray = ByteArray(1024) { (it % 251).toByte() }

        val MANIFEST = Manifest(
            tracks = listOf(
                ManifestTrack(
                    id = TRACK_ID,
                    folder = "Workout",
                    title = "Song Title",
                    artist = "Artist",
                    album = "Album",
                    durationMs = 215_000,
                    sizeBytes = AUDIO_BYTES.size.toLong(),
                    mime = MIME,
                    path = "/media/$TRACK_ID"
                )
            )
        )
    }

    /** A [SecretProvider] returning a fixed [secret] (or `null` to force fail-closed). */
    private class FakeSecretProvider(private val secret: String?) : SecretProvider {
        override suspend fun expectedSecret(): String? = secret
    }

    /**
     * An in-memory [MediaSource]. [manifest] is returned verbatim (may be `null`). [openTrack]
     * resolves [TRACK_ID] to the in-memory [AUDIO_BYTES]; when [failOpen] is set the returned
     * [OpenMedia.open] throws [IOException] to simulate a file that vanished mid-sync (Req 10.1).
     */
    private class FakeMediaSource(
        private val manifest: Manifest?,
        private val bytes: ByteArray? = AUDIO_BYTES,
        private val failOpen: Boolean = false,
    ) : MediaSource {
        override fun manifest(): Manifest? = manifest

        override fun openTrack(trackId: String): OpenMedia? {
            if (trackId != TRACK_ID || bytes == null) return null
            return OpenMedia(
                contentType = MIME,
                length = bytes.size.toLong(),
                open = {
                    if (failOpen) throw IOException("file gone")
                    ByteArrayInputStream(bytes) as InputStream
                }
            )
        }
    }

    // ---- /health (unauthenticated) ------------------------------------------------------------

    @Test
    fun `health returns 200 and ok body without any secret header`() = runTest {
        testApplication {
            application {
                installPNTomeRouting(
                    mediaSource = FakeMediaSource(MANIFEST),
                    secretProvider = FakeSecretProvider(SECRET),
                    logger = NoopLogger,
                )
            }

            val response = client.get("/health")

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("""{"ok":true}""", response.bodyAsText())
        }
    }

    // ---- Secret auth (Req 5.3) ----------------------------------------------------------------

    @Test
    fun `manifest without secret header is 401`() = runTest {
        testApplication {
            application {
                installPNTomeRouting(FakeMediaSource(MANIFEST), FakeSecretProvider(SECRET), NoopLogger)
            }

            assertEquals(HttpStatusCode.Unauthorized, client.get("/manifest").status)
        }
    }

    @Test
    fun `media without secret header is 401`() = runTest {
        testApplication {
            application {
                installPNTomeRouting(FakeMediaSource(MANIFEST), FakeSecretProvider(SECRET), NoopLogger)
            }

            assertEquals(HttpStatusCode.Unauthorized, client.get("/media/$TRACK_ID").status)
        }
    }

    @Test
    fun `wrong secret is 401`() = runTest {
        testApplication {
            application {
                installPNTomeRouting(FakeMediaSource(MANIFEST), FakeSecretProvider(SECRET), NoopLogger)
            }

            val response = client.get("/manifest") {
                header(SECRET_HEADER, "not-the-secret")
            }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }
    }

    @Test
    fun `null expected secret rejects even a presented header (fail closed)`() = runTest {
        testApplication {
            application {
                installPNTomeRouting(FakeMediaSource(MANIFEST), FakeSecretProvider(secret = null), NoopLogger)
            }

            val response = client.get("/manifest") {
                header(SECRET_HEADER, "anything")
            }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }
    }

    @Test
    fun `correct secret proceeds past the auth gate`() = runTest {
        testApplication {
            application {
                installPNTomeRouting(FakeMediaSource(MANIFEST), FakeSecretProvider(SECRET), NoopLogger)
            }

            val response = client.get("/manifest") {
                header(SECRET_HEADER, SECRET)
            }

            assertEquals(HttpStatusCode.OK, response.status)
        }
    }

    // ---- /manifest (Req 5.2) ------------------------------------------------------------------

    @Test
    fun `manifest returns the serialized manifest for an authenticated request`() = runTest {
        testApplication {
            application {
                installPNTomeRouting(FakeMediaSource(MANIFEST), FakeSecretProvider(SECRET), NoopLogger)
            }

            val response = client.get("/manifest") { header(SECRET_HEADER, SECRET) }

            assertEquals(HttpStatusCode.OK, response.status)
            val decoded = PNTomeJson.decodeFromString(Manifest.serializer(), response.bodyAsText())
            assertEquals(MANIFEST, decoded)
        }
    }

    @Test
    fun `manifest is empty when the source has no active session`() = runTest {
        testApplication {
            application {
                installPNTomeRouting(FakeMediaSource(manifest = null), FakeSecretProvider(SECRET), NoopLogger)
            }

            val response = client.get("/manifest") { header(SECRET_HEADER, SECRET) }

            assertEquals(HttpStatusCode.OK, response.status)
            val decoded = PNTomeJson.decodeFromString(Manifest.serializer(), response.bodyAsText())
            assertEquals(Manifest(tracks = emptyList()), decoded)
        }
    }

    // ---- /media full download (Req 5.4, 10.4) -------------------------------------------------

    @Test
    fun `media full download returns the bytes with content type and accept-ranges`() = runTest {
        testApplication {
            application {
                installPNTomeRouting(FakeMediaSource(MANIFEST), FakeSecretProvider(SECRET), NoopLogger)
            }

            val response = client.get("/media/$TRACK_ID") { header(SECRET_HEADER, SECRET) }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(MIME, response.headers[HttpHeaders.ContentType])
            assertEquals("bytes", response.headers[HttpHeaders.AcceptRanges])
            assertEquals(
                AUDIO_BYTES.size.toString(),
                response.headers[HttpHeaders.ContentLength]
            )
            assertArrayEquals(AUDIO_BYTES, response.readBytes())
        }
    }

    // ---- /media Range / partial content (Req 5.4) ---------------------------------------------

    @Test
    fun `media range request returns 206 with content-range and the sliced body`() = runTest {
        testApplication {
            application {
                installPNTomeRouting(FakeMediaSource(MANIFEST), FakeSecretProvider(SECRET), NoopLogger)
            }

            val start = 100
            val end = 199
            val response = client.get("/media/$TRACK_ID") {
                header(SECRET_HEADER, SECRET)
                header(HttpHeaders.Range, "bytes=$start-$end")
            }

            assertEquals(HttpStatusCode.PartialContent, response.status)
            assertEquals(
                "bytes $start-$end/${AUDIO_BYTES.size}",
                response.headers[HttpHeaders.ContentRange]
            )
            assertArrayEquals(
                AUDIO_BYTES.copyOfRange(start, end + 1),
                response.readBytes()
            )
        }
    }

    @Test
    fun `media unsatisfiable range returns 416`() = runTest {
        testApplication {
            application {
                installPNTomeRouting(FakeMediaSource(MANIFEST), FakeSecretProvider(SECRET), NoopLogger)
            }

            // Range entirely beyond the file length is not satisfiable.
            val response = client.get("/media/$TRACK_ID") {
                header(SECRET_HEADER, SECRET)
                header(HttpHeaders.Range, "bytes=100000-100100")
            }

            assertEquals(HttpStatusCode.RequestedRangeNotSatisfiable, response.status)
        }
    }

    // ---- /media 404 (Req 5.5, 10.1) -----------------------------------------------------------

    @Test
    fun `media unknown id returns 404`() = runTest {
        testApplication {
            application {
                installPNTomeRouting(FakeMediaSource(MANIFEST), FakeSecretProvider(SECRET), NoopLogger)
            }

            val response = client.get("/media/does-not-exist") { header(SECRET_HEADER, SECRET) }

            assertEquals(HttpStatusCode.NotFound, response.status)
        }
    }

    @Test
    fun `media rejects a traversal-looking id with 404 without hitting the source`() = runTest {
        testApplication {
            application {
                installPNTomeRouting(FakeMediaSource(MANIFEST), FakeSecretProvider(SECRET), NoopLogger)
            }

            // Encoded so Ktor decodes it into a single "../../etc/passwd" path segment, which
            // TrackId.isValidTrackId rejects (design §8) → 404 before any manifest lookup.
            val response = client.get("/media/..%2F..%2Fetc%2Fpasswd") {
                header(SECRET_HEADER, SECRET)
            }

            assertEquals(HttpStatusCode.NotFound, response.status)
        }
    }

    @Test
    fun `media returns 404 when the backing file vanished mid-sync`() = runTest {
        testApplication {
            application {
                installPNTomeRouting(
                    mediaSource = FakeMediaSource(MANIFEST, failOpen = true),
                    secretProvider = FakeSecretProvider(SECRET),
                    logger = NoopLogger,
                )
            }

            val response = client.get("/media/$TRACK_ID") { header(SECRET_HEADER, SECRET) }

            assertEquals(HttpStatusCode.NotFound, response.status)
        }
    }

    // ---- HEAD /media via AutoHeadResponse (Req 5.4, 10.4) -------------------------------------

    @Test
    fun `head media returns size and accept-ranges without a body`() = runTest {
        testApplication {
            application {
                installPNTomeRouting(FakeMediaSource(MANIFEST), FakeSecretProvider(SECRET), NoopLogger)
            }

            val response = client.head("/media/$TRACK_ID") { header(SECRET_HEADER, SECRET) }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("bytes", response.headers[HttpHeaders.AcceptRanges])
            assertEquals(
                AUDIO_BYTES.size.toString(),
                response.headers[HttpHeaders.ContentLength]
            )
            // AutoHeadResponse strips the body for HEAD.
            assertTrue(response.readBytes().isEmpty())
        }
    }
}
