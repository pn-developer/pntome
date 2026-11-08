# Gsound — Audiobook Folders — Design

Covers `requirements.md` (AB-1 … AB-8). The target is fēnix 6S Pro (`fenix6spro`, Connect IQ
**3.4.5** on part `006-B3288-00`), SDK 9.2.0, plus GsoundA (Room 2.6.1, Compose).

## 1. Platform facts and risks that shape the design

| Fact (checked in SDK 9.2.0 docs / device files) | Consequence |
|---|---|
| `Media.ActiveContent(ref, metadata, startPos)` is API 3.0.0 | Resume-at-position is possible on fēnix 6. |
| `PlaybackProfile.skipForwardTimeDelta/skipBackwardTimeDelta` and `SONG_EVENT_SKIP_FORWARD/BACKWARD` are API **4.2.4** | They are not available on fēnix 6 (3.4.5). We can offer `PLAYBACK_CONTROL_SKIP_FORWARD/BACKWARD`, which use the fixed 30 s default, but we get no event for them. |
| `onSong(id, event, playbackPosition)` reports the position in seconds | This is the only position signal. There is no periodic progress callback, so bookmarks are event-driven. |
| The player and its buttons are system-owned; only the first control may be a hotkey | Same constraint as PF-1.4. No custom resume UI inside the player. |
| ACP modes run in separate processes | Bookmarks and folder kind must live in `Application.Storage`, read by every mode. |

**Risks**, verified first in a hardware spike (task 1):

- **R1 — `ActiveContent` is a fresh object, not the system's cached `Content`.**
  - Playback started working only once we returned `getCachedContentObj()` unmodified
    (PF-1.1), and the fallback `new Media.Content(...)` was never proven on hardware. If a
    freshly built `ActiveContent` freezes at 0:00 the same way, resume needs a different
    approach.
  - Fallbacks, in order:
    1. `ActiveContent` built from `cached.getContentRef()` and `cached.getMetadata()`
       (system metadata, no edits);
    2. resume at chapter start only, with Resume labeled "Ch N" and no position.
- **R2 — position semantics.** A forum report says `playbackPosition` after a custom start
  is *relative to the start position*. Another reports the start position being inaccurate
  on audio over 10 minutes, with the iterator asking for the next file about 20 s early.
  - The spike logs the start offset against reported positions.
  - The bookmark logic stores `startOffset` with the session and adds it when needed
    (AB-5.4).
  - An early "complete" within the last 30 s is treated as a real completion.
- **R3 — stale bookmark on power-off** (AB-8.3). Accepted and documented.

**As built:**
- **R1:** implemented as fallback 1 (`ActiveContent` from the cached ref and metadata). If
  building it throws, the iterator plays the cached `Content` from 0:00. The debug log line
  `RESUME ActiveContent pos=…` shows which path ran.
- **R2:** solved without a hardware spike. `BookSession` measures the chapter's actual playing
  time and compares the reported position against both candidates, `p` (absolute) and `S+p`
  (relative). Whichever is closer wins, and the log shows `-> REL` or `-> ABS`. This is unit-tested
  for both cases.
- **R4 (found while testing):** for an id that isn't cached, `getCachedContentObj` can return
  `null` instead of throwing (seen in the simulator). Calling a method on it is a VM error
  that `try/catch` can't catch. The iterator now null-checks it and uses the fallback
  `Content`.

## 2. Overview

```
GsoundA                                         GsoundG
FolderEntity.isAudiobook ─┐                     trk:<id> { …, seq, book }
TrackEntity.fileName ─────┤ ManifestBuilder     bk:folders  [book folder names]
NaturalOrder(fileName) ───┴─► track {seq,book} ─► bm:<folder> { trk, pos, fin }
                                                FolderList → BookOptions (Menu2)
                                                  ├ Resume → startPlayback{folder,mode:"resume"}
                                                  ├ From start → {folder,startIndex:0}
                                                  └ Chapters → PlaybackConfigView → {folder,startIndex:i}
                                                FolderIterator (ordered by seq; ActiveContent at resume)
                                                ContentDelegate.onSong → Bookmarks.update
```

