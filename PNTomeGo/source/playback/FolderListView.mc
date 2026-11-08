import Toybox.Lang;
import Toybox.WatchUi;
import Toybox.Graphics;

//! FolderListView + FolderListDelegate — the playback-configuration mode surface
//! (design.md §5, §9.1; Req 6.1, 6.6).
//!
//! Task 9.1 (this file): replace the FolderListStub view/delegate with the real
//! pair. The playback-config mode is what the user lands in from the device's
//! native music player when they pick PNTomeGo as a provider and choose what to
//! play. The view LISTS the synced folder names (Repo.loadFolders()); the delegate
//! maps the user's up/down/select input onto that list and, on Select, navigates
//! toward playback of the chosen folder.
//!
//! View approach — why a raw-Dc View + BehaviorDelegate (not a Menu2):
//! getPlaybackConfigurationView() MUST return a [view, delegate] PAIR per the ACP
//! contract (design §5). A WatchUi.Menu2 is shown via WatchUi.pushView, not
//! returned from a hook, so it doesn't fit the pair contract cleanly. A plain
//! View + BehaviorDelegate — matching SyncConfigView/PairingView — renders the
//! folder list, handles selection, AND satisfies the [view, delegate] contract the
//! app already documents. It also lets the SAME view render both the folder list
//! and the empty state (Req 6.6) without swapping view classes.
//!
//! Folder list (design §9.1): read LIVE from Repo.loadFolders() each onUpdate so
//! the screen reflects whatever has been synced. loadFolders() returns an
//! Array<Object?> of folder-name strings; a highlighted index tracks the user's
//! current selection and the list scrolls to keep it visible.
//!
//! Empty state (Req 6.6): when nothing is synced (loadFolders() empty) the view
//! shows a "No music yet / Sync from your phone" prompt rather than a blank list,
//! and Select is a no-op (there is nothing to play).
//!
//! Selection seam for task 9.2: on Select the delegate resolves the highlighted
//! folder name and is meant to push PlaybackConfigView (that folder's track list)
//! — but PlaybackConfigView is task 9.2 and does not exist yet. To keep this task
//! compiling and self-contained, onSelect() resolves + validates the folder name
//! and then calls openFolder(), which is a DOCUMENTED TODO SEAM: task 9.2 fills in
//! the WatchUi.pushView(new PlaybackConfigView(folderName), ...). It is NOT a
//! placeholder view (that would duplicate 9.2's work) — just the clearly marked
//! spot where 9.2 wires the push. Until then Select safely does nothing.
//!
//! Resilience & privacy:
//!   - Every Repo read and the whole draw/input path is wrapped so the
//!     playback-config launch mode can never crash (Req 8.5). Any failure degrades
//!     to the empty state / a neutral no-op.
//!   - Nothing sensitive is shown or logged (Req 7.3) — only folder names.

class FolderListView extends WatchUi.View {

    //! Cached folder-name list for the current frame, refreshed from Repo each
    //! onUpdate. Also read by the paired delegate (via the app) to resolve the
    //! highlighted selection. Always a plain Array of strings (empty when nothing
    //! is synced or a read fails).
    private var _folders as Array<String>;

    //! Index of the currently highlighted folder in _folders. Clamped into range
    //! on every redraw so it can never point past the end after the list shrinks.
    private var _selected as Number;

    //! One-shot latch threaded through PairingView.presentIfAwaiting so an inbound
    //! PAIR_REQUEST presents the pairing screen exactly once while this view is up,
    //! regardless of redraw frequency. See PairingView.presentIfAwaiting.
    private var _pairingPresented as Boolean;

    //! Build a FolderListView. Starts with an empty cached list + selection 0; the
    //! real folder names are pulled live from Repo in onUpdate so the screen always
    //! reflects the current library.
    function initialize() {
        View.initialize();
        _folders = [] as Array<String>;
        _selected = 0;
        _pairingPresented = false;
    }

    //! Refresh the cached folder list from Repo.loadFolders(), coercing every entry
    //! to a non-empty String and dropping anything malformed so a corrupt stored
    //! value can't crash the draw (Req 8.5). Wrapped end-to-end; any failure yields
    //! an empty list (→ empty state).
    private function refreshFolders() as Void {
        var names = [] as Array<String>;
        try {
            var raw = Repo.loadFolders();
            for (var i = 0; i < raw.size(); i++) {
                var name = raw[i];
                if (name instanceof String && !(name as String).equals("")) {
                    names.add(name as String);
                }
            }
        } catch (ex) {
            // Any storage/parse hiccup → treat as nothing synced (Req 8.5).
            names = [] as Array<String>;
        }
        _folders = names;

        // Keep the highlight in range after the list may have changed.
        if (_selected >= _folders.size()) {
            _selected = _folders.size() - 1;
        }
        if (_selected < 0) {
            _selected = 0;
        }
    }

