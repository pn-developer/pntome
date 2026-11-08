# Gsound — Sync over the Phone's Hotspot — Design

Covers `requirements.md` (HS-1 … HS-4). GsoundA only.

## 1. Current code (as checked)

| Piece | File | Behaviour today |
|---|---|---|
| `WifiAddressResolver.resolveWifiIpv4(): String?` | `net/server/WifiAddressResolver.kt` | `ConnectivityWifiAddressResolver` returns an IPv4 only if `cm.activeNetwork` has `TRANSPORT_WIFI`. |
| `WifiGuard.check(): WifiStatus` | `net/server/WifiGuard.kt` | `Available(ipv4)` / `Unavailable`. `GUIDANCE_MESSAGE` is the "same Wi-Fi" text. |
| `GsoundHttpServer.start()` | `net/server/GsoundHttpServer.kt` (line ~130) | `resolveWifiIpv4() ?: return WifiUnavailable`, then binds Ktor CIO to that host only. |
| `SyncOrchestrator.startSync` | `domain/sync/SyncOrchestrator.kt` | `wifiGuard.check()` pre-check, then `httpServer.start()`. `serverUrl` goes into `SyncState.Running`. |
| Sync UI | `ui/sync/SyncScreen.kt` (`LanAddress(url)`), `SyncViewModel.serverUrl` | Shows `http://ip:port`. |
| DI | `di/ServerModule.kt` | `@Binds ConnectivityWifiAddressResolver → WifiAddressResolver`. |
| Tests | `test/.../net/server/WifiGuardTest.kt`, `SyncOrchestratorTest.kt` | Use fake resolvers. |

The server's start, refuse and bind logic already sits behind the resolver interface. Only the
address *source* and the user-facing text need to change.

## 2. Approach

Generalize "Wi-Fi address" to **"LAN serving address with a kind"** while keeping the existing
interface method so that current fakes and tests keep compiling.

```kotlin
/** Which network the phone serves the watch on. */
enum class LanKind { WIFI, HOTSPOT }

/** The chosen serving address. */
data class LanAddress(val ipv4: String, val kind: LanKind)

interface WifiAddressResolver {
    fun resolveWifiIpv4(): String?                 // unchanged (station Wi-Fi only)
    /** Station Wi-Fi first, else hotspot (HS-2.1). Default keeps existing fakes working. */
    fun resolveLanAddress(): LanAddress? = resolveWifiIpv4()?.let { LanAddress(it, LanKind.WIFI) }
}
```

- `ConnectivityWifiAddressResolver` overrides `resolveLanAddress()`:
  `resolveWifiIpv4()?.let { WIFI } ?: HotspotAddressFinder.find(interfaces)?.let { HOTSPOT }`.
- `GsoundHttpServer.start()` binds to `resolveLanAddress()`. `Started` gains `kind`, and
  `WifiUnavailable` keeps its name, meaning "no LAN".
- `WifiGuard.check()` uses `resolveLanAddress()`. `WifiStatus.Available` gains
  `kind: LanKind = WIFI`, a default, so the existing call sites and tests keep compiling.
- The rename to `LanAddressResolver` is left out on purpose (a churn-free change). The KDoc
  notes the broadened meaning.

## 3. Hotspot detection

### 3.1 Pure core (unit-tested, HS-4.3)

`net/server/HotspotAddressFinder.kt`:

```kotlin
/** A platform-free view of one network interface. */
data class IfaceInfo(val name: String, val isUp: Boolean, val isLoopback: Boolean,
                     val isVirtual: Boolean, val ipv4: List<String>)

object HotspotAddressFinder {
    fun find(ifaces: List<IfaceInfo>): String?
}
```

**Rules** (HS-1.2):
- Keep interfaces that are `isUp && !isLoopback && !isVirtual`, whose name is not in the
  exclude list (§3.2), and that have at least one site-local IPv4.
- **Preference** among the survivors: first, names matching the known hotspot patterns
  `^(ap|swlan|softap)\d*$` or `^wlan[1-9]\d*$`; then any other survivor. Within a group, keep
  the interface enumeration order.
- **Site-local check:** 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16. Link-local 169.254/16 and
  CGNAT 100.64/10 (often mobile data) are excluded.

