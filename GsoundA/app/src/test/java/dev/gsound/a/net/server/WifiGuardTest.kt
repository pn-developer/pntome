package dev.gsound.a.net.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for [WifiGuard] (task 9.3) — the Wi-Fi precondition policy over a
 * [WifiAddressResolver] (Req 5.7, 5.8). Driven by a fake resolver so both branches are
 * exercisable without Android: an address → [WifiStatus.Available]; `null` → [WifiStatus.Unavailable].
 */
class WifiGuardTest {

    /** Minimal fake resolver returning a canned value, standing in for the Android impl. */
    private class FakeWifiAddressResolver(private val ipv4: String?) : WifiAddressResolver {
        override fun resolveWifiIpv4(): String? = ipv4
    }

    private fun guard(ipv4: String?) = WifiGuard(FakeWifiAddressResolver(ipv4))

    @Test
    fun `available when resolver returns an address, carrying that ipv4`() {
        val status = guard("192.168.1.42").check()

        assertTrue(status is WifiStatus.Available)
        assertEquals("192.168.1.42", (status as WifiStatus.Available).ipv4)
    }

    @Test
    fun `unavailable when resolver returns null`() {
        val status = guard(null).check()

        assertSame(WifiStatus.Unavailable, status)
    }

    @Test
    fun `guidance message is the canonical same-Wi-Fi text`() {
        // The guard does not fabricate per-call messages; the single source of truth is the
        // constant, which the orchestrator and UI reuse (Req 5.7).
        assertEquals(
            "Connect both this phone and your watch to the same Wi-Fi network.",
            WifiGuard.GUIDANCE_MESSAGE
        )
    }

    @Test
    fun `available passes the resolved address through verbatim`() {
        // A different, non-192 address to show the value is not hard-coded anywhere in the guard.
        val status = guard("10.0.0.7").check()

        assertEquals("10.0.0.7", (status as WifiStatus.Available).ipv4)
    }
}
