import Toybox.Lang;
import Toybox.Media;

//! PNTomeContentDelegate — the real Media.ContentDelegate feeding the native audio
//! player in PLAYBACK mode (design §9.4; Req 6.6, 8.5, 7.3).
//!
//! The ACP playback flow is: playback-config → PlaybackStarter → Media.startPlayback(args)
//! → the system relaunches the app in playback mode → PNTomeGoApp.getContentDelegate(args)
//! resolves folder / start index / resume position and builds THIS delegate → the native
//! player calls getContentIterator() to get the walkable track list.
//!
//! getContentIterator() returns a FolderIterator over the folder's tracks in play order,
//! seeded at the start index (and, for a resumed book chapter, the start position). When
//! nothing is selected / nothing is synced (null or empty folder), or anything throws, it
//! falls back to EmptyContentIterator so the native player gets a real, non-null iterator.
//!
//! Audiobooks (spec gsound-audiobooks AB-5): for a book folder, onSong events drive a
//! BookSession that keeps the book's bookmark current.
class PNTomeContentDelegate extends Media.ContentDelegate {

    //! The folder whose tracks to play, or null when nothing was selected / nothing
    //! is synced (→ safe empty iterator).
    private var _folder as String?;

    //! The start index within the folder's play order (0 when unknown).
    private var _startIndex as Number;

    //! Resume position in seconds for the start track (books; 0 = from the beginning).
    private var _startPos as Number;

    //! True when _folder is an audiobook folder.
    private var _isBook as Boolean;

    //! Bookmark bookkeeping for a book (null for music / until the iterator exists).
    private var _session as BookSession?;

    //! Build a PNTomeContentDelegate for `folder` starting at `startIndex` / `startPos`. A null
    //! folder means "nothing selected / nothing synced" and yields the safe empty iterator.
    function initialize(folder as String?, startIndex as Number, startPos as Number, isBook as Boolean) {
        ContentDelegate.initialize();
        _folder = folder;
        _startIndex = startIndex;
        _startPos = startPos;
        _isBook = isBook;
        _session = null;
    }

    //! Content iterator for the native player: a FolderIterator over the selected
    //! folder's synced tracks seeded at the start index, or EmptyContentIterator
    //! when the folder is null/empty or the build throws (Req 6.6, 8.5).
    function getContentIterator() as Media.ContentIterator or Null {
        DebugLog.log("PB", "getContentIterator folder=" + (_folder == null ? "null" : _folder)
            + " start=" + _startIndex.toString() + " pos=" + _startPos.toString());
        try {
            var folder = _folder;
            if (folder instanceof String && !(folder as String).equals("")) {
                var iterator = new FolderIterator(folder as String, _startIndex, _startPos, _isBook);
                if (_isBook) {
                    _session = new BookSession(folder as String, iterator.ids(), _startIndex, _startPos);
                }
                return iterator;
            }
        } catch (ex) {
            // Fall through to the safe empty iterator (Req 8.5).
            DebugLog.log("PB", "FolderIterator build FAILED " + DebugLog.describe(ex));
        }
        DebugLog.log("PB", "returning EMPTY iterator (nothing will play)");
        return new EmptyContentIterator();
    }

    //! The player asks to restart the current playlist from the beginning: same folder from
    //! track 0 at 0:00.
    function resetContentIterator() as Media.ContentIterator or Null {
        DebugLog.log("PB", "resetContentIterator folder=" + (_folder == null ? "null" : _folder));
        _startIndex = 0;
        _startPos = 0;
        return getContentIterator();
    }

    //! The native player reports a song event (start / skip / pause / complete ...). Logged,
    //! and for a book forwarded to the bookmark session (AB-5.2).
    function onSong(
            contentRefId as Object,
            songEvent as Media.SongEvent,
            playbackPosition as Number or Media.PlaybackPosition) as Void {
        DebugLog.log("PB", "onSong event=" + songEvent.toString() + " id=" + DebugLog.shape(contentRefId)
            + " pos=" + playbackPosition.toString());
        var session = _session;
        if (session != null) {
            session.onSong(contentRefId, songEvent as Number, playbackPosition as Number);
        }
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
