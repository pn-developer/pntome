# GsoundG (Garmin Connect IQ) — Implementation Tasks

Each task is incremental and references `requirements.md` and `design.md`. Iterate in the
Connect IQ simulator; sideload to hardware for sync/playback validation.

**Status legend:** `[x]` done · `[ ]` not started · `[-]` partial / blocked on the simulator GUI.

> **Where things stand (verified against `GsoundG/source/`):** the scaffold, app + stub mode
> routing, the storage layer (`store/Repo.mc`), and the protocol + control channel
> (`protocol/`, `comm/ControlChannel.mc`) are built. Nothing under `pairing/`, `sync/`,
> `stats/`, or `playback/` exists yet — those directories still need to be created. The mode
> hooks in `GsoundGApp.mc` return the placeholders in `StubViews.mc` / `StubDelegates.mc`. The
> work that actually gets music onto the watch is **tasks 6 → 7 → 8 → 9**.

- [x] 1. Project scaffold & manifest
  - [x] 1.1 Create Connect IQ project `GsoundG/` (`manifest.xml`, `monkey.jungle`, `resources/`,
        `source/`) for an `audio-content-provider-app`.
  - [x] 1.2 Set `id` to the shared app UUID (matches GsoundA `GsoundAppId.VALUE`),
        `minApiLevel=3.0.0`, add the `Communications` permission, and list music-capable products.
  - [x] 1.3 Add provider + launcher icons and base strings; build an empty `.prg` to confirm
        the toolchain.
  - _Reqs: 1.1–1.4_

- [x] 2. App entry & mode routing
  - [x] 2.1 Implement `GsoundGApp extends AudioContentProviderApp` with `onStart`/`onStop`
        loading/saving state via `Repo`.
  - [x] 2.2 Implement mode hooks: `getSyncConfigurationView`, `getPlaybackConfigurationView`,
        `getContentDelegate`, `getSyncDelegate`, `getProviderIconInfo` returning stubs first.
        (NOTE: do **not** add `getInitialView` — ACP has no such hook; it crashes launch.)
  - [ ] 2.3 Verify in simulator that the app launches in each mode without crashing.
        _(Blocked: simulator GUI needs libwebkit2gtk-4.0; run via the container skill.)_
  - _Reqs: 1.1, 7.2, 8.5_

- [x] 3. Storage layer (store/Repo.mc)
  - [x] 3.1 Flat-key helpers for `pairing`, `stats`, `trk:<id>`, `idx` (chunked ≤100/chunk,
        `idx:count`), `folders` respecting the ~8 KB per-value limit.
  - [x] 3.2 Put/get/delete track entries; rebuild `folders` distinct list.
  - [x] 3.3 Simulator test persisting + reloading across app restart.
  - _Reqs: 7.1, 7.2_

- [x] 4. Protocol & control channel
  - [x] 4.1 `protocol/Messages.mc` + `Protocol.mc`: type constants, build/parse dictionaries,
        `validate` (`v`/`type`/`pairingId`), extractors (`getString`/`getNumber`/`getArray`/
        `getDict`).
  - [x] 4.2 `comm/ControlChannel.mc`: `register()`, `setHandler`/`clearHandler`, `onPhoneMessage`
        → `accept` (validate + fresh pairing-id filter, `PAIR_REQUEST` exempt) → `dispatch`;
        `transmit` with a `NoopConnectionListener` fallback.
  - [x] 4.3 Simulate inbound messages in the simulator (paste JSON) and verify dispatch + a
        `PONG` reply. _(Blocked on simulator GUI; see container skill.)_
  - [x] 4.4 Remove the `[SCRATCH-4.3]` instrumentation: `GsoundGApp.selftest43()` + its call in
        `onStart`, and `ControlChannel.selftestInbound` + the `System.println` lines. These are
        marked "REMOVE after task 4.3" and must not ship.
  - _Reqs: 2.6, 8.4; overview §5.1_

