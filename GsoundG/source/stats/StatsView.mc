import Toybox.Lang;
import Toybox.WatchUi;
import Toybox.Graphics;
import Toybox.Time;
import Toybox.Time.Gregorian;

//! StatsView — the watch-side screen that shows the persisted library stats
//! (design.md §5, §8.4; Req 5.2, 5.4).
//!
//! Task 8.3 (this view): render the three numbers the last sync persisted —
//! `fileCount` (tracks), `folderCount` (folders) and `lastSyncAt` (when the last
//! sync completed) — pulled LIVE from StatsStore on every onUpdate so the screen
//! always reflects what is actually stored, not a snapshot taken at construction.
//!
//! EMPTY STATE before the first sync (Req 5.4): stats persist across launches, but
//! before any sync has run there is nothing meaningful to show. Rather than paint
//! "0 tracks / 0 folders" next to a bogus or absent date, the view detects the
//! never-synced case — StatsStore.load() is null, OR lastSyncAtOf(stats) is null
//! (the pre-first-sync shape StatsStore documents) — and shows a short two-line
//! empty-state message instead, reusing StubViews.drawCentered.
//!
//! lastSyncAt formatting: the stored value is epoch SECONDS (a Number, from
//! Time.now().value()). We wrap it in a Time.Moment and read a local calendar
//! breakdown via Toybox.Time.Gregorian.info(moment, Time.FORMAT_MEDIUM) — local
//! time so the displayed date/time matches the user's wrist. FORMAT_MEDIUM yields
//! a named month (a String) with numeric day/hour/min, from which we build a
//! compact "MON D  HH:MM" line. The whole conversion is wrapped so a bad stored
//! value can never crash the view (Req 8.5); on a formatting failure we simply
//! omit the date line rather than showing garbage.
//!
//! Resilience & privacy:
//!   - The entire draw path is defensive (Req 8.5): a null store, a malformed
//!     stored dict, or a Gregorian hiccup all degrade gracefully (empty state or a
//!     dropped date line) instead of throwing — this view must never crash a
//!     launch.
//!   - Only the three non-sensitive counts/timestamp are read; nothing is logged
//!     (Req 7.3).
//!
//! Matches the PairingView.mc / SyncConfigView.mc conventions: extends
//! WatchUi.View, draws centered WHITE-on-BLACK text in onUpdate against the raw Dc
//! (no layout XML), using the same FONT_*/justify idioms, and reuses
//! StubViews.drawCentered for the empty state. The numeric values are live, not
//! string resources; only the labels/title/empty-state text come from strings.xml.
//!
//! PRESENTATION: there is no dedicated ACP launch hook for a standalone stats
//! screen (the ACP modes are sync-config / playback-config / playback). StatsView
//! is a view the app PUSHES from a known foreground UI context via present()
//! (mirroring PairingView.present()). It is intentionally NOT wired into a launch
//! hook — doing so from a background/non-UI mode would risk the launch contract
//! (Req 8.5). Whatever foreground surface wants to show stats calls
//! StatsView.present(); the push is wrapped so a mode with no view stack can't
//! crash.
class StatsView extends WatchUi.View {

    //! Present the stats screen by pushing a StatsView onto the UI stack. This is
    //! the single, ready-to-use entry point for surfacing stats from a foreground
    //! UI context (e.g. a menu item). It is NOT auto-pushed from any launch hook:
    //! an ACP app is launched in several modes (sync / playback / config) and
    //! pushing a view from a non-UI/background mode is unsafe (Req 8.5). The push
    //! is wrapped so a mode without a view stack can never crash. A plain
    //! BehaviorDelegate backs it so BACK pops cleanly.
    static function present() as Void {
        try {
            WatchUi.pushView(
                new StatsView(),
                new WatchUi.BehaviorDelegate(),
                WatchUi.SLIDE_LEFT);
        } catch (ex) {
            // No view stack available in this launch mode; ignore (Req 8.5).
        }
    }

    //! Build a StatsView. Holds no state of its own — the stats are pulled live
    //! from StatsStore in onUpdate so the screen always reflects what is persisted.
    function initialize() {
        View.initialize();
    }

