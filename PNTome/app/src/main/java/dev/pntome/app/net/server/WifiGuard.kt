package dev.pntome.app.net.server

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wi-Fi precondition guard for sync (Req 5.7, 5.8, 6.1).
 *
 * ## Why this exists
 * Req 5.7 says PNTome must **not** start the local HTTP server when Wi-Fi is unavailable and
 * must tell the user both devices have to be on the same Wi-Fi network. Req 5.8 says it must be
 * able to show its LAN address. Both of those are "is there a usable Wi-Fi LAN address, and if
 * so what is it?" questions.
 *
 * The authoritative Wi-Fi signal is [WifiAddressResolver.resolveWifiIpv4] (a `null` return means
 * "not on Wi-Fi"), but that is a bare `String?`. This guard turns that raw signal into an
 * explicit, exhaustive [WifiStatus] result and attaches the one canonical user-facing guidance
 * message, so the precondition is:
 *
 * - **centralized** — the "same Wi-Fi network" wording lives in one place ([GUIDANCE_MESSAGE])
 *   instead of being re-typed at each call site (the orchestrator pre-check, the server backstop,
 *   and the Sync UI gating in task 10),
 * - **explicit** — the [SyncOrchestrator] can ask the guard *before* touching the server and
 *   refuse to start it when Wi-Fi is down (Req 5.7), rather than relying on the server's own
 *   fail-fast as the only guard, and
 * - **testable** — it depends only on the [WifiAddressResolver] interface, so a fake resolver
 *   drives both branches on the JVM with no Android.
 *
 * The Android touch stays entirely inside [ConnectivityWifiAddressResolver]; this guard is pure
 * policy over that one injected dependency.
 */
@Singleton
class WifiGuard @Inject constructor(
    private val wifiAddressResolver: WifiAddressResolver
) {

    /**
     * Classify the current Wi-Fi state for serving.
     *
     * Delegates to [WifiAddressResolver.resolveWifiIpv4]: a non-null address means Wi-Fi is
     * usable for serving ([WifiStatus.Available] carrying that LAN IPv4 for Req 5.8 display); a
     * `null` means Wi-Fi is unavailable ([WifiStatus.Unavailable], Req 5.7).
     *
     * @return [WifiStatus.Available] with the resolved LAN IPv4, or [WifiStatus.Unavailable].
     */
    fun check(): WifiStatus =
        when (val lan = wifiAddressResolver.resolveLanAddress()) {
            null -> WifiStatus.Unavailable
            else -> WifiStatus.Available(lan.ipv4, lan.kind)
        }

    companion object {
        /**
         * The single canonical guidance shown whenever Wi-Fi is unavailable (Req 5.7). Centralized
         * here so the orchestrator, the server backstop, and the Sync UI all speak with one voice.
         */
        const val GUIDANCE_MESSAGE: String =
            "Connect this phone and your watch to the same Wi-Fi network, or connect the watch to this phone's hotspot."
    }
}

/**
 * The result of a [WifiGuard.check]: whether Wi-Fi is available for serving, and if so the LAN
 * address to serve on / display (Req 5.7, 5.8).
 *
 * Modeled as a sealed interface so callers handle both cases exhaustively at compile time.
 */
sealed interface WifiStatus {

    /**
     * Wi-Fi is available; the phone has a usable LAN IPv4 address to bind the server to and to
     * display for connectivity verification (Req 5.8).
     *
     * @property ipv4 the phone's Wi-Fi LAN IPv4 address (e.g. `"192.168.1.42"`).
     * @property kind whether the address is a Wi-Fi or phone-hotspot address.
     */
    data class Available(val ipv4: String, val kind: LanKind = LanKind.WIFI) : WifiStatus

    /**
     * Wi-Fi is unavailable: the server must not be started and the user must be told to put both
     * devices on the same Wi-Fi network (Req 5.7). The canonical message is
     * [WifiGuard.GUIDANCE_MESSAGE].
     */
    data object Unavailable : WifiStatus
}
