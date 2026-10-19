# GsoundA (Android) — Implementation Tasks

Each task is incremental, test-backed, and references requirements from `requirements.md`.
Build/verify with Gradle after each major group. Check off as completed.

- [x] 1. Project scaffold & tooling
  - [x] 1.1 Create Android Gradle project `GsoundA/` (Kotlin DSL, version catalog
        `libs.versions.toml`), `compileSdk=35`, `targetSdk=35`, `minSdk=26`, Compose + Material 3.
  - [x] 1.2 Add dependencies: Compose BOM, Navigation-Compose, Hilt, Coroutines, DataStore
        (proto), Room, kotlinx.serialization, Ktor server CIO, Connect IQ Mobile SDK AAR.
  - [x] 1.3 Set up Hilt (`GsoundApplication`, `MainActivity`), base theme, bottom-nav shell
        with three empty destinations (Folders, Pairing, Sync).
  - _Reqs: 9.1_

- [x] 2. Domain models & shared protocol types
  - [x] 2.1 Define `Folder`, `Track`, `PairingRecord`, `SyncSummary`, `ScanProgress`,
        `SyncState`.
  - [x] 2.2 Define kotlinx.serialization message classes for the control channel and
        manifest (overview §5.1) with a `v`/`type` discriminator.
  - [x] 2.3 Implement `TrackIdHasher` (`sha1(path|size|lastModified)`) + unit tests.
  - _Reqs: 3.5; overview §5_

- [x] 3. Persistence layer
  - [x] 3.1 Room DB + `folders`/`tracks` entities + DAOs returning `Flow`; `FolderRepository`.
  - [x] 3.2 Proto DataStore schemas + `PairingStore`/`SettingsStore`/`SyncSummaryStore`.
  - [x] 3.3 Instrumented tests for DAOs and DataStore round-trips.
  - _Reqs: 8.1, 8.2_

- [x] 4. Folder selection & list management
  - [x] 4.1 SAF `OpenDocumentTree` launcher; take persistable read permission; add to Room
        with dedupe.
  - [x] 4.2 `FolderListScreen` + `FolderListViewModel`: list (name/count/size), FAB add,
        remove-with-confirm (release permission), empty state, Sync-disabled-when-empty.
  - [x] 4.3 Tests: dedupe logic, permission release on remove.
  - _Reqs: 1.1–1.5, 2.1–2.5_

- [x] 5. Folder scanning
  - [x] 5.1 `FormatFilter` (mp3 required; optional m4a/aac/wav/flac via settings flag) + tests.
  - [x] 5.2 `FolderScanner` using `DocumentsContract` bulk cursor; recursive enumeration;
        `MediaMetadataRetriever` metadata with filename fallback; emit `Flow<ScanProgress>`.
  - [x] 5.3 Cache tracks in Room; incremental rescan via lastModified; count skipped files.
  - [x] 5.4 Instrumented test against a seeded document tree.
  - _Reqs: 3.1–3.6_

- [x] 6. Garmin control channel (abstraction + SDK impl)
  - [x] 6.1 Define `ControlChannel` interface (send message, observe incoming, connection
        state) — hardware-independent for testing.
  - [x] 6.2 Implement `ConnectIqManager` using the Connect IQ Mobile SDK: init, resolve
        device + GsoundG `IQApp` (shared app UUID), register message listener, sendMessage.
  - [x] 6.3 JSON (de)serialization + validation (`v`, `type`, `pairingId`); drop unpaired-peer
        messages; outgoing retry with backoff. Unit tests with a fake channel.
  - [x] 6.4 Map SDK states (not installed / BT off / device disconnected) to actionable errors.
  - _Reqs: 4.1, 4.2, 10.2, 10.3_

- [x] 7. Pairing flow
  - [x] 7.1 `PairingViewModel` state machine (Idle→Discovering→AwaitingConfirm→Paired/Error);
        generate 6-digit code, `pairingId`, `sharedSecret`; send `PAIR_REQUEST`; timeout.
  - [x] 7.2 `PairingScreen`: show code + Confirm, require local Confirm AND received
        `PAIR_CONFIRM`; persist `PairingRecord`; show paired peer + Unpair (sends `UNPAIR`).
  - [x] 7.3 Unit tests for state machine (confirm, timeout, mismatch, unpair).
  - _Reqs: 4.3–4.8_

- [x] 8. Local HTTP server
  - [x] 8.1 `GsoundHttpServer` (Ktor CIO) bound to LAN interface; resolve Wi-Fi address;
        port selection with fallback.
  - [x] 8.2 Routes `/health`, `/manifest`, `/media/{trackId}` streaming from SAF with
        `Content-Type`, `Accept-Ranges`, and `Range` support; never buffer whole files.
  - [x] 8.3 Secret auth interceptor (`X-Gsound-Secret`) → 401; unknown id → 404; missing
        file → 404 and continue.
  - [x] 8.4 Ktor test-host tests: 401, 404, range/partial content, full download.
  - _Reqs: 5.1–5.8, 10.1, 10.4_

- [x] 9. Sync orchestration & foreground service
  - [x] 9.1 `SyncOrchestrator`: build manifest, start server, send `SYNC_MANIFEST`, collect
        `SYNC_STATUS`, write `SyncSummary` on completion, stop server; single-sync mutex.
  - [x] 9.2 `SyncForegroundService` (mediaSync) with WifiLock, progress notification + Cancel.
  - [x] 9.3 Wi-Fi availability guard with user guidance when devices aren't on same LAN.
  - [x] 9.4 Tests for orchestrator against fake `ControlChannel` + in-memory server.
  - _Reqs: 5.6, 5.7, 6.1–6.6, 8.3_

- [x] 10. Sync UI & stats
  - [x] 10.1 `SyncScreen` + `SyncViewModel`: Sync button gating, live progress, last-sync
        summary, LAN address display, retry/cancel.
  - [x] 10.2 Handle `STATS` messages → show watch file/folder counts + last-sync; "unknown"
        when unavailable; request fresh stats via `PING`/stats request.
  - [x] 10.3 UI tests for gating (disabled when unpaired/empty) and state rendering.
  - _Reqs: 6.2–6.5, 7.1–7.3_

- [x] 11. Permissions, logging, hardening
  - [x] 11.1 Declare only needed permissions (SAF persisted, ACCESS_NETWORK/WIFI_STATE,
        FOREGROUND_SERVICE + type, Bluetooth per SDK); runtime rationale + graceful degrade.
  - [x] 11.2 Redacting logger (never log secrets); validate/sanitize `trackId`.
  - [x] 11.3 End-to-end manual test checklist against Connect IQ simulator + real fēnix.
  - _Reqs: 8.4, 9.2, 9.3, 10.3_

- [ ] 12. Verification
  - [x] 12.1 Run full unit + instrumented test suites; `./gradlew assembleDebug` clean.
  - [~] 12.2 Deploy to phone via `adb`; smoke-test folder add → pair (simulator) → sync.