    //! Render the current library stats. Reads the persisted stats live from
    //! StatsStore each frame, shows the never-synced EMPTY STATE when there is no
    //! stored stats / no lastSyncAt (Req 5.4), otherwise draws tracks + folders +
    //! a formatted last-synced line. Fully wrapped/null-guarded so the view can
    //! never crash a launch (Req 8.5); nothing sensitive is shown or logged
    //! (Req 7.3).
    function onUpdate(dc as Graphics.Dc) as Void {
        var title = WatchUi.loadResource(Rez.Strings.StatsTitle) as String;

        var stats = null;
        var lastSyncAt = null;
        try {
            stats = StatsStore.load();
            lastSyncAt = StatsStore.lastSyncAtOf(stats);
        } catch (ex) {
            // Any read hiccup is treated as "never synced" below (Req 8.5).
            stats = null;
            lastSyncAt = null;
        }

        // EMPTY STATE (Req 5.4): no persisted stats at all, or stats exist but a
        // sync has never completed (no lastSyncAt). Show a short message instead of
        // zeros-with-a-bogus-date.
        if (stats == null || lastSyncAt == null) {
            StubViews.drawCentered(dc, title,
                WatchUi.loadResource(Rez.Strings.StatsEmpty) as String);
            return;
        }

        var fileCount = StatsStore.fileCountOf(stats);
        var folderCount = StatsStore.folderCountOf(stats);

        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_BLACK);
        dc.clear();

        var cx = dc.getWidth() / 2;
        var cy = dc.getHeight() / 2;

        // Title at the top.
        dc.drawText(cx, cy - 72, Graphics.FONT_XTINY, title,
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);

        // Tracks: prominent number + unit label.
        dc.drawText(cx, cy - 40, Graphics.FONT_NUMBER_MEDIUM, fileCount.toString(),
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        dc.drawText(cx, cy - 8, Graphics.FONT_XTINY,
            WatchUi.loadResource(Rez.Strings.StatsTracksLabel) as String,
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);

        // Folders: "N folders" on a single line beneath the tracks block.
        dc.drawText(cx, cy + 22, Graphics.FONT_SMALL,
            folderCount.toString() + " "
                + (WatchUi.loadResource(Rez.Strings.StatsFoldersLabel) as String),
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);

        // Last-synced line near the bottom. Format defensively; if formatting
        // fails we omit the date rather than risk a crash (Req 8.5).
        var when = formatLastSync(lastSyncAt);
        if (when != null) {
            dc.drawText(cx, cy + 56, Graphics.FONT_XTINY,
                (WatchUi.loadResource(Rez.Strings.StatsLastSync) as String)
                    + " " + when,
                Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        }
    }

    //! Convert an epoch-SECONDS timestamp into a compact, readable LOCAL
    //! date/time string ("MON D  HH:MM"), or null if it can't be formatted.
    //!
    //! Uses Toybox.Time.Gregorian.info(Moment, Time.FORMAT_MEDIUM): FORMAT_MEDIUM
    //! gives a named month (String) with numeric day/hour/min, which reads better
    //! than an all-numeric date. Gregorian.info (not utcInfo) applies the device's
    //! local time zone so the shown time matches the user's wrist. Wrapped so a bad
    //! value / SDK hiccup yields null (the caller then drops the line) instead of
    //! crashing (Req 8.5).
    private function formatLastSync(epochSeconds as Number) as String? {
        try {
            var moment = new Time.Moment(epochSeconds);
            var info = Gregorian.info(moment, Time.FORMAT_MEDIUM);

            // month is a String under FORMAT_MEDIUM; day/hour/min are Numbers.
            var month = info.month;
            var day = info.day;
            var hour = info.hour;
            var min = info.min;

            var monthStr = (month instanceof String) ? month : month.toString();
            var hh = (hour < 10) ? ("0" + hour.toString()) : hour.toString();
            var mm = (min < 10) ? ("0" + min.toString()) : min.toString();

            return monthStr + " " + day.toString() + "  " + hh + ":" + mm;
        } catch (ex) {
            // Unformattable value — caller omits the date line (Req 8.5).
            return null;
        }
    }
}
