import Toybox.Application;
import Toybox.Lang;
import Toybox.Media;
import Toybox.Time;

//! Repo — Application.Storage persistence facade (design.md §8.3, §10).
//!
//! Task 3.1 (this module): the flat-key HELPER LAYER. Every piece of persisted
//! state lives under its own top-level Application.Storage key so no single value
//! approaches the ~8 KB per-value limit and so we never keep one large nested
//! table (Req 7.1). This task provides get/set/delete for:
//!   - pairing  -> { pairingId, peerName, secret, createdAt }
//!   - stats    -> { fileCount, folderCount, lastSyncAt }
//!   - idx      -> array of track ids, TRANSPARENTLY CHUNKED across idx:0, idx:1,
//!                 ... when it would exceed the per-value budget
//!   - folders  -> array of distinct folder names
//! Per-track put/get/delete (trk:<id>) and the folders rebuild from the index are
//! task 3.2 and are intentionally NOT built here — only the solid helper
//! foundation the rest of the storage layer and the higher layers build on.
//!
//! Persistence rules (Req 7):
//!   - Application.Storage only.
//!   - Flat keys, <= ~8 KB per value (no single large nested table).
//!   - Never log the pairing secret (Req 7.3) — this module never prints values.
module Repo {

    //! Storage key: persisted PairingRecord
    //! { pairingId, peerName, secret, createdAt }. (Req 2.3, 7.1)
    const KEY_PAIRING = "pairing";

    //! Storage key: persisted stats
    //! { fileCount, folderCount, lastSyncAt }. (Req 5.1, 7.1)
    const KEY_STATS = "stats";

    //! Base storage key for the track-id index (array of ids). The index is
    //! written as one or more CHUNKS: "idx:0", "idx:1", ... Each chunk holds at
    //! most INDEX_CHUNK_SIZE ids so a single stored value stays well under the
    //! ~8 KB per-value limit (Req 7.1). "idx:count" holds the number of chunks.
    const KEY_INDEX = "idx";

    //! Storage key: distinct folder names (array) for playback + folder count.
    const KEY_FOLDERS = "folders";

    //! Suffix key holding the number of index chunks currently persisted.
    //! Stored as "<KEY_INDEX>:count" (i.e. "idx:count").
    const KEY_INDEX_COUNT_SUFFIX = ":count";

    //! Max track ids per index chunk. Track ids are short strings; 100 ids per
    //! chunk keeps each stored value comfortably below the ~8 KB per-value limit
    //! even for long ids, while keeping the number of chunk reads/writes small.
    //! A conservative bound chosen for safety rather than maximum packing.
    const INDEX_CHUNK_SIZE = 100;

    // --- pairing ---------------------------------------------------------------

    //! Load the pairing record from storage, or null if unpaired.
    //! (Supports Req 7.2: restore state when launched in any mode.)
    function loadPairing() as Dictionary? {
        return Application.Storage.getValue(KEY_PAIRING) as Dictionary?;
    }

    //! Save the pairing record, or clear it when null. (Req 2.3, 2.5)
    function savePairing(record as Dictionary?) as Void {
        if (record == null) {
            Application.Storage.deleteValue(KEY_PAIRING);
        } else {
            Application.Storage.setValue(KEY_PAIRING, record as Application.Storage.ValueType);
        }
    }

    //! Delete the pairing record (used on UNPAIR / user unpair; Req 2.5).
    function deletePairing() as Void {
        Application.Storage.deleteValue(KEY_PAIRING);
    }

    // --- stats -----------------------------------------------------------------

    //! Load stats from storage, or null if none persisted yet.
    function loadStats() as Dictionary? {
        return Application.Storage.getValue(KEY_STATS) as Dictionary?;
    }

    //! Save stats, or clear when null.
    function saveStats(stats as Dictionary?) as Void {
        if (stats == null) {
            Application.Storage.deleteValue(KEY_STATS);
        } else {
            Application.Storage.setValue(KEY_STATS, stats as Application.Storage.ValueType);
        }
    }

    //! Delete persisted stats.
    function deleteStats() as Void {
        Application.Storage.deleteValue(KEY_STATS);
    }

    // --- track-id index (chunked) ----------------------------------------------

