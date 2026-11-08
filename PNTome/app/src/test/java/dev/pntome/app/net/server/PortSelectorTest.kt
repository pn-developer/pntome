package dev.pntome.app.net.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the pure [PortSelector] (task 8.1).
 *
 * Exercises every branch of the fallback policy with a fake `isBindable` predicate — no real
 * sockets — covering: preferred port available, preferred busy → nearby fixed fallback, and the
 * whole range busy → ephemeral (`0`).
 */
class PortSelectorTest {

    @Test
    fun `preferred port available returns preferred`() {
        val selection = PortSelector.select(preferredPort = 8080) { port -> port == 8080 }

        assertEquals(8080, selection.port)
        assertEquals(PortSelector.Strategy.PREFERRED, selection.strategy)
    }

    @Test
    fun `preferred busy falls back to the next bindable port in range`() {
        // 8080 busy, 8081 busy, 8082 free.
        val free = setOf(8082)
        val selection = PortSelector.select(preferredPort = 8080) { port -> port in free }

        assertEquals(8082, selection.port)
        assertEquals(PortSelector.Strategy.FALLBACK_FIXED, selection.strategy)
    }

    @Test
    fun `preferred busy falls back to the first port after preferred when it is free`() {
        val selection = PortSelector.select(preferredPort = 8080) { port -> port == 8081 }

        assertEquals(8081, selection.port)
        assertEquals(PortSelector.Strategy.FALLBACK_FIXED, selection.strategy)
    }

    @Test
    fun `all ports in range busy falls back to ephemeral`() {
        // Nothing is bindable → ephemeral.
        val selection = PortSelector.select(preferredPort = 8080) { _ -> false }

        assertEquals(PortSelector.EPHEMERAL_PORT, selection.port)
        assertEquals(0, selection.port)
        assertEquals(PortSelector.Strategy.EPHEMERAL, selection.strategy)
    }

    @Test
    fun `fallback scan is bounded to the fallback range`() {
        // Only a port beyond the fallback window is free → selector should not reach it.
        val outsideWindow = 8080 + PortSelector.FALLBACK_RANGE + 1
        val probed = mutableListOf<Int>()
        val selection = PortSelector.select(preferredPort = 8080) { port ->
            probed += port
            port == outsideWindow
        }

        assertEquals(PortSelector.Strategy.EPHEMERAL, selection.strategy)
        assertTrue(
            "selector must not probe beyond the fallback window",
            probed.none { it > 8080 + PortSelector.FALLBACK_RANGE }
        )
    }

    @Test
    fun `invalid preferred port skips straight to ephemeral without probing`() {
        var probed = false
        val selection = PortSelector.select(preferredPort = 0) {
            probed = true
            true
        }

        assertEquals(PortSelector.Strategy.EPHEMERAL, selection.strategy)
        assertEquals(PortSelector.EPHEMERAL_PORT, selection.port)
        assertTrue("must not probe for an out-of-range preferred port", !probed)
    }

    @Test
    fun `preferred port is tried before any fallback`() {
        val probed = mutableListOf<Int>()
        PortSelector.select(preferredPort = 9000) { port ->
            probed += port
            true // first probe (the preferred port) wins
        }

        assertEquals(listOf(9000), probed)
    }
}
