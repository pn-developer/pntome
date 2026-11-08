package dev.gsound.a.domain.scan

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for the pure path/name helpers in [ScanPaths] used by `FolderScanner` (task 5.2).
 *
 * These cover the JVM-testable string logic — relative-path construction (Req 3.5 input),
 * folder grouping (Req 3.2 grouping on the watch), and the filename-fallback title (Req 3.4).
 * The Android-coupled enumeration/metadata is covered by the instrumented test (task 5.4).
 */
class ScanPathsTest {

    // --- relativePath ----------------------------------------------------------------------

    @Test
    fun `relativePath joins nested segments with the file name`() {
        assertEquals(
            "Rock/Live/song.mp3",
            ScanPaths.relativePath(listOf("Rock", "Live"), "song.mp3")
        )
    }

    @Test
    fun `relativePath for a root-level file is just the file name`() {
        assertEquals("song.mp3", ScanPaths.relativePath(emptyList(), "song.mp3"))
    }

    @Test
    fun `relativePath drops blank segments so there is no double slash`() {
        assertEquals(
            "Rock/song.mp3",
            ScanPaths.relativePath(listOf("", "Rock", "  "), "song.mp3")
        )
    }

    // --- relativeFolder --------------------------------------------------------------------

    @Test
    fun `relativeFolder is the slash-joined subfolder path`() {
        assertEquals(
            "Rock/Live",
            ScanPaths.relativeFolder(listOf("Rock", "Live"), rootName = "My Music")
        )
    }

    @Test
    fun `relativeFolder falls back to the root name at the top level`() {
        assertEquals("My Music", ScanPaths.relativeFolder(emptyList(), rootName = "My Music"))
    }

    @Test
    fun `relativeFolder ignores blank segments and falls back when all blank`() {
        assertEquals("My Music", ScanPaths.relativeFolder(listOf("", "  "), rootName = "My Music"))
    }

    // --- titleFromFileName -----------------------------------------------------------------

    @Test
    fun `titleFromFileName strips the trailing extension`() {
        assertEquals("01 - Song", ScanPaths.titleFromFileName("01 - Song.mp3"))
    }

    @Test
    fun `titleFromFileName keeps a name without an extension`() {
        assertEquals("README", ScanPaths.titleFromFileName("README"))
    }

    @Test
    fun `titleFromFileName keeps a leading-dot name unchanged`() {
        assertEquals(".hidden", ScanPaths.titleFromFileName(".hidden"))
    }

    @Test
    fun `titleFromFileName strips only the final extension`() {
        assertEquals("song.live", ScanPaths.titleFromFileName("song.live.flac"))
    }
}
