import Toybox.Lang;
import Toybox.WatchUi;
import Toybox.Graphics;
import Toybox.Media;

//! PlaybackConfigView + PlaybackConfigDelegate — the per-folder track list surface
//! of playback-configuration mode (design.md §5, §9.2; Req 6.1, 6.6).
//!
//! Task 9.2 (this file): the second screen of playback-config. The user lands in
//! FolderListView (task 9.1), picks a folder, and FolderListDelegate.openFolder()
//! pushes THIS view — the list of that folder's tracks. The view LISTS the track
//! titles belonging to one folder; the delegate maps up/down/select input onto
//! that list and, on Select, resolves the chosen track and hands it to the
//! task-9.4 playback seam.
//!
//! View approach — a raw-Dc View + BehaviorDelegate, mirroring FolderListView
//! exactly: same cached list + clamped highlight, same scrolling-window draw, same
//! empty-state fallback, same wrapped Repo reads. Reusing that proven pattern keeps
//! the two playback-config screens visually and structurally consistent.
//!
//! Track list (design §9.2): a folder has no stored "track list" of its own — the
//! persisted index is a flat list of ALL track ids (Repo.loadIndex()). So this view
//! builds the folder's list by iterating that index, loading each track entry
//! (Repo.getTrack(id)), and KEEPING only the ids whose entry "folder" matches this
//! view's folder name. Each kept track is shown by its "title"; when a track has no
//! usable title we fall back to the raw id so a row is never blank. The ordered
//! {id, title} pairs drive both the draw and the delegate's selection.
//!
//! Empty state: a folder reached from a non-empty FolderListView should always have
//! at least one track, but we defend against a race (tracks deleted between screens)
//! or a corrupt entry by showing a "No tracks" prompt rather than a blank list, and
//! making Select a no-op (there is nothing to play).
//!
//! Playback seam for task 9.4: on Select the delegate resolves the highlighted
//! track id and calls openTrack(folder, trackId) — a DOCUMENTED TODO SEAM. Actually
//! building the FolderIterator and calling Media.startPlayback is tasks 9.3/9.4, so
//! openTrack() is a clearly marked no-op spot rather than a throwaway implementation
//! (which would duplicate 9.4's work). Until then Select safely does nothing.
//!
//! Resilience & privacy:
//!   - Every Repo read and the whole draw/input path is wrapped so this launch mode
//!     can never crash (Req 8.5). Any failure degrades to the empty state / a
//!     neutral no-op.
//!   - Only track titles are shown; nothing sensitive is logged (Req 7.3).

class PlaybackConfigView extends WatchUi.View {

    //! The folder whose tracks this view lists. Live value passed in from the
    //! FolderListView selection (not a resource).
    private var _folder as String;

    //! Cached ordered track rows for the current frame, each `{ "id" => String,
    //! "title" => String }`, refreshed from Repo each onUpdate. Also read by the
    //! paired delegate to resolve the highlighted selection. Empty when the folder
    //! has no (loadable) tracks or a read fails.
    private var _tracks as Array<Dictionary>;

    //! Index of the currently highlighted track in _tracks. Clamped into range on
    //! every redraw so it can never point past the end after the list shrinks.
    private var _selected as Number;

    //! One-shot latch threaded through PairingView.presentIfAwaiting so an inbound
    //! PAIR_REQUEST presents the pairing screen exactly once while this view is up,
    //! regardless of redraw frequency. See PairingView.presentIfAwaiting.
    private var _pairingPresented as Boolean;

    //! Build a PlaybackConfigView for `folder`. Starts with an empty cached list +
    //! selection 0; the real track rows are pulled live from Repo in onUpdate so the
    //! screen always reflects the current library.
    //! Whether _tracks reflects storage for the current showing (reset in onShow).
    private var _loaded as Boolean;

    function initialize(folder as String) {
        View.initialize();
        _folder = folder;
        _tracks = [] as Array<Dictionary>;
        _selected = 0;
        _pairingPresented = false;
        _loaded = false;
    }

    //! Re-read the track list each time the view comes to the foreground (e.g. back from the
    //! player or after a sync), keeping the current highlight where possible.
    function onShow() as Void {
        _loaded = false;
    }