    //! Load the full track-id index, reassembled from its chunks ("idx:0",
    //! "idx:1", ...). Returns an empty array when nothing is persisted yet.
    //!
    //! Reads "idx:count" to learn how many chunks exist, then concatenates them in
    //! order. Any missing/malformed chunk is skipped defensively so a partially
    //! written index can't crash a launch (Req 7.2, 8.5).
    function loadIndex() as Array<Object?> {
        var count = chunkCount();
        var ids = [] as Array<Object?>;
        if (count <= 0) {
            return ids;
        }
        for (var i = 0; i < count; i++) {
            var chunk = Application.Storage.getValue(chunkKey(i));
            if (chunk instanceof Array) {
                ids.addAll(chunk as Array<Object?>);
            }
        }
        return ids;
    }

    //! Persist the full track-id index, splitting it into chunks of at most
    //! INDEX_CHUNK_SIZE ids each so no single stored value approaches the ~8 KB
    //! limit (Req 7.1). Writes the new chunks, records the chunk count, then
    //! deletes any now-unused chunks left over from a previously larger index.
    function saveIndex(ids as Array<Object?>?) as Void {
        var safeIds = [] as Array<Object?>;
        if (ids != null) {
            safeIds = ids;
        }
        var previousCount = chunkCount();

        var newCount = 0;
        var offset = 0;
        var total = safeIds.size();
        while (offset < total) {
            var end = offset + INDEX_CHUNK_SIZE;
            if (end > total) {
                end = total;
            }
            Application.Storage.setValue(
                chunkKey(newCount),
                safeIds.slice(offset, end) as Application.Storage.ValueType);
            newCount++;
            offset = end;
        }

        // Record how many chunks are live now (0 when the index is empty).
        Application.Storage.setValue(
            KEY_INDEX + KEY_INDEX_COUNT_SUFFIX,
            newCount as Application.Storage.ValueType);

        // Drop any leftover chunks from a previously larger index so stale ids
        // can't resurface on the next load.
        for (var i = newCount; i < previousCount; i++) {
            Application.Storage.deleteValue(chunkKey(i));
        }
    }

    //! Delete the entire track-id index (all chunks + the count key).
    function deleteIndex() as Void {
        var count = chunkCount();
        for (var i = 0; i < count; i++) {
            Application.Storage.deleteValue(chunkKey(i));
        }
        Application.Storage.deleteValue(KEY_INDEX + KEY_INDEX_COUNT_SUFFIX);
    }

    //! Number of index chunks currently persisted (0 when none). Reads the
    //! "idx:count" key and coerces defensively to a non-negative integer.
    function chunkCount() as Number {
        var raw = Application.Storage.getValue(KEY_INDEX + KEY_INDEX_COUNT_SUFFIX);
        if (raw instanceof Number && raw > 0) {
            return raw as Number;
        }
        return 0;
    }

    //! Storage key for index chunk `i`, e.g. 0 -> "idx:0".
    function chunkKey(i as Number) as String {
        return KEY_INDEX + ":" + i.toString();
    }

    // --- folders ---------------------------------------------------------------

    // --- last playback selection ------------------------------------------------
    //
    // The folder + start index the user last picked in the playback-config track list. The native
    // player only passes our startPlayback() args when OUR list starts playback; when it starts or
    // resumes on its own (its own Play button, after the headphone prompt, after a reboot) it calls
    // getContentDelegate(null). Persisting the selection lets that null-args call still play it.

    //! Storage key: last playback selection { "folder" => String, "startIndex" => Number }.
    const KEY_LAST_PLAYBACK = "pb:last";

    //! Persist the last playback selection.
    function savePlaybackSelection(folder as String, startIndex as Number) as Void {
        var selection = {
            "folder" => folder,
            "startIndex" => startIndex
        };
        Application.Storage.setValue(KEY_LAST_PLAYBACK, selection as Application.Storage.ValueType);
    }

    //! Persist the last playback args as given (folder + startIndex, or folder + mode).
    function savePlaybackArgs(args as Dictionary) as Void {
        Application.Storage.setValue(KEY_LAST_PLAYBACK, args as Application.Storage.ValueType);
    }

