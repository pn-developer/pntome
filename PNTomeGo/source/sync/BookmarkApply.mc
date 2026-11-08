import Toybox.Lang;
import Toybox.Time;
//! BookmarkApply — applies an inbound BOOKMARK from the phone (spec gsound-player PL-5.3, PL-5.4,
//! PL-6, PL-7.2; design §7). Validate -> resolve folder/track -> merge -> save.
//!
//! No resident data: everything is read from storage per message and dropped again.
//! Never throws: any fault yields RESULT_ERROR.
module BookmarkApply {
    //! Result codes of apply().
    const RESULT_TAKEN = 0;          // bookmark stored
    const RESULT_KEPT_LOCAL = 1;     // merge rule kept the local bookmark (older or equal)
    const RESULT_BAD_PAIRING = 2;    // pairingId does not match the stored pairing
    const RESULT_BAD_FIELDS = 3;     // missing / wrongly typed field
    const RESULT_UNKNOWN_FOLDER = 4; // not a known book folder
    const RESULT_UNKNOWN_TRACK = 5;  // trk not in the folder's track list
    const RESULT_LIVE = 6;           // folder is being played right now (task 10.3)
    const RESULT_ERROR = 7;

    //! Control-channel handler: applies the message at the current time. Never throws.
    function handle(msg as Dictionary) as Void {
        try {
            var r = apply(msg, Time.now().value());
            DebugLog.log("BM", "rx result=" + r.toString());
        } catch (ex) {
        }
    }

    //! A live marker older than this (seconds) is stale and ignored (PL-5.5, risk R5).
    const LIVE_STALE_SECONDS = 120;

    //! PL-5.5: true while `folder` is the book of the live playback session (marker set by
    //! BookSession, refreshed on each bookmark save), in which case an incoming bookmark must not
    //! be written under the player. A stale marker is ignored and cleared lazily.
    function isLive(folder as String, now as Number) as Boolean {
        var m = Repo.loadLive();
        if (m == null) {
            return false;
        }
        if (now - (m.get("at") as Number) >= LIVE_STALE_SECONDS) {
            Repo.clearLive();
            return false;
        }
        return folder.equals(m.get("f") as String);
    }

    //! The local book folder an incoming bookmark belongs to: by `fid` first (join key), then by
    //! folder name. Null when it is not a known book folder.
    function resolveFolder(fid as String?, name as String?) as String? {
        if (fid != null && !fid.equals("")) {
            var fids = Repo.loadBookFids();
            var keys = fids.keys();
            for (var i = 0; i < keys.size(); i++) {
                var k = keys[i];
                var v = fids.get(k);
                if (k instanceof String && v instanceof String && (v as String).equals(fid)
                        && Repo.isBookFolder(k as String)) {
                    return k as String;
                }
            }
        }
        if (name != null && Repo.isBookFolder(name)) {
            return name;
        }
        return null;
    }

    //! Apply the BOOKMARK `msg` at time `now` (epoch seconds). Returns a RESULT_* code.
    function apply(msg as Dictionary, now as Number) as Number {
        try {
            var pid = Protocol.pairingIdOf(msg);
            var rec = Repo.loadPairing();
            var stored = (rec != null) ? rec.get(Protocol.KEY_PAIRING_ID) : null;
            if (pid == null || !(stored instanceof String) || !(stored as String).equals(pid)) {
                return RESULT_BAD_PAIRING;
            }
            var trk = Protocol.getString(msg, Messages.FIELD_BM_TRK);
            var pos = Protocol.getNumber(msg, Messages.FIELD_BM_POS);
            var at = Protocol.getNumber(msg, Messages.FIELD_BM_AT);
            if (trk == null || pos == null || at == null) {
                return RESULT_BAD_FIELDS;
            }
            var fin = Protocol.getBooleanOr(msg, Messages.FIELD_BM_FIN, false);

            var folder = resolveFolder(Protocol.getString(msg, Messages.FIELD_BM_FID),
                Protocol.getString(msg, Messages.FIELD_BM_FOLDER));
            if (folder == null) {
                return RESULT_UNKNOWN_FOLDER;
            }
            var idx = FolderTracks.indexOf(FolderTracks.orderedIds(folder), trk);
            if (idx < 0) {
                return RESULT_UNKNOWN_TRACK;
            }
            if (isLive(folder, now)) {
                return RESULT_LIVE;
            }
            var eff = BookmarkMerge.decideFor(Repo.loadBookmark(folder), at, now);
            if (eff == null) {
                return RESULT_KEPT_LOCAL;
            }
            Repo.saveBookmarkAt(folder, trk, pos, idx, fin, eff);
            Repo.saveBookmarkRx(folder, eff);
            return RESULT_TAKEN;
        } catch (ex) {
            return RESULT_ERROR;
        }
    }
}
