package dev.gsound.a.net.server

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.Inet4Address
import java.net.NetworkInterface
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves the phone's current LAN IPv4 address when it is connected to Wi-Fi (design §5.5,
 * Req 5.1, Req 5.7).
 *
 * ## Why an interface
 * The HTTP server ([GsoundHttpServer]) must bind to the Wi-Fi interface, and must *refuse to
 * start* when Wi-Fi is unavailable so the caller can show the Req 5.7 guidance ("both devices
 * must be on the same Wi-Fi network"). The actual address lookup needs Android
 * ([ConnectivityManager]), which is awkward to unit-test. Hiding it behind this one-method
 * interface keeps the Android touch isolated and lets the server be driven by a fake resolver
 * (returning a canned address or `null`) in tests — the server's start/refuse logic is then
 * exercisable without a device.
 */
interface WifiAddressResolver {

    /**
     * The phone's current Wi-Fi LAN IPv4 address, or `null` when the phone is not on Wi-Fi (or
     * no usable IPv4 address is available on the Wi-Fi link).
     *
     * `null` is the Req 5.7 signal: the server must not start and the user must be told to put
     * both devices on the same Wi-Fi network.
     *
     * @return the dotted-quad address string (e.g. `"192.168.1.42"`) or `null`.
     */
    fun resolveWifiIpv4(): String?

    /**
     * The chosen serving address: station Wi-Fi first, else hotspot (HS-2.1). The default keeps
     * existing Wi-Fi-only implementations and fakes working (HS-1.3).
     */
    fun resolveLanAddress(): LanAddress? = resolveWifiIpv4()?.let { LanAddress(it, LanKind.WIFI) }
}

/** Which network the phone serves the watch on. */
enum class LanKind { WIFI, HOTSPOT }

/** The chosen serving address. */
data class LanAddress(val ipv4: String, val kind: LanKind)

/** Supplies the device's network interfaces; injectable so the resolver is JVM-testable. */
fun interface InterfaceListProvider {
    fun interfaces(): List<IfaceInfo>
}

/** Default [InterfaceListProvider] backed by [NetworkInterface]; returns empty on any failure. */
internal object NetworkInterfaceListProvider : InterfaceListProvider {
    override fun interfaces(): List<IfaceInfo> = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().map { ni ->
            IfaceInfo(
                ni.name, ni.isUp, ni.isLoopback, ni.isVirtual,
                ni.inetAddresses.toList().filterIsInstance<Inet4Address>().map { it.hostAddress!! },
            )
        }
    }.getOrDefault(emptyList())
}

/**
 * Android implementation of [WifiAddressResolver] backed by [ConnectivityManager] (design §5.5).
 *
 * ## Approach
 * Rather than the legacy `WifiManager.connectionInfo` (deprecated, returns a packed int, and
 * increasingly permission-gated), this reads the active network's [LinkAddress]es and returns the
 * first non-loopback IPv4 address, but only when the active network reports Wi-Fi transport. This
 * matches the modern [ConnectivityManager] model and avoids the Wi-Fi-specific location
 * permission creep.
 *
 * ## Wi-Fi gating (Req 5.7)
 * The address is returned only if the active network has
 * [NetworkCapabilities.TRANSPORT_WIFI]. On cellular/none/other transports this returns `null`
 * even if some IPv4 address exists, because serving over a non-Wi-Fi/LAN interface would not let
 * the watch reach the phone and would contradict the "same Wi-Fi network" requirement.
 *
 * ## Permissions
 * Needs `ACCESS_NETWORK_STATE` (declared in the manifest). It does not require location, since it
 * reads link addresses from the active network rather than Wi-Fi scan results.
 *
 * ## Robustness
 * All lookups are null-guarded; any missing capability/link info yields `null` (treated as
 * "Wi-Fi unavailable") rather than throwing, so a transient framework hiccup degrades to the
 * safe Req 5.7 path.
 */
@Singleton
open class ConnectivityWifiAddressResolver(
    private val context: Context,
    private val interfaceProvider: InterfaceListProvider,
) : WifiAddressResolver {

    /** Hilt entry point: uses the real [NetworkInterface] adapter. */
    @Inject
    constructor(@ApplicationContext context: Context) :
        this(context, NetworkInterfaceListProvider)

    /**
     * Station Wi-Fi first, else the phone's own hotspot (HS-2.1, 2.2). Evaluated on every call,
     * never cached (HS-1.3), and never throws.
     */
    override fun resolveLanAddress(): LanAddress? = try {
        resolveWifiIpv4()?.let { LanAddress(it, LanKind.WIFI) }
            ?: HotspotAddressFinder.find(interfaceProvider.interfaces())
                ?.let { LanAddress(it, LanKind.HOTSPOT) }
    } catch (_: Exception) {
        null
    }

    override fun resolveWifiIpv4(): String? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null

        val network = cm.activeNetwork ?: return null

        val caps = cm.getNetworkCapabilities(network) ?: return null
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null

        val linkProperties = cm.getLinkProperties(network) ?: return null

        return linkProperties.linkAddresses
            .asSequence()
            .map(LinkAddress::getAddress)
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && !it.isAnyLocalAddress }
            ?.hostAddress
    }
}