### 3.2 Excluded interface name prefixes

- **Mobile data:** `rmnet`, `ccmni`, `pdp`, `v4-rmnet`, `clat`.
- **VPN and tunnels:** `tun`, `ppp`, `ipsec`, `wg`.
- **Other:** `dummy`, `ifb`, `p2p` (Wi-Fi Direct), `rndis` / `usb` (USB tethering) and
  `bt-pan` (Bluetooth tethering). The last three are out of scope.

These are prefix matches, case-insensitive.

### 3.3 Android adapter

In `ConnectivityWifiAddressResolver`:

```kotlin
private fun interfaces(): List<IfaceInfo> = runCatching {
    NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().map { ni ->
        IfaceInfo(ni.name, ni.isUp, ni.isLoopback, ni.isVirtual,
            ni.inetAddresses.toList().filterIsInstance<Inet4Address>().map { it.hostAddress!! })
    }
}.getOrDefault(emptyList())
```

- It needs only INTERNET, which is already declared. Android 11+ restricts hardware (MAC)
  addresses, but not IP addresses.
- It runs at each `start()`, never cached (HS-1.3).

## 4. Guidance and UI (HS-3)

- `WifiGuard.GUIDANCE_MESSAGE` becomes: "Connect this phone and your watch to the same Wi-Fi
  network, or connect the watch to this phone's hotspot."
- `SyncState.Running` gains `networkKind: LanKind? = null`, which `SyncOrchestrator` fills from
  `Started.kind`.
- `SyncScreen.LanAddress` shows `"Wi-Fi · host:port"` or `"Hotspot · host:port"`. It falls back
  to the bare URL when the kind is null.
- Logging: `HTTP server started at <url> (HOTSPOT)` and
  `startSync: serving on HOTSPOT 10.42.0.1`. These are not sensitive.

## 5. Tests

- **`HotspotAddressFinderTest` (JVM):**
  - typical `ap0 10.42.0.1` plus `rmnet_data0 100.71.x` → `10.42.0.1`;
  - `swlan0 192.168.43.1` → that address;
  - only mobile data or VPN → null;
  - a down interface → ignored;
  - link-local or CGNAT addresses → ignored;
  - a hotspot-pattern interface preferred over another site-local one;
  - a 172.16/12 boundary case;
  - an empty list → null.
- **Resolver choice:** Wi-Fi present → WIFI, even when a hotspot interface exists (HS-2.2).
  Wi-Fi absent and hotspot present → HOTSPOT. Neither → null. The interface list is made
  injectable, a constructor-default lambda, to test this on the JVM.
- **`WifiGuardTest` / `GsoundHttpServer` tests:** `Available(kind = HOTSPOT)` path; the
  guidance message text.
- **`SyncOrchestratorTest`:** `Running.networkKind` is propagated; the existing Wi-Fi tests are
  unchanged (HS-4.1).
- **Sync screen:** a Compose JVM test (`SyncContentTest`) for the `Hotspot · …` label.

## 6. Manual verification (hardware)

1. Phone hotspot on (2.4 GHz, WPA2), the watch joined to it (saved in Garmin Connect), and the
   phone's own Wi-Fi off.
2. Tap Sync. Expect:
   - logcat: `HTTP server started at http://10.x.x.1:8080 (HOTSPOT)`;
   - `Gsound.HTTP --> GET /media/…` lines;
   - the Sync screen shows `Hotspot · …`;
   - the watch log shows `DL: progress` up to the full size.
3. Hotspot off and Wi-Fi off, then tap Sync. Expect the new guidance message.
4. The phone on home Wi-Fi with the hotspot off: unchanged behaviour (regression check).

## 7. Risks

- **Vendor interface names:** these vary. Mitigated by the "any other site-local survivor"
  fallback and by logging the chosen interface name.
- **Client isolation on the hotspot:** some OEM hotspots isolate clients from each other, but
  the phone itself, as the gateway, is reachable. To be confirmed in manual step 2.
- **Wi-Fi + hotspot concurrency:** Wi-Fi is preferred (HS-2.2). If the watch is on the
  hotspot while the phone is also on Wi-Fi, the sync fails until Wi-Fi is turned off. This is
  documented, and a setting is out of scope.
