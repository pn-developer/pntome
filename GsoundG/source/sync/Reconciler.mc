import Toybox.Lang;

//! Reconciler — delete tracks that are no longer in the manifest (design.md §8.4,
//! §8.5; Req 4.5).
//!
//! Task 7.6 (this module): after the Downloader's work set drains on a NORMAL,
//! non-cancelled, non-error completion, the watch's stored library can still hold
//! tracks the phone no longer lists — a track the user removed on the phone side.
//! `deleteMissing(manifest)` is the one pass that brings the watch back in line
//! with the authoritative manifest: for every id in the persisted index that is
//! NOT present in the manifest's track list, `Repo.deleteTrack(id)` removes the
//! `trk:<id>` entry, drops the id from the chunked index, and rebuilds the
//! distinct `folders` list (design §8.5). A track added on the phone is handled by
//! the Downloader's dedupe/download path; a track removed on the phone is handled
//! HERE — together they make the watch mirror the phone (Req 4.5).
//!
//! Pure in spirit beyond the storage writes: no network, no logging (the pairing
//! secret is not even in scope here, but the no-logging discipline holds — this
//! module never prints), and defensive about the manifest shape so a malformed
//! payload can't crash reconciliation (Req 8.5). A module (not a class) to match
//! the ManifestParser / MimeEncoding / Repo style.
//!
//! Storage note (Req 4.9): the storage-full guard the requirement describes is a
//! DOWNLOAD-path concern — `Repo.putTrack` → `Application.Storage.setValue` throws
//! `Toybox.Lang.StorageFullException` only when WRITING a new value runs out of
//! space. Deletion FREES space, so `deleteMissing` does not trigger storage-full;
//! the Downloader owns the put-path guard (see Downloader.handleOk). This module
//! is still defensive per-delete so one failing delete can't abort the whole
//! reconciliation, and it reports what happened back to the caller.
module Reconciler {

    //! Delete every persisted track whose id is absent from `manifest`'s track
    //! list (Req 4.5). Returns a small result dict the Downloader can act on /
    //! surface without logging:
    //!   {
    //!     "deleted" => Number,   // tracks removed because they left the manifest
    //!     "errored" => Number    // per-delete failures swallowed to keep going
    //!   }
    //!
    //! Steps:
    //!   1. Build the set of ids the manifest still lists (defensive about shape:
    //!      a missing/!Array `tracks`, a non-Dictionary entry, or a track with no
    //!      usable String `id` is simply skipped — it just means "keep nothing on
    //!      its behalf").
    //!   2. Load the current persisted index (`Repo.loadIndex()`).
    //!   3. For every index id NOT in the manifest set, `Repo.deleteTrack(id)`.
    //!      Each delete is wrapped so a single failure (e.g. a transient storage
    //!      read error) increments `errored` and the pass continues rather than
    //!      aborting — a partial reconciliation is better than none, and deletes
    //!      free space so they do not hit storage-full.
    //!
    //! An empty manifest (no tracks) legitimately means "the phone has nothing" →
    //! every stored track is missing from it → the whole library is deleted. That
    //! is the correct reconciliation outcome and matches Req 4.5. The caller
    //! (Downloader) only runs this on a clean, non-cancelled, non-error completion
    //! (design §8.4), so a partially-processed sync never deletes the user's music.
    //!
    //! @param manifest the resolved manifest { serverUrl, tracks:[ ManifestTrack ] }.
    //! @return a result dict { deleted, errored } (both Numbers).
    function deleteMissing(manifest as Dictionary) as Dictionary {
        var deleted = 0;
        var errored = 0;

        var present = manifestIdSet(manifest);
        var index = Repo.loadIndex();

        for (var i = 0; i < index.size(); i++) {
            var id = index[i];
            if (!(id instanceof String) || (id as String).equals("")) {
                continue;
            }
            var idStr = id as String;
            // Keep ids the manifest still lists; delete the rest (Req 4.5).
            if (Repo.containsValue(present, idStr)) {
                continue;
            }
            try {
                Repo.deleteTrack(idStr);
                deleted++;
            } catch (ex) {
                // One failing delete must not abort the whole reconciliation
                // (Req 8.5). Deletes free space, so storage-full is not expected
                // here; count it and move on.
                errored++;
            }
        }

        return { "deleted" => deleted, "errored" => errored };
    }

    //! Build the list of String ids the manifest still lists. Defensive about the
    //! payload shape (Req 8.5): a missing/non-Array `tracks`, a non-Dictionary
    //! entry, or an entry whose `id` is missing/not a String is skipped. Ids are
    //! read via `Protocol.getString` so the extraction matches how intake and the
    //! downloader read the same field. Returned as an Array<Object?> so it can be
    //! passed straight to `Repo.containsValue`.
    function manifestIdSet(manifest as Dictionary) as Array<Object?> {
        var ids = [] as Array<Object?>;
        var tracks = manifest.get(Messages.FIELD_TRACKS);
        if (!(tracks instanceof Array)) {
            return ids;
        }
        var all = tracks as Array<Object?>;
        for (var i = 0; i < all.size(); i++) {
            var track = all[i];
            if (!(track instanceof Dictionary)) {
                continue;
            }
            var id = Protocol.getString(track, Messages.FIELD_ID);
            if (id != null && !id.equals("") && !Repo.containsValue(ids, id)) {
                ids.add(id);
            }
        }
        return ids;
    }
}
