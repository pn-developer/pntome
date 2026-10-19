# GsoundG (Garmin Connect IQ) — Technical Design

## 1. Overview

GsoundG is a Connect IQ **Audio Content Provider (ACP)** app written in **Monkey C**. It
pairs with GsoundA, syncs music over Wi-Fi via HTTP into its encrypted media sandbox, keeps
stats, and plays music through the native media player with basic transport controls. This
realizes `requirements.md` and conforms to `../gsound-overview/product-overview.md`.

## 2. Platform facts that shape the design

- An ACP app plugs into the **native media player**; it does **not** render its own audio
  pipeline. We supply content via `Toybox.Media` (`ContentDelegate` → `ContentIterator` →
  `ContentRef`), and the system plays and controls it.
- Media is downloaded during the system-driven **sync** flow (`Communications.SyncDelegate`),
  which activates Wi-Fi and calls `onStartSync()`; we download with
  `Communications.makeWebRequest(...)` using an **audio response type** and store into the
  encrypted sandbox. No unencrypted audio touches the filesystem.
- `Application.Storage` values are limited (~8 KB each); use a **flat key structure**, one
  entry per track, never a single large nested table.
- The app is launched by the system in one of several **modes** (standalone / sync config /
  playback config / playback). `AudioContentProviderApp` exposes hooks for each.
- API Level 3.0 minimum; device list restricted to music-capable fēnix models.

## 3. App type, manifest, permissions

`manifest.xml`:
- `<iq:application type="audio-content-provider-app" entry="GsoundGApp" minApiLevel="3.0.0" id="<SHARED_APP_UUID>">`
  — the `id` UUID is the shared constant GsoundA uses to resolve the app.
- `<iq:products>`: `fenix6spro`, `fenix6pro`, `fenix6xpro`, `fenix6prosolar`, and fēnix 8
  product ids (`fenix8*` — exact ids filled from the installed SDK device list).
- `<iq:permissions>`: `Communications` (web requests + phone app messages). Media/background
  capabilities come with the ACP type.

## 4. Project layout

```
GsoundG/
├── manifest.xml
├── monkey.jungle
├── resources/
│   ├── drawables/ (provider icon, launcher icon)
│   ├── strings/
│   ├── layouts/
│   └── menus/
└── source/
    ├── GsoundGApp.mc            # AudioContentProviderApp subclass + mode routing
    ├── protocol/
    │   ├── Messages.mc          # message type constants + JSON (de)serialize
    │   └── Protocol.mc          # version, validation
    ├── pairing/
    │   ├── PairingManager.mc    # state, code gen/verify, persist record
    │   ├── PairingView.mc       # show code + confirm/reject
    │   └── PairingDelegate.mc
    ├── sync/
    │   ├── GsoundSyncDelegate.mc  # Communications.SyncDelegate
    │   ├── Downloader.mc          # makeWebRequest per track, retry, store
    │   ├── Reconciler.mc          # dedupe + delete-missing
    │   └── SyncConfigView.mc
    ├── playback/
    │   ├── GsoundContentDelegate.mc  # Media.ContentDelegate
    │   ├── FolderIterator.mc         # Media.ContentIterator over a folder
    │   ├── PlaybackConfigView.mc     # pick folder/track
    │   └── FolderListView.mc
    ├── stats/
    │   ├── StatsStore.mc        # counts + lastSyncAt, STATS message
    │   └── StatsView.mc
    ├── comm/
    │   └── ControlChannel.mc    # Communications phone message in/out
    └── store/
        └── Repo.mc              # Application.Storage flat-key index helpers
```

## 5. Application entry & mode routing

`GsoundGApp extends Application.AudioContentProviderApp`:
- `getInitialView()` — standalone launch → a hub view (status, stats, pairing entry).
- `getSyncConfigurationView()` → `SyncConfigView` (choose/confirm what to sync; here it
  mostly confirms the pending manifest and triggers sync).
- `getPlaybackConfigurationView()` → `FolderListView` / `PlaybackConfigView`.
- `getContentDelegate()` → `GsoundContentDelegate`.
- `getSyncDelegate()` → `GsoundSyncDelegate`.
- `getProviderIconInfo()` → provider icon for the music-apps list.

`onStart()` loads persisted state (pairing, stats, track index) via `Repo`.

## 6. Control channel (comm/ControlChannel.mc)

- Register with `Communications.registerForPhoneAppMessages(callback)`.
- Incoming: parse JSON (Monkey C dictionaries), check `v` and `pairingId`; dispatch by `type`
  to `PairingManager` (`PAIR_REQUEST`, `PAIR_CONFIRM`, `UNPAIR`), `SyncController`
  (`SYNC_MANIFEST`), or `StatsStore` (`PING`).
- Outgoing: `Communications.transmit(dict, options, listener)` for `PAIR_CONFIRM`,
  `SYNC_STATUS`, `STATS`, `PONG`.
- Drop messages from unpaired peers except an initial `PAIR_REQUEST` (Req 2.6, 8.4).
- Keep payloads small; if a manifest exceeds message limits, store only `serverUrl` + count
  and fetch the full list from `GET /manifest` (Req 3.2).

## 7. Pairing (pairing/*)

`PairingManager` state: `IDLE → AWAITING_CONFIRM → PAIRED | REJECTED | TIMEOUT`.
- On `PAIR_REQUEST`: store temp `{code, pairingId, secret, peerName}`, show `PairingView`
  with the 6-digit `code`.
- On user **Confirm**: send `PAIR_CONFIRM {pairingId}`, persist `PairingRecord` to
  `Application.Storage` key `pairing`.
- On user **Reject**/timer expiry: discard temp values.
- `UNPAIR`: delete `pairing` key.
- If GsoundG initiates (optional in v1): generate code/id/secret and send `PAIR_REQUEST` to
  the phone; same confirm flow.

