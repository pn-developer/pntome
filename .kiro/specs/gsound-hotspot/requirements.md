# Gsound — Sync over the Phone's Hotspot — Requirements

## Introduction

Today a sync only starts when the phone is **connected to a Wi-Fi network**: the HTTP
server binds to the Wi-Fi address of the phone's *active* network
(`ConnectivityWifiAddressResolver`). When the phone instead **provides a hotspot** and the
watch is connected to it:
- the active network is mobile data (or none), so the resolver returns `null`;
- `WifiGuard` therefore reports Unavailable, and the sync is refused with "Connect both this
  phone and your watch to the same Wi-Fi network."

The hotspot (tethering) interface isn't exposed as a `Network` through `ConnectivityManager`
at all, so the current resolver can't find its address even in principle.

This spec adds the phone's hotspot as a supported LAN for syncing. It extends
`../gsound-android/requirements.md` (Req 5.1, 5.7, 5.8) and `../gsound-overview/product-overview.md`
§5.2. Requirement ids are prefixed **HS**.

The scope is GsoundA only. The watch app, the protocol and the HTTP routes are unchanged.

## Glossary

- **Station Wi-Fi**: the phone is a client of a Wi-Fi network (today's supported case).
- **Hotspot**: the phone provides a Wi-Fi access point (tethering), and the watch is its
  client.
- **Serving address**: the IPv4 address the HTTP server binds to and advertises as
  `serverUrl` in `SYNC_MANIFEST`.

## Requirements

### HS-1 — Find the hotspot address

**User story:** As a user without a Wi-Fi network nearby, I want to sync while the watch is
connected to my phone's hotspot.

#### Acceptance Criteria

1. WHEN the phone has no usable station Wi-Fi address THEN GsoundA SHALL look for a hotspot
   address among the phone's network interfaces.
2. A hotspot candidate SHALL be:
   - an interface that is up, not loopback and not virtual;
   - with a site-local (private) IPv4 address: 10/8, 172.16/12 or 192.168/16;
   - whose name is not a mobile-data, VPN or other excluded interface (design §3.2).
3. The address SHALL be resolved again at every server start. Android 11+ randomizes the
   hotspot subnet, so it must never be cached across syncs.
4. Resolution SHALL need no new Android permission (INTERNET and ACCESS_NETWORK_STATE are
   already declared) and SHALL never throw. Any failure means "no hotspot address".

### HS-2 — Choose the serving address

#### Acceptance Criteria

1. The serving address SHALL be chosen in this order:
   1. the **station Wi-Fi** address, when available (unchanged behaviour);
   2. else the **hotspot** address, when available;
   3. else none. The sync is refused (HS-3.1).
2. WHEN both station Wi-Fi and a hotspot are active (Wi-Fi + hotspot concurrency) THEN the
   station Wi-Fi address SHALL be used. The phone can't know which network the watch joined.
   A user choice is out of scope for v1 (see §Out of scope).
3. The server SHALL bind only to the chosen address, never to `0.0.0.0` (unchanged security
   posture). The `X-Gsound-Secret` check stays as is.
4. The chosen network kind (Wi-Fi or Hotspot) SHALL be logged and exposed with the address.

### HS-3 — User-facing guidance and display

#### Acceptance Criteria

1. WHEN neither address is available THEN the sync SHALL be refused with updated guidance:
   "Connect this phone and your watch to the same Wi-Fi network, or connect the watch to this
   phone's hotspot."
2. While a sync runs, the Sync screen SHALL show the kind and the address, e.g.
   `Wi-Fi · 192.168.1.42:8080` or `Hotspot · 10.42.0.1:8080` (extends Req 5.8).
3. The documentation SHALL describe the watch-side hotspot setup:
   - save the hotspot as a Wi-Fi network for the watch in Garmin Connect;
   - use the **2.4 GHz** band (fēnix 6 Wi-Fi is 2.4 GHz only, to be confirmed);
   - use **WPA2-Personal**, not WPA3-only (to be confirmed for fēnix 6);
   - the hotspot normally needs mobile data, because the watch may check internet access.

### HS-4 — Robustness

#### Acceptance Criteria

1. Station-Wi-Fi behaviour SHALL stay exactly as today when the phone is on Wi-Fi. Existing
   tests stay green.
2. Losing the chosen network mid-sync SHALL behave as a lost Wi-Fi network does today
   (transfer errors surface through the existing paths). There is no automatic switch to
   another network during a sync.
3. The choice logic SHALL be unit-testable without Android, using a fake interface list.

## Out of scope (v1)

- A user setting to prefer the hotspot when both Wi-Fi and a hotspot are active.
- Advertising several addresses in the manifest (a protocol change).
- USB and Bluetooth tethering (they have no watch use case).
- Starting the hotspot from the app (needs system permissions).
