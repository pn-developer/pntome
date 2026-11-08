package dev.pntome.app.net.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HotspotAddressFinderTest {

    private fun iface(
        name: String,
        vararg ips: String,
        up: Boolean = true,
        loopback: Boolean = false,
        virtual: Boolean = false,
    ) = IfaceInfo(name, up, loopback, virtual, ips.toList())

    @Test
    fun apInterfaceChosenOverMobileData() {
        val result = HotspotAddressFinder.find(
            listOf(iface("rmnet_data0", "100.71.5.9"), iface("ap0", "10.42.0.1")),
        )
        assertEquals("10.42.0.1", result)
    }

    @Test
    fun swlanInterfaceAddressReturned() {
        assertEquals(
            "192.168.43.1",
            HotspotAddressFinder.find(listOf(iface("swlan0", "192.168.43.1"))),
        )
    }

    @Test
    fun onlyMobileOrVpnReturnsNull() {
        val result = HotspotAddressFinder.find(
            listOf(
                iface("rmnet_data1", "10.20.30.40"),
                iface("tun0", "10.8.0.2"),
                iface("lo", "127.0.0.1", loopback = true),
            ),
        )
        assertNull(result)
    }

    @Test
    fun downInterfaceIgnored() {
        assertNull(HotspotAddressFinder.find(listOf(iface("ap0", "10.42.0.1", up = false))))
    }

    @Test
    fun linkLocalAndCgnatIgnored() {
        val result = HotspotAddressFinder.find(
            listOf(iface("ap0", "169.254.10.1"), iface("swlan0", "100.64.0.1", "100.127.255.254")),
        )
        assertNull(result)
    }

    @Test
    fun hotspotPatternPreferredOverOtherSiteLocal() {
        val result = HotspotAddressFinder.find(
            listOf(iface("eth0", "192.168.1.5"), iface("ap0", "10.42.0.1")),
        )
        assertEquals("10.42.0.1", result)
    }

    @Test
    fun nonHotspotSiteLocalUsedWhenNoPatternMatch() {
        assertEquals(
            "192.168.1.5",
            HotspotAddressFinder.find(listOf(iface("eth0", "192.168.1.5"))),
        )
    }

    @Test
    fun range172Boundaries() {
        fun find(ip: String) = HotspotAddressFinder.find(listOf(iface("ap0", ip)))
        assertNull(find("172.15.255.255"))
        assertEquals("172.16.0.1", find("172.16.0.1"))
        assertEquals("172.31.255.254", find("172.31.255.254"))
        assertNull(find("172.32.0.1"))
    }

    @Test
    fun emptyListReturnsNull() {
        assertNull(HotspotAddressFinder.find(emptyList()))
    }
}
