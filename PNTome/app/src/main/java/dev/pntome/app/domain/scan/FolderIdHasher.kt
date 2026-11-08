package dev.pntome.app.domain.scan

import java.security.MessageDigest

/**
 * Derives the stable [dev.pntome.app.domain.model.Folder.id] used to dedupe selected folders
 * (Req 1.4) and as the foreign-key target for cached tracks.
 *
 * The id is `sha1(normalizedTreeUri)` rendered as a lowercase hex string (design §4 / §5.1).
 * Hashing the normalized SAF tree URI means re-selecting the same folder yields the same id,
 * so [dev.pntome.app.data.folders.FolderRepository.addFolderIfAbsent] can reject it as a
 * duplicate rather than inserting a second row.
 *
 * Mirrors [TrackIdHasher]: pure Kotlin, no Android dependencies, so it is unit-testable on
 * the JVM.
 */
object FolderIdHasher {

    /**
     * Compute the stable folder id for a SAF tree URI.
     *
     * @param treeUri the persisted SAF `content://` tree URI string (e.g. the result of
     *   `ACTION_OPEN_DOCUMENT_TREE`).
     * @return a 40-character lowercase hex SHA-1 digest. Two tree URIs that [normalize] to the
     *   same canonical form always yield the same id, so selecting the same folder twice
     *   dedupes.
     */
    fun hash(treeUri: String): String = sha1Hex(normalize(treeUri))

    /**
     * Canonicalize a tree URI so trivially-different-but-equivalent strings dedupe to the same
     * id. SAF already returns a canonical `content://` URI, so normalization is intentionally
     * conservative: trim surrounding whitespace and drop a single trailing slash (which is not
     * semantically meaningful for a tree URI). The URI's percent-encoding and case are left
     * untouched because they are significant to the document provider.
     */
    fun normalize(treeUri: String): String {
        val trimmed = treeUri.trim()
        return trimmed.removeSuffix("/")
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
