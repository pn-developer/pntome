package dev.gsound.a.ui.util

import java.util.Locale

/**
 * Formats a byte count as a short, human-readable size for the folder list (Req 2.1).
 *
 * Pure Kotlin with no Android dependencies so it is unit-testable on the JVM. Uses binary
 * (1024-based) units — `KB`, `MB`, `GB`, `TB` — matching how music libraries are typically
 * reported, with one decimal place above a kilobyte and whole bytes below.
 */
object ByteSizeFormatter {

    private const val UNIT = 1024L
    private val SUFFIXES = arrayOf("KB", "MB", "GB", "TB", "PB")

    /**
     * Render [bytes] as e.g. `0 B`, `512 B`, `1.5 MB`, `2.0 GB`.
     *
     * @param bytes a non-negative byte count; negative values are clamped to `0 B`.
     * @return a compact size string with a single-space between the number and unit.
     */
    fun format(bytes: Long): String {
        if (bytes < UNIT) {
            val safe = if (bytes < 0) 0 else bytes
            return "$safe B"
        }

        // Pick the largest unit that keeps the value < 1024, scaling down by 1024 each step.
        var value = bytes.toDouble()
        var index = -1
        do {
            value /= UNIT
            index++
        } while (value >= UNIT && index < SUFFIXES.lastIndex)

        return String.format(Locale.US, "%.1f %s", value, SUFFIXES[index])
    }
}
