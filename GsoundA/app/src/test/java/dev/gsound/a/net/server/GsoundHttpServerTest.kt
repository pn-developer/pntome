package dev.gsound.a.net.server

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [GsoundHttpServer.start] result kinds (HS-2.3, HS-2.4, HS-3.1). The resolver is faked; the
 * "hotspot" address is 127.0.0.1 so no real non-local socket is needed.
 */
class GsoundHttpServerTest {

    private class FakeResolver(private val lan: LanAddress?) : WifiAddressResolver {
        override fun resolveWifiIpv4(): String? = null
        override fun resolveLanAddress(): LanAddress? = lan
    }

    @Test
    fun hotspotAddress_startsWithKindHotspotBoundToThatAddress() = runBlocking {
        val server = GsoundHttpServer(FakeResolver(LanAddress("127.0.0.1", LanKind.HOTSPOT)), emptySet())
        try {
            val result = server.start(preferredPort = 0)

            assertTrue(result is GsoundHttpServer.StartResult.Started)
            result as GsoundHttpServer.StartResult.Started
            assertEquals(LanKind.HOTSPOT, result.kind)
            assertEquals("127.0.0.1", result.host)
            assertTrue(result.url.startsWith("http://127.0.0.1:"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun noAddress_returnsWifiUnavailable() = runBlocking {
        val server = GsoundHttpServer(FakeResolver(null), emptySet())

        assertSame(GsoundHttpServer.StartResult.WifiUnavailable, server.start(preferredPort = 0))
    }
}
