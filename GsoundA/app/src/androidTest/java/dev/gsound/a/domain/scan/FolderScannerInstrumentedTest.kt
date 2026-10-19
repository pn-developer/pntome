package dev.gsound.a.domain.scan

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.gsound.a.data.settings.SettingsStore
import dev.gsound.a.domain.model.AppSettings
import dev.gsound.a.domain.model.ScanProgress
import dev.gsound.a.domain.model.Track
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Instrumented coverage for [FolderScanner] against a seeded SAF document tree (task 5.4).
 *
 * The real Android-coupled path — [DocumentsContract] bulk cursor enumeration plus
 * [android.media.MediaMetadataRetriever] metadata extraction — is exercised here through a
 * hermetic [SeededDocumentsProvider] (registered in the androidTest manifest) backed by a seeded
 * on-disk tree. No device media library or manual folder picking is involved, so the test is
 * self-contained and repeatable.
 *
 * Seeded tree (settings default to mp3-only):
 * ```
 * root/
 *   a_withmeta.mp3   ID3 tags -> metadata path (Req 3.4)
 *   b_nometa.mp3     no ID3   -> filename-fallback title (Req 3.4)
 *   notes.txt        unsupported -> skipped (Req 3.3)
 *   cover.ogg        unsupported (ogg not whitelisted) -> skipped (Req 3.3)
 *   Rock/
 *     live.mp3       nested  -> recursion (Req 3.1), grouped under "Rock"
 * ```
 * Expected: 3 eligible tracks, 2 skipped.
 */
@RunWith(AndroidJUnit4::class)
class FolderScannerInstrumentedTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private lateinit var scanner: FolderScanner
    private lateinit var root: File

    private val folderId = "folder-1"
    private val rootName = "Music"
    private val mp3Only = AppSettings() // all optional-format flags default to false

    @Before
    fun setUp() {
        // The scanner reads settings from SettingsStore only when a caller passes null; every
        // call below passes explicit settings, so the store is never actually touched.
        scanner = FolderScanner(context, SettingsStore(context))

        root = SeededDocumentsProvider.freshRoot(context)
        Mp3Fixtures.write(
            File(root, "a_withmeta.mp3"),
            title = "Seeded Title",
            artist = "Seeded Artist",
            album = "Seeded Album"
        )
        Mp3Fixtures.write(File(root, "b_nometa.mp3")) // no tags -> fallback title
        File(root, "notes.txt").writeText("not audio")
        File(root, "cover.ogg").writeBytes(ByteArray(16))
        Mp3Fixtures.write(File(root, "Rock/live.mp3"), title = "Live Track")
    }

    @After
    fun tearDown() {
        SeededDocumentsProvider.reset()
    }

    @Test
    fun scanToResult_enumeratesRecursively_filtersAndCountsSkipped() = runBlocking {
        val result = scanner.scanToResult(treeUri(), folderId, rootName, mp3Only)

        // Req 3.1 recursion + Req 3.2 filter: 3 mp3s found across root and the Rock subfolder.
        assertEquals(3, result.tracks.size)
        // Req 3.3: the .txt and .ogg files are skipped and counted.
        assertEquals(2, result.skippedCount)

        val byTitle = result.tracks.associateBy { it.title }
        assertEquals(setOf("Seeded Title", "b_nometa", "Live Track"), byTitle.keys)
    }

    @Test
    fun scanToResult_readsMetadata_andFallsBackToFileName() = runBlocking {
        val tracks = scanner.scanToResult(treeUri(), folderId, rootName, mp3Only).tracks
        val byTitle = tracks.associateBy { it.title }

        // Req 3.4 metadata path: tagged file surfaces its ID3 title/artist/album.
        assertTrue("tagged track present", byTitle.containsKey("Seeded Title"))
        val tagged = byTitle.getValue("Seeded Title")
        assertEquals("Seeded Artist", tagged.artist)
        assertEquals("Seeded Album", tagged.album)
        assertTrue("duration should be read from real frames", tagged.durationMs > 0)
        assertEquals("audio/mpeg", tagged.mime)

        // Req 3.4 fallback: untagged file falls back to its filename (extension stripped) and
        // reports no artist/album.
        val fallback = byTitle.getValue("b_nometa")
        assertNull(fallback.artist)
        assertNull(fallback.album)
    }

    @Test
    fun scanToResult_groupsByRelativeFolder_withRootNameForTopLevel() = runBlocking {
        val tracks = scanner.scanToResult(treeUri(), folderId, rootName, mp3Only).tracks
        val folderByTitle = tracks.associate { it.title to it.relativeFolder }

        // Top-level tracks group under the folder display name; nested track under its subfolder.
        assertEquals(rootName, folderByTitle.getValue("Seeded Title"))
        assertEquals(rootName, folderByTitle.getValue("b_nometa"))
        assertEquals("Rock", folderByTitle.getValue("Live Track"))

        // Every track is stamped with the owning folder id and a usable document URI.
        tracks.forEach { track ->
            assertEquals(folderId, track.folderId)
            assertTrue(track.documentUri.startsWith("content://"))
            assertTrue(track.sizeBytes > 0)
        }
    }

    @Test
    fun scanToResult_assignsStableIdsAcrossScans() = runBlocking {
        val first = scanner.scanToResult(treeUri(), folderId, rootName, mp3Only).tracks
        val second = scanner.scanToResult(treeUri(), folderId, rootName, mp3Only).tracks

        val firstIds = first.associate { it.title to it.id }
        val secondIds = second.associate { it.title to it.id }

        // Req 3.5: unchanged files yield identical ids on a rescan.
        assertEquals(firstIds, secondIds)
        // Ids are distinct per track.
        assertEquals(first.size, first.map(Track::id).toSet().size)
    }

    @Test
    fun scan_emitsProgress_withTerminalDone() = runBlocking {
        val emissions: List<ScanProgress> =
            scanner.scan(treeUri(), folderId, rootName, mp3Only).toList()

        assertTrue("expected progress emissions", emissions.isNotEmpty())

        // Req 3.6: exactly one terminal emission, and it is last.
        assertTrue("last emission is done", emissions.last().done)
        assertFalse("non-terminal emissions are not done", emissions.dropLast(1).any { it.done })

        val terminal = emissions.last()
        assertEquals(3, terminal.tracksFound)
        assertEquals(2, terminal.skippedCount)
        // filesScanned counts supported + skipped files (not directories).
        assertEquals(5, terminal.filesScanned)
        assertNull("terminal has no current file", terminal.currentFile)
    }

    private fun treeUri() = SeededDocumentsProvider.treeUri()
}
