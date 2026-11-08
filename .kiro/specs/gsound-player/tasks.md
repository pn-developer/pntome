# Gsound — Phone Player, Android Auto and Bookmark Sync — Implementation Tasks

Each task references `requirements.md` (PL-x) and `design.md` (§x).

**Status legend:** `[x]` done · `[ ]` not started · `[-]` partial / needs a manual check.

- [x] 1. Decisions and prerequisites
  - [x] 1.1 Confirm what the sync manifest sends as the folder name and that it matches the
        phone folder's display name. If not, add the folder id to the manifest and use it
        as the bookmark key (design R2).
  - [x] 1.2 Add Media3 (`exoplayer`, `session`, optionally `ui`) to `libs.versions.toml` and
        `app/build.gradle.kts` with pinned versions.
  - _Reqs: PL-1, PL-4.3; design §2, §9 R2_

- [x] 2. Phone playback service (spike, then feature)
  - [x] 2.1 Spike: `PlaybackService : MediaLibraryService` plays one SAF `content://` track
        in the background with a media notification (design R1). If it fails, switch the
        data source as described in R1.
  - [x] 2.2 `QueueBuilder`: queue from a folder (natural order for books, stored order for
        music), `MediaItem` ids = track ids, metadata (title, folder, default artwork).
  - [x] 2.3 Audio focus, becoming-noisy pause, wake mode, 30 s seek increments for books,
        unreadable-file skip with a message.
  - [x] 2.4 Manifest: service entry, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, Hilt wiring.
  - [x] 2.5 JVM tests for `QueueBuilder` order and ids.
  - _Reqs: PL-1.2, 1.5–1.7; design §2_

- [ ] 3. Phone player UI
  - [x] 3.1 `PlayerViewModel` + `MediaController`, `PlayerUiState`.
  - [x] 3.2 `PlayerScreen` (title, folder, position, seek, prev/next, ±30 s for books) and a
        mini-player on the Folders screen. Start playback from a folder or track row.
  - [x] 3.3 ViewModel and Compose tests (Robolectric, like the existing UI tests).
        (`PlayerScreenTest`, `FolderPlayButtonTest`; Folders entry now uses
        `connectIfRunning()` so the service is not bound until playback starts, PL-7.1.)
  - _Reqs: PL-1.1, 1.3, 1.4; design §2_

- [x] 4. Bookmark storage on the phone
  - [x] 4.1 `BookmarkEntity` + `BookmarkDao`, DB v3, `MIGRATION_2_3`, export `3.json`.
  - [x] 4.2 Delete bookmarks when a folder is removed or un-marked as an audiobook.
  - [x] 4.3 `BookmarkResolver` (rewind, finished, missing chapter) + unit tests.
  - [x] 4.4 Migration tests: JVM (from `2.json`) and instrumented `MigrationTestHelper`.
  - _Reqs: PL-3.4, 3.6, 3.7, PL-7.6; design §4.1, §4.3_

- [x] 5. Bookmark recorder
  - [x] 5.1 `BookmarkRecorder` listener: pause/stop, transition, seek, 15 s ticker, final write
        on destroy/task removal/disconnect. Books only. Skips unchanged values.
  - [x] 5.2 Chapter complete → next chapter at 0; last chapter → `finished`.
  - [x] 5.3 Non-blocking writes (conflated channel on IO).
  - [x] 5.4 JVM tests with a fake player: triggers, throttling, completion, non-books ignored.
  - _Reqs: PL-3.1–3.5; design §4.2_

- [x] 6. Android Auto
  - [x] 6.1 `automotive_app_desc.xml`, manifest meta-data.
  - [x] 6.2 `MediaLibrarySession.Callback`: root, `onGetChildren` with paging (Audiobooks,
        Music, Recently played, folders, chapters), `onGetItem`.
  - [x] 6.3 `onSetMediaItems`: expand a folder into a queue, start at the bookmark for books.
  - [x] 6.4 Custom layout with ±30 s for book queues.
  - [x] 6.5 Search/voice: title match, fallback to the last book.
  - [x] 6.6 Unit tests for the browse tree and the resume start; document DHU and developer
        mode steps in `INSTALL.md`.
  - [x] 6.7 Manual: DHU browse/play/skip/resume; then in the car (voice, steering wheel).
  - _Reqs: PL-2; design §3_

- [x] 7. Protocol: BOOKMARK message
  - [x] 7.1 Watch `Messages.mc`: `TYPE_BOOKMARK`, fields, `buildBookmark`, `isValidType`;
        `Protocol` extractors.
  - [x] 7.2 Phone: `Bookmark` message class and `MessageCodec` entry.
  - [x] 7.3 Tests: codec round trip (phone), builder/validation (`source/test`, watch);
        old payload / unknown `type` ignored.
  - _Reqs: PL-4.2, PL-7.5; design §5_

- [x] 8. Watch → phone (Refresh stats)
  - [x] 8.1 Watch `BookmarkSender`: one in flight, chained on complete, stops on error, guard
        against double runs. Called from `GsoundGApp.onPing` after STATS, before the debug
        log forwarding.
  - [x] 8.2 Phone `BookmarkSync`: collect `BOOKMARK`, validate pairing and folder, merge, store
        with `source = "watch"`.
  - [x] 8.3 Refresh stats UI: per-book "Ch N · mm:ss" and the source device.
  - [x] 8.4 Tests: phone sync with the fake `ControlChannel`; watch sender chain with a fake
        transmit; old watch (no bookmarks) still shows STATS.
  - _Reqs: PL-4; design §6_

- [x] 9. Merge rule
  - [x] 9.1 Kotlin `BookmarkMerge` and Monkey C `BookmarkMerge` with the same case table
        (newer wins, equal keeps local, future clamp, finished vs newer, idempotent).
  - [x] 9.2 `lastSentAt` bookkeeping so a taken bookmark is not echoed back.
  - [x] 9.3 Tests on both platforms using the same cases.
  - _Reqs: PL-6; design §8_

- [x] 10. Phone → watch
  - [x] 10.1 Phone: after Refresh stats and after a sync, send newer phone bookmarks
         sequentially; `markSent` on success.
  - [x] 10.2 Watch: `onBookmark` handler → validate (book folder, known track), merge, save
         with the incoming `at` (add an optional `at` argument to `Repo.saveBookmark`).
  - [x] 10.3 Live-session marker (`bm:live`) set/cleared by `BookSession`; ignore incoming
         bookmarks while live (with a 2 minute staleness limit).
  - [x] 10.4 Watch tests: apply rules (unknown folder/track, live marker, older bookmark),
         Resume label after apply.
  - _Reqs: PL-5, PL-6.6; design §7_

- [x] 11. Checkpoint and end-to-end
  - [x] 11.1 Build both apps; run JVM and watch unit tests; check the watch release memory
         headroom (PL-7.2).
  - [x] 11.2 Manual flow: listen on the watch, pause, Refresh stats → the phone shows the
         position; resume on the phone from it.
  - [x] 11.3 Manual flow: listen in the car, Refresh stats → the watch Resume shows the car
         position (±5 s rewind).
  - [x] 11.4 Manual: conflicting edits (both played since the last refresh) → the newest wins
         and the Refresh stats list explains which device it came from.
  - [x] 11.5 Update `INSTALL.md` (player, Android Auto setup, bookmark sync, limitations) and
         `product-overview.md` §5 (BOOKMARK message).
  - _Reqs: PL-4–PL-7_
