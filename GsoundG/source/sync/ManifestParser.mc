import Toybox.Lang;

//! ManifestParser — reusable defensive ManifestTrack parsing
//! (design.md §6.1, §8.0; Req 3.3, 8.4).
//!
//! Task 7.1 (this module): factored out of SyncController's cleanTrack/cleanTracks
//! so BOTH manifest sources parse identically:
//!   - the inline SYNC_MANIFEST `tracks[]` (SyncController.onManifest, task 6), and
//!   - the HTTP `GET /manifest` fallback body fetched at sync start by
//!     GsoundSyncDelegate.onStartSync (design §6.1) — both yield the same
//!     `ManifestTrack` shape, so the downloader consumes one representation.
//!
//! Every entry is turned from a hostile-until-proven dict into a clean,
//! FIELD_*-keyed ManifestTrack, DROPPING any entry missing a non-empty `id`,
//! `path`, or `mime` (the three fields the downloader must have — design §8.3).
//! Optional fields (folder/title/artist/album/durationMs/sizeBytes) are carried
//! through present-or-absent. Nothing here throws per element — one bad entry is
//! skipped, not fatal (Req 8.4) — and nothing is logged (Req 7.3).
//!
//! Behavior is intentionally IDENTICAL to the original private SyncController
//! methods; SyncController now delegates here so there is a single source of
//! truth for manifest cleaning.
module ManifestParser {

    //! Clean a raw `tracks` array into a list of validated ManifestTrack dicts.
    //! Null input → empty list. Each element must itself be a Dictionary
    //! (non-dict elements are skipped). Each surviving dict is run through
    //! cleanTrack, which drops entries missing a required id/path/mime.
    function cleanTracks(rawTracks as Array?) as Array<Dictionary> {
        var result = [] as Array<Dictionary>;
        if (rawTracks == null) {
            return result;
        }
        for (var i = 0; i < rawTracks.size(); i++) {
            var element = rawTracks[i];
            if (!(element instanceof Dictionary)) {
                // Not a track object — ignore (never trust payload shape).
                continue;
            }
            var track = cleanTrack(element as Dictionary);
            if (track != null) {
                result.add(track);
            }
        }
        return result;
    }

    //! Validate and normalize a single raw track dict into a cleaned ManifestTrack
    //! keyed by Messages.FIELD_* constants, or null when the entry is unusable.
    //!
    //! Required (entry DROPPED if any is missing/empty): `id`, `path`, `mime` —
    //! the downloader needs all three (id = dedupe key + part of the media path,
    //! path = URL suffix appended to serverUrl, mime = media encoding). Optional
    //! fields (folder/title/artist/album/durationMs/sizeBytes) are copied through
    //! only when present and of the right type.
    function cleanTrack(raw as Dictionary) as Dictionary? {
        var id = Protocol.getString(raw, Messages.FIELD_ID);
        var path = Protocol.getString(raw, Messages.FIELD_PATH);
        var mime = Protocol.getString(raw, Messages.FIELD_MIME);
        if (id == null || id.equals("")
                || path == null || path.equals("")
                || mime == null || mime.equals("")) {
            // Missing a field required to download this track — drop the entry.
            return null;
        }

        var track = {
            Messages.FIELD_ID => id,
            Messages.FIELD_PATH => path,
            Messages.FIELD_MIME => mime
        };

        // Optional string fields — carried through only when actually present and
        // a String (null = absent, which the downloader/UI tolerates).
        putIfString(track, raw, Messages.FIELD_FOLDER);
        putIfString(track, raw, Messages.FIELD_TITLE);
        putIfString(track, raw, Messages.FIELD_ARTIST);
        putIfString(track, raw, Messages.FIELD_ALBUM);
        // Phone folder id (join key for BOOKMARK; absent from older phones).
        putIfString(track, raw, Messages.FIELD_BM_FID);

        // Optional numeric fields — likewise present-or-absent.
        putIfNumber(track, raw, Messages.FIELD_DURATION_MS);
        putIfNumber(track, raw, Messages.FIELD_SIZE_BYTES);

        // Audiobook fields (spec gsound-audiobooks AB-3.1). Optional: an older phone omits
        // them (→ music folder, index order). `seq` may arrive as a Long from the phone's
        // JSON bridge, so it is normalised to a Number.
        var seq = seqOf(raw.get(Messages.FIELD_SEQ));
        if (seq != null) {
            track.put(Messages.FIELD_SEQ, seq);
        }
        var book = raw.get(Messages.FIELD_BOOK);
        if (book instanceof Boolean) {
            track.put(Messages.FIELD_BOOK, book);
        }

        return track;
    }

    //! A non-negative Number from a Number or Long value, else null.
    function seqOf(value as Object?) as Number? {
        if (value instanceof Number) {
            return ((value as Number) >= 0) ? (value as Number) : null;
        }
        if (value instanceof Long) {
            var n = (value as Long).toNumber();
            return (n >= 0) ? n : null;
        }
        return null;
    }

    //! Copy `key` from `raw` into `dest` only if present as a String. Keeps
    //! optional fields present-or-absent (never a wrong-typed or defaulted value).
    function putIfString(dest as Dictionary, raw as Dictionary, key as String) as Void {
        var value = Protocol.getString(raw, key);
        if (value != null) {
            dest.put(key, value);
        }
    }

    //! Copy `key` from `raw` into `dest` only if present as a Number. Keeps
    //! optional fields present-or-absent (never a wrong-typed or defaulted value).
    function putIfNumber(dest as Dictionary, raw as Dictionary, key as String) as Void {
        var value = Protocol.getNumber(raw, key);
        if (value != null) {
            dest.put(key, value);
        }
    }
}