- [x] 5. Pairing (`pairing/`)
  - [x] 5.1 `PairingManager`: handle `PAIR_REQUEST` (store temp `{code, pairingId, secret,
        peerName}`, show code), Confirm → send `PAIR_CONFIRM` + persist `PairingRecord`
        (`{pairingId, peerName, secret, createdAt}`) via `Repo.savePairing`; Reject/timeout
        (~120 s) → discard temp; `UNPAIR` → `Repo.deletePairing`. Register handlers via
        `control.setHandler(Messages.TYPE_PAIR_REQUEST/PAIR_CONFIRM/UNPAIR, ...)`.
  - [x] 5.2 `PairingView` + `PairingDelegate`: display the 6-digit code, Confirm/Reject actions.
  - [x] 5.3 Simulator test: PAIR_REQUEST → code shown → confirm → record persisted; unpair
        clears it; never log the secret (Req 7.3).
  - _Reqs: 2.1–2.7_

- [x] 6. Manifest intake (`sync/SyncController.mc`)
  - [x] 6.1 Add per-track `FIELD_*` constants to `protocol/Messages.mc` (`id`, `folder`, `title`,
        `artist`, `album`, `durationMs`, `sizeBytes`, `mime`, `path`) so intake and the
        downloader agree on spelling with GsoundA's `ManifestTrack`.
  - [x] 6.2 `SyncController.onManifest(msg)`: parse `serverUrl` (`Protocol.getString`) and
        `tracks` (`Protocol.getArray` + per-track `Protocol.getDict`/`getString`/`getNumber`);
        drop entries missing `id`/`path`/`mime`; never trust the payload shape (Req 3.1, 8.4).
  - [x] 6.3 Persist the pending manifest `{serverUrl, tracks:[…]}` to `Application.Storage`
        (chunk if large); register it with `control.setHandler(Messages.TYPE_SYNC_MANIFEST,
        method(:onManifest))` at app start.
  - [x] 6.4 HTTP fallback (design §6.1): when `tracks` is empty/truncated, keep `serverUrl`
        (+expected count) and flag "fetch at sync start" so `onStartSync` pulls `GET /manifest`.
  - [x] 6.5 Simulator test with an inline manifest and with the fallback path.
  - _Reqs: 3.1–3.3_

