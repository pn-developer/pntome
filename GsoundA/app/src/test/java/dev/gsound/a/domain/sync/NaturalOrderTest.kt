package dev.gsound.a.domain.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Pure JVM tests for [NaturalOrder] (spec gsound-audiobooks AB-2.2, AB-2.5). */
class NaturalOrderTest {

    private fun sorted(vararg names: String) = names.toList().sortedWith(NaturalOrder)

    @Test
    fun `numeric prefixes compare by value not text`() {
        assertEquals(
            listOf("1 One.mp3", "2 Two.mp3", "10 Ten.mp3"),
            sorted("10 Ten.mp3", "2 Two.mp3", "1 One.mp3")
        )
    }

    @Test
    fun `zero-padded and unpadded prefixes interleave by value`() {
        assertEquals(
            listOf("01 a.mp3", "2 b.mp3", "03 c.mp3", "10 d.mp3"),
            sorted("10 d.mp3", "03 c.mp3", "2 b.mp3", "01 a.mp3")
        )
    }

    @Test
    fun `disc-track prefixes order by disc then track`() {
        assertEquals(
            listOf("1-01.mp3", "1-02.mp3", "1-10.mp3", "2-01.mp3"),
            sorted("2-01.mp3", "1-10.mp3", "1-01.mp3", "1-02.mp3")
        )
    }

    @Test
    fun `text compares case-insensitively with numbers inside`() {
        assertEquals(
            listOf("Chapter 2.mp3", "chapter 10.mp3"),
            sorted("chapter 10.mp3", "Chapter 2.mp3")
        )
    }

    @Test
    fun `names without digits sort alphabetically and are kept`() {
        assertEquals(
            listOf("Epilogue.mp3", "intro.mp3", "Prologue.mp3"),
            sorted("Prologue.mp3", "intro.mp3", "Epilogue.mp3")
        )
    }

    @Test
    fun `extension does not affect order`() {
        assertEquals(listOf("1.mp3", "2.m4a"), sorted("2.m4a", "1.mp3"))
    }

    @Test
    fun `very long digit runs do not overflow`() {
        assertEquals(
            listOf("99999999999999999999.mp3", "100000000000000000000.mp3"),
            sorted("100000000000000000000.mp3", "99999999999999999999.mp3")
        )
    }

    @Test
    fun `order is total - distinct names never compare equal`() {
        assertNotEquals(0, NaturalOrder.compare("01.mp3", "1.mp3"))
        assertNotEquals(0, NaturalOrder.compare("a.mp3", "A.mp3"))
        assertEquals(0, NaturalOrder.compare("same.mp3", "same.mp3"))
    }

    @Test
    fun `equal value prefers the shorter digit run`() {
        assertEquals(listOf("1.mp3", "01.mp3"), sorted("01.mp3", "1.mp3"))
    }
}
