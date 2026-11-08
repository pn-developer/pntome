package dev.gsound.a.domain.scan

import dev.gsound.a.domain.model.AppSettings
import dev.gsound.a.domain.scan.FormatFilter.AudioFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [FormatFilter] (Req 3.2, Req 3.3).
 *
 * Covers: mp3 always eligible; optional formats gated by settings flags (off by default →
 * skipped, on → eligible); unsupported extensions rejected; case-insensitivity; null/blank
 * MIME falling back to extension; and the documented mismatched extension-vs-MIME behaviour.
 */
class FormatFilterTest {

    private val defaults = AppSettings() // mp3-only
    private val allOptional = AppSettings(allowM4aAac = true, allowWav = true, allowFlac = true)

    // --- mp3 is always eligible, regardless of flags ---------------------------------------

    @Test
    fun `mp3 by extension and mime is eligible with defaults`() {
        assertTrue(FormatFilter.isEligible("song.mp3", "audio/mpeg", defaults))
    }

    @Test
    fun `mp3 is eligible even when all optional flags are off`() {
        assertTrue(FormatFilter.isEligible("a/b/track.mp3", "audio/mpeg", defaults))
    }

    @Test
    fun `mp3 by extension with unknown mime still eligible`() {
        assertTrue(FormatFilter.isEligible("track.mp3", "application/octet-stream", defaults))
    }

    // --- optional formats gated by flags (default off → skipped; on → eligible) ------------

    @Test
    fun `m4a is skipped by default and eligible when allowM4aAac`() {
        assertFalse(FormatFilter.isEligible("track.m4a", "audio/mp4", defaults))
        assertTrue(FormatFilter.isEligible("track.m4a", "audio/mp4", allOptional))
    }

    @Test
    fun `aac is skipped by default and eligible when allowM4aAac`() {
        assertFalse(FormatFilter.isEligible("track.aac", "audio/aac", defaults))
        assertTrue(
            FormatFilter.isEligible("track.aac", "audio/aac", AppSettings(allowM4aAac = true))
        )
    }

    @Test
    fun `wav is skipped by default and eligible when allowWav`() {
        assertFalse(FormatFilter.isEligible("track.wav", "audio/wav", defaults))
        assertTrue(FormatFilter.isEligible("track.wav", "audio/wav", AppSettings(allowWav = true)))
    }

    @Test
    fun `flac is skipped by default and eligible when allowFlac`() {
        assertFalse(FormatFilter.isEligible("track.flac", "audio/flac", defaults))
        assertTrue(
            FormatFilter.isEligible("track.flac", "audio/flac", AppSettings(allowFlac = true))
        )
    }

    @Test
    fun `enabling one optional format does not enable the others`() {
        val onlyWav = AppSettings(allowWav = true)
        assertTrue(FormatFilter.isEligible("track.wav", "audio/wav", onlyWav))
        assertFalse(FormatFilter.isEligible("track.flac", "audio/flac", onlyWav))
        assertFalse(FormatFilter.isEligible("track.m4a", "audio/mp4", onlyWav))
    }

    @Test
    fun `wav synonymous mime x-wav is recognised when enabled`() {
        assertTrue(FormatFilter.isEligible("track.wav", "audio/x-wav", AppSettings(allowWav = true)))
    }

    // --- unsupported extensions are rejected -----------------------------------------------

    @Test
    fun `ogg is rejected even with all flags on`() {
        assertFalse(FormatFilter.isEligible("track.ogg", "audio/ogg", allOptional))
    }

    @Test
    fun `txt is rejected`() {
        assertFalse(FormatFilter.isEligible("notes.txt", "text/plain", allOptional))
    }

    @Test
    fun `file with no extension and no mime is rejected`() {
        assertFalse(FormatFilter.isEligible("README", null, allOptional))
    }

    @Test
    fun `trailing dot with empty extension is rejected`() {
        assertFalse(FormatFilter.isEligible("weird.", null, allOptional))
    }

    @Test
    fun `blank filename and blank mime is rejected`() {
        assertFalse(FormatFilter.isEligible("", "", allOptional))
    }

    @Test
    fun `null filename and null mime is rejected`() {
        assertFalse(FormatFilter.isEligible(null, null, allOptional))
    }

    // --- case-insensitivity -----------------------------------------------------------------

    @Test
    fun `uppercase extension is eligible`() {
        assertTrue(FormatFilter.isEligible("SONG.MP3", null, defaults))
    }

    @Test
    fun `mixed-case extension is eligible`() {
        assertTrue(FormatFilter.isEligible("Song.Mp3", null, defaults))
    }