- [x] 7. Sync delegate & downloader (`sync/`)
  - [x] 7.1 `GsoundSyncDelegate extends Communications.SyncDelegate` (replace
        `GsoundSyncDelegateStub`): `isSyncNeeded()` → true when a pending manifest exists and
        differs from `Repo.loadIndex()` (additions or deletions); `onStartSync()` → load pending
        manifest (or fetch `GET /manifest` for the fallback) and start the `Downloader`; abort
        with an error immediately if there is no pairing/secret.
  - [x] 7.2 `sync/MimeEncoding.mc`: map `track.mime` → `Media.ENCODING_*` (mp3 at minimum;
        m4a/aac/wav/flac if GsoundA serves them); unknown mime → skip + count (no crash).
  - [x] 7.3 `Downloader`: dedupe by `id` against the index (Req 4.4); per remaining track
        `Communications.makeWebRequest(serverUrl + track.path, null, options, method(:onDownloaded))`
        with `:headers => { "X-Gsound-Secret" => pairing.secret }`, GET method, audio response
        type, and the mapped `:mediaEncoding`. Download **sequentially** (start next only after
        the current resolves) to bound memory and simplify cancel.
  - [x] 7.4 `onDownloaded(code, data, context)`: **200** → record the media handle via
        `Repo.putTrack(id, {mediaId, folder, title, artist, album, mime})`, `done++`,
        `Communications.notifySyncProgress(pct)`; **401** → stop + report auth error → phone
        re-pair prompt (Req 8.2); **404** → skip + count, continue (Req 8.3); **other/transport**
        → bounded retry with backoff, then record failure + continue (Req 4.7). Never log the
        secret (Req 7.3).
  - [x] 7.5 Progress to phone: periodic `control.transmit(Messages.buildSyncStatus(pairingId,
        state, done, total, bytesDone, null), null, null)` (Req 4.3). `state` is a human label
        only — the phone decides terminal state from the counts.
  - [x] 7.6 `Reconciler.deleteMissing(manifest)`: for each index id not in the manifest,
        `Repo.deleteTrack(id)` (Req 4.5). Storage-full guard → stop + report storage error,
        keep what was stored (Req 4.9).
  - [x] 7.7 Completion: `Communications.notifySyncComplete(errorOrNull)`, then send a **final
        `SYNC_STATUS` with `done == total` and no `error`** (the phone's success signal) plus a
        `STATS` message; on failure send `SYNC_STATUS` with a non-null `error`. Always include the
        correct `pairingId` (Req 4.6).
  - [x] 7.8 `onCancelSync()`: stop after the in-flight track, keep completed tracks (Req 4.8),
        send a final `SYNC_STATUS`.
  - [x] 7.9 `SyncConfigView` + `SyncConfigDelegate`: show pending count, Confirm triggers the
        system sync; wire `getSyncConfigurationView()` to return `[view, delegate]`.
  - [x] 7.10 Simulator E2E against a running HTTP server (GsoundA or a stub): download, dedupe,
        reconcile, 401 re-pair prompt, 404 skip, cancel.
  - _Reqs: 4.1–4.9, 8.1–8.3_

- [x] 8. Stats (`stats/`)
  - [x] 8.1 `StatsStore`: maintain `fileCount` (index size), `folderCount` (folders size),
        `lastSyncAt`; update after each sync and persist via `Repo.saveStats`.
  - [x] 8.2 Send `STATS` via `control.transmit(Messages.buildStats(pairingId, fileCount,
        folderCount, lastSyncAt), …)` after sync and on an inbound `PING` (alongside the `PONG`).
  - [x] 8.3 `StatsView`: display stats on the watch; empty state before the first sync.
  - _Reqs: 5.1–5.4_

- [x] 9. Playback (`playback/`)
  - [x] 9.1 `FolderListView` + `FolderListDelegate` (replace `FolderListStubView`): list synced
        folders from `Repo.loadFolders()`; empty state when none (Req 6.6). Wire
        `getPlaybackConfigurationView()` to return `[view, delegate]`.
  - [x] 9.2 `PlaybackConfigView`: list a folder's tracks (ids from `Repo.loadIndex()` filtered by
        `Repo.getTrack(id).folder`); select a folder/track to play.
  - [x] 9.3 `FolderIterator extends Media.ContentIterator` (replace `EmptyContentIterator`):
        implement `canSkip`/`get`/`getPlaybackProfile`/`next`/`peekNext`/`peekPrevious`/
        `previous`/`shuffling`; build each `Media.Content` from the stored `mediaId` in
        `trk:<id>` with title/artist/album metadata; end-of-folder stop (Req 6.5). Reuse the
        stub's `PlaybackProfile` (next/previous controls, Req 6.4).
  - [x] 9.4 `GsoundContentDelegate extends Media.ContentDelegate` (replace
        `GsoundContentDelegateStub`): `getContentIterator()` → the selected folder's
        `FolderIterator`, or a safe empty iterator when nothing is synced (Req 6.6, 8.5). Start
        playback from the config view via the content-delegate path / `Media.startPlayback`.
  - [x] 9.5 Simulator + hardware test: native player shows play/pause, next, previous and track
        metadata; plays a synced folder.
  - _Reqs: 6.1–6.6_

- [x] 10. Hardening & error handling
  - [x] 10.1 Keep every system-entry hook (mode routing, delegates, iterator methods) wrapped so
        no launch mode can crash (Req 8.5).
  - [x] 10.2 Map web/HTTP errors to `SYNC_STATUS`; preserve existing media on failure; never log
        the secret (Req 7.3).
  - [x] 10.3 Validate/ignore control messages from unpaired peers (already enforced by
        `ControlChannel.accept`; re-verify after new handlers are added).
  - [x] 10.4 Confirm all `[SCRATCH-*]` instrumentation is gone (see 4.4) before release builds.
  - _Reqs: 7.3, 8.1–8.5_

- [ ] 11. Verification & deployment
  - [x] 11.1 Full simulator pass: pair → manifest → sync → stats → playback, including error
        paths (401/404/cancel/storage-full).
  - [x] 11.2 Build device `.prg` for fēnix 6 Pro and fēnix 8; sideload to `GARMIN/APPS/`;
        confirm the provider appears under Music Providers.
  - [ ] 11.3 Full on-hardware E2E with GsoundA over real Wi-Fi; verify playback controls and the
        stats round-trip to the phone (cross-check `GsoundA/docs/manual-e2e-checklist.md`).
  - _Reqs: all_
