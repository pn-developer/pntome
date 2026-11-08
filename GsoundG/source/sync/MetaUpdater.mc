import Toybox.Lang;

//! MetaUpdater — applies manifest METADATA to tracks that are already on the watch, without
//! downloading anything (spec gsound-audiobooks AB-3.4, design §4.2).
//!
//! Track ids encode path/size/mtime, so marking a folder as an audiobook on the phone, or a
//! change of chapter order, does not change any id — SyncNeed sees "nothing new" and no sync
//! runs. This updater is therefore called when a manifest ARRIVES (SyncController /
//! GsoundSyncDelegate fetch path): for every manifest track whose `trk:<id>` exists, it rewrites
//! `seq`, `book` and `title` when they differ, then rebuilds the folder + audiobook lists
//! (which also drops bookmarks of folders that are no longer books).
//!
//! Writes only changed entries. Never throws.
module MetaUpdater {

    //! Apply `seq`/`book`/`title` from the cleaned manifest tracks. Returns how many stored
    //! entries changed.
    function apply(cleanTracks as Array<Dictionary>) as Number {
        var changed = 0;
        try {
            for (var i = 0; i < cleanTracks.size(); i++) {
                var track = cleanTracks[i];
                var id = track.get(Messages.FIELD_ID);
                if (!(id instanceof String)) {
                    continue;
                }
                var entry = Repo.getTrack(id as String);
                if (entry == null) {
                    continue; // not downloaded yet — the Downloader stores it with metadata
                }
                if (applyTo(entry, track)) {
                    Repo.putTrackEntryOnly(id as String, entry);
                    changed += 1;
                }
            }
            if (changed > 0) {
                Repo.rebuildFolders();
            }
            DebugLog.log("META", "manifest metadata applied: changed=" + changed.toString());
        } catch (ex) {
            DebugLog.log("META", "apply FAILED " + DebugLog.describe(ex));
        }
        return changed;
    }

    //! Copy the metadata fields from `track` into `entry`; true when anything changed.
    function applyTo(entry as Dictionary, track as Dictionary) as Boolean {
        var changed = false;

        var seq = track.get(Messages.FIELD_SEQ);
        if (seq instanceof Number) {
            if (!(entry.get("seq") instanceof Number) || (entry.get("seq") as Number) != (seq as Number)) {
                entry.put("seq", seq);
                changed = true;
            }
        }

        // `book` absent in the manifest (older phone) leaves the entry as is; present → mirror.
        var book = track.get(Messages.FIELD_BOOK);
        if (book instanceof Boolean) {
            var isBook = (book as Boolean);
            var wasBook = Repo.isTrue(entry.get("book"));
            if (isBook != wasBook) {
                if (isBook) {
                    entry.put("book", true);
                } else {
                    entry.remove("book");
                }
                changed = true;
            }
        }

        var title = track.get(Messages.FIELD_TITLE);
        if (title instanceof String) {
            var old = entry.get("title");
            if (!(old instanceof String) || !(old as String).equals(title)) {
                entry.put("title", title);
                changed = true;
            }
        }
        return changed;
    }
}
