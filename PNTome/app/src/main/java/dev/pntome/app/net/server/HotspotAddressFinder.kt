package dev.pntome.app.net.server

/** A platform-free view of one network interface. */
data class IfaceInfo(
    val name: String,
    val isUp: Boolean,
    val isLoopback: Boolean,
    val isVirtual: Boolean,
    val ipv4: List<String>,
)

/**
 * Picks the phone's own hotspot (tethering) IPv4 address from a list of interfaces.
 * Pure and JVM-testable; the Android adapter builds the [IfaceInfo] list.
 */
object HotspotAddressFinder {

    private val EXCLUDED_PREFIXES = listOf(
        // Mobile data
        "rmnet", "ccmni", "pdp", "v4-rmnet", "clat",
        // VPN and tunnels
        "tun", "ppp", "ipsec", "wg",
        // Other: dummy, ifb, Wi-Fi Direct, USB and Bluetooth tethering
        "dummy", "ifb", "p2p", "rndis", "usb", "bt-pan",
    )

    private val HOTSPOT_NAME = Regex("^(ap|swlan|softap)\\d*$|^wlan[1-9]\\d*$")

    /** Returns the preferred site-local IPv4 of a hotspot-like interface, or null. */
    fun find(ifaces: List<IfaceInfo>): String? {
        val candidates = ifaces.mapNotNull { iface ->
            if (!iface.isUp || iface.isLoopback || iface.isVirtual) return@mapNotNull null
            val lower = iface.name.lowercase()
            if (EXCLUDED_PREFIXES.any { lower.startsWith(it) }) return@mapNotNull null
            val ip = iface.ipv4.firstOrNull { isSiteLocalIpv4(it) } ?: return@mapNotNull null
            lower to ip
        }
        return (candidates.firstOrNull { HOTSPOT_NAME.matches(it.first) } ?: candidates.firstOrNull())?.second
    }

    /** 10/8, 172.16/12, 192.168/16 only; excludes 169.254/16 and 100.64/10. */
    internal fun isSiteLocalIpv4(ip: String): Boolean {
        val parts = ip.split('.')
        if (parts.size != 4) return false
        val o = parts.map { it.toIntOrNull()?.takeIf { v -> v in 0..255 } ?: return false }
        return when (o[0]) {
            10 -> true
            172 -> o[1] in 16..31
            192 -> o[1] == 168
            else -> false
        }
    }
}