## 3. GsoundA

### 3.1 Data model and migration (AB-1.2–1.4, AB-2.1)

- `FolderEntity` gains `isAudiobook: Boolean = false`, and the domain `Folder` gains
  `isAudiobook`.
- `TrackEntity` gains `fileName: String = ""`, and the domain `Track` gains `fileName`.
- `GsoundDatabase` goes from `version = 1` to `version = 2`. The 2.json schema is exported.
- The migration is explicit and registered in `DatabaseModule`:
  ```sql
  ALTER TABLE folders ADD COLUMN isAudiobook INTEGER NOT NULL DEFAULT 0;
  ALTER TABLE tracks  ADD COLUMN fileName    TEXT    NOT NULL DEFAULT '';
  ```
  Using `Migration(1, 2)` keeps it reviewable. Room's `@ColumnInfo(defaultValue)` must match
  the SQL defaults.
- Existing rows get `fileName = ""` until the next scan. `SyncOrchestrator` already rescans
  every folder before building a manifest (PF-4.4), so the manifest always has file names.
- `FolderScanner.buildTrack` sets `fileName = child.displayName`.
- `ScanAndCacheUseCase` writes the folder with `folder.copy(...)`, so the flag is preserved.
  A test pins this.

### 3.2 Toggle UI (AB-1.1, 1.2)

- `FolderListItem` gains `isAudiobook`.
- The row's `supportingContent` gains a Material3 `FilterChip("Audiobook", selected =
  isAudiobook)`. It needs no extended-icons dependency and is accessible with its text label.
- `FolderListViewModel.onAudiobookToggled(id, value)` calls
  `FolderRepository.setAudiobook(id, value)`, backed by
  `FolderDao: UPDATE folders SET isAudiobook = :value WHERE id = :id`.
- The reactive flow repaints the row.

### 3.3 Natural order (AB-2.2, 2.5)

`domain/sync/NaturalOrder.kt` is a pure, unit-tested comparator:
- It tokenizes the file name with the extension stripped into digit runs and non-digit runs.
- Digit runs compare by numeric value (leading zeros ignored, arbitrary length via string
  compare after trimming zeros), then by run length.
- Text runs compare case-insensitively with `Locale.ROOT`.
- A shorter token list sorts first. A final tie-break on the full name keeps the order total.

Examples, in sorted order: `01 Intro` < `02 Chapter` < `10 Chapter`; `1-02` < `1-10` <
`2-01`; `Chapter 2` < `chapter 10`.

### 3.4 Manifest (AB-2.3, 2.4, AB-3.1, 3.2, 3.5)

- `ManifestTrack` gains two fields with defaults, so existing constructors and tests keep
  compiling:
  ```kotlin
  @SerialName("seq")  val seq: Int? = null,
  @SerialName("book") val book: Boolean? = null,
  ```
- `ManifestBuilder.build(tracks, folders)`:
  1. groups the tracks by `(folderId, relativeFolder)`;
  2. sorts each group by `NaturalOrder` on `fileName` (falling back to `title` when
     `fileName` is empty);
  3. assigns `seq` = 0..n-1;
  4. sets `book = folder.isAudiobook`;
  5. for book tracks, sets `title = ScanPaths.titleFromFileName(fileName)`.
- The manifest is emitted grouped by folder, in `seq` order, so the watch's insertion order
  matches even without `seq`.
- `SyncOrchestrator` passes the folders it already loads.
- `GsoundJson` has `encodeDefaults = true`, so `seq` and `book` are always present.
- The protocol stays `v: 1` (AB-3.2).
- `product-overview.md` §5.1 and §9 get updated.

## 4. GsoundG

### 4.1 Storage (AB-3.3, AB-5.1, AB-8.1)

| Key | Value | Writer |
|---|---|---|
| `trk:<id>` | adds `"seq" => Number`, `"book" => Boolean` | Downloader (new), MetaUpdater (§4.2) |
| `bk:folders` | `Array<String>` of book folder names | `Repo.putTrack`, `rebuildFolders`, MetaUpdater |
| `bm:<folder>` | `{ "trk" => trackId, "pos" => sec, "fin" => Bool, "at" => epochSec }` | `Bookmarks` (§4.5) |
| `pb:last` | adds `"mode" => "resume"` when it was a book resume | PlaybackConfig / BookOptions |

- `ManifestParser.cleanTrack` adds `putIfNumber(seq)` and `putIfBoolean(book)`, using a new
  helper, `putIfBoolean`. Messages get `FIELD_SEQ = "seq"` and `FIELD_BOOK = "book"`.
- The track entries grow by about 20 bytes. `MANIFEST_CHUNK_SIZE = 40` keeps chunks well under
  8 KB; this is re-checked with 120-char titles.
- `Repo.isBookFolder(name)` reads `bk:folders`. It's cheap enough for a per-frame `onUpdate`.
- `Repo.deleteTrack` and `rebuildFolders` recompute `bk:folders`. When a book folder
  disappears, its `bm:<folder>` is deleted (AB-5.6).

### 4.2 Metadata refresh on manifest (AB-3.4)

`SyncController.onManifest`, after `ManifestParser.cleanTracks`, calls the new
`MetaUpdater.apply(cleanTracks)`:
- For each manifest track whose `trk:<id>` exists, it updates `seq`, `book` and `title` when
  they differ.
- It then recomputes `bk:folders`.
- It writes only changed entries. It is bounded by the manifest size and never throws.

This runs in the process that received the manifest, before `SyncNeed`. A flag or order
change with no file change therefore needs no sync. The phone still gets `SYNC_STATUS` "up to
date" (PF-5.3).

### 4.3 Ordering (AB-2, shared helper)

The new `playback/FolderTracks.mc` has a single function, `orderedIds(folder) as
Array<String>`:
- It filters the index by `entry.folder == folder` (as today).
- It then sorts by `seq`, with entries without `seq` keeping index order after the sequenced
  ones. The sort is a stable insertion sort; n is small (chapters per book).

Both `PlaybackConfigView.refreshTracks` and `FolderIterator.buildIds` call it. This removes
today's duplicated filter.

Performance: `PlaybackConfigView` currently rebuilds its list on every `onUpdate`. It changes
to build in `onShow` and on demand. Per-frame storage reads of N entries are wasteful with
large books.

### 4.4 UI flow (AB-4)

```
FolderListView ──select──► music folder ─► PlaybackConfigView (track list, as today)
                         └► book folder ──► BookOptionsMenu (WatchUi.Menu2)
                                              [Resume  "Ch 3 · 12:40"]   (only if bookmark && !fin)
                                              [Play from start]
                                              [Chapters ›] ─► PlaybackConfigView(folder, chapterMode)
