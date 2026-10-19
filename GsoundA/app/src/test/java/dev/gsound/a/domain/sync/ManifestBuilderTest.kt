package dev.gsound.a.domain.sync

import dev.gsound.a.domain.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for [ManifestBuilder] (task 9.1) — the Track→ManifestTrack mapping used to
 * build the sync manifest (overview §5.1/§5.2, design §5.6). No Android, server, or control
 * channel involved; the orchestrator's wiring is covered separately (task 9.4).
 */
class ManifestBuilderTest {

    private fun track(
        id: String,
        relativeFolder: String = "Workout",
        title: String = "Title $id",
        artist: String? = "Artist",
        album: String? = "Album",
        durationMs: Long = 215_000L,
        sizeBytes: Long = 5_123_456L,
        mime: String = "audio/mpeg"
    ) = Track(
        id = id,
        folderId = "folder-1",
        documentUri = "content://docs/$id",
        relativeFolder = relativeFolder,
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        sizeBytes = sizeBytes,
        mime = mime
    )

    @Test
    fun `maps every field and derives the media path from the track id`() {
        val manifest = ManifestBuilder.build(listOf(track("abc123")))

        assertEquals(1, manifest.tracks.size)
        val mt = manifest.tracks.single()
        assertEquals("abc123", mt.id)
        assertEquals("Workout", mt.folder)      // relativeFolder → folder
        assertEquals("Title abc123", mt.title)
        assertEquals("Artist", mt.artist)
        assertEquals("Album", mt.album)
        assertEquals(215_000L, mt.durationMs)
        assertEquals(5_123_456L, mt.sizeBytes)
        assertEquals("audio/mpeg", mt.mime)
        assertEquals("/media/abc123", mt.path)   // path = "/media/{id}"
    }

    @Test
    fun `path prefix matches the published constant`() {
        val mt = ManifestBuilder.build(listOf(track("xyz"))).tracks.single()
        assertEquals("${ManifestBuilder.MEDIA_PATH_PREFIX}xyz", mt.path)
    }

    @Test
    fun `preserves null artist and album`() {
        val mt = ManifestBuilder
            .build(listOf(track("n", artist = null, album = null)))
            .tracks.single()

        assertEquals(null, mt.artist)
        assertEquals(null, mt.album)
    }

    @Test
    fun `empty track list yields an empty manifest`() {
        val manifest = ManifestBuilder.build(emptyList())
        assertTrue(manifest.tracks.isEmpty())
    }

    @Test
    fun `preserves input order and maps every track`() {
        val manifest = ManifestBuilder.build(listOf(track("a"), track("b"), track("c")))

        assertEquals(listOf("a", "b", "c"), manifest.tracks.map { it.id })
        assertEquals(
            listOf("/media/a", "/media/b", "/media/c"),
            manifest.tracks.map { it.path }
        )
    }

    @Test
    fun `distinct folders are carried through per track`() {
        val manifest = ManifestBuilder.build(
            listOf(track("a", relativeFolder = "Rock"), track("b", relativeFolder = "Jazz"))
        )

        assertEquals("Rock", manifest.tracks[0].folder)
        assertEquals("Jazz", manifest.tracks[1].folder)
    }
}
