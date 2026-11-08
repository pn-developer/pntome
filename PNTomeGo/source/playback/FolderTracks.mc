import Toybox.Lang;

//! FolderTracks — the ONE definition of a folder's play order (spec gsound-audiobooks AB-2,
//! design §4.3), shared by the track/chapter list (PlaybackConfigView), the player
//! (FolderIterator) and the bookmark logic, so a "start index" means the same track everywhere.
//!
//! Order: filter the flat index by `entry.folder == folder`, then a STABLE sort by the
//! phone-assigned `seq` (natural file-name order). Entries without `seq` (synced by an older
//! phone) keep their index order and come after the sequenced ones.
//!
//! The sort is a stable merge sort (O(n log n)) so a large music folder stays well inside the
//! Connect IQ watchdog budget.
module FolderTracks {

    //! Sort key for entries without `seq`: after every sequenced track.
    const NO_SEQ = 0x7FFFFFFF;

    //! Ordered track ids of `folder`. Never throws (empty on any failure).
    function orderedIds(folder as String) as Array<String> {
        var ids = [] as Array<String>;
        var keys = [] as Array<Number>;
        try {
            var all = Repo.loadIndex();
            for (var i = 0; i < all.size(); i++) {
                var id = all[i];
                if (!(id instanceof String) || (id as String).equals("")) {
                    continue;
                }
                var entry = Repo.getTrack(id as String);
                if (entry == null) {
                    continue;
                }
                var f = entry.get("folder");
                if (!(f instanceof String) || !(f as String).equals(folder)) {
                    continue;
                }
                var seq = entry.get("seq");
                ids.add(id as String);
                keys.add((seq instanceof Number) ? (seq as Number) : NO_SEQ);
            }
            return sortedByKey(ids, keys);
        } catch (ex) {
            return [] as Array<String>;
        }
    }

    //! `ids` reordered by ascending `keys`, stable (equal keys keep their input order).
    function sortedByKey(ids as Array<String>, keys as Array<Number>) as Array<String> {
        var n = ids.size();
        // Sort an index permutation, then map it back to ids.
        var order = new [n] as Array<Number>;
        for (var i = 0; i < n; i++) {
            order[i] = i;
        }
        var buf = new [n] as Array<Number>;
        var width = 1;
        while (width < n) {
            var lo = 0;
            while (lo < n) {
                var mid = (lo + width < n) ? lo + width : n;
                var hi = (lo + 2 * width < n) ? lo + 2 * width : n;
                // Merge order[lo, mid) and order[mid, hi) into buf[lo, hi).
                var a = lo;
                var b = mid;
                var k = lo;
                while (a < mid && b < hi) {
                    // `<=` keeps it stable: the left run wins ties.
                    if (keys[order[a]] <= keys[order[b]]) {
                        buf[k] = order[a];
                        a += 1;
                    } else {
                        buf[k] = order[b];
                        b += 1;
                    }
                    k += 1;
                }
                while (a < mid) {
                    buf[k] = order[a];
                    a += 1;
                    k += 1;
                }
                while (b < hi) {
                    buf[k] = order[b];
                    b += 1;
                    k += 1;
                }
                lo = hi;
            }
            var tmp = order;
            order = buf;
            buf = tmp;
            width *= 2;
        }
        var out = [] as Array<String>;
        for (var i = 0; i < n; i++) {
            out.add(ids[order[i]]);
        }
        return out;
    }

    //! Position of `trackId` within `ids`, or -1.
    function indexOf(ids as Array<String>, trackId as String?) as Number {
        if (trackId == null) {
            return -1;
        }
        for (var i = 0; i < ids.size(); i++) {
            if (ids[i].equals(trackId)) {
                return i;
            }
        }
        return -1;
    }
}
