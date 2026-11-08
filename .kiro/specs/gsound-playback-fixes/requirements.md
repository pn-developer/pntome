# Gsound — Playback Fixes (post first-sync milestone) — Requirements

## Introduction

Milestone `d4c2f47` was the first build where GsoundA → GsoundG sync transferred music to the
watch. Nothing played, though: the native player showed the track title with the timer stuck
at 0:00. This spec covers the work from that milestone to the first working playback on a
fēnix 6S Pro (`006-B3288-00`, CIQ id `fenix6spro`). It covers playback, media-cache hygiene,
the sync lifecycle, and the diagnostics used to find the faults.

It extends, and does not replace, `../gsound-garmin/requirements.md` (GsoundG) and
`../gsound-android/requirements.md` (GsoundA). Requirement numbers below are local to this spec
and are prefixed **PF**.

## Glossary

- **ACP**: Connect IQ Audio Content Provider app; it runs in separate short-lived processes per
  mode (sync, playback-config, playback, phone messages).
- **Media cache**: the system-managed, encrypted store behind `Toybox.Media`
  (`getCachedContentObj`, `getContentRefIter`, `deleteCachedItem`, `getCacheStatistics`).
- **Index**: GsoundG's persisted list of synced track ids (`Repo.loadIndex()`), each entry
  holding the `mediaId` returned by the download.
- **Orphan**: an audio item in the media cache whose id no indexed track references.
- **Debug build / release build**: `./build.sh --debug` vs `./build.sh` (see PF-6).

## Requirements

### PF-1 — Playable content handed to the native player

**User story:** As a user, I want a synced track to actually play when I select it, so that I
hear music, not a frozen 0:00 timer.

#### Acceptance Criteria

1. WHEN the player asks the iterator for a track (`get`/`next`/`previous`/`peekNext`/
   `peekPrevious`) THEN GsoundG SHALL return the `Media.Content` obtained from
   `Media.getCachedContentObj(new ContentRef(mediaId, CONTENT_TYPE_AUDIO))` **unmodified**.
   It SHALL NOT call `setMetadata` on it.
2. IF `getCachedContentObj` throws THEN GsoundG SHALL log the failure and fall back to
   `new Media.Content(ref, metadata)` built from the index entry. It SHALL never throw into
   the player.
3. The iterator SHALL implement `repeatMode()` explicitly (`REPEAT_MODE_OFF`), plus `canSkip()`
   (true) and `shuffling()` (false).
4. The playback profile SHALL list `PLAYBACK_CONTROL_PLAYBACK` first, then `PREVIOUS` and
   `NEXT`. It SHALL set `requirePlaybackNotification = true` with
   `playbackNotificationThreshold = 0`, so that `onSong` reports START/PAUSE/RESUME/STOP/
   COMPLETE, and `skipPreviousThreshold = 5`.
5. Playback SHALL walk one folder in index order from the selected start index, with no wrap
   at either end.
6. The playback process SHALL stay lean. It SHALL NOT walk the media cache on start, and it
   SHALL stay well below the device's ACP memory limit (512 KB on fēnix 6S Pro; measured peak
   ≈ 71 KB).

### PF-2 — Playback without explicit arguments

**User story:** As a user, I want the player's own Play button (or resume after connecting
headphones) to play my music, not an empty list.

#### Acceptance Criteria

1. WHEN the user picks a track in the playback-config list THEN GsoundG SHALL persist the
   selection (`folder`, `startIndex`) before calling `Media.startPlayback`.
2. WHEN `getContentDelegate(args)` receives `null` (or args without a folder) THEN GsoundG
   SHALL use the last persisted selection, ELSE the first synced folder, ELSE an empty
   iterator.
3. `resetContentIterator()` SHALL return the same folder's iterator from track 0.

### PF-3 — Downloaded audio is really stored

**User story:** As a developer, I want proof that each download put real bytes into the media
cache, so that "synced" means "playable".

#### Acceptance Criteria

1. Each audio download SHALL use `HTTP_RESPONSE_CONTENT_TYPE_AUDIO` with the `:mediaEncoding`
   mapped from the manifest MIME type, and SHALL register a `:fileDownloadProgressCallback`.