    //! The last playback selection, or null when none was saved / it is malformed.
    function loadPlaybackSelection() as Dictionary? {
        var raw = Application.Storage.getValue(KEY_LAST_PLAYBACK);
        if (raw instanceof Dictionary && (raw as Dictionary).get("folder") instanceof String) {
            return raw as Dictionary;
        }
        return null;
    }

    //! Load the distinct-folder-name list, or an empty array when none persisted.
    function loadFolders() as Array<Object?> {
        var folders = Application.Storage.getValue(KEY_FOLDERS);
        if (folders instanceof Array) {
            return folders as Array<Object?>;
        }
        return [] as Array<Object?>;
    }

    //! Persist the distinct-folder-name list (or clear when null/empty). The
    //! folder list is small (distinct names only) and fits in a single value.
    function saveFolders(folders as Array<Object?>?) as Void {
        if (folders == null || folders.size() == 0) {
            Application.Storage.deleteValue(KEY_FOLDERS);
        } else {
            Application.Storage.setValue(KEY_FOLDERS, folders as Application.Storage.ValueType);
        }
    }

    //! Delete the persisted folder list.
    function deleteFolders() as Void {
        Application.Storage.deleteValue(KEY_FOLDERS);
    }

    // --- per-track entries (trk:<id>) ------------------------------------------
    //
    // Task 3.2: each track lives under its own flat key "trk:<id>" holding a small
    // dict { mediaId, folder, title, artist, album, mime } (design.md §8.3). The
    // track-id index ("idx", chunked) and the distinct "folders" list are kept in
    // sync by putTrack/deleteTrack so playback and the folder-count stat always
    // reflect what is actually stored (Req 7.1, 7.2).

    //! Prefix for a per-track entry key: "trk:" + id, e.g. "trk:song-42".
    const KEY_TRACK_PREFIX = "trk:";

    //! Storage key for the track entry with the given id.
    function trackKey(id as String) as String {
        return KEY_TRACK_PREFIX + id;
    }

    //! Store a track entry under "trk:<id>", add the id to the index (deduped),
    //! and keep the distinct folders list in sync.
    //!
    //! The entry is a small dict { mediaId, folder, title, artist, album, mime }
    //! — well under the ~8 KB per-value limit (Req 7.1). A null/empty id is a
    //! no-op so a malformed manifest entry can't create a "trk:" ghost key
    //! (defensive; Req 3.3, 8.5). The folder name (when present) is appended to
    //! the folders list if not already there, avoiding a full rebuild on the
    //! common add path.
    function putTrack(id as String?, entry as Dictionary?) as Void {
        if (id == null || id.equals("") || entry == null) {
            return;
        }
        Application.Storage.setValue(trackKey(id), entry as Application.Storage.ValueType);

        // Add to the index, deduped.
        var ids = loadIndex();
        if (!containsValue(ids, id)) {
            ids.add(id);
            saveIndex(ids);
        }

        // Keep the distinct folders list in sync (incremental add).
        var folder = entry.get("folder");
        if (folder instanceof String && !folder.equals("")) {
            var folders = loadFolders();
            if (!containsValue(folders, folder)) {
                folders.add(folder);
                saveFolders(folders);
            }
            // Audiobook folder list (incremental add; removals go through rebuildFolders).
            if (isTrue(entry.get("book"))) {
                var books = loadBookFolders();
                if (!containsValue(books, folder)) {
                    books.add(folder);
                    saveBookFolders(books);
                }
            }
        }
    }

    //! Rewrite an EXISTING track entry's dict only (no index/folder bookkeeping). Used by
    //! MetaUpdater, which rebuilds the folder lists once after all updates.
    function putTrackEntryOnly(id as String, entry as Dictionary) as Void {
        Application.Storage.setValue(trackKey(id), entry as Application.Storage.ValueType);
    }

    //! Load the track entry for `id`, or null if there is none.
    function getTrack(id as String?) as Dictionary? {
        if (id == null || id.equals("")) {
            return null;
        }
        return Application.Storage.getValue(trackKey(id)) as Dictionary?;
    }

