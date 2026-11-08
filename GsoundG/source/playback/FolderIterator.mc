import Toybox.Lang;
import Toybox.Media;
import Toybox.System;

//! FolderIterator — a real Media.ContentIterator over one folder's synced tracks
//! (design §9.3; Req 6.4, 6.5, 8.5, 7.3).
//!
//! This replaces the behavior of StubDelegates' EmptyContentIterator (which always
//! yields nothing). The native audio player drives playback by walking a
//! ContentIterator: it asks for the current track (get()), steps with next()/
//! previous(), and peeks ahead/behind to pre-buffer (peekNext()/peekPrevious()).
//! This iterator exposes exactly one folder's tracks, in index order, with no wrap
//! (Req 6.5).
//!
//! Track ordering (mirrors PlaybackConfigView.refreshTracks EXACTLY): a folder has
//! no stored track list of its own — the persisted index (Repo.loadIndex()) is a
//! flat list of ALL track ids. The folder's ordered list is built by iterating that
//! index and KEEPING only the ids whose entry "folder" String-equals this folder's
//! name. Using the identical filter guarantees the player's track order matches the
//! order the user saw in the playback-config track list.
//!
//! Content construction (design §9.3): each Media.Content is built from the handle
//! the downloader stored in the track entry under "mediaId" (tasks 7.4/7.10). We
//! wrap it in a Media.ContentRef(mediaId, CONTENT_TYPE_AUDIO) and attach a
//! Media.ContentMetadata carrying the entry's title/artist/album so the native
//! player shows real track info rather than a bare handle.
//!
//! KNOWN HARDWARE ASSUMPTION (tasks 7.4/7.10, confirmed on-device in 9.5/11.3):
//! the exact runtime shape of the stored "mediaId" — and therefore whether a
//! playable Media.Content can be rebuilt from it with this per-SDK construct — is
//! only fully verifiable ON HARDWARE. The simulator/compiler confirms the API shape;
//! real playback from a rebuilt Content is a device-only check. This class
//! implements the correct per-SDK approach; hardware confirms the handle round-trip.
//!
//! Resilience & privacy:
//!   - Every Content build is wrapped (contentAt): a bad/corrupt entry returns null
//!     instead of crashing the native player mid-playback (Req 8.5).
//!   - Nothing is logged (Req 7.3).
class FolderIterator extends Media.ContentIterator {

    //! The folder whose tracks this iterator walks. Only used to build the id list.
    private var _folder as String;

    //! Ordered track ids for _folder, built once at construction from Repo's flat
    //! index using the same filter as PlaybackConfigView. Empty if the folder has no
    //! (loadable) tracks or the index read failed.
    private var _ids as Array<String>;

    //! Cursor into _ids pointing at the "current" track. Clamped into range at
    //! construction; next()/previous() may move it one step past either end to mean
    //! "no more" (get() then returns null — end-of-folder stop, no wrap; Req 6.5).
    private var _cursor as Number;

    //! Build a FolderIterator for `folder`, starting at `startIndex` (default 0,
    //! clamped into range). Builds the ordered id list up front; the per-track
    //! Media.Content objects are built lazily in contentAt() so a corrupt entry only
    //! affects its own track.
    function initialize(folder as String, startIndex as Number) {
        ContentIterator.initialize();
        _folder = folder;
        _ids = buildIds(folder);
        DebugLog.log("PB", "FolderIterator folder=" + folder + " tracks=" + _ids.size().toString()
            + " start=" + startIndex.toString());
        var start = startIndex;
        if (start < 0) {
            start = 0;
        }
        if (start >= _ids.size()) {
            start = _ids.size() - 1;
        }
        if (start < 0) {
            start = 0;
        }
        _cursor = start;
    }

