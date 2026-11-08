# Gsound — Playback Fixes — Design

Covers `requirements.md` (PF-1 … PF-7). Baseline: milestone `d4c2f47` (first music sync).
Target device: fēnix 6S Pro (`fenix6spro`), CIQ SDK 9.2.0.

## 1. Symptom timeline and root causes

The symptom was the same throughout ("no sound"), but it had several independent causes,
stacked on top of each other. Each one was found through the diagnostics in §6.

| # | Symptom on device | Root cause | Fix (section) |
|---|---|---|---|
| 1 | Player's own Play → nothing; log `args=null → EMPTY iterator` | `getContentDelegate(null)` produced an empty iterator | §3.2 last-selection fallback |
| 2 | Watch stuck on "Synchronization in progress" | `startSync()` issued while `isSyncNeeded()` was false → `onStartSync` never ran → no `notifySyncComplete` | §4.1 shared `SyncNeed` |
| 3 | `System Error: Error in comm callback` | `startSync()` called inline in the phone-message callback | §4.2 deferred timer |
| 4 | Cancel left sync hanging | SDK callback is `onStopSync`, not `onCancelSync` | §4.3 |
| 5 | Old folders ("Maly princ") still on the watch; 37 cached items for 3 tracks | deletes removed the index entry only, never the media item; phone manifest built from a stale library | §5 cache hygiene |
| 6 | Cache held refs but `size=0B` | stale/empty items from earlier broken syncs. New downloads proved to store full size (+8,683,520 B for an 8,671,223 B file) | §5 sweep + clean resync |
| 7 | **Real file selected, title shown, timer frozen at 0:00, no `onSong`** | most likely `setMetadata()` on the system's cached `Content` (see §3.1) | §3.1 hand Content over unmodified |
| 8 | Phone crash `IllegalArgumentException: 8 > -856573612` in `SerializedObject` | watch sent one LOG message carrying an Array of 20 lines from the dying sync process | §6.2 one flat line per message |
| 9 | Phone notification spam, dropped frames | server progress projected on every ~4 KB chunk | §4.4 throttle |

Ruled out by experiment, not by assumption:
- **Audio format:** a clean 128k test tone failed the same way, and the native player played
  the same files.
- **Headphones:** connected, and the native player worked.
- **Truncated transfer:** the watch progress callback reached the full size, and the cache
  grew by the file size.
- **Memory:** the playback process peaks at ≈71 KB of 508 KB.

## 2. Process model reminder

An ACP app runs as **separate processes**: phone-message, sync, playback-config and playback.
In-memory state never crosses them, so every cross-mode decision reads `Application.Storage`
through `Repo`. Consequences used below:
- "Is a sync needed?" must be computed from persisted state (§4.1).
- Logs must be persisted to be seen later (§6.1).
- `onStop` must not write back stale snapshots. It had clobbered pairing state, and was fixed
  before this milestone.

## 3. Playback (GsoundG `playback/*`, `GsoundGApp.getContentDelegate`)

### 3.1 Content construction — `FolderIterator.contentAt(i)`

```
ref    = new Media.ContentRef(entry.mediaId, Media.CONTENT_TYPE_AUDIO)
try    return Media.getCachedContentObj(ref)          // UNMODIFIED
catch  log; return new Media.Content(ref, metadataFromIndex)   // fallback
```

- **Why unmodified:** the system's cached `Content` already carries what the player needs,
  and it reads its metadata from the file's ID3 tags. Replacing that metadata
  (`cached.setMetadata(...)`) was the one step that differed from Garmin's reference flow.
  Removing it, together with the explicit `repeatMode()`, turned a frozen 0:00 into working
  playback. The two were changed in the same build. Memory and the cache walk were ruled out
  by the `mem=` log, so the cause is one of these two, and `setMetadata` is the likely one. A
  one-change bisect would confirm it (open item §8).
- Every path is wrapped, so a bad entry yields `null` (end of folder), never an exception
  into the player.
- Ordering is the same filter as `PlaybackConfigView`: the flat index, filtered by
  `entry.folder == folder`.

### 3.2 Delegate and arguments

- `PlaybackConfigView` calls `Repo.savePlaybackSelection(folder, startIndex)`, then
  `Media.startPlayback({folder, startIndex})`.