    //! Delete a track entry: remove "trk:<id>", drop the id from the index, and
    //! rebuild the distinct folders list from the remaining tracks.
    //!
    //! The folders list is rebuilt (rather than incrementally pruned) because a
    //! folder must only be removed once its LAST track is gone — rebuilding from
    //! the surviving entries is the simplest correct way to decide that.
    function deleteTrack(id as String?) as Void {
        if (id == null || id.equals("")) {
            return;
        }
        // Delete the downloaded AUDIO too, not just our bookkeeping. Previously only the trk:
        // entry / index were removed, so every removed or replaced track stayed in the system
        // media cache forever (seen on device: 12+ cached files, ~295 MB, for 2 tracks).
        var entry = getTrack(id);
        if (entry != null) {
            deleteCachedMedia(entry.get("mediaId"));
        }
        Application.Storage.deleteValue(trackKey(id));

        // Remove the id from the index (preserving order of the rest).
        var ids = loadIndex();
        var kept = [] as Array<Object?>;
        for (var i = 0; i < ids.size(); i++) {
            var existing = ids[i];
            if (!(existing instanceof String && (existing as String).equals(id))) {
                kept.add(existing);
            }
        }
        saveIndex(kept);

        // Rebuild the distinct folders list from what remains.
        rebuildFolders();
    }

    //! Delete one downloaded audio item from the system media cache by its stored mediaId.
    //! Best effort: a missing/already-deleted item or an unsupported context must not stop the
    //! caller. Returns true when the delete call succeeded.
    function deleteCachedMedia(mediaId as Object?) as Boolean {
        if (mediaId == null) {
            return false;
        }
        try {
            Media.deleteCachedItem(new Media.ContentRef(mediaId, Media.CONTENT_TYPE_AUDIO));
            return true;
        } catch (ex) {
            DebugLog.log("REPO", "deleteCachedItem FAILED id=" + DebugLog.shape(mediaId) + " " + DebugLog.describe(ex));
            return false;
        }
    }

    //! Delete every audio item in the system media cache that no stored track references
    //! (orphans from earlier syncs, removed/replaced tracks, or interrupted downloads).
    //! Run after a clean sync, once the index matches the manifest. Returns how many were deleted.
    //! Never throws.
    function sweepOrphanedMedia() as Number {
        var deleted = 0;
        try {
            // mediaIds still referenced by our library.
            var keep = [] as Array<Object?>;
            var ids = loadIndex();
            for (var i = 0; i < ids.size(); i++) {
                var id = ids[i];
                if (id instanceof String) {
                    var entry = getTrack(id as String);
                    if (entry != null && entry.get("mediaId") != null) {
                        keep.add(entry.get("mediaId"));
                    }
                }
            }

            // Collect first, delete after: don't mutate the cache while iterating it.
            var orphans = [] as Array<Media.ContentRef>;
            var it = Media.getContentRefIter({ :contentType => Media.CONTENT_TYPE_AUDIO });
            var ref = it.next();
            var scanned = 0;
            while (ref != null && scanned < 500) {
                scanned += 1;
                if (!containsObject(keep, ref.getId())) {
                    orphans.add(ref);
                }
                ref = it.next();
            }

            for (var i = 0; i < orphans.size(); i++) {
                try {
                    Media.deleteCachedItem(orphans[i]);
                    deleted += 1;
                } catch (ex) {
                    DebugLog.log("REPO", "orphan delete FAILED " + DebugLog.describe(ex));
                }
            }
            DebugLog.log("REPO", "orphan sweep: cached=" + scanned.toString()
                + " kept=" + keep.size().toString() + " deleted=" + deleted.toString());
        } catch (ex) {
            DebugLog.log("REPO", "orphan sweep FAILED " + DebugLog.describe(ex));
        }
        return deleted;
    }

    //! True when the system media cache holds audio that no stored track references, i.e. there is
    //! something for sweepOrphanedMedia to clean. Used by SyncNeed so a cleanup sync runs even
    //! when no track is new. Never throws (false on any error).
    function hasOrphanedMedia() as Boolean {
        try {
            var keep = [] as Array<Object?>;
            var ids = loadIndex();
            for (var i = 0; i < ids.size(); i++) {
                var id = ids[i];
                if (id instanceof String) {
                    var entry = getTrack(id as String);
                    if (entry != null && entry.get("mediaId") != null) {
                        keep.add(entry.get("mediaId"));
                    }
                }
            }
            var it = Media.getContentRefIter({ :contentType => Media.CONTENT_TYPE_AUDIO });
            var ref = it.next();
            var scanned = 0;
            while (ref != null && scanned < 500) {
                scanned += 1;
                if (!containsObject(keep, ref.getId())) {
                    return true;
                }
                ref = it.next();
            }
        } catch (ex) {
        }
        return false;
    }

