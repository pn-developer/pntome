# Gsound — Audiobook Folders — Requirements

## Introduction

Users want to listen to audiobooks on the watch the way the native player handles them:
- chapters play in order;
- the player remembers where you stopped;
- you can resume, restart, or jump to a chapter.

Gsound syncs **folders**, so a book is modeled as a folder that the user **marks as an
audiobook** in GsoundA. ID3 tags are deliberately not used: chapter order comes from the file
names, and the book is identified by its folder.

The feature spans GsoundA (flag, ordering, manifest) and GsoundG (storage, options screen,
chapter list, resume tracking).

It extends `../gsound-overview/product-overview.md` (§5 protocol, §9 scope),
`../gsound-android/requirements.md`, `../gsound-garmin/requirements.md` and
`../gsound-playback-fixes/requirements.md`. Requirement ids are prefixed **AB**.

### Assumptions (from the request)

- One folder holds exactly one book. Nested folders are not supported in v1.
- File names carry an ordering prefix, e.g. `01 …`, `02 …`, `1-01 …`, `1-02 …`.
- Chapters are long (up to ~30 min or more).

## Glossary

- **Book folder**: a synced folder marked as an audiobook in GsoundA.
- **Chapter**: one audio file in a book folder.
- **Chapter order**: the natural sort order of chapter file names (§AB-2).
- **Bookmark**: the last chapter and position played in a book, persisted on the watch.

## Requirements

### AB-1 — Mark a folder as an audiobook (GsoundA)

**User story:** As a user, I want to mark a synced folder as an audiobook, so that the watch
treats it as one book with chapters instead of a playlist.

#### Acceptance Criteria

1. Each folder row in the Folders screen SHALL show an "Audiobook" toggle reflecting the
   folder's current kind.
2. WHEN the user toggles it THEN GsoundA SHALL persist the flag on the folder. The flag
   SHALL survive app restarts and rescans.
3. Newly added folders SHALL default to "not an audiobook" (music).
4. Upgrading GsoundA SHALL keep all existing folders and tracks. Existing folders SHALL
   become music folders (database migration, no data loss).
5. The flag SHALL take effect on the watch at the next sync, even when no file changed and
   nothing needs downloading (see AB-3.4).

### AB-2 — Chapter order from file names

**User story:** As a user, I want chapters to play in the order of their file names, so that
the book plays correctly regardless of tags.

#### Acceptance Criteria

1. GsoundA SHALL store each track's file name (display name) when scanning.
2. Within a folder, GsoundA SHALL assign each track a chapter sequence number using a
   **natural sort** of file names:
   - digit runs compare numerically (`2` < `10`, `1-02` < `1-10` < `2-01`);
   - other text compares case-insensitively;
   - ties are broken by the full name, so the order is deterministic.
3. The sequence number SHALL be sent to the watch for every track. Music folders also get
   it, so their playback order becomes the file-name order instead of the provider's arbitrary
   order.
4. For book folders, the chapter label shown on the watch SHALL be the file name without its
   extension (not the ID3 title).
5. Files without a numeric prefix SHALL still be ordered (by name), never dropped.

### AB-3 — Protocol and sync

#### Acceptance Criteria

1. Each manifest track SHALL carry two new optional fields: `seq` (Number, the 0-based chapter
   order within its folder) and `book` (Boolean, true when the folder is an audiobook).
2. The fields SHALL be backward compatible:
   - the protocol version stays `v: 1`;
   - a watch that doesn't know the fields ignores them;
   - a phone that doesn't send them yields music folders in index order.
3. GsoundG SHALL persist `seq` and `book` with each track entry.
4. WHEN a manifest arrives THEN GsoundG SHALL update `seq`, `book` and `title` for tracks
   already on the watch, without re-downloading. A folder's kind or order can then change
   without a file change.
5. The HTTP `/manifest` fallback SHALL carry the same fields.

### AB-4 — Book options on the watch