- `getContentDelegate(args)` uses `args` when it holds a String `folder`. Otherwise it falls
  back to `Repo.loadPlaybackSelection()` (key `pb:last`), then the first synced folder, then
  `null`, which gives `EmptyContentIterator`.
- `resetContentIterator()` resets the start index to 0 and rebuilds the iterator.
- `onSong`, `onShuffle` and `onRepeat` are logged. `onSong` events: 0 START,
  1 SKIP_NEXT, 2 SKIP_PREVIOUS, 3 PLAYBACK_NOTIFY, 4 COMPLETE, 5 STOP, 6 PAUSE, 7 RESUME.

### 3.3 Playback profile

`[NEXT, PLAYBACK, PREVIOUS]`. The first entry may become the device hotkey: bottom-left (DOWN)
on fēnix 6S Pro, confirmed on hardware with `PLAYBACK` first, where it toggled play. `NEXT`
first matches the built-in player's skip-next. That hotkey is the only physical-button
influence an ACP has. The player UI and the other buttons (including bottom-right BACK) are
system-owned, and no API remaps them. Play/pause and previous remain on-screen controls.
Also set: `requirePlaybackNotification = true`,
threshold 0 (so `onSong` is observable), `skipPreviousThreshold = 5`, and
`attemptSkipAfterThumbsDown = false`.

### 3.4 Memory budget

ACP memory limit on fēnix 6S Pro is 512 KB (`compiler.json`). Measured at start in the
simulator: release 48.7 KB, debug 60.6 KB. On device (debug build), the peak during `get()`
was ≈71 KB. The playback path performs no cache enumeration; `MediaDiag` runs only at the end
of a sync.

## 4. Sync lifecycle (GsoundG `sync/*`, GsoundA `domain/sync`)

### 4.1 `SyncNeed.isNeeded()` — single source of truth

The function returns true when a pending manifest exists AND any of these holds:
- the fetch-fallback flag is set with a server URL, or
- the manifest ids differ from the index (in either direction), or
- `Repo.hasOrphanedMedia()` is true.

It is used by both `GsoundSyncDelegate.isSyncNeeded()` and `SyncController` auto-start. Any
error degrades to `false`.

### 4.2 Auto-start on manifest (`SyncController`)

On `SYNC_MANIFEST`, the controller persists the manifest and then branches:
- **Sync needed:** a one-shot 500 ms `Timer`, then `Communications.startSync()`. A deferred
  start avoids "Error in comm callback".
- **Not needed:** `reportUpToDate()` sends `SYNC_STATUS` with done = total = the manifest
  count, then forwards pending debug log lines. This process is not about to exit, so
  forwarding is safe here.

### 4.3 Completion and cancel (`Downloader`, `GsoundSyncDelegate`)

- `onStopSync()` → `Communications.cancelAllRequests()` → `Downloader.finishCancelled()`.
- `finish()` is guarded by `_finished`, so a late HTTP callback cannot double-complete.
- On a clean finish, `finish()` runs these steps in order: reconcile deletions (§5.1), then
  `Repo.sweepOrphanedMedia()`, then stats, then the final `SYNC_STATUS`, then
  `notifySyncComplete`.

### 4.4 Phone-side progress throttle (`SyncOrchestrator.collectStatuses`)

A `ProgressEvent.Server` is projected (log, `SyncState.Running`, notification) only when:
- a track completed, or
- all tracks are done, or
- `SERVER_PROGRESS_INTERVAL_MS` (1000 ms) has elapsed.

Completion detection is unaffected, because the all-done event always passes the throttle.

## 5. Media-cache hygiene

### 5.1 Delete = index + media item

`Repo.deleteTrack(id)` deletes the media item first:
`Repo.deleteCachedMedia(mediaId)` → `Media.deleteCachedItem(new ContentRef(...))`. This is
best effort. It then removes the index entry and rebuilds the folder list.

### 5.2 Orphan sweep

`Repo.sweepOrphanedMedia()` walks `Media.getContentRefIter({:contentType => AUDIO})` and
deletes every ref whose id is not among the indexed `mediaId`s (`containsObject` compares
Number ids by value). It runs only after a clean sync, when the index matches the manifest.
`Repo.hasOrphanedMedia()` is the cheap read-only check that `SyncNeed` uses.

Observed on device: `orphan sweep: cached=37 kept=3 deleted=34`, and later
`cached=5 kept=5 deleted=0`.

