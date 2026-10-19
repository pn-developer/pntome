# GsoundG (Garmin Connect IQ) — Implementation Tasks

Each task is incremental and references `requirements.md`. Iterate in the Connect IQ
simulator; sideload to hardware for sync/playback validation. Check off as completed.

- [ ] 1. Project scaffold & manifest
  - [~] 1.1 Create Connect IQ project `GsoundG/` (`manifest.xml`, `monkey.jungle`, `resources/`,
        `source/`) for an `audio-content-provider-app`.
  - [~] 1.2 Set `id` to the shared app UUID (matches GsoundA), `minApiLevel=3.0.0`, add the
        `Communications` permission, and list music-capable products (fēnix 6 Pro variants,
        fēnix 8 ids from SDK device list).
  - [~] 1.3 Add provider + launcher icons and base strings; build an empty `.prg` to confirm
        the toolchain.
  - _Reqs: 1.1–1.4_

- [ ] 2. App entry & mode routing
  - [~] 2.1 Implement `GsoundGApp extends AudioContentProviderApp` with `onStart`/`onStop`
        loading/saving state via `Repo`.
  - [~] 2.2 Implement mode hooks: `getInitialView`, `getSyncConfigurationView`,
        `getPlaybackConfigurationView`, `getContentDelegate`, `getSyncDelegate`,
        `getProviderIconInfo` returning stubs first.
  - [~] 2.3 Verify in simulator that the app launches in each mode without crashing.
  - _Reqs: 1.1, 7.2, 8.5_

- [ ] 3. Storage layer (store/Repo.mc)
  - [~] 3.1 Flat-key helpers for `pairing`, `stats`, `trk:<id>`, `idx`, `folders` respecting
        the ~8 KB per-value limit (chunk `idx` if needed).
  - [~] 3.2 Put/get/delete track entries; rebuild `folders` distinct list.
  - [~] 3.3 Simulator test persisting + reloading across app restart.
  - _Reqs: 7.1, 7.2_

- [ ] 4. Protocol & control channel
  - [~] 4.1 `protocol/Messages.mc` + `Protocol.mc`: type constants, build/parse dictionaries,
        validate `v`/`type`/`pairingId`.
  - [~] 4.2 `comm/ControlChannel.mc`: `registerForPhoneAppMessages`, dispatch by type,
        `transmit` outgoing; drop unpaired-peer messages (except initial `PAIR_REQUEST`).
  - [~] 4.3 Simulate inbound messages in the simulator (paste JSON) and verify dispatch + a
        `PONG` reply.
  - _Reqs: 2.6, 8.4; overview §5.1_

- [ ] 5. Pairing
  - [~] 5.1 `PairingManager`: handle `PAIR_REQUEST` (store temp, show code), Confirm → send
        `PAIR_CONFIRM` + persist `PairingRecord`; Reject/timeout → discard; `UNPAIR` → delete.
  - [~] 5.2 `PairingView` + `PairingDelegate`: display 6-digit code, Confirm/Reject actions.
  - [~] 5.3 Simulator test: PAIR_REQUEST → code shown → confirm → record persisted; unpair
        clears it.
  - _Reqs: 2.1–2.7_

- [ ] 6. Manifest intake
  - [~] 6.1 `SyncController`: on `SYNC_MANIFEST` persist pending `{serverUrl, tracks}`; validate
        entries, ignore malformed.
  - [~] 6.2 HTTP fallback: if manifest too large, store `serverUrl`+count and fetch
        `GET /manifest` at sync start.
  - [~] 6.3 Simulator test with inline manifest and with fallback.
  - _Reqs: 3.1–3.3_

- [ ] 7. Sync delegate & downloader
  - [~] 7.1 `GsoundSyncDelegate extends Communications.SyncDelegate`: `isSyncNeeded`,
        `onStartSync`, `onCancelSync`.
  - [~] 7.2 `Downloader`: per-track `makeWebRequest` to `/media/{id}` with `X-Gsound-Secret`,
        audio response type + `mediaEncoding`; `onDownloaded` handling 200/401/404/other with
        bounded retry; store media id into index.
  - [~] 7.3 Progress: `notifySyncProgress` + periodic `SYNC_STATUS`; dedupe already-synced
        tracks (same id).
  - [~] 7.4 `Reconciler.deleteMissing(manifest)`; storage-full guard; final
        `notifySyncComplete` + `SYNC_STATUS` + `STATS`.
  - [~] 7.5 Simulator E2E against a running HTTP server (GsoundA or stub): download, dedupe,
        reconcile, 401 re-pair prompt, 404 skip.
  - _Reqs: 4.1–4.9, 8.1–8.3_

- [ ] 8. Stats
  - [~] 8.1 `StatsStore`: maintain fileCount/folderCount/lastSyncAt; update after sync; persist.
  - [~] 8.2 Send `STATS` after sync and on `PING`/stats request.
  - [~] 8.3 `StatsView`: display stats on the watch; empty state before first sync.
  - _Reqs: 5.1–5.4_

- [ ] 9. Playback
  - [~] 9.1 `FolderListView`: list synced folders from `folders` index; empty state when none.
  - [~] 9.2 `PlaybackConfigView`: list a folder's tracks; select folder/track to play.
  - [~] 9.3 `FolderIterator extends Media.ContentIterator`: yield `ContentRef`s with metadata;
        `next`/`previous` across the folder; end-of-folder stop.
  - [~] 9.4 `GsoundContentDelegate extends Media.ContentDelegate`: `getContentIterator`,
        `PlaybackProfile` enabling next/previous; start playback via `Media.startPlayback`.
  - [~] 9.5 Simulator + hardware test: native player shows play/pause, next, previous and track
        metadata; plays synced folder.
  - _Reqs: 6.1–6.6_

- [ ] 10. Hardening & error handling
  - [~] 10.1 Wrap all system-entry methods so no launch mode can crash the app.
  - [~] 10.2 Map web/HTTP errors to sync status; preserve existing media on failure; never log
        the secret.
  - [~] 10.3 Validate/ignore control messages from unpaired peers.
  - _Reqs: 7.3, 8.1–8.5_

- [ ] 11. Verification & deployment
  - [~] 11.1 Full simulator pass: pair → manifest → sync → stats → playback, including error
        paths (401/404/cancel/storage-full).
  - [~] 11.2 Build device `.prg` for fēnix 6 Pro and fēnix 8; sideload to `GARMIN/APPS/`;
        confirm the provider appears under Music Providers.
  - [~] 11.3 Full on-hardware E2E with GsoundA over real Wi-Fi; verify playback controls and
        stats round-trip to the phone.
  - _Reqs: all_