    //! Render the folder list, or a pairing-aware empty state when nothing is
    //! synced. Reads the folder names live from Repo each frame and the pairing
    //! state live from the PairingManager. Fully wrapped so playback-config mode
    //! can never crash (Req 8.5); only folder names / counts are shown, never the
    //! secret (Req 7.3).
    function onUpdate(dc as Graphics.Dc) as Void {
        // Surface pairing regardless of screen: if a PAIR_REQUEST arrived while
        // this playback-config view is up, present the PairingView (Req 2.2-2.4).
        _pairingPresented = PairingView.presentIfAwaiting(_pairingPresented);

        refreshFolders();

        var title = WatchUi.loadResource(Rez.Strings.FolderListTitle) as String;
        var paired = isPaired();

        // Empty state (Req 6.6): nothing synced. The prompt now depends on pairing:
        //   - not paired → "Pair with phone first" (pairing is the prerequisite).
        //   - paired     → "Sync from your phone" (pairing done, just sync music).
        if (_folders.size() == 0) {
            var prompt = paired
                ? WatchUi.loadResource(Rez.Strings.FolderListEmptyPrompt) as String
                : WatchUi.loadResource(Rez.Strings.FolderListPairFirst) as String;
            StubViews.drawCentered(dc,
                WatchUi.loadResource(Rez.Strings.FolderListEmptyTitle) as String,
                prompt);
            drawVersionFooter(dc);
            return;
        }

        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_BLACK);
        dc.clear();

        var cx = dc.getWidth() / 2;
        var cy = dc.getHeight() / 2;

        // Title + a small pairing/stats status line at the top.
        dc.drawText(cx, 16, Graphics.FONT_XTINY, title,
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        if (paired) {
            // "Paired · N files" — confirms the pairing is active and how much is
            // synced, and the Menu-cancel hint (handled by the delegate's onMenu).
            var fileCount = syncedFileCount();
            var status = (WatchUi.loadResource(Rez.Strings.FolderListPaired) as String)
                + " · " + fileCount.toString() + " "
                + (WatchUi.loadResource(Rez.Strings.FolderListFilesLabel) as String);
            dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_BLACK);
            dc.drawText(cx, 38, Graphics.FONT_XTINY, status,
                Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
            dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_BLACK);
        }

        // Draw a short vertical window of folder names centered on the highlighted
        // one. The highlighted row is emphasized (larger font); neighbours are
        // dimmed so the current selection is obvious on both button and touch
        // models.
        var rowSpacing = 30;
        var window = 2; // rows above/below the highlight to show
        // Audiobook folders are marked with a text prefix (AB-4.1) — plain text, since device
        // fonts have no book glyph. One small storage read per frame.
        var books = [] as Array<Object?>;
        try {
            books = Repo.loadBookFolders();
        } catch (ex) {
        }
        var bookPrefix = WatchUi.loadResource(Rez.Strings.FolderListBookPrefix) as String;
        for (var offset = -window; offset <= window; offset++) {
            var idx = _selected + offset;
            if (idx < 0 || idx >= _folders.size()) {
                continue;
            }
            var y = cy + offset * rowSpacing;
            var label = Repo.containsValue(books, _folders[idx]) ? bookPrefix + _folders[idx] : _folders[idx];
            if (offset == 0) {
                dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_BLACK);
                dc.drawText(cx, y, Graphics.FONT_SMALL, label,
                    Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
            } else {
                dc.setColor(Graphics.COLOR_DK_GRAY, Graphics.COLOR_BLACK);
                dc.drawText(cx, y, Graphics.FONT_XTINY, label,
                    Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
            }
        }

        drawVersionFooter(dc);
    }

    //! Draw the app version string ("v<semver> (<commit>)") centered near the bottom of the
    //! screen in a dim gray XTINY font, shown on both the folder list and its empty state so the
    //! build is always identifiable from the watch. Exception-free: only a draw call plus the
    //! pure AppVersion.display().
    //!
    //! Positioned at ~85% of the height (NOT the very bottom edge): on a ROUND fēnix display the
    //! circular bezel clips the extreme top/bottom, so `height - 6` sat in the cut-off zone and
    //! was barely readable. 85% keeps the whole line inside the safe area. The text is
    //! bottom-anchored (TEXT_JUSTIFY_VCENTER at that y) so descenders stay clear of the edge.
    static function drawVersionFooter(dc as Graphics.Dc) as Void {
        dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_TRANSPARENT);
        dc.drawText(
            dc.getWidth() / 2,
            (dc.getHeight() * 85) / 100,
            Graphics.FONT_XTINY,
            AppVersion.display(),
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
    }

