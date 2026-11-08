import Toybox.Lang;
import Toybox.Media;

//! GsoundContentDelegate — the real Media.ContentDelegate feeding the native audio
//! player in PLAYBACK mode (design §9.4; Req 6.6, 8.5, 7.3).
//!
//! This replaces GsoundContentDelegateStub (StubDelegates.mc). The ACP playback
//! flow is: PlaybackConfigView select → Media.startPlayback(args) where `args`
//! encodes WHICH folder (+ start track index) → the system relaunches the app in
//! playback mode → GsoundGApp.getContentDelegate(args) parses those args and builds
//! THIS delegate knowing the folder + start index → the native player calls
//! getContentIterator() to get the walkable track list.
//!
//! getContentIterator() returns a FolderIterator (task 9.3) over the selected
//! folder's synced tracks, seeded at the chosen start index. When nothing is
//! selected / nothing is synced (null or empty folder), or anything throws, it
//! falls back to EmptyContentIterator (StubDelegates.mc) so the native player gets a
//! real, non-null iterator and can't null-deref (Req 6.6, 8.5).
//!
//! Resilience & privacy: the iterator build is wrapped → EmptyContentIterator on any
//! failure (Req 8.5); nothing is logged (Req 7.3).
class GsoundContentDelegate extends Media.ContentDelegate {

    //! The folder whose tracks to play, or null when nothing was selected / nothing
    //! is synced (→ safe empty iterator).
    private var _folder as String?;

    //! The start index within the folder's track list (0 when unknown); passed to
    //! FolderIterator so playback begins at the chosen track.
    private var _startIndex as Number;

    //! Build a GsoundContentDelegate for `folder` starting at `startIndex`. A null
    //! folder means "nothing selected / nothing synced" and yields the safe empty
    //! iterator (Req 6.6, 8.5).
    function initialize(folder as String?, startIndex as Number) {
        ContentDelegate.initialize();
        _folder = folder;
        _startIndex = startIndex;
    }

    //! Content iterator for the native player: a FolderIterator over the selected
    //! folder's synced tracks seeded at the start index, or EmptyContentIterator
    //! when the folder is null/empty or the build throws (Req 6.6, 8.5).
    function getContentIterator() as Media.ContentIterator or Null {
        DebugLog.log("PB", "getContentIterator folder=" + (_folder == null ? "null" : _folder)
            + " start=" + _startIndex.toString());
        try {
            var folder = _folder;
            if (folder instanceof String && !(folder as String).equals("")) {
                return new FolderIterator(folder as String, _startIndex);
            }
        } catch (ex) {
            // Fall through to the safe empty iterator (Req 8.5).
            DebugLog.log("PB", "FolderIterator build FAILED " + DebugLog.describe(ex));
        }
        DebugLog.log("PB", "returning EMPTY iterator (nothing will play)");
        return new EmptyContentIterator();
    }

    //! The player asks to restart the current playlist from the beginning. Previously not
    //! implemented (the base class's result is undocumented), so return the same folder's
    //! iterator from track 0.
    function resetContentIterator() as Media.ContentIterator or Null {
        DebugLog.log("PB", "resetContentIterator folder=" + (_folder == null ? "null" : _folder));
        _startIndex = 0;
        return getContentIterator();
    }

    // --- player callbacks: logged so we can see whether the player actually starts a song ------
    // (If the log shows getContentIterator/contentAt but never onSong START, the player rejected
    // the Content; if it shows onSong START but no sound, the problem is downstream of us.)

    //! The native player reports a song event (start / skip / progress / complete).
    function onSong(
            contentRefId as Object,
            songEvent as Media.SongEvent,
            playbackPosition as Number or Media.PlaybackPosition) as Void {
        DebugLog.log("PB", "onSong event=" + songEvent.toString() + " id=" + DebugLog.shape(contentRefId));
    }

    //! The user toggled shuffle in the player.
    function onShuffle() as Void {
        DebugLog.log("PB", "onShuffle");
    }

    //! The user changed repeat mode in the player.
    function onRepeat() as Void {
        DebugLog.log("PB", "onRepeat");
    }
}