    //! True when `list` contains a value equal to `value` (works for Number media ids).
    function containsObject(list as Array<Object?>, value as Object?) as Boolean {
        if (value == null) {
            return false;
        }
        for (var i = 0; i < list.size(); i++) {
            var item = list[i];
            if (item != null && item.equals(value)) {
                return true;
            }
        }
        return false;
    }

    //! Derive the distinct folder names from all persisted track entries and
    //! persist them via the folders helper. First-seen order is preserved so the
    //! folder list stays stable across rebuilds. Track ids with no stored entry
    //! or no usable folder are skipped defensively (Req 7.2, 8.5).
    function rebuildFolders() as Void {
        var ids = loadIndex();
        var folders = [] as Array<Object?>;
        var books = [] as Array<Object?>;
        for (var i = 0; i < ids.size(); i++) {
            var id = ids[i];
            if (!(id instanceof String)) {
                continue;
            }
            var entry = getTrack(id as String);
            if (entry == null) {
                continue;
            }
            var folder = entry.get("folder");
            if (folder instanceof String && !folder.equals("") && !containsValue(folders, folder)) {
                folders.add(folder);
            }
            if (folder instanceof String && !folder.equals("") && isTrue(entry.get("book"))
                    && !containsValue(books, folder)) {
                books.add(folder);
            }
        }
        saveFolders(folders);
        replaceBookFolders(books);
    }

    // --- audiobook folders + bookmarks (spec gsound-audiobooks AB-3.3, AB-5) -----------------
    //
    //   - "bk:folders"   -> Array<String> of folder names whose tracks are flagged `book`
    //                       (kept in sync by putTrack / rebuildFolders / MetaUpdater). Small.
    //   - "bm:<folder>"  -> bookmark { "trk" => trackId, "pos" => seconds, "idx" => chapter
    //                       index, "fin" => Boolean, "at" => epoch seconds }, one small value
    //                       per book. `at` enables a longer rewind after a long break (later).

    //! Storage key: names of the audiobook folders.
    const KEY_BOOK_FOLDERS = "bk:folders";

    //! Prefix for a book's bookmark key: "bm:" + folder name.
    const KEY_BOOKMARK_PREFIX = "bm:";

    //! The audiobook folder names, or an empty array.
    function loadBookFolders() as Array<Object?> {
        var raw = Application.Storage.getValue(KEY_BOOK_FOLDERS);
        return (raw instanceof Array) ? (raw as Array<Object?>) : ([] as Array<Object?>);
    }

    //! Persist the audiobook folder names (cleared when empty).
    function saveBookFolders(books as Array<Object?>) as Void {
        if (books.size() == 0) {
            Application.Storage.deleteValue(KEY_BOOK_FOLDERS);
        } else {
            Application.Storage.setValue(KEY_BOOK_FOLDERS, books as Application.Storage.ValueType);
        }
    }

    //! Replace the audiobook folder list and drop the bookmark of every folder that stopped
    //! being a book (unmarked on the phone, or all its chapters removed; AB-5.6).
    function replaceBookFolders(books as Array<Object?>) as Void {
        var old = loadBookFolders();
        for (var i = 0; i < old.size(); i++) {
            var name = old[i];
            if (name instanceof String && !containsValue(books, name)) {
                deleteBookmark(name as String);
            }
        }
        saveBookFolders(books);
    }

    //! True only for a Boolean `true` (stored flags may be absent or of another type).
    function isTrue(value as Object?) as Boolean {
        return (value instanceof Boolean) && (value as Boolean);
    }

    //! True when `folder` is an audiobook folder. Cheap (one small value), safe per frame.
    function isBookFolder(folder as String?) as Boolean {
        if (folder == null) {
            return false;
        }
        try {
            return containsValue(loadBookFolders(), folder);
        } catch (ex) {
            return false;
        }
    }