    //! Build the ordered track-id list for `folder`. IDENTICAL filter to
    //! PlaybackConfigView.refreshTracks: iterate Repo.loadIndex(), load each entry,
    //! keep ids whose entry "folder" String-equals `folder`. Fully wrapped; any
    //! storage/parse hiccup yields an empty list (Req 8.5).
    private function buildIds(folder as String) as Array<String> {
        var ids = [] as Array<String>;
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
                ids.add(id as String);
            }
        } catch (ex) {
            ids = [] as Array<String>;
        }
        return ids;
    }

    //! Skipping is allowed (Req 6.4) — the player exposes skip-forward/back controls.
    function canSkip() as Lang.Boolean {
        return true;
    }

    //! No shuffle in v1 — tracks play in index order.
    function shuffling() as Lang.Boolean {
        return false;
    }

    //! Playback profile for the native player. COPIED VERBATIM from
    //! EmptyContentIterator.getPlaybackProfile in source/StubDelegates.mc so the
    //! real iterator presents the same controls (Req 6.4): skip back / play / skip
    //! forward, no playback notification requirement, no thumbs-down skip.
    function getPlaybackProfile() as Media.PlaybackProfile or Null {
        DebugLog.log("PB", "iter.getPlaybackProfile");
        var profile = new Media.PlaybackProfile();
        profile.attemptSkipAfterThumbsDown = false;
        // NEXT FIRST: the SDK notes "the first entry in the array may be used as a hotkey in the
        // media player (device dependent)". On the fēnix 6S Pro that hotkey is the bottom-left
        // (DOWN) button; with NEXT first it skips to the next song, matching the built-in music
        // player. This is the ONLY physical-button influence an ACP has — the player UI and the
        // other buttons are system-owned and cannot be remapped. Play/pause and previous stay
        // available as on-screen controls. Track-level previous/next, not seconds-based skips.
        profile.playbackControls = [
            Media.PLAYBACK_CONTROL_NEXT,
            Media.PLAYBACK_CONTROL_PLAYBACK,
            Media.PLAYBACK_CONTROL_PREVIOUS
        ];
        // Ask the player to report song events right away (threshold 0 = as soon as a song
        // begins). With this false we never saw onSong, so we couldn't tell whether a track
        // actually started; now ContentDelegate.onSong logs START/PAUSE/COMPLETE.
        profile.requirePlaybackNotification = true;
        profile.playbackNotificationThreshold = 0;
        // Back within the first 5 s goes to the previous track; later it restarts the track.
        profile.skipPreviousThreshold = 5;
        return profile;
    }

    //! The current track's Content, or null if the cursor is out of range (past
    //! either end → end-of-folder) or the entry couldn't be built.
    function get() as Media.Content or Null {
        DebugLog.log("PB", "iter.get cursor=" + _cursor.toString() + " " + DebugLog.mem());
        var c = contentAt(_cursor);
        DebugLog.log("PB", "iter.get done " + DebugLog.mem());
        return c;
    }

    //! Repeat is off (no wrap, Req 6.5). Implemented explicitly rather than relying on the base.
    function repeatMode() as Media.RepeatMode or Null {
        return Media.REPEAT_MODE_OFF;
    }

    //! Advance to the next track and return it. If that moves past the last track,
    //! the cursor parks one past the end and we return null — END-OF-FOLDER STOP,
    //! NO WRAP (Req 6.5).
    function next() as Media.Content or Null {
        if (_cursor < _ids.size()) {
            _cursor += 1;
        }
        DebugLog.log("PB", "iter.next -> cursor=" + _cursor.toString());
        return contentAt(_cursor);
    }

    //! Step back to the previous track and return it. If that moves before the first
    //! track, the cursor parks at -1 and we return null (no wrap).
    function previous() as Media.Content or Null {
        if (_cursor >= 0) {
            _cursor -= 1;
        }
        DebugLog.log("PB", "iter.previous -> cursor=" + _cursor.toString());
        return contentAt(_cursor);
    }

    //! The next track's Content WITHOUT moving the cursor (null at end — no wrap).
    function peekNext() as Media.Content or Null {
        DebugLog.log("PB", "iter.peekNext cursor=" + _cursor.toString());
        return contentAt(_cursor + 1);
    }

    //! The previous track's Content WITHOUT moving the cursor (null at start).
    function peekPrevious() as Media.Content or Null {
        DebugLog.log("PB", "iter.peekPrevious cursor=" + _cursor.toString());
        return contentAt(_cursor - 1);
    }

    //! Build the Media.Content for the track at `index`, or null on any problem.
    //!
    //! Steps: bounds-check `index`; resolve the track id; load Repo.getTrack(id);
    //! pull the stored "mediaId" handle (may be any Object — if null, bail);
    //! wrap it in a ContentRef(mediaId, CONTENT_TYPE_AUDIO); attach a
    //! ContentMetadata carrying any non-null-String title/artist/album; return
    //! new Media.Content(ref, metadata).
    //!
    //! The whole thing is wrapped in try/catch → null on any failure, so a single
    //! bad/corrupt entry (or an unexpected mediaId shape) can't crash the native
    //! player mid-playback (Req 8.5). See the class-level hardware note: the mediaId
    //! round-trip is device-confirmed in tasks 9.5/11.3.
    private function contentAt(index as Number) as Media.Content? {
        if (index < 0 || index >= _ids.size()) {
            return null;
        }
        try {
            var id = _ids[index];
            var entry = Repo.getTrack(id);
            if (entry == null) {
                return null;
            }
            var mediaId = entry.get("mediaId");
            if (mediaId == null) {
                DebugLog.log("PB", "contentAt[" + index.toString() + "] NO mediaId stored");
                return null;
            }
            var ref = new Media.ContentRef(mediaId, Media.CONTENT_TYPE_AUDIO);

            var metadata = new Media.ContentMetadata();
            var title = entry.get("title");
            if (title instanceof String) {
                metadata.title = title as String;
            }
            var artist = entry.get("artist");
            if (artist instanceof String) {
                metadata.artist = artist as String;
            }
            var album = entry.get("album");
            if (album instanceof String) {
                metadata.album = album as String;
            }

            // Preferred: ask the SYSTEM for the persisted Content (Garmin's documented way to get a
            // playable Content for downloaded audio). It throws if the id isn't a String for audio,
            // or if nothing with that id is cached — exactly the failures we need to see.
            try {
                // Returned UNMODIFIED (no setMetadata): the system already knows this item's
                // metadata from the file, and replacing it on the system's own object is the one
                // thing we do that Garmin's reference flow doesn't — ruled out as a cause here.
                var cached = Media.getCachedContentObj(ref);
                DebugLog.log("PB", "contentAt[" + index.toString() + "] cached OK id=" + DebugLog.shape(mediaId)
                    + " start=" + cached.getPlaybackStartPosition().toString());
                return cached;
            } catch (cex) {
                DebugLog.log("PB", "contentAt[" + index.toString() + "] getCachedContentObj FAILED id="
                    + DebugLog.shape(mediaId) + " " + DebugLog.describe(cex));
            }

            // Fallback (previous behaviour): wrap the stored id in a fresh Content.
            DebugLog.log("PB", "contentAt[" + index.toString() + "] fallback new Content");
            return new Media.Content(ref, metadata);
        } catch (ex) {
            // A bad entry / unexpected mediaId shape must never crash playback (Req 8.5).
            DebugLog.log("PB", "contentAt[" + index.toString() + "] FAILED " + DebugLog.describe(ex));
            return null;
        }
    }
}
