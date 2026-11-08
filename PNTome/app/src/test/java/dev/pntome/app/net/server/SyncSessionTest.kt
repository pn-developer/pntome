package dev.pntome.app.net.server

import dev.pntome.app.net.garmin.messages.Manifest
import dev.pntome.app.net.garmin.messages.ManifestTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for the task 8.2 session abstraction ([SyncSession] / [SyncSessionHolder]) that
 * the HTTP routes read from. The full route behavior (status codes, Range/partial content) is
 * exercised against the Ktor test host in task 8.4; these cover the non-Ktor logic the routes
 * rely on so it is correct independently of the server harness.
 */
class SyncSessionTest {

    private fun track(id: String, mime: String = "audio/mpeg", size: Long = 1_000L) =
        ManifestTrack(
            id = id,
            folder = "Workout",
            title = "Title $id",
            artist = "Artist",
            album = "Album",
            durationMs = 1_000L,
            sizeBytes = size,
            mime = mime,
            path = "/media/$id"
        )

    @Test
    fun `from builds a TrackRef per manifest track carrying mime size and resolved uri`() {
        val manifest = Manifest(tracks = listOf(track("a", "audio/mpeg", 10), track("b", "audio/flac", 20)))

        val session = SyncSession.from(manifest) { id -> "content://docs/$id" }

        assertEquals(2, session.tracks.size)
        val refA = session.tracks.getValue("a")
        assertEquals("content://docs/a", refA.documentUri)
        assertEquals("audio/mpeg", refA.contentType)
        assertEquals(10L, refA.sizeBytes)
        val refB = session.tracks.getValue("b")
        assertEquals("content://docs/b", refB.documentUri)
        assertEquals("audio/flac", refB.contentType)
        assertEquals(20L, refB.sizeBytes)
        // The manifest is carried through verbatim for /manifest.
        assertEquals(manifest, session.manifest)
    }

    @Test
    fun `from with an empty manifest yields an empty track table`() {
        val session = SyncSession.from(Manifest(tracks = emptyList())) { "unused" }
        assertTrue(session.tracks.isEmpty())
    }

    @Test
    fun `holder starts empty, exposes the set session, and clears back to null`() {
        val holder = SyncSessionHolder()
        assertNull(holder.current)

        val session = SyncSession.from(Manifest(tracks = listOf(track("x")))) { "content://docs/x" }
        holder.set(session)
        assertEquals(session, holder.current)

        holder.clear()
        assertNull(holder.current)
    }
}
