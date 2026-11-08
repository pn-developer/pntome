package dev.gsound.a.net.server

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import androidx.test.core.app.ApplicationProvider

/**
 * Wi-Fi-preferred / hotspot / none choice in [ConnectivityWifiAddressResolver.resolveLanAddress]
 * (HS-2.1, HS-2.2, HS-4.3). The Android-bound Wi-Fi lookup is replaced by overriding
 * `resolveWifiIpv4()`; the interface list comes from a fake provider.
 */
@RunWith(RobolectricTestRunner::class)
class ConnectivityWifiAddressResolverTest {

    private val hotspotIface = IfaceInfo("wlan1", true, false, false, listOf("192.168.43.1"))

    private fun resolver(wifi: String?, provider: InterfaceListProvider) =
        object : ConnectivityWifiAddressResolver(
            ApplicationProvider.getApplicationContext<Context>(),
            provider,
        ) {
            override fun resolveWifiIpv4(): String? = wifi
        }

    @Test
    fun wifiPresent_andHotspotIface_prefersWifi() {
        val r = resolver("10.0.0.5") { listOf(hotspotIface) }
        assertEquals(LanAddress("10.0.0.5", LanKind.WIFI), r.resolveLanAddress())
    }

    @Test
    fun wifiAbsent_andHotspotIface_returnsHotspot() {
        val r = resolver(null) { listOf(hotspotIface) }
        assertEquals(LanAddress("192.168.43.1", LanKind.HOTSPOT), r.resolveLanAddress())
    }

    @Test
    fun neitherWifiNorHotspot_returnsNull() {
        val r = resolver(null) { emptyList() }
        assertNull(r.resolveLanAddress())
    }

    @Test
    fun providerThrows_returnsNull() {
        val r = resolver(null) { throw IllegalStateException("boom") }
        assertNull(r.resolveLanAddress())
    }
}
