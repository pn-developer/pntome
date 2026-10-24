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
    │   ├── SyncController.mc      # SYNC_MANIFEST handler + pending-manifest store (task 6)
    │   ├── GsoundSyncDelegate.mc  # Communications.SyncDelegate (task 7)
    │   ├── Downloader.mc          # makeWebRequest per track, retry, store (task 7)
    │   ├── Reconciler.mc          # dedupe + delete-missing (task 7)
    │   ├── SyncConfigView.mc      # + SyncConfigDelegate (confirm manifest, trigger sync)
    │   └── MimeEncoding.mc        # map track.mime -> Media.ENCODING_* (task 7)
    ├── playback/
    │   ├── GsoundContentDelegate.mc  # Media.ContentDelegate (task 9)
    │   ├── FolderIterator.mc         # Media.ContentIterator over a folder (task 9)
    │   ├── PlaybackConfigView.mc     # pick folder/track (task 9)
    │   └── FolderListView.mc         # + FolderListDelegate (task 9)
    ├── stats/
    │   ├── StatsStore.mc        # counts + lastSyncAt, STATS message
    │   └── StatsView.mc
    ├── comm/
    │   └── ControlChannel.mc    # Communications phone message in/out
    └── store/
        └── Repo.mc              # Application.Storage flat-key index helpers
```

## 5. Application entry & mode routing

`GsoundGApp extends Application.AudioContentProviderApp`. The system launches an ACP app in
one of several **modes** and calls a different hook for each. The hook set is fixed by the
SDK base class (verified against `connectiq-sdk-lin-9.2.0` and the official ACP template):

- `getSyncConfigurationView()` → `[SyncConfigView, SyncConfigDelegate]` — confirms the pending
  manifest and triggers the system sync. **Must return a `[view, delegate]` pair.**
- `getPlaybackConfigurationView()` → `[FolderListView, FolderListDelegate]` (which may push
  `PlaybackConfigView`). **Must return a `[view, delegate]` pair.**
- `getContentDelegate(args)` → `GsoundContentDelegate` (`Media.ContentDelegate`).
- `getSyncDelegate()` → `GsoundSyncDelegate` (`Communications.SyncDelegate`) or `null`.
- `getProviderIconInfo()` → `Media.ProviderIconInfo(Rez.Drawables.ProviderIcon, COLOR_BLUE)`.

> **Do NOT implement `getInitialView()`.** `AudioContentProviderApp` does not define it; an ACP
> app is never launched through a standalone initial view, and overriding `AppBase.getInitialView()`
> here crashes the app at launch ("Null Reference Error / Failed invoking <symbol>"). This was
> already removed from the code; the design must not reintroduce it.

Every hook is wrapped in `try/catch` with a safe fallback so no launch mode can crash (Req 8.5).

`onStart(state)` restores persisted state (pairing, stats, track index) via `Repo`, then
constructs the single `ControlChannel`, registers message handlers (`PING` today; `SYNC_MANIFEST`
and stats handlers wire in via `setHandler` in tasks 6/8), and calls `register()`. `onStop(state)`
persists pairing + stats via `Repo`. The `ControlChannel` instance is held on the app
(`_control`) so handlers can transmit replies for the whole app lifetime.

> **Cleanup owed:** `GsoundGApp.mc` still carries `selftest43()` / `[SCRATCH-4.3]` runtime
> instrumentation (and a matching `ControlChannel.selftestInbound` / `System.println` lines)
> marked "REMOVE after task 4.3". These must be deleted as part of finishing task 4 /
> hardening (task 10) — see tasks.md 4.4 and 10.4.

## 6. Control channel (comm/ControlChannel.mc) — AS BUILT

`ControlChannel` is already implemented (task 4.2). It is a **handler registry + I/O gateway**;
feature modules plug in rather than editing it. Public API (the contract tasks 5–8 depend on):

- `initialize()` — `_handlers = {}`.
- `register()` — `Communications.registerForPhoneAppMessages(method(:onPhoneMessage))`
  (try/catch so an unsupported platform can't crash launch).
- `setHandler(type as String, handler as Method)` / `clearHandler(type as String)` — register
  or remove the `Method(msg as Dictionary) as Void` for a message `type`. **This is how new
  features attach** (e.g. `control.setHandler(Messages.TYPE_SYNC_MANIFEST, method(:onManifest))`).
- `transmit(payload as Dictionary, options as Dictionary?, listener as ConnectionListener?)` —
  wraps `Communications.transmit`; substitutes a `NoopConnectionListener` when `listener` is
  null (the SDK requires non-null). Used to send `PAIR_CONFIRM`, `SYNC_STATUS`, `STATS`, `PONG`.
- `onPhoneMessage(message)` — SDK callback: reads `message.data` (decoded JSON = Dictionary),
  runs `accept(msg)`, and on success `dispatch(msg)`. Whole body try/catch (Req 8.4/8.5).

Inbound gate (`accept`): (1) `Protocol.isValid(msg)` structural check (`v == 1`, known `type`,
`pairingId` present/non-empty except for `PAIR_REQUEST`); (2) `PAIR_REQUEST` always allowed;
(3) otherwise the `pairingId` must match `Repo.loadPairing()` — **read fresh on every message**
so unpair/re-pair takes effect immediately (Req 2.6, 8.4). `dispatch` looks up the handler by
`Protocol.typeOf(msg)` and invokes it; no handler = silent no-op.

Message vocabulary (protocol/Messages.mc, as built): type constants `TYPE_SYNC_MANIFEST`
(inbound, no builder), `TYPE_SYNC_STATUS`, `TYPE_STATS`, `TYPE_PING`/`TYPE_PONG`, `TYPE_PAIR_*`,
`TYPE_UNPAIR`; builders `buildSyncStatus(pairingId, state, done, total, bytesDone, error)`,
`buildStats(pairingId, fileCount, folderCount, lastSyncAt)`, `buildPong(pairingId)`, and the
pairing builders. Field-key constants exist for status/stats payloads.

> **Gap to close (task 6):** there are **no `FIELD_*` constants for the per-track manifest
> keys** (`id`, `folder`, `title`, `artist`, `album`, `durationMs`, `sizeBytes`, `mime`,
> `path`) nor for `serverUrl`/`tracks` parsing on the inbound side beyond `FIELD_SERVER_URL`/
> `FIELD_TRACKS`. Task 6 adds per-track field constants and uses `Protocol.getString` /
> `getNumber` / `getArray` / `getDict` to parse `SYNC_MANIFEST` defensively.

Cleanup: `onPhoneMessage` and `selftestInbound` still contain `[SCRATCH-4.3]` `System.println`
instrumentation — remove when finishing task 4 / hardening (tasks 4.4, 10.4).

### 6.1 Large-manifest fallback (Req 3.2)
The phone-app message channel has a payload ceiling. If `SYNC_MANIFEST.tracks` is empty or the
message looks truncated, the watch keeps only `serverUrl` (+ expected count if present) and
fetches the authoritative list from `GET /manifest` at sync start (§8). `GET /manifest` returns
the identical `ManifestTrack` shape, so the downloader consumes one representation either way.

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

## 8. Sync (sync/*) — TO BUILD (tasks 6 + 7)

This is the piece that actually puts music on the watch; it is **not yet implemented** (only
`GsoundSyncDelegateStub` exists). It spans manifest intake (task 6) and the delegate/downloader
(task 7).

### 8.0 Wire shapes this consumes (authoritative)
`SYNC_MANIFEST` (A→G) carries **`serverUrl` and `tracks` only — no `secret`**:
```
{ "v":1, "type":"SYNC_MANIFEST", "pairingId":"<uuid>",
  "serverUrl":"http://192.168.1.42:8080",
  "tracks":[ ManifestTrack, ... ] }
