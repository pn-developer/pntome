# Gsound — Sync over the Phone's Hotspot — Implementation Tasks

Each task references `requirements.md` (HS-x) and `design.md` (§x). All of it is GsoundA.
Run `cd GsoundA && ./gradlew :app:testDebugUnitTest :app:assembleDebug --console=plain -q`
after each task, and check that the existing tests stay green (HS-4.1).

**Status legend:** `[x]` done · `[ ]` not started · `[-]` partial / needs a manual check.

- [x] 1. Hotspot address detection (pure core)
  - [x] 1.1 Add `net/server/HotspotAddressFinder.kt` with `IfaceInfo` and `find(ifaces)`:
        - filter: up, not loopback, not virtual, excluded name prefixes (§3.2);
        - site-local IPv4 only (no 169.254/16, no 100.64/10);
        - prefer hotspot name patterns.
  - [x] 1.2 Add `HotspotAddressFinderTest` with the cases in design §5.
  - _Reqs: HS-1.1, 1.2, 1.4, HS-4.3; design §3.1, §3.2_

- [x] 2. Resolver: LAN address with kind
  - [x] 2.1 Add `LanKind`, `LanAddress` and
        `WifiAddressResolver.resolveLanAddress()` (with a default implementation).
  - [x] 2.2 `ConnectivityWifiAddressResolver`: override it (Wi-Fi first, else hotspot), with an
        injectable interface-list provider (defaults to the `NetworkInterface` adapter, §3.3).
        Never cache; never throw.
  - [x] 2.3 Tests for the Wi-Fi-preferred / hotspot / none choice using a fake provider.
  - _Reqs: HS-1.3, HS-2.1, 2.2; design §2, §3.3_

- [x] 3. Server and guard use the LAN address
  - [x] 3.1 `GsoundHttpServer.start()` binds to `resolveLanAddress()`, and
        `StartResult.Started` carries `kind`. It still binds only to that address (HS-2.3).
  - [x] 3.2 `WifiGuard.check()` uses `resolveLanAddress()`. `WifiStatus.Available` gets
        `kind` (default WIFI). Update `GUIDANCE_MESSAGE` (HS-3.1).
  - [x] 3.3 Update or extend `WifiGuardTest` and the server tests: the HOTSPOT path and the
        message text.
  - _Reqs: HS-2.3, 2.4, HS-3.1; design §2, §4_

- [x] 4. Orchestrator + UI
  - [x] 4.1 `SyncState.Running.networkKind`, filled by `SyncOrchestrator`, and log
        `serving on <KIND> <ip>`.
  - [x] 4.2 `SyncScreen` shows `Wi-Fi · host:port` or `Hotspot · host:port`.
  - [x] 4.3 Tests: `SyncOrchestratorTest` (kind propagated) and `SyncContentTest` (label).
  - _Reqs: HS-2.4, HS-3.2; design §4_

- [x] 5. Docs
  - [x] 5.1 Add a "Sync over the phone's hotspot" section to INSTALL.md (the watch-side setup
        from HS-3.3, the Wi-Fi-preferred rule and the concurrency caveat from design §7).
  - [x] 5.2 product-overview §5.2: the serving address may be the hotspot gateway.
  - _Reqs: HS-3.3_

- [ ] 6. Manual verification (hardware, design §6)
  - [~] 6.1 Hotspot only. Expect:
        - `HTTP server started at … (HOTSPOT)`;
        - `/media` 200s;
        - the `Hotspot · …` label;
        - the watch `DL: progress` reaching the full size.
  - [~] 6.2 No network → the new guidance message.
  - [~] 6.3 Home Wi-Fi: unchanged behaviour (regression).
  - [~] 6.4 Confirm the fēnix 6 band and security limits (2.4 GHz, WPA2), then update HS-3.3
        and INSTALL.md.
