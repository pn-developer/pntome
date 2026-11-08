package dev.gsound.a.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerTimeFormatTest {
    @Test
    fun formatsMinutesAndHours() {
        assertEquals("0:00", formatPlayerTime(-5))
        assertEquals("0:59", formatPlayerTime(59_999))
        assertEquals("12:05", formatPlayerTime(725_000))
        assertEquals("1:02:03", formatPlayerTime(3_723_000))
    }
}