    //! Number of folders currently listed (0 in the empty state). Lets the paired
    //! delegate guard its navigation without re-reading storage.
    function folderCount() as Number {
        return _folders.size();
    }

    //! True when a pairing record is currently active. Read live from the
    //! PairingManager (which reflects the persisted record) so the view shows the
    //! current pairing status each frame. Fully wrapped; any failure degrades to
    //! "not paired" so a read hiccup can't crash the view (Req 8.5).
    function isPaired() as Boolean {
        try {
            var manager = getApp().getPairingManager();
            return manager != null && manager.isPaired();
        } catch (ex) {
            return false;
        }
    }

    //! Number of synced files (tracks) currently cached, read from the persisted
    //! stats via StatsStore. Defensive: any failure yields 0 rather than crashing
    //! the view (Req 8.5). Shown in the paired status line.
    function syncedFileCount() as Number {
        try {
            return StatsStore.fileCountOf(StatsStore.load());
        } catch (ex) {
            return 0;
        }
    }

    //! The currently highlighted folder index.
    function selectedIndex() as Number {
        return _selected;
    }

    //! Move the highlight to `index`, clamped into [0, count-1]. Used by the
    //! delegate's up/down navigation. A no-op when the list is empty.
    function setSelectedIndex(index as Number) as Void {
        var count = _folders.size();
        if (count == 0) {
            _selected = 0;
            return;
        }
        var clamped = index;
        if (clamped < 0) {
            clamped = 0;
        }
        if (clamped >= count) {
            clamped = count - 1;
        }
        _selected = clamped;
    }

    //! The folder name at the currently highlighted index, or null when the list is
    //! empty / the index is somehow out of range (defensive; Req 8.5). Used by the
    //! delegate's Select action to know which folder to open.
    function selectedFolder() as String? {
        if (_selected < 0 || _selected >= _folders.size()) {
            return null;
        }
        return _folders[_selected];
    }
}

//! FolderListDelegate — input delegate paired with FolderListView (design §5,
//! §9.1; Req 6.1, 6.6).
//!
//! Navigation:
//!   - NEXT PAGE / DOWN (onNextPage) → move the highlight down one folder.
//!   - PREVIOUS PAGE / UP (onPreviousPage) → move the highlight up one folder.
//!   - SELECT (onSelect — ENTER/START or a center tap) → open the highlighted
//!     folder (seam for task 9.2; see openFolder()).
//!   - BACK/MENU → leave playback-config.
//! After a navigation change we request a UI update so the new highlight is drawn.
//!
//! Everything is wrapped so playback-config input can never crash the launch mode
//! (Req 8.5); nothing sensitive is read or logged (Req 7.3).
class FolderListDelegate extends WatchUi.BehaviorDelegate {

    //! The view this delegate drives. Held so Select/navigation can read and move
    //! the highlighted selection without re-reading storage. Nullable defensively.
    private var _view as FolderListView?;

    //! Build a FolderListDelegate bound to `view` (nullable — a null view makes
    //! every action a safe no-op so a construction mishap can't crash input, Req
    //! 8.5).
    function initialize(view as FolderListView?) {
        BehaviorDelegate.initialize();
        _view = view;
    }

    //! DOWN: highlight the next folder and redraw. Returns true (consumed).
    function onNextPage() as Boolean {
        move(1);
        return true;
    }

    //! UP: highlight the previous folder and redraw. Returns true (consumed).
    function onPreviousPage() as Boolean {
        move(-1);
        return true;
    }

    //! SELECT: open the highlighted folder (Req 6.1). Resolves the folder name from
    //! the view and hands it to openFolder(). A null/empty selection (empty state,
    //! Req 6.6) is a safe no-op. Returns true (consumed).
    function onSelect() as Boolean {
        var view = _view;
        if (view == null) {
            return true;
        }
        var folder = null;
        try {
            folder = view.selectedFolder();
        } catch (ex) {
            folder = null;
        }
        if (folder != null && !folder.equals("")) {
            openFolder(folder as String);
        }
        return true;
    }

