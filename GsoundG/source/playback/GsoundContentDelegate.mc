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
        try {
            var folder = _folder;
            if (folder instanceof String && !(folder as String).equals("")) {
                return new FolderIterator(folder as String, _startIndex);
            }
        } catch (ex) {
            // Fall through to the safe empty iterator (Req 8.5).
        }
        return new EmptyContentIterator();
    }
}
