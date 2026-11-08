package dev.gsound.a.net.server

import dev.gsound.a.net.garmin.messages.Manifest
import dev.gsound.a.net.garmin.messages.ManifestTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JVM tests for [ServerTransferMonitor]: the server-side, byte-activity progress source that lets
 * the UI show real download progress/speed even when the watch never sends `SYNC_STATUS`.
 *
 * Speed is wall-clock based and deliberately not asserted here (it needs ≥1s to sample); these
 * tests cover the deterministic parts: totals, Range-safe (monotonic, max-offset) byte counting,
 * per-track completion, current-track tracking, and lifecycle reset.
 */
class ServerTransferMonitorTest {

    private val monitor = ServerTransferMonitor()

    private fun session(vararg tracks: Pair<String, Long>): SyncSession {
        val manifestTracks = tracks.map { (id, size) ->
            ManifestTrack(
                id = id,
                folder = "Rock",
                title = "Title $id",
                durationMs = 0L,
                sizeBytes = size,
                mime = "audio/mpeg",
                path = "/media/$id",
            )
        }
        val manifest = Manifest(tracks = manifestTracks)
        return SyncSession.from(manifest = manifest, documentUriFor = { "content://docs/$it" })
    }

    @Test
    fun `begin seeds totals and zeroed progress`() {
        monitor.begin(session("a" to 1_000L, "b" to 3_000L))

        val p = monitor.progress.value!!
        assertEquals(2, p.tracksTotal)
        assertEquals(0, p.tracksComplete)
        assertEquals(0L, p.bytesServed)
        assertEquals(4_000L, p.bytesTotal)
        assertNull(p.currentTrackTitle)
    }

    @Test
    fun `reporting offsets aggregates bytes and tracks the current title`() {
        monitor.begin(session("a" to 1_000L, "b" to 3_000L))

        monitor.reportTrackOffset("a", 400L)
        monitor.reportTrackOffset("b", 1_000L)

        val p = monitor.progress.value!!
        assertEquals(1_400L, p.bytesServed)
        assertEquals(0, p.tracksComplete)
        assertEquals("Title b", p.currentTrackTitle) // most recently reported
    }

    @Test
    fun `offset is monotonic (max) so a ranged re-read does not overcount`() {
        monitor.begin(session("a" to 1_000L))

        monitor.reportTrackOffset("a", 800L)
        // A later ranged/retried GET reports a smaller running offset — must not lower the total.
        monitor.reportTrackOffset("a", 200L)

        assertEquals(800L, monitor.progress.value!!.bytesServed)
    }

    @Test
    fun `a track counts complete once its offset reaches its size`() {
        monitor.begin(session("a" to 1_000L, "b" to 2_000L))

        monitor.reportTrackOffset("a", 1_000L) // exactly complete
        monitor.reportTrackOffset("b", 2_500L) // over-read (e.g. clamped) still counts complete

        val p = monitor.progress.value!!
        assertEquals(2, p.tracksComplete)
        assertEquals(3_000L, p.bytesServed)
    }

    @Test
    fun `offsets for ids outside the session are ignored`() {
        monitor.begin(session("a" to 1_000L))

        monitor.reportTrackOffset("ghost", 5_000L)

        val p = monitor.progress.value!!
        assertEquals(0L, p.bytesServed)
        assertNull(p.currentTrackTitle)
    }

    @Test
    fun `reset clears progress back to null`() {
        monitor.begin(session("a" to 1_000L))
        monitor.reportTrackOffset("a", 500L)

        monitor.reset()

        assertNull(monitor.progress.value)
    }
}
