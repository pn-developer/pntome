import Toybox.Lang;

//! SyncNeed — the single "is a sync needed?" decision (design §8.2), shared by:
//!   - GsoundSyncDelegate.isSyncNeeded() — the system's gate in sync mode, and
//!   - SyncController's auto-start — so we only call Communications.startSync() when the system
//!     will actually run onStartSync().
//!
//! Why shared: the SDK documents that when isSyncNeeded() is false, onStartSync() is NOT called.
//! If we triggered startSync() anyway (e.g. the phone re-sent a manifest whose tracks are all
//! already on the watch), the watch entered sync mode, nothing ever called notifySyncComplete(),
//! and it stayed on "Synchronization in progress".
//!
//! Reads only persisted state (Repo), so it gives the same answer in every ACP process.
module SyncNeed {

    //! True when there is sync work: a pending manifest exists AND either
    //!   - the fetch-at-sync-start fallback is set with a usable serverUrl (we can't diff without
    //!     fetching, so assume work), or
    //!   - its track ids differ from the stored index (an addition or a deletion).
    //! Never throws; any failure degrades to false (Req 8.5).
    function isNeeded() as Boolean {
        try {
            var manifest = Repo.loadPendingManifest();
            if (manifest == null) {
                return false;
            }
            var fetchFlag = Repo.loadPendingFetchFlag();
            if (fetchFlag.get("set") instanceof Boolean && (fetchFlag.get("set") as Boolean)) {
                var serverUrl = manifest.get(Messages.FIELD_SERVER_URL);
                return (serverUrl instanceof String) && !(serverUrl as String).equals("");
            }
            if (manifestDiffersFromIndex(manifest)) {
                return true;
            }
            // Also run a (download-free) sync when the media cache holds audio the library no
            // longer references, so the end-of-sync sweep can delete it.
            return Repo.hasOrphanedMedia();
        } catch (ex) {
            return false;
        }
    }

    //! Number of tracks in the persisted pending manifest (0 when none). Used to report
    //! "N of N tracks present" to the phone when nothing needs downloading.
    function manifestTrackCount() as Number {
        try {
            var manifest = Repo.loadPendingManifest();
            if (manifest != null) {
                var tracks = manifest.get(Messages.FIELD_TRACKS);
                if (tracks instanceof Array) {
                    return (tracks as Array).size();
                }
            }
        } catch (ex) {
        }
        return 0;
    }

    //! True when the manifest's track ids differ from the stored index — any manifest id not in
    //! the index (addition) or any index id not in the manifest (deletion to reconcile).
    function manifestDiffersFromIndex(manifest as Dictionary) as Boolean {
        var tracks = manifest.get(Messages.FIELD_TRACKS);
        var manifestIds = [] as Array<String>;
        if (tracks instanceof Array) {
            var arr = tracks as Array;
            for (var i = 0; i < arr.size(); i++) {
                var track = arr[i];
                if (track instanceof Dictionary) {
                    var id = Protocol.getString(track as Dictionary, Messages.FIELD_ID);
                    if (id != null && !id.equals("")) {
                        manifestIds.add(id);
                    }
                }
            }
        }

        var index = Repo.loadIndex();

        for (var i = 0; i < manifestIds.size(); i++) {
            if (!Repo.containsValue(index, manifestIds[i])) {
                return true;
            }
        }
        for (var i = 0; i < index.size(); i++) {
            var existing = index[i];
            if (existing instanceof String && !listContains(manifestIds, existing as String)) {
                return true;
            }
        }
        return false;
    }

    //! True when `ids` contains a String equal to `value`.
    function listContains(ids as Array<String>, value as String) as Boolean {
        for (var i = 0; i < ids.size(); i++) {
            if (ids[i].equals(value)) {
                return true;
            }
        }
        return false;
    }
}