    //! Refresh the cached track rows for this folder from Repo. Iterates the flat
    //! track-id index (Repo.loadIndex()), loads each entry (Repo.getTrack(id)), and
    //! keeps only those whose "folder" matches this view's folder name. Each kept
    //! row is `{ id, title }` with the title falling back to the id when absent.
    //! Wrapped end-to-end; any storage/parse hiccup yields an empty list (→ empty
    //! state) so a corrupt stored value can't crash the draw (Req 8.5).
    private function refreshTracks() as Void {
        var rows = [] as Array<Dictionary>;
        try {
            // Play order = FolderTracks (natural file-name order via `seq`), the SAME order the
            // player walks, so the selected row index is the player's start index (AB-2).
            var isBook = Repo.isBookFolder(_folder);
            var ids = FolderTracks.orderedIds(_folder);
            for (var i = 0; i < ids.size(); i++) {
                var id = ids[i];
                var entry = Repo.getTrack(id);
                var title = (entry != null) ? entry.get("title") : null;
                var label = (title instanceof String && !(title as String).equals(""))
                    ? (title as String)
                    : id;
                if (isBook) {
                    // Chapter list (AB-4.3): numbered in chapter order.
                    label = (i + 1).toString() + ". " + label;
                }
                rows.add({ "id" => id, "title" => label });
            }
        } catch (ex) {
            // Any storage/parse hiccup → treat the folder as empty (Req 8.5).
            rows = [] as Array<Dictionary>;
        }
        _tracks = rows;

        // Keep the highlight in range after the list may have changed.
        if (_selected >= _tracks.size()) {
            _selected = _tracks.size() - 1;
        }
        if (_selected < 0) {
            _selected = 0;
        }
    }

    //! Render the folder's track list, or the empty state when the folder has no
    //! (loadable) tracks. Reads the tracks live from Repo each frame. Fully wrapped
    //! so playback-config mode can never crash (Req 8.5); only titles are shown
    //! (Req 7.3).
    function onUpdate(dc as Graphics.Dc) as Void {
        // Surface pairing regardless of screen: if a PAIR_REQUEST arrived while
        // this track-list view is up, present the PairingView (Req 2.2-2.4).
        _pairingPresented = PairingView.presentIfAwaiting(_pairingPresented);

        // Built on show (and lazily on the first frame), not every frame: a long book would
        // otherwise re-read N storage entries on every redraw (design §4.3).
        if (!_loaded) {
            refreshTracks();
            _loaded = true;
        }

        // Empty state: shouldn't happen when reached from a non-empty folder, but
        // defend against a race / corrupt entry (Req 8.5) rather than a blank list.
        if (_tracks.size() == 0) {
            StubViews.drawCentered(dc, _folder,
                WatchUi.loadResource(Rez.Strings.PlaybackTracksEmpty) as String);
            return;
        }

        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_BLACK);
        dc.clear();

        var cx = dc.getWidth() / 2;
        var cy = dc.getHeight() / 2;

        // Title at the top: the folder name (a live value, not a resource).
        dc.drawText(cx, 20, Graphics.FONT_XTINY, _folder,
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);

        // Draw a short vertical window of track titles centered on the highlighted
        // one. The highlighted row is emphasized (larger font); neighbours are
        // dimmed so the current selection is obvious on both button and touch
        // models.
        var rowSpacing = 30;
        var window = 2; // rows above/below the highlight to show
        for (var offset = -window; offset <= window; offset++) {
            var idx = _selected + offset;
            if (idx < 0 || idx >= _tracks.size()) {
                continue;
            }
            var label = _tracks[idx].get("title") as String;
            var y = cy + offset * rowSpacing;
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
    }

    //! The folder this view lists (used by the paired delegate's playback seam).
    function folder() as String {
        return _folder;
    }

    //! Number of tracks currently listed (0 in the empty state). Lets the paired
    //! delegate guard its navigation without re-reading storage.
    function trackCount() as Number {
        return _tracks.size();
    }

    //! The currently highlighted track index.
    function selectedIndex() as Number {
        return _selected;
    }

    //! Move the highlight to `index`, clamped into [0, count-1]. Used by the
    //! delegate's up/down navigation. A no-op when the list is empty.
    function setSelectedIndex(index as Number) as Void {
        var count = _tracks.size();
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

    //! The track id at the currently highlighted index, or null when the list is
    //! empty / the index is somehow out of range (defensive; Req 8.5). Used by the
    //! delegate's Select action to know which track to play.
    function selectedTrackId() as String? {
        if (_selected < 0 || _selected >= _tracks.size()) {
            return null;
        }
        return _tracks[_selected].get("id") as String;
    }
}

//! PlaybackConfigDelegate — input delegate paired with PlaybackConfigView
//! (design §5, §9.2; Req 6.1, 6.6).
//!
//! Navigation:
//!   - NEXT PAGE / DOWN (onNextPage) → move the highlight down one track.
//!   - PREVIOUS PAGE / UP (onPreviousPage) → move the highlight up one track.
//!   - SELECT (onSelect — ENTER/START or a center tap) → play the highlighted
//!     track (seam for task 9.4; see openTrack()).
//!   - BACK/MENU → return to the folder list.
//! After a navigation change we request a UI update so the new highlight is drawn.
//!
//! Everything is wrapped so playback-config input can never crash the launch mode
//! (Req 8.5); nothing sensitive is read or logged (Req 7.3).
class PlaybackConfigDelegate extends WatchUi.BehaviorDelegate {

