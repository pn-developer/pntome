# Gsound — Audiobook Folders — Implementation Tasks

Each task references `requirements.md` (AB-x) and `design.md` (§x).

**Status legend:** `[x]` done · `[ ]` not started · `[-]` partial / needs a manual check.

- [-] 1. Resume at a position (risks R1, R2)
  - [x] 1.1 Instead of a separate spike path, resume ships as designed (R1 fallback 1:
        `ActiveContent` from the cached ref and metadata, falling back to the cached Content
        at 0:00). The debug log shows `RESUME ActiveContent pos=… start=…`.
  - [x] 1.2 R2 is handled in code: `BookSession` chooses relative vs absolute by measured
        playing time. Unit-tested for both cases.
  - [ ] 1.3 Hardware confirmation on the fēnix 6S Pro (manual test M5/M6). If `ActiveContent`
        freezes at 0:00 like the old `setMetadata` case, switch Resume to chapter-only
        (R1 fallback 2).
  - _Reqs: AB-5.4, AB-6.1; design §1, §4.5_

- [x] 2. GsoundA data model + migration
  - [x] 2.1 `isAudiobook` (FolderEntity/Folder), `fileName` (TrackEntity/Track), with defaults.
  - [x] 2.2 DB v2, `MIGRATION_1_2`, registered in DatabaseModule, `schemas/…/2.json` exported.
  - [x] 2.3 `FolderScanner` records `fileName`. `FolderDao.setAudiobook` / `updateAggregates`.
        The rescan now updates only the aggregates, so a toggle made during a scan is never
        lost.
  - [x] 2.4 Tests:
        - JVM migration test: a real v1 DB built from `1.json`, migrated and validated by Room.
        - Instrumented `MigrationTestHelper` test (compiles; runs on a device).
        - Rescan and flag tests in ScanAndCacheUseCaseTest.
  - _Reqs: AB-1.2–1.4, AB-2.1; design §3.1_

- [x] 3. GsoundA toggle UI
  - [x] 3.1 "Audiobook" `FilterChip` on each folder row, `onAudiobookToggled`.
  - [x] 3.2 `FolderListViewModelTest`.
  - _Reqs: AB-1.1, 1.2; design §3.2_

- [x] 4. GsoundA natural order + manifest fields
  - [x] 4.1 `NaturalOrder` + `NaturalOrderTest`.
  - [x] 4.2 `ManifestTrack.seq/book`. `ManifestBuilder` groups and sorts, assigns `seq`,
        sets `book`, and uses file-name titles for books. `SyncOrchestrator` passes the
        audiobook folder ids.
  - [x] 4.3 ManifestBuilderTest and MessageCodecTest (round-trip, map types, old payload).
  - [x] 4.4 `product-overview.md` §5.1 and §9 updated.
  - _Reqs: AB-2.2–2.5, AB-3.1, 3.2, 3.5; design §3.3, §3.4_

- [x] 5. GsoundG storage + intake
  - [x] 5.1 `FIELD_SEQ/FIELD_BOOK`. `ManifestParser` copies them (Long → Number).
  - [x] 5.2 Downloader persists `seq`/`book`. Repo additions:
        - `bk:folders` and `isBookFolder`;
        - `bm:<folder>` bookmarks (with `idx` and `at`);
        - bookmarks dropped when a folder stops being a book.
  - [x] 5.3 `MetaUpdater.apply` on manifest arrival (control channel and HTTP fetch paths).
  - _Reqs: AB-3.3, 3.4, AB-5.6, AB-8.1; design §4.1, §4.2_

- [x] 6. GsoundG ordering helper
  - [x] 6.1 `FolderTracks.orderedIds`: stable merge sort by `seq`, shared by the list, the
        iterator and the bookmarks.
  - [x] 6.2 PlaybackConfigView builds its list on show, not every frame. Chapter labels are
        `"<n>. <title>"`.
  - _Reqs: AB-2.3, AB-4.3; design §4.3_

- [x] 7. GsoundG book options UI
  - [x] 7.1 FolderListView shows the "Book · " prefix. Book folders open BookOptionsMenu.
  - [x] 7.2 `BookOptionsMenu` (Menu2): Resume (sub-label "Ch N · m:ss", first), Play from
        start ("Finished" sub-label once done), Chapters.
  - [x] 7.3 `PlaybackStarter.startAt/resume`, used by all start paths.
  - _Reqs: AB-4.1–4.5; design §4.4_

- [x] 8. GsoundG playback + bookmarks
  - [x] 8.1 `getContentDelegate`: `mode: resume` args and the `pb:last` resume fallback, via
        `Bookmarks.resolve` (5 s rewind, missing-chapter fallback, finished → no resume).
  - [x] 8.2 FolderIterator: the start position on the resumed chapter only, the book profile
        with ±30 s skips, and the null-safe `getCachedContentObj` (R4).
  - [x] 8.3 `BookSession.onSong`: start, pause, stop, skip, complete and finished.
  - _Reqs: AB-5, AB-6, AB-7; design §4.5_

- [-] 9. Verification
  - [x] 9.1 GsoundA: 276 unit tests passing + assembleDebug + androidTest compile.
  - [x] 9.2 GsoundG:
        - strict build (`-l 3`) for debug, release and unit-test;
        - 9 Monkey C unit tests passing in the simulator (`ciq-sim.sh run <prg> fenix6spro -t`);
        - playback-mode resume tested end to end in the simulator.
  - [ ] 9.3 Manual checks of the UI in the simulator and of playback on hardware (see the
        manual test list M1–M8 delivered with this change). Memory: about 81 KB of 508 KB in
        the playback path (simulator), well inside AB-8.4.
  - [x] 9.4 INSTALL.md "Audiobooks" section (marking, prefixes, limitations).
