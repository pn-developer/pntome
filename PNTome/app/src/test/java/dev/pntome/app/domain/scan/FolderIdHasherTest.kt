package dev.pntome.app.domain.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [FolderIdHasher] (Req 1.4).
 *
 * Verifies: a known-vector digest, stability across identical/equivalent tree URIs (so
 * re-selecting the same folder dedupes), distinctness for different folders, conservative
 * normalization, and well-formed hex output.
 */
class FolderIdHasherTest {

    private val treeUri = "content://com.android.externalstorage.documents/tree/primary%3AMusic"

    @Test
    fun `known vector matches reference sha1`() {
        // sha1("content://com.android.externalstorage.documents/tree/primary%3AMusic")
        val expected = "555e8e8f50a5a78cfae11b0a9f84f56c648bac96"
        assertEquals(expected, FolderIdHasher.hash(treeUri))
    }

    @Test
    fun `identical tree uris produce identical ids`() {
        assertEquals(FolderIdHasher.hash(treeUri), FolderIdHasher.hash(treeUri))
    }

    @Test
    fun `different tree uris produce different ids`() {
        val other = "content://com.android.externalstorage.documents/tree/primary%3APodcasts"
        assertNotEquals(FolderIdHasher.hash(treeUri), FolderIdHasher.hash(other))
    }

    @Test
    fun `surrounding whitespace is normalized away so the same folder dedupes`() {
        assertEquals(FolderIdHasher.hash(treeUri), FolderIdHasher.hash("  $treeUri  "))
    }

    @Test
    fun `a single trailing slash is normalized away`() {
        assertEquals(FolderIdHasher.hash(treeUri), FolderIdHasher.hash("$treeUri/"))
    }

    @Test
    fun `normalize is conservative about percent-encoding and case`() {
        // Percent-encoding and case are significant to the document provider and must be kept.
        assertEquals(treeUri, FolderIdHasher.normalize(treeUri))
        assertNotEquals(
            FolderIdHasher.hash(treeUri),
            FolderIdHasher.hash(treeUri.uppercase()),
        )
    }

    @Test
    fun `output is 40 char lowercase hex`() {
        val id = FolderIdHasher.hash(treeUri)
        assertEquals(40, id.length)
        assertTrue("expected lowercase hex but was '$id'", id.matches(Regex("[0-9a-f]{40}")))
    }
}