```
Each `ManifestTrack` (same shape from the message and from `GET /manifest`):
```
{ "id":"<opaque url-safe hash>", "folder":"Workout", "title":"...",
  "artist":"..."?, "album":"..."?, "durationMs":215000, "sizeBytes":5123456,
  "mime":"audio/mpeg", "path":"/media/<id>" }
```
The watch already holds the **secret** from pairing (`Repo.loadPairing().secret`); it is never
re-sent on the message and must go only in the `X-Gsound-Secret` HTTP header. The download URL
for a track is `serverUrl + track.path` (i.e. `serverUrl + "/media/" + id`). **Use `serverUrl`
verbatim** — the port is not always 8080 (phone falls back to another port if 8080 is taken).

### 8.1 SyncController + manifest intake (task 6)
- Register `control.setHandler(Messages.TYPE_SYNC_MANIFEST, method(:onManifest))` at app start.
- `onManifest(msg)`: parse defensively with `Protocol.getString(FIELD_SERVER_URL)` /
  `Protocol.getArray(FIELD_TRACKS)` and per-track `Protocol.getDict`/`getString`/`getNumber`;
  drop malformed entries (missing `id`/`path`/`mime`). Persist a **pending manifest**
  `{ serverUrl, tracks:[{id,folder,title,artist,album,mime,sizeBytes}] }` to `Application.Storage`
  (chunk if large). If `tracks` is empty/truncated, keep `serverUrl` and set a "fetch at sync
  start" flag → §6.1 HTTP fallback.
- Requesting the system sync: on a valid manifest, make `isSyncNeeded()` return true and surface
  the pending count in `SyncConfigView`; the user confirms and the system drives Wi-Fi + calls
  `onStartSync()`.

### 8.2 GsoundSyncDelegate (task 7) — replaces GsoundSyncDelegateStub
`class GsoundSyncDelegate extends Communications.SyncDelegate`:
- `isSyncNeeded() as Boolean` → true when a pending manifest exists AND it differs from the
  stored index (something to add, or missing tracks to delete). The stub returns false.
- `onStartSync() as Void` → load the pending manifest (or fetch `GET /manifest` for the fallback
  case), compute the work set, and start the `Downloader`. If no pairing/secret, finish with an
  error immediately.
- `onCancelSync() as Void` → stop after the in-flight track; keep already-downloaded tracks
  (Req 4.8); report via a final `SYNC_STATUS`.

### 8.3 Downloader (task 7)
Walk the manifest; **dedupe by `id`** — skip tracks whose `id` is already in `Repo.loadIndex()`
(Req 4.4). For each remaining track:
```
options = {
  :method               => Communications.HTTP_REQUEST_METHOD_GET,
  :headers              => { "X-Gsound-Secret" => secret },   // from Repo pairing, never logged
  :responseType         => Communications.HTTP_RESPONSE_CONTENT_TYPE_AUDIO,  // media download
  :mediaEncoding        => <map from track.mime>,             // e.g. audio/mpeg -> Media.ENCODING_MP3
  :context              => trackId
}
Communications.makeWebRequest(serverUrl + track.path, null, options, method(:onDownloaded))
```
`onDownloaded(responseCode, data, context)` — one track per call, sequential (start the next
only after the current resolves, to bound memory and simplify cancel):
- **200** → obtain the media handle/id from `data`, then
  `Repo.putTrack(id, { mediaId, folder, title, artist, album, mime })` (this also adds the id to
  the chunked index and the distinct `folders` list). Increment `done`; `notifySyncProgress`.
- **401** → stop the sync; report an auth error so the phone prompts re-pair (Req 8.2). The
  secret came from pairing; a 401 means the pairing/secret is stale.
- **404** → the source file vanished or the id is unknown mid-sync → skip + count, continue with
  the next track (Req 8.3, mirrors the phone's per-file 404 behaviour).
- **other / transport error** → bounded retry (small fixed count with backoff); on exhaustion
  record the failure and continue (Req 4.7). Note: `makeWebRequest` does not expose HTTP Range,
  so partial resume across app restarts is not attempted in v1 — a retry re-fetches the track.
- Advertise progress to the phone with periodic `SYNC_STATUS` via
  `control.transmit(Messages.buildSyncStatus(pairingId, state, done, total, bytesDone, null), ...)`
  (Req 4.3). `state` is a human label; the **phone treats it as opaque** and decides terminal
  state purely from the counts/error (see §8.4).

### 8.4 Completion / reconciliation / stats (task 7)
- After the work set drains, run `Reconciler.deleteMissing(manifest)`: for every id in the index
  **not** in the manifest, `Repo.deleteTrack(id)` (removes the track entry, drops it from the
  index, rebuilds `folders`) — Req 4.5.
- Update stats via `StatsStore` (task 8): `fileCount = index size`,
  `folderCount = folders size`, `lastSyncAt = now`; persist with `Repo.saveStats`.
- Finish: `Communications.notifySyncComplete(errorOrNull)` (null on success), then send a
  **final `SYNC_STATUS` with `done == total` and no `error`** followed by a `STATS` message.
  **This `done >= total` with no error is the phone's success signal** — the phone's
  `SyncOrchestrator` resolves `Completed` from the counts, not from the `state` label. On failure
  send `SYNC_STATUS` with a non-null `error`. Always include the correct `pairingId` or the phone
  drops the message.
- Storage-full guard: if `putTrack`/download fails for space, stop and report a storage error
  (Req 4.9); keep what was already stored.

### 8.5 Track index & dedupe (store/Repo.mc) — AS BUILT
`Repo` already provides everything the downloader needs; do not reinvent it:
- `trk:<id>` → `{ mediaId, folder, title, artist, album, mime }` via `putTrack`/`getTrack`/`deleteTrack`.
- Chunked `idx` (≤100 ids/chunk, `idx:count` holds the chunk count) via `loadIndex`/`saveIndex`;
  `putTrack` auto-dedupes the id into the index.
- `folders` → distinct folder names, kept in sync by `putTrack` (incremental add) and
  `deleteTrack`/`rebuildFolders` (prune when a folder's last track is removed).
- `mediaId` is the key link to playback (§9): the content iterator rebuilds `Media.Content`
  from the stored `mediaId`.

## 9. Playback (playback/*) — TO BUILD (task 9)

Not yet implemented; today `GsoundContentDelegateStub` returns an always-empty
`EmptyContentIterator`, so the native player shows an empty library. Task 9 replaces the stub
with real views + a content delegate/iterator backed by the synced `Repo` data.

### 9.1 Config views (replace StubViews.mc)
- `FolderListView extends WatchUi.View` + `FolderListDelegate extends WatchUi.BehaviorDelegate`
  — list synced folders from `Repo.loadFolders()`; empty state when none (Req 6.6). Selecting a
  folder pushes `PlaybackConfigView` (that folder's tracks via `Repo.loadIndex` filtered by the
  track entry's `folder`) or starts playback of the folder immediately.
- `getPlaybackConfigurationView()` returns `[FolderListView, FolderListDelegate]` (the required
  `[view, delegate]` pair). `getSyncConfigurationView()` similarly returns
  `[SyncConfigView, SyncConfigDelegate]` (task 7's confirm-and-sync screen).

### 9.2 Content delegate + iterator (replace StubDelegates.mc)
`class GsoundContentDelegate extends Media.ContentDelegate`:
- `getContentIterator() as Media.ContentIterator or Null` → a `FolderIterator` over the selected
  folder's track-id list (replaces `EmptyContentIterator`). Fall back to a safe empty iterator
  when nothing is synced so the player can't null-deref (Req 6.6, 8.5).

`class FolderIterator extends Media.ContentIterator` — implement the full surface the stub pins:
`canSkip()`, `get()`, `getPlaybackProfile()`, `next()`, `peekNext()`, `peekPrevious()`,
`previous()`, `shuffling()`.
- Backing data: an ordered array of track ids for the folder (from `Repo.loadIndex()` filtered
  by `getTrack(id).folder`) and a cursor.
- `get()/next()/previous()/peek*` return a `Media.Content` built from the stored **`mediaId`**
  in `trk:<id>` (the handle recorded by the downloader in §8.3), carrying title/artist/album
  metadata so the player shows track info.
- `getPlaybackProfile()` reuses the stub's profile: `playbackControls =
  [PLAYBACK_CONTROL_SKIP_BACKWARD, PLAYBACK_CONTROL_PLAYBACK, PLAYBACK_CONTROL_SKIP_FORWARD]`
  → native **play/pause, next, previous** (Req 6.4); `requirePlaybackNotification=false`,
  `playbackNotificationThreshold=1`, `attemptSkipAfterThumbsDown=false`, `skipPreviousThreshold=null`.
- `next()` past the last track / `previous()` before the first → end-of-folder stop (Req 6.5);
  `shuffling()` stays false in v1.

### 9.3 Starting playback
From `FolderListView`/`PlaybackConfigView`, build the `FolderIterator` for the chosen folder and
hand it to the system (the ACP content-delegate path), or call `Media.startPlayback` with the
first `Media.Content`. The native player then owns transport; the content iterator supplies
next/previous.

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
  device definitions. The `track.mime → Media.ENCODING_*` map (sync/MimeEncoding.mc) must cover
  the formats GsoundA can serve (mp3 always; optionally m4a/aac/wav/flac) and degrade safely for
  an unknown mime (skip + count rather than crash).
- `HTTP_RESPONSE_CONTENT_TYPE_AUDIO` download semantics and the returned media handle differ
  slightly across SDK versions; pin to a known-good SDK and verify the `onDownloaded` `data`
  shape (how the `mediaId`/handle is obtained) in the simulator before hardware.
- `Communications.makeWebRequest` does **not** expose HTTP `Range`, so even though the phone
  server supports `206 Partial Content`, the watch cannot resume a partially-downloaded track —
  a retry re-fetches the whole file. Acceptable for v1; revisit if large libraries stress it.
- Message-size limits on the control channel → rely on the `GET /manifest` HTTP fallback for
  large libraries (§6.1). Both paths yield the identical `ManifestTrack` shape.
- **Completion contract coupling:** the phone decides success from `SYNC_STATUS` counts
  (`done >= total`, no `error`), treating `state` as opaque. The watch must send a final status
  with `done == total` on success and set `error` on failure, always with the correct
  `pairingId`, or the phone will never resolve the sync (§8.4).
