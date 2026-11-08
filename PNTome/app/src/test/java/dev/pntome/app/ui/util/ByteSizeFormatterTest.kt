package dev.gsound.a.ui.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [ByteSizeFormatter] (Req 2.1).
 *
 * Verifies plain-byte rendering below a kilobyte, binary (1024-based) unit scaling, decimal
 * formatting at each tier, unit-boundary behaviour, and clamping of negative input.
 */
class ByteSizeFormatterTest {

    @Test
    fun `zero bytes renders as plain bytes`() {
        assertEquals("0 B", ByteSizeFormatter.format(0))
    }

    @Test
    fun `values below one kilobyte render as plain bytes`() {
        assertEquals("512 B", ByteSizeFormatter.format(512))
        assertEquals("1023 B", ByteSizeFormatter.format(1023))
    }

    @Test
    fun `exactly one kilobyte crosses into KB`() {
        assertEquals("1.0 KB", ByteSizeFormatter.format(1024))
    }

    @Test
    fun `kilobytes render with one decimal`() {
        assertEquals("1.5 KB", ByteSizeFormatter.format(1536))
    }

    @Test
    fun `megabytes scale correctly`() {
        assertEquals("1.0 MB", ByteSizeFormatter.format(1024L * 1024))
        assertEquals("1.5 MB", ByteSizeFormatter.format(1024L * 1024 * 3 / 2))
    }

    @Test
    fun `gigabytes scale correctly`() {
        assertEquals("2.0 GB", ByteSizeFormatter.format(2L * 1024 * 1024 * 1024))
    }

    @Test
    fun `terabytes scale correctly`() {
        assertEquals("1.0 TB", ByteSizeFormatter.format(1024L * 1024 * 1024 * 1024))
    }

    @Test
    fun `negative input is clamped to zero bytes`() {
        assertEquals("0 B", ByteSizeFormatter.format(-5))
    }
}
