import Toybox.Lang;
import Toybox.WatchUi;

//! BookOptionsMenu — what to do with a selected audiobook folder (spec gsound-audiobooks AB-4,
//! design §4.4). A native WatchUi.Menu2 (API 3.0+), so it looks and navigates like the
//! system menus:
//!   - Resume          "Ch N · m:ss" — only when the book has an unfinished bookmark; first
//!                     (and therefore highlighted) item when present (AB-4.3, 4.4).
//!   - Play from start chapter 1 at 0:00.
//!   - Chapters        the chapter list (PlaybackConfigView); picking one starts it at 0:00.
//! Every choice continues through the following chapters to the end of the book (AB-4.5).
module BookOptionsMenu {

    const ID_RESUME = :resume;
    const ID_START = :start;
    const ID_CHAPTERS = :chapters;

    //! Build and push the menu for `folder`. Falls back to the chapter list if the menu can't be
    //! built (design §5). Never throws.
    function push(folder as String) as Void {
        try {
            var menu = new WatchUi.Menu2({ :title => folder });
            var label = Bookmarks.resumeLabel(folder, FolderTracks.orderedIds(folder));
            if (label != null) {
                menu.addItem(new WatchUi.MenuItem(
                    WatchUi.loadResource(Rez.Strings.BookResume) as String, label, ID_RESUME, {}));
            }
            var bm = Repo.loadBookmark(folder);
            var startSub = (bm != null && Repo.isTrue(bm.get("fin")))
                ? WatchUi.loadResource(Rez.Strings.BookFinished) as String
                : null;
            menu.addItem(new WatchUi.MenuItem(
                WatchUi.loadResource(Rez.Strings.BookFromStart) as String, startSub, ID_START, {}));
            menu.addItem(new WatchUi.MenuItem(
                WatchUi.loadResource(Rez.Strings.BookChapters) as String, null, ID_CHAPTERS, {}));
            DebugLog.log("BOOK", "options folder=" + folder + " resume=" + (label == null ? "none" : label));
            WatchUi.pushView(menu, new BookOptionsDelegate(folder), WatchUi.SLIDE_LEFT);
        } catch (ex) {
            DebugLog.log("BOOK", "options menu FAILED " + DebugLog.describe(ex) + " -> chapters");
            pushChapters(folder);
        }
    }

    //! Push the chapter list for `folder`. Never throws.
    function pushChapters(folder as String) as Void {
        try {
            var view = new PlaybackConfigView(folder);
            WatchUi.pushView(view, new PlaybackConfigDelegate(folder, view), WatchUi.SLIDE_LEFT);
        } catch (ex) {
            // Never crash input handling (Req 8.5).
        }
    }
}

//! Input for BookOptionsMenu.
class BookOptionsDelegate extends WatchUi.Menu2InputDelegate {

    private var _folder as String;

    function initialize(folder as String) {
        Menu2InputDelegate.initialize();
        _folder = folder;
    }

    function onSelect(item as WatchUi.MenuItem) as Void {
        var id = item.getId();
        if (id == BookOptionsMenu.ID_RESUME) {
            PlaybackStarter.resume(_folder);
        } else if (id == BookOptionsMenu.ID_START) {
            PlaybackStarter.startAt(_folder, 0);
        } else if (id == BookOptionsMenu.ID_CHAPTERS) {
            BookOptionsMenu.pushChapters(_folder);
        }
    }

    function onBack() as Void {
        WatchUi.popView(WatchUi.SLIDE_RIGHT);
    }
}
