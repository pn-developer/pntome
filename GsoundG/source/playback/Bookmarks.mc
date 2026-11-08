import Toybox.Lang;
import Toybox.Media;
import Toybox.System;

//! Bookmarks — per-book resume position (spec gsound-audiobooks AB-5, AB-6; design §4.5).
//!
//! Stored per book folder by Repo ("bm:<folder>" → { trk, pos, idx, fin, at }):
//!   - trk: track id of the chapter, pos: absolute seconds into it,
//!   - idx: its chapter index at save time (fallback when that chapter disappears, AB-5.5),
//!   - fin: the last chapter completed (Resume hidden; playback stops, AB-5.3).
//!
//! Updated only from the native player's onSong events (no periodic callback exists on
//! CIQ 3.4). Position semantics (risk R2): after a resume at offset S the player MAY report
//! positions relative to S. BookSession measures how long the chapter actually played and
//! picks whichever interpretation (p or S+p) matches; both candidates are logged so the first
//! hardware run shows which one the fēnix uses.
module Bookmarks {

    //! Resume rewinds this much for context (AB-6.1).
    const RESUME_REWIND_SEC = 5;

    //! Resolve where Resume starts: { "index" => chapter index in `ids`, "pos" => seconds } or
    //! null when there is no usable bookmark (none, finished, or nothing left to play).
    //! The rewind is applied here (clamped at 0).
    function resolve(folder as String, ids as Array<String>) as Dictionary? {
        var bm = Repo.loadBookmark(folder);
        if (bm == null || Repo.isTrue(bm.get("fin")) || ids.size() == 0) {
            return null;
        }
        var index = FolderTracks.indexOf(ids, bm.get("trk") as String);
        var pos = (bm.get("pos") instanceof Number) ? (bm.get("pos") as Number) : 0;
        if (index < 0) {
            // The bookmarked chapter is gone (AB-5.5): the chapter now at its old index is the
            // next one in order; start it from the beginning.
            var old = bm.get("idx");
            index = (old instanceof Number) ? (old as Number) : 0;
            if (index < 0) {
                index = 0;
            }
            if (index >= ids.size()) {
                return null;
            }
            pos = 0;
        }
        pos -= RESUME_REWIND_SEC;
        if (pos < 0) {
            pos = 0;
        }
        return { "index" => index, "pos" => pos };
    }

    //! Human label for the Resume item, e.g. "Ch 3 · 12:40", or null when Resume isn't offered.
    function resumeLabel(folder as String, ids as Array<String>) as String? {
        var bm = Repo.loadBookmark(folder);
        if (bm == null || Repo.isTrue(bm.get("fin"))) {
            return null;
        }
        var index = FolderTracks.indexOf(ids, bm.get("trk") as String);
        var pos = (bm.get("pos") instanceof Number) ? (bm.get("pos") as Number) : 0;
        if (index < 0) {
            var r = resolve(folder, ids);
            if (r == null) {
                return null;
            }
            index = r.get("index") as Number;
            pos = 0;
        }
        return "Ch " + (index + 1).toString() + " · " + formatTime(pos);
    }

    //! "m:ss" or "h:mm:ss".
    function formatTime(seconds as Number) as String {
        var s = (seconds < 0) ? 0 : seconds;
        var h = s / 3600;
        var m = (s % 3600) / 60;
        var sec = s % 60;
        if (h > 0) {
            return h.toString() + ":" + m.format("%02d") + ":" + sec.format("%02d");
        }
        return m.toString() + ":" + sec.format("%02d");
    }
}

//! BookSession — bookmark bookkeeping for one playback session of one book, driven by the
//! content delegate's onSong. Lives in the playback process only.
class BookSession {

    private var _folder as String;
    private var _ids as Array<String>;

    //! mediaId → track id for this book (onSong reports the content ref id = mediaId).
    private var _byMedia as Dictionary;

    //! The chapter that the resume offset applies to, and the offset (seconds). Cleared once
    //! that chapter has started, so later chapters start at 0 (AB-6.3).
    private var _resumeTrack as String?;
    private var _resumeOffset as Number;

    //! Current chapter + its offset (the resume offset for the resumed chapter, else 0).
    private var _curTrack as String?;
    private var _curOffset as Number;

    //! Wall-clock playing time of the current chapter (ms), to disambiguate positions (R2).
    private var _playedMs as Number;
    private var _playingSince as Number?;