    //! The folder being configured — resolved from the pushing FolderListDelegate
    //! and used by the task-9.4 playback seam alongside the selected track id.
    private var _folder as String;

    //! The view this delegate drives. Held so Select/navigation can read and move
    //! the highlighted selection without re-reading storage. Nullable defensively.
    private var _view as PlaybackConfigView?;

    //! Build a PlaybackConfigDelegate for `folder`, bound to `view` (nullable — a
    //! null view makes every action a safe no-op so a construction mishap can't
    //! crash input, Req 8.5).
    function initialize(folder as String, view as PlaybackConfigView?) {
        BehaviorDelegate.initialize();
        _folder = folder;
        _view = view;
    }

    //! DOWN: highlight the next track and redraw. Returns true (consumed).
    function onNextPage() as Boolean {
        move(1);
        return true;
    }

    //! UP: highlight the previous track and redraw. Returns true (consumed).
    function onPreviousPage() as Boolean {
        move(-1);
        return true;
    }

    //! SELECT: play the highlighted track (Req 6.1). Resolves the track id from the
    //! view and hands it to openTrack(). A null/empty selection (empty state) is a
    //! safe no-op. Returns true (consumed).
    function onSelect() as Boolean {
        var view = _view;
        if (view == null) {
            return true;
        }
        var trackId = null;
        try {
            trackId = view.selectedTrackId();
        } catch (ex) {
            trackId = null;
        }
        if (trackId != null && !trackId.equals("")) {
            var startIndex = 0;
            try {
                startIndex = view.selectedIndex();
            } catch (ex) {
                startIndex = 0;
            }
            openTrack(_folder, startIndex);
        }
        return true;
    }

    //! BACK: return to the folder list. Returns true (consumed).
    function onBack() as Boolean {
        popView();
        return true;
    }

    //! MENU: same as back — return to the folder list.
    function onMenu() as Boolean {
        popView();
        return true;
    }

    //! Start playback of the chosen track within its folder (task 9.4).
    //!
    //! Uses the ACP content-delegate path: Media.startPlayback(args) relaunches the
    //! app in playback mode, handing `args` back to
    //! GsoundGApp.getContentDelegate(args), which rebuilds a GsoundContentDelegate
    //! for this folder → its getContentIterator() returns a FolderIterator seeded at
    //! `startIndex`. So we encode the folder + start index as a Dictionary (a valid
    //! PersistableType) and call startPlayback — we do NOT build the iterator here.
    //! `startIndex` is the selected track's position within the folder's track list
    //! so playback begins at the chosen track. Wrapped so a startup failure can't
    //! crash input (Req 8.5); nothing is logged (Req 7.3).
    private function openTrack(folder as String, startIndex as Number) as Void {
        // Shared start path (saves the selection for player-initiated starts; never throws).
        // For a book this is "play from the chosen chapter at 0:00" (AB-4.3).
        PlaybackStarter.startAt(folder, startIndex);
    }

    //! Apply a highlight delta on the view and request a redraw so the new selection
    //! is drawn. Guarded so a null view / read hiccup is a no-op (Req 8.5).
    private function move(delta as Number) as Void {
        var view = _view;
        if (view == null) {
            return;
        }
        try {
            if (view.trackCount() == 0) {
                return;
            }
            view.setSelectedIndex(view.selectedIndex() + delta);
            WatchUi.requestUpdate();
        } catch (ex) {
            // Navigation hiccup must not crash input handling (Req 8.5).
        }
    }

    //! Pop the track-list view off the stack, wrapped so a pop with nothing to pop
    //! can't crash (Req 8.5).
    private function popView() as Void {
        try {
            WatchUi.popView(WatchUi.SLIDE_RIGHT);
        } catch (ex) {
            // No-op: nothing to pop / UI not in a poppable state.
        }
    }
}
