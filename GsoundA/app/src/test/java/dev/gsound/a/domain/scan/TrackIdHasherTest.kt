package dev.gsound.a.domain.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [TrackIdHasher] (Req 3.5).
 *
 * Verifies: stability across identical inputs, a difference when any single component
 * changes, correct known-vector output, and well-formed hex digests.
 */
class TrackIdHasherTest {

    private val path = "Music/song.mp3"
    private val size = 1_048_576L
    private val modified = 1_700_000_000_000L

    @Test
    fun `known vector matches reference sha1`() {
        // sha1("Music/song.mp3|1048576|1700000000000")
        val expected = "ed293f859394a58f55cdcf0b96416f9004e0aff7"
        assertEquals(expected, TrackIdHasher.hash(path, size, modified))
    }

    @Test
    fun `identical inputs produce identical ids`() {
        val a = TrackIdHasher.hash(path, size, modified)
        val b = TrackIdHasher.hash(path, size, modified)
        assertEquals(a, b)
    }

    @Test
    fun `changing the path changes the id`() {
        val original = TrackIdHasher.hash(path, size, modified)
        val changed = TrackIdHasher.hash("Music/other.mp3", size, modified)
        assertNotEquals(original, changed)
    }

    @Test
    fun `changing the size changes the id`() {
        val original = TrackIdHasher.hash(path, size, modified)
        val changed = TrackIdHasher.hash(path, size + 1, modified)
        assertNotEquals(original, changed)
    }

    @Test
    fun `changing the lastModified changes the id`() {
        val original = TrackIdHasher.hash(path, size, modified)
        val changed = TrackIdHasher.hash(path, size, modified + 1)
        assertNotEquals(original, changed)
    }

    @Test
    fun `output is 40 char lowercase hex`() {
        val id = TrackIdHasher.hash(path, size, modified)
        assertEquals(40, id.length)
        assertTrue("expected lowercase hex but was '$id'", id.matches(Regex("[0-9a-f]{40}")))
    }

    @Test
    fun `empty path is handled and still produces a valid digest`() {
        val id = TrackIdHasher.hash("", 0L, 0L)
        assertEquals(40, id.length)
        assertTrue(id.matches(Regex("[0-9a-f]{40}")))
    }

    @Test
    fun `separator placement avoids collisions between adjacent components`() {
        // Without a separator, ("a", 1, 23) and ("a", 12, 3) could collide ("a123").
        // The explicit "|" separator must keep these distinct.
        val a = TrackIdHasher.hash("a", 1L, 23L)
        val b = TrackIdHasher.hash("a", 12L, 3L)
        assertNotEquals(a, b)
    }
}