    function initialize(folder as String, ids as Array<String>, startIndex as Number, startPos as Number) {
        _folder = folder;
        _ids = ids;
        _byMedia = {};
        for (var i = 0; i < ids.size(); i++) {
            var entry = Repo.getTrack(ids[i]);
            var mediaId = (entry != null) ? entry.get("mediaId") : null;
            if (mediaId != null) {
                _byMedia.put(mediaId as Object, ids[i]);
            }
        }
        _resumeTrack = (startPos > 0 && startIndex >= 0 && startIndex < ids.size()) ? ids[startIndex] : null;
        _resumeOffset = (_resumeTrack != null) ? startPos : 0;
        _curTrack = null;
        _curOffset = 0;
        _playedMs = 0;
        _playingSince = null;
    }

    //! Handle a player event for this book. Never throws.
    function onSong(contentRefId as Object, songEvent as Number, playbackPosition as Number) as Void {
        try {
            var trk = _byMedia.get(contentRefId) as String?;
            if (trk == null) {
                DebugLog.log("BM", "event " + songEvent.toString() + " for unknown media "
                    + DebugLog.shape(contentRefId));
                return;
            }
            var now = System.getTimer();

            if (songEvent == Media.SONG_EVENT_START) {
                _curTrack = trk;
                if (_resumeTrack != null && trk.equals(_resumeTrack)) {
                    _curOffset = _resumeOffset;
                    _resumeTrack = null; // only the first start of the resumed chapter
                } else {
                    _curOffset = 0;
                }
                _playedMs = 0;
                _playingSince = now;
                save(trk, _curOffset, false);
                DebugLog.log("BM", "start idx=" + indexOf(trk).toString() + " off=" + _curOffset.toString());
                return;
            }

            if (songEvent == Media.SONG_EVENT_RESUME) {
                _playingSince = now;
                return;
            }

            if (songEvent == Media.SONG_EVENT_PLAYBACK_NOTIFY) {
                return;
            }

            // PAUSE / STOP / SKIP_* / COMPLETE: close the playing interval first.
            if (_playingSince != null) {
                _playedMs += now - (_playingSince as Number);
                _playingSince = null;
            }

            if (songEvent == Media.SONG_EVENT_COMPLETE) {
                var idx = indexOf(trk);
                if (idx >= 0 && idx + 1 < _ids.size()) {
                    save(_ids[idx + 1], 0, false);
                    DebugLog.log("BM", "complete idx=" + idx.toString() + " -> next at 0");
                } else {
                    save(trk, 0, true);
                    DebugLog.log("BM", "complete last chapter -> finished");
                }
                return;
            }

            // PAUSE (6), STOP (5), SKIP_NEXT (1), SKIP_PREVIOUS (2), skip fwd/back (8/9 on 4.2.4+).
            var abs = absolutePosition(trk, playbackPosition);
            save(trk, abs, false);
        } catch (ex) {
            DebugLog.log("BM", "onSong FAILED " + DebugLog.describe(ex));
        }
    }

    //! The absolute position for a reported `pos` (R2). Without an offset it is `pos`. With a
    //! resume offset S, the player reports either S+played (absolute) or played (relative):
    //! pick the candidate closest to the measured playing time.
    function absolutePosition(trk as String, pos as Number) as Number {
        if (_curTrack == null || !trk.equals(_curTrack) || _curOffset <= 0) {
            return pos;
        }
        var played = _playedMs / 1000;
        var asRelative = (pos - played).abs();               // pos ≈ played  → relative
        var asAbsolute = (pos - (_curOffset + played)).abs(); // pos ≈ S+played → absolute
        var result = (asRelative < asAbsolute) ? pos + _curOffset : pos;
        DebugLog.log("BM", "pos=" + pos.toString() + " played=" + played.toString()
            + " off=" + _curOffset.toString() + " -> " + ((asRelative < asAbsolute) ? "REL" : "ABS")
            + " abs=" + result.toString());
        return result;
    }

    private function indexOf(trk as String) as Number {
        return FolderTracks.indexOf(_ids, trk);
    }

    private function save(trk as String, pos as Number, finished as Boolean) as Void {
        Repo.saveBookmark(_folder, trk, pos, indexOf(trk), finished);
    }
}