2. On a 200 response GsoundG SHALL store `data.getId()` as the track's `mediaId`.
3. (Debug build) GsoundG SHALL log the expected size, download progress, and the cache-size
   delta caused by each stored track.

### PF-4 — Media-cache hygiene

**User story:** As a user, I want removed folders and stale downloads to disappear from the
watch, so that what plays is exactly what the phone library contains.

#### Acceptance Criteria

1. WHEN a track is removed during reconcile THEN GsoundG SHALL delete its media-cache item
   (`Media.deleteCachedItem`) as well as its index entry.
2. WHEN a sync finishes without error THEN GsoundG SHALL delete every cached audio item no
   indexed track references (orphan sweep).
3. The "sync needed" decision SHALL also be true when orphans exist, so that a cleanup-only
   sync runs even when no track is new.
4. Before building a manifest, GsoundA SHALL rescan all configured folders. Removed or changed
   files then drop out of the manifest.
5. IF that rescan fails THEN GsoundA SHALL keep its cached library. It SHALL NOT treat the
   library as empty, because that would delete all music on the watch.

### PF-5 — Sync lifecycle robustness

**User story:** As a user, I want a sync to start, finish, and report status reliably, so
that the watch never hangs on "Synchronization in progress".

#### Acceptance Criteria

1. A single shared decision (`SyncNeed.isNeeded()`) SHALL drive both the system's
   `isSyncNeeded()` and GsoundG's auto-start on manifest receipt.
2. WHEN a manifest arrives and a sync is needed THEN GsoundG SHALL call
   `Communications.startSync()` from a deferred timer (500 ms), never inline in the comm
   callback.
3. WHEN a manifest arrives and no sync is needed THEN GsoundG SHALL reply
   `SYNC_STATUS` done = total = number of manifest tracks.
4. GsoundG SHALL implement the SDK's cancel callback `onStopSync()`. It SHALL cancel pending
   requests and complete the sync as cancelled.
5. Sync completion SHALL be idempotent: a late request callback after cancel or finish SHALL
   NOT complete the sync twice.
6. GsoundA SHALL throttle server-side transfer progress (logs and notification) to at most one
   update per second, plus every completed track and the final event.

### PF-6 — Diagnostics and build flavors

**User story:** As a developer, I want watch-side diagnostics I can read on the phone during
development, at no cost to everyday builds.

#### Acceptance Criteria

1. `./build.sh` SHALL produce a **release** build (`monkeyc -r`). In it, all `(:debug)` code
   (storage ring buffer, phone forwarding, media-cache snapshots) is stripped, and only
   `System.println` output remains.
2. `./build.sh --debug` SHALL produce a **debug** build. It keeps a 40-line log ring buffer in
   `Application.Storage` that survives across ACP processes.
3. In debug builds, a phone PING (GsoundA "Refresh stats") SHALL forward unsent lines to the
   phone. Forwarding SHALL send **one flat `LOG` message per line**
   (`{v,type:"LOG",pairingId,line}`), strictly one in flight, chained via `onComplete`. It
   SHALL never send from the short-lived sync process.
4. GsoundA SHALL write received watch lines to logcat under the tag `Gsound.Watch`.
5. The watch UI version footer SHALL show a `dbg` suffix on debug builds.
6. Logs SHALL never contain the pairing secret or the server URL.
7. `run_in_simulator.sh` SHALL build the debug flavor.

### PF-7 — Verification assets

#### Acceptance Criteria

1. The repo SHALL contain a known-clean test file (`test-media/gsound-test-tone.mp3`: 30 s,
   440 Hz, 128 kbps CBR, 44.1 kHz stereo, ID3v2.3 title/artist/album, no artwork). It
   separates "file problem" from "app problem".
2. Hardware acceptance: on fēnix 6S Pro with Bluetooth headphones, the test tone and real
   320 kbps MP3s (with embedded artwork) SHALL play. The debug log SHALL show `onSong` START
   followed by user PAUSE/RESUME/STOP events.