**User story:** As a user, when I open a book on the watch, I want to choose how to start
listening.

#### Acceptance Criteria

1. The folder list SHALL mark book folders (e.g. a "Book" prefix or icon) so that they are
   distinguishable from music folders.
2. WHEN the user selects a music folder THEN GsoundG SHALL behave as today (track list).
3. WHEN the user selects a book folder THEN GsoundG SHALL show an options screen with:
   - **Resume**: shown only when a bookmark exists, labeled with the chapter and position
     (e.g. "Ch 3 · 12:40");
   - **Play from start**: chapter 1 at 0:00;
   - **Chapters**: the chapter list in chapter order. Selecting a chapter starts it at 0:00.
4. When a bookmark exists, Resume SHALL be the first and initially highlighted item.
5. Every choice SHALL continue through the following chapters to the end of the book.

### AB-5 — Bookmark tracking

**User story:** As a user, I want the watch to remember where I stopped in each book, so
that I can continue later, even after listening to something else.

#### Acceptance Criteria

1. GsoundG SHALL keep **one bookmark per book folder**: the chapter (track id) plus the
   position in seconds. Bookmarks for different books SHALL be independent.
2. The bookmark SHALL be updated from the player's events:
   - **chapter start:** record the chapter, keeping the position if it is a resume;
   - **pause / stop / skip:** record the chapter and the reported position;
   - **chapter complete:** move to the next chapter at 0:00.
3. WHEN the last chapter completes THEN playback SHALL stop and the bookmark SHALL be marked
   finished. Resume SHALL then not be offered; Play from start remains. *(Later: report the
   finished status back to the phone.)*
4. The position recorded SHALL be the absolute position in the chapter. A resumed chapter
   that reports positions relative to its start offset SHALL be corrected by adding that
   offset (see design risk R2).
5. IF the bookmarked chapter no longer exists after a sync THEN Resume SHALL fall back to the
   next existing chapter in order, at 0:00, or to no bookmark if none remains.
6. Removing a book folder from the watch SHALL delete its bookmark.
7. Music folders SHALL NOT record per-position bookmarks. The existing last-selection
   behaviour stays.

### AB-6 — Resume playback

#### Acceptance Criteria

1. **Resume** SHALL start the bookmarked chapter at the bookmarked position, rewound by a
   small amount (default 5 s) for context. The position is clamped at 0. *(Later: a longer
   rewind, about 30 s, after a long break of more than about 20 min. The bookmark stores its
   timestamp for this.)*
2. WHEN the native player starts on its own (`getContentDelegate(null)`) and the last
   selection was a book THEN playback SHALL resume that book from its bookmark.
3. Chapters after the resumed one SHALL start at 0:00.

### AB-7 — Player controls for books

#### Acceptance Criteria

1. The bottom-left hotkey SHALL stay "next" (consistent with music, PF-1.4).
2. Book playback SHALL also offer the player's 30-second skip-back and skip-forward
   controls on screen. These are useful for long chapters. The skip size can't be changed on
   fēnix 6 (CIQ 3.4).
3. "Previous" within the first 5 s of a chapter goes to the previous chapter. Later in the
   chapter it restarts the current one (`skipPreviousThreshold = 5`).

### AB-8 — Robustness and limits

#### Acceptance Criteria

1. All new storage SHALL use small flat keys (§ Repo convention, ≤ ~8 KB per value). Bookmark
   writes SHALL be small and SHALL happen only on player events, not periodically.
2. Any bookmark read or write failure SHALL degrade to "no bookmark" and SHALL never break
   playback.
3. A bookmark is only as fresh as the last player event. If the watch powers off or the app
   is killed mid-chapter, the position since the last pause/skip is lost; the chapter is kept.
   This limitation SHALL be documented.
4. The release build SHALL stay within the ACP memory limit with headroom (≥ 50 % free during
   playback on fēnix 6S Pro).