    //! BACK: leave playback-config. Returns true (consumed).
    function onBack() as Boolean {
        popView();
        return true;
    }

    //! MENU: when a pairing is active, offer to CANCEL it (unpair) behind a
    //! confirmation dialog; otherwise behave like Back and leave playback-config.
    //! The confirmation guards against an accidental unpair of a working setup.
    //! Wrapped so a read/dialog hiccup can never crash input (Req 8.5).
    function onMenu() as Boolean {
        var paired = false;
        try {
            var manager = getApp().getPairingManager();
            paired = manager != null && manager.isPaired();
        } catch (ex) {
            paired = false;
        }

        if (paired) {
            try {
                WatchUi.pushView(
                    new WatchUi.Confirmation(
                        WatchUi.loadResource(Rez.Strings.FolderListUnpairTitle) as String),
                    new UnpairConfirmationDelegate(),
                    WatchUi.SLIDE_LEFT);
            } catch (ex) {
                // If the confirmation can't be shown, do nothing rather than
                // unpairing without consent (Req 8.5).
            }
            return true;
        }

        // Not paired: Menu just leaves the view, matching Back.
        popView();
        return true;
    }

    //! Open the chosen folder's track list for playback (task 9.2): push the
    //! PlaybackConfigView for `folder` with its paired delegate. The delegate is
    //! given the view so its Select action can read the highlighted track, mirroring
    //! how this delegate is given FolderListView. The folder name is already
    //! resolved + validated by the caller. Wrapped so a push failure can't crash
    //! input (Req 8.5).
    private function openFolder(folder as String) as Void {
        // Audiobook folders get the book options first (AB-4.3); music opens the track list.
        if (Repo.isBookFolder(folder)) {
            BookOptionsMenu.push(folder);
            return;
        }
        try {
            var view = new PlaybackConfigView(folder);
            WatchUi.pushView(view,
                new PlaybackConfigDelegate(folder, view),
                WatchUi.SLIDE_LEFT);
        } catch (ex) {
            // Never crash input handling (Req 8.5).
        }
    }

    //! Apply a highlight delta on the view and request a redraw so the new
    //! selection is drawn. Guarded so a null view / read hiccup is a no-op (Req
    //! 8.5).
    private function move(delta as Number) as Void {
        var view = _view;
        if (view == null) {
            return;
        }
        try {
            if (view.folderCount() == 0) {
                return;
            }
            view.setSelectedIndex(view.selectedIndex() + delta);
            WatchUi.requestUpdate();
        } catch (ex) {
            // Navigation hiccup must not crash input handling (Req 8.5).
        }
    }

    //! Pop the folder-list view off the stack, wrapped so a pop with nothing to pop
    //! can't crash (Req 8.5).
    private function popView() as Void {
        try {
            WatchUi.popView(WatchUi.SLIDE_RIGHT);
        } catch (ex) {
            // No-op: nothing to pop / UI not in a poppable state.
        }
    }
}

//! UnpairConfirmationDelegate — handles the Yes/No response of the "Cancel
//! pairing?" confirmation shown from FolderListDelegate.onMenu (Req 2.5).
//!
//! On a YES response it drives PairingManager.unpair(), which sends an UNPAIR to
//! the phone (best effort) and deletes the local PairingRecord, returning the
//! manager to IDLE — after which FolderListView's next redraw reflects the
//! now-unpaired state (its "Pair with phone first" empty prompt / dropped status
//! line). A NO response is a no-op. Everything is wrapped so a response can never
//! crash the mode (Req 8.5); nothing sensitive is read or logged (Req 7.3).
class UnpairConfirmationDelegate extends WatchUi.ConfirmationDelegate {

    //! Build the confirmation delegate. Stateless — it reaches the PairingManager
    //! through the app when a YES response arrives.
    function initialize() {
        ConfirmationDelegate.initialize();
    }

    //! Confirmation result. On RESPONSE_YES, unpair via the PairingManager; any
    //! other response leaves the pairing intact. Returns true so the response is
    //! consumed (the confirmation dismisses itself). Wrapped so a missing
    //! app/manager can't crash input (Req 8.5).
    function onResponse(response as WatchUi.Confirm) as Boolean {
        if (response == WatchUi.CONFIRM_YES) {
            try {
                var manager = getApp().getPairingManager();
                if (manager != null) {
                    manager.unpair();
                    WatchUi.requestUpdate();
                }
            } catch (ex) {
                // A unpair hiccup must not crash the response path (Req 8.5).
            }
        }
        return true;
    }
}