```

- **FolderListView:** book rows are prefixed with "Book · " (string resource
  `FolderListBookPrefix`). It is plain text, because device fonts have no book glyph, and a
  second line under the name would collide with the next row at the 30 px row spacing.
- **BookOptionsMenu:** uses `WatchUi.Menu2` + `Menu2InputDelegate` (API 3.0+) for native look
  and button handling.
  - The Resume sublabel uses the chapter's 1-based number in `seq` order and `mm:ss` (or
    `h:mm:ss`).
  - A menu is a fresh view, so it re-reads the bookmark whenever it is shown.
- **PlaybackConfigView in chapter mode:** labels are `"<n>. <title>"`.
- Every start path calls the shared `PlaybackStarter.start(folder, startIndex, mode)`. It
  saves `pb:last` and calls `Media.startPlayback(args)`.

### 4.5 Playback (AB-5, AB-6, AB-7)

**Finished book:** after the last chapter's COMPLETE, the iterator's `next()` returns `null`,
so the player stops (AB-5.3). The bookmark is marked `fin`, Resume is hidden, and Play from
start shows a "Finished" sub-label. A longer rewind after a long break (for example 30 s after
more than 20 min) is planned for later. The bookmark's `at` timestamp already supports it.

**Args**:
- `{ "folder", "startIndex" }` for start or chapter;
- `{ "folder", "mode" => "resume" }` for resume.

`getContentDelegate`:
- **args null:** falls back to `pb:last`, which may carry `mode: resume` (AB-6.2).
- **resume:** resolves the bookmark through `Bookmarks.resolve(folder, orderedIds)`, which
  returns `{ index, pos }`. It falls back to the next existing chapter when the bookmarked
  one is gone (AB-5.5).
- **result:** `new GsoundContentDelegate(folder, startIndex, startPos, isBook)`.

**FolderIterator**:
- **Ordered ids:** from `FolderTracks.orderedIds`.
- **First item:** the first item served at the initial cursor uses `startPos`:
  - `startPos > 0`: `new Media.ActiveContent(cached.getContentRef(), cached.getMetadata(),
    max(0, startPos - 5))` (R1 fallback 1);
  - otherwise: `getCachedContentObj(ref)` unmodified, as today.
  - After the first item, `startPos` is cleared, so later chapters and re-gets start at 0
    (AB-6.3).
- **Profile:**
  - **Music:** `[NEXT, PLAYBACK, PREVIOUS]`.
  - **Books:** `[NEXT, PLAYBACK, PREVIOUS, SKIP_BACKWARD, SKIP_FORWARD]`, with the 30 s
    defaults (AB-7).
  - **Both:** `skipPreviousThreshold = 5`, notifications on with threshold 0.

**Bookmark updates.** `GsoundContentDelegate.onSong` → `Bookmarks.onEvent(folder, trackId,
event, pos)`, books only:

| Event | Bookmark write |
|---|---|
| START (0) | `{trk, pos: sessionStart(trk), fin:false}`. `sessionStart` is the resume offset for the first chapter, else 0. |
| PAUSE (6), STOP (5), SKIP_NEXT (1), SKIP_PREVIOUS (2) | `{trk, pos: abs(pos)}`. `abs` adds the start offset when the reported position is relative (R2; decided by the spike and kept behind one flag). |
| COMPLETE (4) | next chapter in order at 0; if there is none, `fin: true` |
| RESUME (7), PLAYBACK_NOTIFY (3) | none |

- `contentRefId` is the mediaId. `Bookmarks` maps it to the track id through the iterator's
  ordered list, which the delegate holds.
- Every write is one `setValue` of a small dict, wrapped in try/catch (AB-8.2).

## 5. Error handling

- A missing or corrupt bookmark means no Resume item, and playback still works.
- A missing chapter falls back as in AB-5.5.
- If the ActiveContent build throws, the iterator falls back to the unmodified cached
  `Content` at 0:00 and logs it.
- If the menu fails to build, the app falls back to the chapter list (`PlaybackConfigView`).

## 6. Testing

- **GsoundA unit tests:**
  - `NaturalOrderTest`: prefixes, multi-part `1-02`, case, leading zeros, no digits, ties.
  - `ManifestBuilderTest`: seq per folder, book flag, filename titles for books, grouping
    order.
  - `FolderListViewModelTest`: the toggle.
  - `ScanAndCacheUseCaseTest`: the flag survives a rescan.
  - `MessageCodecTest`: `seq`/`book` round-trip, and an old payload without them still
    decodes.
- **GsoundA instrumented tests:**
  - a `MigrationTestHelper` test for 1→2 that keeps rows and defaults;
  - `FolderDaoTest` for `setAudiobook`;
  - `FolderScannerInstrumentedTest` for the `fileName` capture.
- **GsoundG:** strict `monkeyc -l 3` for debug and release, plus simulator runs:
  - book options with and without a bookmark;
  - chapter list order.
- **Hardware (fēnix 6S Pro):**
  - spike R1/R2 (task 1);
  - then the end-to-end checks: mark a book, sync, play from start, pause mid-chapter, leave,
    Resume (position ±5 s), complete a chapter, then the next bookmark at 0, then the last
    chapter, then finished.

## 7. Out of scope (v1)

- Nested folders and multi-book folders.
- Playback speed (not exposed to ACPs).
- Configurable skip size (needs CIQ 4.2.4).
- Syncing bookmarks back to the phone.
- Cue/chapter markers inside a single file.
