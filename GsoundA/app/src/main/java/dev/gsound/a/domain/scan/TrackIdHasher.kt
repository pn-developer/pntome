package dev.gsound.a.domain.scan

import java.security.MessageDigest

/**
 * Derives the stable [dev.gsound.a.domain.model.Track.id] used for dedupe and cross-scan
 * reconciliation (Req 3.5).
 *
 * The id is `sha1(relativePath + "|" + sizeBytes + "|" + lastModified)` rendered as a
 * lowercase hex string (design §4 / §5.2). Hashing path/size/lastModified rather than file
 * content keeps the computation cheap while remaining stable across scans: the id only
 * changes when the file is moved/renamed, resized, or its modification time changes.
 *
 * Pure Kotlin, no Android dependencies, so it is unit-testable on the JVM.
 */
object TrackIdHasher {

    private const val SEPARATOR = "|"

    /**
     * Compute the stable track id.
     *
     * @param relativePath the track's path relative to its folder root (e.g. `Rock/song.mp3`).
     * @param sizeBytes the file size in bytes.
     * @param lastModified the file's last-modified timestamp (epoch millis).
     * @return a 40-character lowercase hex SHA-1 digest; identical inputs always yield the
     *   same output, and any change to a component yields a different output.
     */
    fun hash(relativePath: String, sizeBytes: Long, lastModified: Long): String {
        val input = buildString {
            append(relativePath)
            append(SEPARATOR)
            append(sizeBytes)
            append(SEPARATOR)
            append(lastModified)
        }
        return sha1Hex(input)
    }

    private fun sha1Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest(input.toByteArray(Charsets.UTF_8))
        val hex = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            hex.append(HEX_CHARS[v ushr 4])
            hex.append(HEX_CHARS[v and 0x0F])
        }
        return hex.toString()
    }

    private val HEX_CHARS = "0123456789abcdef".toCharArray()
}