## 8. Sync (sync/*)

### 8.1 SyncController + GsoundSyncDelegate
- `SYNC_MANIFEST` arrival → `SyncController` persists pending `{serverUrl, tracks}` and
  requests the system sync (the user starts it from the sync-config flow; Wi-Fi handled by
  the system).
- `GsoundSyncDelegate extends Communications.SyncDelegate`:
  - `isSyncNeeded()` → true when a pending manifest differs from the stored index.
  - `onStartSync()` → kicks off `Downloader` over the pending tracks.
  - `onCancelSync()` → stop after the current track; keep completed tracks (Req 4.8).

### 8.2 Downloader
For each track not already present (dedupe by `id`, Req 4.4):
```
options = {
  :method => Communications.HTTP_REQUEST_METHOD_GET,
  :headers => { "X-Gsound-Secret" => secret },
  :responseType => Communications.HTTP_RESPONSE_CONTENT_TYPE_AUDIO,   // audio download
  :mediaEncoding => Media.ENCODING_MP3,                              // per track mime
  :fileDownloadProgressCallback => method(:onProgress),
  :context => trackId
}
Communications.makeWebRequest(serverUrl + "/media/" + trackId, null, options, method(:onDownloaded))
```
- `onDownloaded(responseCode, data, context)`:
  - `200` → obtain a media id and record it in the track index (`Repo.putTrack`).
  - `401` → abort sync with auth error → prompt re-pair (Req 8.2).
  - `404` → skip + count (Req 8.3); continue.
  - other → bounded retry, then record failure + continue (Req 4.7).
- Report progress: `notifySyncProgress(pct)` and periodic `SYNC_STATUS` to phone (Req 4.3).
- On completion: `Reconciler.deleteMissing(manifest)` (Req 4.5), update `StatsStore`,
  `notifySyncComplete(error?)`, send final `SYNC_STATUS` + `STATS` (Req 4.6).
- Guard storage-full → report error (Req 4.9).

### 8.3 Track index & dedupe (store/Repo.mc)
- Flat keys: `trk:<id>` → small dict `{ mediaId, folder, title, artist, album, mime }`.
- `idx` → array of track ids (kept small; if large, chunk across `idx:0`, `idx:1`, …).
- `folders` → array of distinct folder names (for playback + folder count stat).

## 9. Playback (playback/*)

- `FolderListView` lists synced folders (from `folders` index); selecting a folder opens
  `PlaybackConfigView` listing that folder's tracks, or starts playback immediately.
- Starting playback: build a `FolderIterator` (a `Media.ContentIterator`) over the folder's
  `ContentRef`s and call `Media.startPlayback(firstRef, playbackSettings)` or hand control
  to the system via the content delegate.
- `GsoundContentDelegate extends Media.ContentDelegate`:
  - `getContentIterator()` → returns the current `FolderIterator`.
  - supplies a `Media.PlaybackProfile` enabling **next/previous** skip (Req 6.4) and metadata
    display; the native player renders **play/pause, next, previous** controls.
  - handles end-of-folder → stop (Req 6.5); optional loop flag.
  - receives playback reporting callbacks (used only for liveness/stats in v1).
- `FolderIterator` yields `Media.ContentRef(mediaId, Media.CONTENT_TYPE_AUDIO)` built from the
  track index; `next()`/`previous()` walk the folder's id list; exposes metadata via
  `ContentRef` so titles show in the player.
- Empty state when no media synced (Req 6.6).

## 10. Persistence (store/Repo.mc) (Req 7)
- `Application.Storage` only; flat keys, ≤ 8 KB per value:
  - `pairing` → `{ pairingId, peerName, secret, createdAt }`
  - `stats` → `{ fileCount, folderCount, lastSyncAt }`
  - `trk:<id>`, `idx[...]`, `folders[...]` as in §8.3.
- Never log `secret`.

## 11. Error handling (Req 8)
- Wrap every system-entry method so an exception can't crash the app in any launch mode.
- Map HTTP/web errors to user-facing sync status; preserve existing media on failure.
- Validate control messages (version, pairingId, required fields); ignore invalid/unpaired.

## 12. Testing strategy
- **Simulator-first:** use the Connect IQ simulator with simulated phone messages (paste
  `PAIR_REQUEST` / `SYNC_MANIFEST` JSON) and a local HTTP server (GsoundA or a stub) reachable
  from the simulator to exercise download, dedupe, reconciliation, and playback.
- Unit-ish logic (protocol parse/validate, reconciler, id dedupe, flat-key store) factored
  into pure Monkey C functions testable in the simulator harness.
- **Hardware:** sideload the `.prg` to a music-capable fēnix (6 Pro / 8), verify provider
  appears under Music Providers, run a real Wi-Fi sync from GsoundA, verify native-player
  controls (play/pause/next/prev) and stats.

## 13. Build & deploy
- Build device `.prg` with `monkeyc -d <device> -f monkey.jungle -o GsoundG.prg -y <dev-key>`.
- Iterate in the simulator; sideload to hardware by copying `GsoundG.prg` into `GARMIN/APPS/`
  (and `.set` into `GARMIN/APPS/SETTINGS/` if settings are added). Remove via Garmin Express /
  Connect IQ mobile app.

## 14. Risks / open items
- Exact fēnix 8 product ids and per-model audio encodings must be taken from the installed SDK
  device definitions.
- `HTTP_RESPONSE_CONTENT_TYPE_AUDIO` download semantics and the returned media handle differ
  slightly across SDK versions; pin to a known-good SDK and verify the `onDownloaded` data
  shape in the simulator before hardware.
- Message-size limits on the control channel → rely on the `/manifest` HTTP fallback for large
  libraries.
