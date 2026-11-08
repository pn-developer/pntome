# Gsound — Phone Player, Android Auto and Bookmark Sync — Requirements

## Introduction

GsoundA today manages folders and syncs them to the watch. This feature turns it into a player
as well, so the user can listen:

- on the **phone** (headphones, speaker, lock screen);
- in the **car** through **Android Auto** (browse and control, no custom UI);
- on the **watch** (existing GsoundG player, e.g. running or gym).

The same book is listened to in several places, so the **listening position** (book, chapter,
position) must follow the user. The watch already stores one bookmark per audiobook folder
(`bm:<folder>`, see `../gsound-audiobooks`). This feature adds a phone bookmark and a two-way
sync that runs as part of the existing **"Refresh stats"** action.

It extends `../gsound-overview/product-overview.md` (§5 protocol), `../gsound-android`,
`../gsound-garmin` and `../gsound-audiobooks`. Requirement ids are prefixed **PL**.

### Assumptions

- Playback uses the files the user already picked through the Storage Access Framework. No
  new storage permission is requested.
- Only the user's own media is played. Publishing to the Play Store (Android Auto review) is
  out of scope; Android Auto is used with a sideloaded build in developer mode.
- Books are folders marked as audiobooks. Chapter order is the existing natural order of file
  names (`NaturalOrder`, same as the watch's `seq`).
- Music folders keep no per-position bookmark (as on the watch, AB-5.7). Only audiobooks
  are synced.

## Glossary

- **Playback service**: the phone's media service (Media3 `MediaLibraryService`) that owns the
  player and the media session.
- **Browse tree**: the folder, then chapter hierarchy that Android Auto shows.
- **Bookmark**: `{ folder, trackId, chapterIndex, position (s), finished, at (epoch s) }` for
  one book. `at` is the time of the last update on the device that wrote it.
- **Refresh stats**: the existing phone action that PINGs the watch and shows its stats.

## Requirements

### PL-1 — Play on the phone

**User story:** As a user, I want to play my synced folders on the phone, so that I can listen
without the watch.

#### Acceptance Criteria

1. The Folders screen SHALL let the user start playback of a folder or a single track.
2. Playback SHALL continue with the screen off or the app in the background. It SHALL run in
   a foreground service of type `mediaPlayback` with a media notification showing
   play/pause, previous/next and the current title.
3. The notification and lock screen SHALL expose the same controls as the in-app player.
4. A player screen SHALL show title, folder, position, duration, and play/pause, previous/next,
   seek, and for audiobooks skip back/forward 30 s.
5. Playback SHALL pause on headphone unplug and on audio-focus loss, and SHALL resume
   after a transient focus loss.
6. Audiobook folders SHALL play chapters in natural file-name order. Music folders SHALL play
   in the existing track order.
7. IF a file is no longer readable (permission revoked, file removed) THEN the player SHALL
   skip it, show a short message, and not crash.

### PL-2 — Android Auto

**User story:** As a user, I want to browse and control my books and music from the car display
and steering wheel, so that I do not have to touch the phone while driving.

#### Acceptance Criteria

1. GsoundA SHALL appear as a media app in Android Auto, declared through
   `automotive_app_desc.xml` (`<uses name="media"/>`) and the
   `com.google.android.gms.car.application` manifest entry.
2. The browse tree SHALL be: root, then (Audiobooks, Music, Recently played), then folders,
   then chapters or tracks. Depth and list sizes SHALL respect Android Auto limits (paged
   children for large folders).
3. Selecting a chapter SHALL start playback. Selecting an audiobook folder SHALL start at its
   bookmark (the "resume" behaviour of the watch: 5 s rewind, finished book starts from
   chapter 1).
4. The now-playing screen SHALL offer play/pause, previous/next and, for audiobooks,
   skip back/forward 30 s as custom actions. Steering-wheel and Bluetooth media buttons SHALL
   work.
5. Titles and artwork SHALL come from media metadata. Items without artwork SHALL show a
   neutral default.
6. Voice requests ("play <book title>") SHALL be handled by a best-effort title search; no
   match SHALL fall back to "continue the last book".
7. No custom UI, video or free-text input SHALL be shown in the car.
8. The Android Auto path SHALL be verifiable without a car using the Desktop Head Unit.

### PL-3 — Capture the phone bookmark

**User story:** As a user, I want the phone to remember exactly where I stopped, so that I
can continue from there, on the phone or on the watch.

#### Acceptance Criteria

1. For audiobook folders the playback service SHALL persist a bookmark `{ trackId,
   chapterIndex, position, finished, at }` on: pause, stop, chapter change, seek, Android Auto
   disconnect, service destroy and task removal.
2. While playing it SHALL also persist the bookmark periodically, at most every 15 s, so a
   crash or kill loses at most that interval.
3. WHEN the last chapter completes THEN the bookmark SHALL be marked finished. WHEN a chapter
   completes THEN the bookmark SHALL move to the next chapter at 0.
4. Positions SHALL be stored in whole seconds (the watch's unit).
5. Bookmark writes SHALL be small and SHALL NOT block the player thread.
6. Removing a folder, or un-marking it as an audiobook, SHALL delete its bookmark.
7. IF the bookmarked chapter no longer exists after a rescan THEN the bookmark SHALL fall back
   to the chapter now at the same index, at 0 (same rule as AB-5.5).

### PL-4 — Pull watch bookmarks into the phone (Refresh stats)

**User story:** As a user, I want "Refresh stats" to also fetch where I stopped on the
watch, so that I can continue the book on the phone.

#### Acceptance Criteria

1. WHEN the phone sends a PING (Refresh stats) THEN the watch SHALL, after the PONG and
   STATS, send one `BOOKMARK` message per audiobook that has a bookmark.
2. Each `BOOKMARK` SHALL be a flat dictionary (strings and numbers only, no arrays):
   `{ v, type:"BOOKMARK", pairingId, folder, trk, idx, pos, fin, at }`. Messages SHALL be sent
   strictly one in flight, chained on completion, like `LOG`. A failure SHALL stop the chain;
   the remaining bookmarks go on the next refresh.
3. The phone SHALL accept `BOOKMARK` only for the current `pairingId` and only for a known
   audiobook folder (matched by folder name). Others SHALL be ignored and logged.
4. The phone SHALL merge each incoming bookmark by PL-6.
5. The Refresh stats UI SHALL show, per audiobook, the chapter and position
   (e.g. "Ch 3 · 12:40") and the device it came from (phone / watch).
6. A missing, unreachable or old watch (no `BOOKMARK` support) SHALL NOT break Refresh
   stats: STATS still arrives and the screen shows the phone's own bookmarks.

### PL-5 — Push the phone bookmark to the watch

**User story:** As a user, I want the position from the car or phone to be available on the
watch, so that the watch Resume continues from there.

#### Acceptance Criteria

1. WHEN a Refresh stats completes, and at the end of a sync, the phone SHALL send a
   `BOOKMARK` message for each audiobook whose phone bookmark is newer than the last value it
   knows from the watch.
2. The message uses the same shape as PL-4.2, with `at` set by the phone.
3. The watch SHALL apply an incoming `BOOKMARK` by PL-6 and only for a folder that is a book
   on the watch and a `trk` that exists there. Otherwise it SHALL ignore it.
4. After applying, the watch Resume label SHALL reflect it (e.g. "Ch 5 · 03:10").
5. IF the watch is playing that book at that moment THEN the incoming bookmark SHALL be
   ignored (the live session wins) and logged.

### PL-6 — Merge rule

**User story:** As a user, I want the most recent listening position to win, so that
neither device overwrites newer progress.

#### Acceptance Criteria

1. The bookmark with the greater `at` SHALL win. On equal `at` the local one is kept.
2. A finished bookmark SHALL win over an unfinished one only if its `at` is greater. A
   finished bookmark SHALL NOT be reverted by an older unfinished one.
3. Both devices SHALL compare `at` values as given. To limit clock-skew problems, a
   bookmark with `at` more than 5 minutes in the future SHALL be clamped to "now" on receipt.
4. A bookmark that loses SHALL NOT be re-sent in a loop: each side remembers the last `at`
   it sent or received per book.
5. Merging SHALL be idempotent: applying the same bookmark twice has no further effect.
6. When the winner is the phone's, the watch Resume SHALL apply the usual 5 s rewind. The
   same SHALL apply on the phone for a watch bookmark.

### PL-7 — Non-functional

1. The playback service SHALL NOT start, and Android Auto SHALL NOT bind, until the user
   has started playback or Android Auto asks for the browse tree.
2. The watch release build SHALL stay within the ACP memory limit: the new `BOOKMARK`
   handler SHALL reuse the existing handler and storage paths and add no resident data.
3. No secrets or file paths are written to the logs; the `pairingId` check of the existing
   protocol applies.
4. Behaviour SHALL degrade without errors when the watch is not paired: the player and
   Android Auto work, the sync parts do nothing.
5. The new protocol messages SHALL be backward compatible: an older watch or phone ignores
   an unknown `type` without error.
6. Existing data SHALL be kept on upgrade (Room migration, no data loss).
