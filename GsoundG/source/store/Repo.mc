import Toybox.Application;
import Toybox.Lang;

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
        }
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

    //! Derive the distinct folder names from all persisted track entries and
    //! persist them via the folders helper. First-seen order is preserved so the
    //! folder list stays stable across rebuilds. Track ids with no stored entry
    //! or no usable folder are skipped defensively (Req 7.2, 8.5).
    function rebuildFolders() as Void {
        var ids = loadIndex();
        var folders = [] as Array<Object?>;
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
        }
        saveFolders(folders);
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