### 5.3 Phone: rescan before manifest

`SyncOrchestrator` calls `LibraryRefresher.refreshAll()` (`ScanLibraryRefresher`, bound in
`ScanModule`) before it builds the manifest. A failed scan logs a warning and keeps the cached
library (PF-4.5). Covered by `SyncOrchestratorTest` with `FakeRefresher`.

## 6. Diagnostics

### 6.1 Watch log (`DebugLog.mc`)

`DebugLog.log(tag, msg)` behaves differently per build:
- **Both builds:** `System.println("Gsound.<tag>: <msg>")`. This reaches the simulator console
  or `GARMIN/APPS/LOGS/<PRG>.TXT`, but only if that file was created by hand.
- **Debug builds:** `store()` also appends `#<seq> t=<ms> <tag>: <msg>` (≤120 chars) to a
  40-line ring buffer. The keys are `dbg`, `dbg:seq` and `dbg:sent`.

Other helpers:
- `DebugLog.mem()` reports `used/total free`.
- `DebugLog.shape()` and `describe()` describe values and exceptions safely.
- `MediaDiag.snapshot()` logs the cache size, capacity and the first 3 ids. It is debug-only
  and a no-op in release builds.

### 6.2 Forwarding to the phone

- **Watch:** `DebugLog.sendPending` (debug) creates a `LogSender`, which sends one
  `{v,type:"LOG",pairingId,line}` per transmit after a 1.5 s delay. The next line goes only
  from `onComplete`. A line is marked sent only after its transmit completes. On `onError`
  the chain stops, and the remaining lines go on the next PING.
- **Triggers:** the PING handler and the up-to-date path only. The sync process exits too
  soon for forwarding, so it never forwards.
- **Phone:** `ControlMessage.WatchLogLine` (`@SerialName("LOG")`). `ConnectIqManager` logs
  each line under `Gsound.Watch`. Covered by `MessageCodecTest`.

### 6.3 Build flavors

The flavors use Monkey C's built-in `(:debug)` and `(:release)` annotations, applied to
functions, the `activeSender` var and the `LogSender` class.

| | `./build.sh` (release, `-r`) | `./build.sh --debug` |
|---|---|---|
| println | yes | yes |
| Storage ring + phone forwarding | stripped | yes |
| MediaDiag | no-op | yes |
| Footer | `v0.1.0 (<commit>)` | `v0.1.0 (<commit>) dbg` |
| fenix6spro `.prg` size | 52 KB | 193 KB |

`DebugLog.isDebugBuild()` exposes the flavor. `run_in_simulator.sh` builds `--debug`.

### 6.4 Device-side files

- `GARMIN/APPS/LOGS/<PRGNAME>.TXT` receives println output. The name must match the installed
  `.prg`, for example `GSOUNDG-FENIX6PRO.TXT`.
- `GARMIN/APPS/LOGS/CIQ_LOG.YAML` holds crashes. Its `Part-Number` identifies the device
  build target.

## 7. Verification performed

- **Phone:** `./gradlew :app:testDebugUnitTest :app:assembleDebug` passes: 252 tests,
  0 failures.
- **Watch:** strict type check (`monkeyc -l 3`) passes for both flavors on `fenix6spro`.
- **Simulator:** both flavors launch, and playback mode resolves without errors.
- **Hardware, fēnix 6S Pro with BT headphones:**
  - Downloads of 3.4–8.7 MB each were stored at full size.
  - The orphan sweep cleaned stale items.
  - The test tone and real albums played. The log shows `onSong` 0 (START), then user events
    6/7/5 (PAUSE, RESUME, STOP).

## 8. Open items

1. **Bisect PF-1.1 vs PF-1.3:** reintroduce `setMetadata` alone to confirm the root cause.
   This is optional, because the current code works.
2. **Fallback path:** `new Media.Content(ref, metadata)` is untested on hardware.
   `getCachedContentObj` has always succeeded so far.
3. **Mixed library:** the playback-config track list still shows titles from the index. If a
   file lacks ID3 tags, the player shows the system's fallback name.
4. **Release logging:** release builds still format log strings before `println`. That cost
   is small, but the calls could become `(:debug)` too if profiling shows a need.
5. **Hardware coverage:** extend the verification to fēnix 8 targets.