    //! The bookmark for `folder`, or null when none/malformed. Never throws.
    function loadBookmark(folder as String) as Dictionary? {
        try {
            var raw = Application.Storage.getValue(KEY_BOOKMARK_PREFIX + folder);
            if (raw instanceof Dictionary && (raw as Dictionary).get("trk") instanceof String) {
                return raw as Dictionary;
            }
        } catch (ex) {
        }
        return null;
    }

    //! Persist the bookmark for `folder`. Never throws (AB-8.2): a failed write just keeps
    //! the previous bookmark.
    function saveBookmark(folder as String, trackId as String, pos as Number, index as Number,
            finished as Boolean) as Void {
        try {
            var value = {
                "trk" => trackId,
                "pos" => (pos < 0) ? 0 : pos,
                "idx" => index,
                "fin" => finished,
                "at" => Time.now().value()
            };
            Application.Storage.setValue(KEY_BOOKMARK_PREFIX + folder, value as Application.Storage.ValueType);
        } catch (ex) {
            DebugLog.log("REPO", "saveBookmark FAILED " + DebugLog.describe(ex));
        }
    }

    //! Delete the bookmark for `folder`. Never throws.
    function deleteBookmark(folder as String) as Void {
        try {
            Application.Storage.deleteValue(KEY_BOOKMARK_PREFIX + folder);
        } catch (ex) {
        }
    }

    // --- pending manifest (chunked) --------------------------------------------
    //
    // Task 6.3: the most recently received SYNC_MANIFEST { serverUrl, tracks:[...] }
    // is persisted so a sync launched by the system (a separate ACP process, Req
    // 7.2) can download without the phone resending it. The manifest can be large
    // (many tracks), so — exactly like the track index above — it is written as
    // flat, chunked keys instead of one giant nested value (Req 7.1):
    //   - "pm:serverUrl"  -> the server base URL (one small string).
    //   - "pm:trk:<n>"    -> chunk n of the cleaned ManifestTrack dicts, at most
    //                        MANIFEST_CHUNK_SIZE tracks each.
    //   - "pm:trk:count"  -> number of track chunks persisted.
    // A "pm:fetch" fallback flag key is reserved for task 6.4's "fetch at sync
    // start" seam; this task neither reads nor writes it, leaving room for it in
    // the pm: namespace.
    //
    // Each stored track keeps only the fields the downloader needs (id, path,
    // mime, folder, title, artist, album, sizeBytes) — the cleaned ManifestTrack
    // produced by SyncController.cleanTrack — so chunks stay small. loadPendingManifest
    // tolerates missing/partial chunks without crashing (Req 7.2, 8.5).

    //! Base storage key namespace for the pending manifest.
    const KEY_PENDING_MANIFEST = "pm";

    //! Storage key: the pending manifest's server base URL (one small string).
    const KEY_PM_SERVER_URL = "pm:serverUrl";

    //! Base storage key for the chunked pending-manifest track list. Chunks are
    //! "pm:trk:0", "pm:trk:1", ...; "pm:trk:count" holds the chunk count.
    const KEY_PM_TRACKS = "pm:trk";

    //! Storage key for the task-6.4 "fetch manifest at sync start" fallback flag
    //! (design §6.1). Owned by this module as of task 6.4. The value is a small
    //! dict `{ set: Boolean, expected: Number? }` where `set` means "the inline
    //! manifest was empty/truncated, so task 7's onStartSync must fetch the
    //! authoritative list from GET {serverUrl}/manifest" and `expected` is the
    //! phone-reported expected track count when one is available (it is not in v1
    //! — see savePendingFetchFlag — so this is reserved for forward use).
    const KEY_PM_FETCH = "pm:fetch";

    //! Max cleaned ManifestTrack dicts per manifest chunk. Each track dict is a
    //! handful of short strings; 40 per chunk keeps a stored value well under the
    //! ~8 KB per-value limit even with long titles/paths, while keeping the number
    //! of chunk reads/writes modest. Conservative by design (Req 7.1).
    const MANIFEST_CHUNK_SIZE = 40;

