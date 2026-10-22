import Toybox.Lang;
import Toybox.Media;
import Toybox.Communications;

//! StubDelegates — minimal placeholder delegates used by the mode-routing hooks
//! (task 2.2).
//!
//! TEMPORARY STUBS. The ACP framework asks for a content delegate (playback mode)
//! and a sync delegate (sync mode). Returning functional-but-empty delegates lets
//! those modes launch without crashing (Req 8.5). Replaced by the real
//! implementations in later tasks:
//!   - GsoundContentDelegateStub -> playback/GsoundContentDelegate.mc  [task 9]
//!   - GsoundSyncDelegateStub    -> sync/GsoundSyncDelegate.mc         [task 7]

//! Content-delegate placeholder. Returns a minimal, empty ContentIterator (no
//! tracks) rather than null. The official ACP template's ContentDelegate always
//! returns a real iterator; returning null risks a null-deref in the native
//! player, so the stub mirrors the template with an always-empty iterator. The
//! real delegate returns a FolderIterator over synced tracks in task 9.
class GsoundContentDelegateStub extends Media.ContentDelegate {
    function initialize() {
        ContentDelegate.initialize();
    }

    //! Empty-library iterator; the real delegate returns a FolderIterator (task 9).
    function getContentIterator() as Media.ContentIterator or Null {
        return new EmptyContentIterator();
    }
}

//! Minimal empty ContentIterator: no current track, no next/previous. Mirrors the
//! official ACP template's ContentIterator shape but yields nothing, so the native
//! player shows an empty library instead of crashing (Req 8.5). Replaced by
//! FolderIterator in task 9.
class EmptyContentIterator extends Media.ContentIterator {
    function initialize() {
        ContentIterator.initialize();
    }

    function canSkip() as Lang.Boolean {
        return false;
    }

    function get() as Media.Content or Null {
        return null;
    }

    function getPlaybackProfile() as Media.PlaybackProfile or Null {
        var profile = new Media.PlaybackProfile();
        profile.attemptSkipAfterThumbsDown = false;
        profile.playbackControls = [
            Media.PLAYBACK_CONTROL_SKIP_BACKWARD,
            Media.PLAYBACK_CONTROL_PLAYBACK,
            Media.PLAYBACK_CONTROL_SKIP_FORWARD
        ];
        profile.playbackNotificationThreshold = 1;
        profile.requirePlaybackNotification = false;
        profile.skipPreviousThreshold = null;
        return profile;
    }

    function next() as Media.Content or Null {
        return null;
    }

    function peekNext() as Media.Content or Null {
        return null;
    }

    function peekPrevious() as Media.Content or Null {
        return null;
    }

    function previous() as Media.Content or Null {
        return null;
    }

    function shuffling() as Lang.Boolean {
        return false;
    }
}

//! Sync-delegate placeholder. Reports "nothing to sync" and completes immediately
//! so the system sync flow can run without error. Replaced by GsoundSyncDelegate
//! (Downloader over the pending manifest) in task 7.
class GsoundSyncDelegateStub extends Communications.SyncDelegate {
    function initialize() {
        SyncDelegate.initialize();
    }

    //! Nothing pending in the stub.
    function isSyncNeeded() as Lang.Boolean {
        return false;
    }

    //! No-op sync: immediately signal completion so the system doesn't hang.
    function onStartSync() as Void {
        try {
            Communications.notifySyncComplete(null);
        } catch (ex) {
            // Defensive: never let the stub sync crash the launch (Req 8.5).
        }
    }
}
