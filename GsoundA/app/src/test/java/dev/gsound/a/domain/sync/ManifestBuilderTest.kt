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
    fun `maps every track in natural title order when file names are unknown`() {
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

    // --- spec gsound-audiobooks: ordering, seq, book ------------------------------------------

    private fun file(
        id: String,
        fileName: String,
        relativeFolder: String = "Book",
        folderId: String = "folder-1",
        title: String = "ID3 $id"
    ) = track(id, relativeFolder = relativeFolder, title = title)
        .copy(fileName = fileName, folderId = folderId)

    @Test
    fun `orders each folder naturally by file name and numbers seq from 0`() {
        val manifest = ManifestBuilder.build(
            listOf(
                file("c10", "10 Ten.mp3"),
                file("c2", "2 Two.mp3"),
                file("c1", "01 One.mp3")
            )
        )
        assertEquals(listOf("c1", "c2", "c10"), manifest.tracks.map { it.id })
        assertEquals(listOf(0, 1, 2), manifest.tracks.map { it.seq })
    }

    @Test
    fun `multi-part prefixes order by disc then track`() {
        val manifest = ManifestBuilder.build(
            listOf(
                file("d2t1", "2-01.mp3"),
                file("d1t10", "1-10.mp3"),
                file("d1t2", "1-02.mp3")
            )
        )
        assertEquals(listOf("d1t2", "d1t10", "d2t1"), manifest.tracks.map { it.id })
    }

    @Test
    fun `groups keep first-seen order and seq restarts per folder`() {
        val manifest = ManifestBuilder.build(
            listOf(
                file("r2", "2.mp3", relativeFolder = "Rock"),
                file("j1", "1.mp3", relativeFolder = "Jazz"),
                file("r1", "1.mp3", relativeFolder = "Rock")
            )
        )
        assertEquals(listOf("r1", "r2", "j1"), manifest.tracks.map { it.id })
        assertEquals(listOf(0, 1, 0), manifest.tracks.map { it.seq })
    }

    @Test
    fun `audiobook folders are flagged and use file-name chapter titles`() {
        val manifest = ManifestBuilder.build(
            listOf(
                file("b1", "01 - The Beginning.mp3", folderId = "book"),
                file("m1", "01 Song.mp3", relativeFolder = "Music", folderId = "music")
            ),
            audiobookFolderIds = setOf("book")
        )
        val book = manifest.tracks.first { it.id == "b1" }
        val music = manifest.tracks.first { it.id == "m1" }
        assertEquals(true, book.book)
        assertEquals("01 - The Beginning", book.title)   // not the ID3 title
        assertEquals(false, music.book)
        assertEquals("ID3 m1", music.title)              // music keeps its tag title
    }

    @Test
    fun `audiobook track without a recorded file name keeps its title`() {
        val mt = ManifestBuilder.build(
            listOf(file("b1", "", folderId = "book", title = "Fallback")),
            audiobookFolderIds = setOf("book")
        ).tracks.single()
        assertEquals("Fallback", mt.title)
        assertEquals(true, mt.book)
    }

    @Test
    fun `manifest carries the owning folder id as fid even for nested sub-folders`() {
        val mt = ManifestBuilder.build(
            listOf(file("b1", "01.mp3", relativeFolder = "Book/CD1", folderId = "book"))
        ).tracks.single()
        assertEquals("book", mt.fid)
        assertEquals("Book/CD1", mt.folder)   // `folder` is a sub-folder path, not the display name
    }
}