    @Test
    fun `uppercase mime is recognised`() {
        assertTrue(FormatFilter.isEligible("song", "AUDIO/MPEG", defaults))
    }

    // --- null / empty / parameterised MIME falls back to extension -------------------------

    @Test
    fun `null mime falls back to extension`() {
        assertTrue(FormatFilter.isEligible("song.mp3", null, defaults))
    }

    @Test
    fun `blank mime falls back to extension`() {
        assertTrue(FormatFilter.isEligible("song.mp3", "   ", defaults))
    }

    @Test
    fun `mime with parameters is parsed to base type`() {
        assertTrue(FormatFilter.isEligible("song.mp3", "audio/mpeg; charset=binary", defaults))
    }

    // --- mismatched extension vs MIME: documented lenient OR behaviour ---------------------

    @Test
    fun `supported extension with unknown mime is eligible via extension`() {
        // .mp3 recognised; octet-stream not — extension rescues the decision.
        assertTrue(FormatFilter.isEligible("song.mp3", "application/octet-stream", defaults))
    }

    @Test
    fun `unknown extension with supported-and-enabled mime is eligible via mime`() {
        // .dat not recognised; audio/mpeg is mp3 (always enabled) — mime rescues the decision.
        assertTrue(FormatFilter.isEligible("song.dat", "audio/mpeg", defaults))
    }

    @Test
    fun `mp3 extension with disabled-format mime is still eligible because mp3 is enabled`() {
        // Signals disagree: extension says mp3 (enabled), mime says flac (disabled by default).
        // The OR rule includes the file because at least one signal maps to an enabled format.
        assertTrue(FormatFilter.isEligible("song.mp3", "audio/flac", defaults))
    }

    @Test
    fun `disabled extension with mp3 mime is eligible because mp3 is enabled`() {
        // extension flac (disabled), mime mp3 (enabled) → eligible via the enabled mime.
        assertTrue(FormatFilter.isEligible("song.flac", "audio/mpeg", defaults))
    }

    @Test
    fun `two disabled signals remain skipped`() {
        // extension m4a + mime flac, both optional and both off by default → skipped.
        assertFalse(FormatFilter.isEligible("song.m4a", "audio/flac", defaults))
    }

    // --- flag-level overload (mp3 always allowed, no flag) ---------------------------------

    @Test
    fun `flag overload defaults to mp3-only`() {
        assertTrue(FormatFilter.isEligible("song.mp3", "audio/mpeg"))
        assertFalse(FormatFilter.isEligible("song.flac", "audio/flac"))
    }

    @Test
    fun `flag overload honours individual flags`() {
        assertTrue(FormatFilter.isEligible("song.flac", "audio/flac", allowFlac = true))
    }

    // --- exposed extension / mime maps ------------------------------------------------------

    @Test
    fun `exposed extension map resolves canonical formats`() {
        assertEquals(AudioFormat.MP3, FormatFilter.SUPPORTED_EXTENSIONS["mp3"])
        assertEquals(AudioFormat.M4A_AAC, FormatFilter.SUPPORTED_EXTENSIONS["m4a"])
        assertEquals(AudioFormat.M4A_AAC, FormatFilter.SUPPORTED_EXTENSIONS["aac"])
        assertEquals(AudioFormat.WAV, FormatFilter.SUPPORTED_EXTENSIONS["wav"])
        assertEquals(AudioFormat.FLAC, FormatFilter.SUPPORTED_EXTENSIONS["flac"])
        assertNull(FormatFilter.SUPPORTED_EXTENSIONS["ogg"])
    }

    @Test
    fun `exposed mime map resolves canonical formats`() {
        assertEquals(AudioFormat.MP3, FormatFilter.SUPPORTED_MIME_TYPES["audio/mpeg"])
        assertEquals(AudioFormat.M4A_AAC, FormatFilter.SUPPORTED_MIME_TYPES["audio/mp4"])
        assertEquals(AudioFormat.WAV, FormatFilter.SUPPORTED_MIME_TYPES["audio/x-wav"])
        assertEquals(AudioFormat.FLAC, FormatFilter.SUPPORTED_MIME_TYPES["audio/flac"])
        assertNull(FormatFilter.SUPPORTED_MIME_TYPES["audio/ogg"])
    }

    @Test
    fun `formatFromExtension and formatFromMime expose per-signal resolution`() {
        assertEquals(AudioFormat.MP3, FormatFilter.formatFromExtension("a/b/c.MP3"))
        assertNull(FormatFilter.formatFromExtension("noext"))
        assertEquals(AudioFormat.WAV, FormatFilter.formatFromMime("audio/wave"))
        assertNull(FormatFilter.formatFromMime(null))
    }
}
