package dev.gsound.a.net.server

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for [TrackId.isValidTrackId] (design §8: validate/sanitize the `trackId` path
 * segment, no traversal; ids are opaque hashes).
 *
 * Covers the accepted shape (SHA-1 hex and the URL-safe alphabet) and the rejected cases that
 * matter for traversal/injection: separators, `..`, backslash, whitespace, empty, and over-long.
 */
class TrackIdTest {

    @Test
    fun `sha1 hex id passes`() {
        // 40-char lowercase hex, the current TrackIdHasher output shape.
        assertTrue(TrackId.isValidTrackId("da39a3ee5e6b4b0d3255bfef95601890afd80709"))
    }

    @Test
    fun `url-safe alphabet passes`() {
        assertTrue(TrackId.isValidTrackId("Track_123-abc"))
        assertTrue(TrackId.isValidTrackId("AbCdEf0123456789_-"))
    }

    @Test
    fun `single character id passes`() {
        assertTrue(TrackId.isValidTrackId("a"))
    }

    @Test
    fun `id at the max length passes and over the max fails`() {
        val atMax = "a".repeat(TrackId.MAX_LENGTH)
        val overMax = "a".repeat(TrackId.MAX_LENGTH + 1)
        assertTrue(TrackId.isValidTrackId(atMax))
        assertFalse(TrackId.isValidTrackId(overMax))
    }

    @Test
    fun `null is rejected`() {
        assertFalse(TrackId.isValidTrackId(null))
    }

    @Test
    fun `empty is rejected`() {
        assertFalse(TrackId.isValidTrackId(""))
    }

    @Test
    fun `forward slash is rejected`() {
        assertFalse(TrackId.isValidTrackId("foo/bar"))
        assertFalse(TrackId.isValidTrackId("/etc/passwd"))
    }

    @Test
    fun `backslash is rejected`() {
        assertFalse(TrackId.isValidTrackId("foo\\bar"))
    }

    @Test
    fun `dot and traversal sequences are rejected`() {
        assertFalse(TrackId.isValidTrackId("."))
        assertFalse(TrackId.isValidTrackId(".."))
        assertFalse(TrackId.isValidTrackId("../../etc/passwd"))
        assertFalse(TrackId.isValidTrackId("track.mp3"))
    }

    @Test
    fun `whitespace is rejected`() {
        assertFalse(TrackId.isValidTrackId("has space"))
        assertFalse(TrackId.isValidTrackId("tab\there"))
        assertFalse(TrackId.isValidTrackId("new\nline"))
    }

    @Test
    fun `null byte and control characters are rejected`() {
        assertFalse(TrackId.isValidTrackId("abc\u0000def"))
        assertFalse(TrackId.isValidTrackId("abc\u0007"))
    }

    @Test
    fun `other path and injection metacharacters are rejected`() {
        assertFalse(TrackId.isValidTrackId("a%2e%2e"))   // percent (would be pre-decoded, still rejected)
        assertFalse(TrackId.isValidTrackId("a:b"))
        assertFalse(TrackId.isValidTrackId("a?b"))
        assertFalse(TrackId.isValidTrackId("a#b"))
    }
}