    //! Load the persisted pending manifest as { serverUrl, tracks:[...] }, or null
    //! when none is stored (or the stored serverUrl is missing/empty — a manifest
    //! with no server is unusable, mirroring SyncController.onManifest). The track
    //! list is reassembled from its chunks ("pm:trk:0", ...); any missing or
    //! malformed chunk is skipped defensively so a partially written manifest can
    //! never crash a sync launch (Req 7.2, 8.5) — the result is a best-effort
    //! manifest of whatever survived.
    function loadPendingManifest() as Dictionary? {
        var serverUrl = Application.Storage.getValue(KEY_PM_SERVER_URL);
        if (!(serverUrl instanceof String) || (serverUrl as String).equals("")) {
            return null;
        }
        var count = manifestChunkCount();
        var tracks = [] as Array<Object?>;
        for (var i = 0; i < count; i++) {
            var chunk = Application.Storage.getValue(manifestChunkKey(i));
            if (chunk instanceof Array) {
                tracks.addAll(chunk as Array<Object?>);
            }
        }
        return {
            Messages.FIELD_SERVER_URL => serverUrl as String,
            Messages.FIELD_TRACKS => tracks
        };
    }

    //! Persist the pending manifest { serverUrl, tracks:[...] }, replacing any
    //! previously stored one. The serverUrl is written as one small value; the
    //! tracks are split into chunks of at most MANIFEST_CHUNK_SIZE so no single
    //! stored value approaches the ~8 KB limit (Req 7.1). A null manifest, or one
    //! with a missing/empty serverUrl, clears any stale pending manifest instead
    //! of writing a half-usable one. Leftover chunks from a previously larger
    //! manifest are deleted so stale tracks can't resurface on the next load.
    function savePendingManifest(manifest as Dictionary?) as Void {
        if (manifest == null) {
            deletePendingManifest();
            return;
        }
        var serverUrl = manifest.get(Messages.FIELD_SERVER_URL);
        if (!(serverUrl instanceof String) || (serverUrl as String).equals("")) {
            deletePendingManifest();
            return;
        }

        var tracks = manifest.get(Messages.FIELD_TRACKS);
        var safeTracks = [] as Array<Object?>;
        if (tracks instanceof Array) {
            safeTracks = tracks as Array<Object?>;
        }

        var previousCount = manifestChunkCount();

        Application.Storage.setValue(
            KEY_PM_SERVER_URL, serverUrl as Application.Storage.ValueType);

        var newCount = 0;
        var offset = 0;
        var total = safeTracks.size();
        while (offset < total) {
            var end = offset + MANIFEST_CHUNK_SIZE;
            if (end > total) {
                end = total;
            }
            Application.Storage.setValue(
                manifestChunkKey(newCount),
                safeTracks.slice(offset, end) as Application.Storage.ValueType);
            newCount++;
            offset = end;
        }

        Application.Storage.setValue(
            KEY_PM_TRACKS + KEY_INDEX_COUNT_SUFFIX,
            newCount as Application.Storage.ValueType);

        // Drop any leftover chunks from a previously larger manifest.
        for (var i = newCount; i < previousCount; i++) {
            Application.Storage.deleteValue(manifestChunkKey(i));
        }
    }

    //! Delete the entire pending manifest (serverUrl + all track chunks + the
    //! chunk-count key) AND the "fetch at sync start" fallback flag. As of task
    //! 6.4 this module owns the flag's lifecycle, so clearing a pending manifest
    //! also clears its fallback state — the two always rise and fall together.
    function deletePendingManifest() as Void {
        var count = manifestChunkCount();
        for (var i = 0; i < count; i++) {
            Application.Storage.deleteValue(manifestChunkKey(i));
        }
        Application.Storage.deleteValue(KEY_PM_TRACKS + KEY_INDEX_COUNT_SUFFIX);
        Application.Storage.deleteValue(KEY_PM_SERVER_URL);
        deletePendingFetchFlag();
    }

