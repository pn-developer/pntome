import Toybox.Lang;
import Toybox.Media;

//! StubDelegates — shared safe-empty ContentIterator used by the playback path.
//!
//! The content-delegate stub (GsoundContentDelegateStub) was removed in task 9.4
//! when the real playback/GsoundContentDelegate.mc replaced it. What remains here
//! is EmptyContentIterator: a functional-but-empty ContentIterator that
//! GsoundContentDelegate returns as its SAFE FALLBACK whenever nothing is selected
//! / nothing is synced, or the real FolderIterator can't be built (Req 6.6, 8.5).
//! Returning this (never null) keeps the native player from null-dereffing.
//!
//! The sync-delegate stub (GsoundSyncDelegateStub) was removed in task 7.1 when the
//! real sync/GsoundSyncDelegate.mc replaced it.

//! Minimal empty ContentIterator: no current track, no next/previous. Mirrors the
//! official ACP template's ContentIterator shape but yields nothing, so the native
//! player shows an empty library instead of crashing (Req 8.5). Used by
//! GsoundContentDelegate as the safe-empty fallback (the real tracks come from
//! FolderIterator, task 9.3).
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