    // --- pending-manifest fetch-at-sync-start fallback flag (task 6.4) ---------
    //
    // design §6.1: the control-channel message has a payload ceiling, so a large
    // library can arrive as a SYNC_MANIFEST whose `tracks` list is empty (the
    // phone deliberately sends serverUrl only) or truncated. In that case the
    // watch must NOT treat "no inline tracks" as "nothing to sync"; it keeps the
    // serverUrl and records that task 7's onStartSync has to pull the authoritative
    // list from GET {serverUrl}/manifest over HTTP. Both paths yield the identical
    // ManifestTrack shape (§8.0), so the downloader consumes one representation.
    //
    // The flag is persisted (not just held in memory) because the sync runs in a
    // separate system-launched ACP process (Req 7.2): the process that receives
    // the manifest and the process that downloads are not guaranteed to be the
    // same, so the "must fetch" decision has to survive in Application.Storage
    // right next to the pending manifest it describes.
    //
    // Wire note: GsoundA's SYNC_MANIFEST carries pairingId/serverUrl/tracks/v only
    // — there is NO expected-count/total field on the message (GsoundA
    // ControlMessage.SyncManifest). So in v1 the ONLY detectable fallback trigger
    // is an empty `tracks` list; a count that is merely truncated below some
    // "expected" total cannot be distinguished from a genuinely short library
    // without a count field the phone does not send. `expectedCount` is therefore
    // accepted and persisted for forward-compatibility but is null in practice.

    //! Set the "fetch manifest at sync start" fallback flag (design §6.1). Call
    //! this when a received SYNC_MANIFEST had an empty/truncated `tracks` list but
    //! a usable serverUrl, so task 7's onStartSync knows to fetch GET /manifest
    //! instead of relying on the (absent) inline tracks. `expectedCount` is the
    //! phone-reported expected track count when one is available, or null (the v1
    //! wire shape carries none; see the section note). Stored as a small dict
    //! alongside the pending manifest under KEY_PM_FETCH.
    function savePendingFetchFlag(expectedCount as Number?) as Void {
        var flag = { "set" => true };
        if (expectedCount != null) {
            flag.put("expected", expectedCount);
        }
        Application.Storage.setValue(KEY_PM_FETCH, flag as Application.Storage.ValueType);
    }

    //! Load the fetch-at-sync-start fallback flag as `{ set: Boolean, expected:
    //! Number? }`. Always returns a dict (never null) so callers can read it
    //! without a null check: when no flag is persisted it returns
    //! `{ set => false }`. `expected` is present only when a non-null expected
    //! count was recorded. Coerces defensively so a malformed stored value degrades
    //! to "not set" rather than crashing a sync launch (Req 7.2, 8.5).
    function loadPendingFetchFlag() as Dictionary {
        var raw = Application.Storage.getValue(KEY_PM_FETCH);
        if (!(raw instanceof Dictionary)) {
            return { "set" => false };
        }
        var stored = raw as Dictionary;
        var isSet = stored.get("set");
        var result = { "set" => (isSet instanceof Boolean) ? (isSet as Boolean) : false };
        var expected = stored.get("expected");
        if (expected instanceof Number) {
            result.put("expected", expected as Number);
        }
        return result;
    }

    //! Clear the fetch-at-sync-start fallback flag. Called when a complete inline
    //! manifest arrives (normal path — nothing to fetch) and as part of
    //! deletePendingManifest so the flag never outlives the manifest it describes.
    function deletePendingFetchFlag() as Void {
        Application.Storage.deleteValue(KEY_PM_FETCH);
    }

    //! Number of pending-manifest track chunks currently persisted (0 when none).
    //! Reads "pm:trk:count" and coerces defensively to a non-negative integer.
    function manifestChunkCount() as Number {
        var raw = Application.Storage.getValue(KEY_PM_TRACKS + KEY_INDEX_COUNT_SUFFIX);
        if (raw instanceof Number && raw > 0) {
            return raw as Number;
        }
        return 0;
    }

    //! Storage key for pending-manifest track chunk `i`, e.g. 0 -> "pm:trk:0".
    function manifestChunkKey(i as Number) as String {
        return KEY_PM_TRACKS + ":" + i.toString();
    }

    //! True when `list` already contains a String value equal to `value`.
    //! Used to dedupe track ids and folder names (Application.Storage values come
    //! back as generic Objects, so compare via String.equals rather than ==).
    function containsValue(list as Array<Object?>, value as String) as Boolean {
        for (var i = 0; i < list.size(); i++) {
            var item = list[i];
            if (item instanceof String && (item as String).equals(value)) {
                return true;
            }
        }
        return false;
    }
}
