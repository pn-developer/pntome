import Toybox.Lang;
import Toybox.WatchUi;
import Toybox.Graphics;
import Toybox.Communications;

//! SyncConfigView + SyncConfigDelegate — the sync-configuration mode surface
//! (design.md §5, §8.1; Req 4.1).
//!
//! Task 7.9 (this file): replace the SyncConfigStub view/delegate with the real
//! pair. The sync-config mode is what the user lands in from the device's Music
//! Providers list when they choose to sync Gsound. The view shows how many tracks
//! are queued to sync (the PENDING count), and the delegate's Confirm action
//! TRIGGERS THE SYSTEM SYNC via Communications.startSync() — the SDK call that
//! exits this foreground mode and relaunches the app in sync mode, where the
//! system drives Wi-Fi and calls GsoundSyncDelegate.onStartSync() (design §8.1:
//! "the user confirms and the system drives Wi-Fi + calls onStartSync()").
//!
//! Pending count (design §8.1): read LIVE from the SyncController each onUpdate so
//! the screen reflects whatever manifest has arrived over the control channel.
//!   - getPendingTrackCount() → Number of inline tracks queued.
//!   - needsFetchAtSyncStart() → true when the phone sent a "serverUrl only" /
//!     truncated manifest for a large library; the authoritative list is fetched
//!     at sync start, so the inline count is 0 but there IS a sync to run. In that
//!     case we show a "Ready to sync" state rather than a misleading "0 tracks".
//!
//! Resilience & privacy:
//!   - A null SyncController (the control channel failed to init — Req 8.5) yields
//!     the neutral empty state instead of crashing. Every read is null-guarded and
//!     the whole draw/input path is wrapped.
//!   - Nothing sensitive is shown or logged (Req 7.3) — only the track count.
//!
//! Matches the StubViews.mc / PairingView.mc conventions: extends WatchUi.View,
//! draws centered WHITE-on-BLACK text in onUpdate against the raw Dc (no layout
//! XML), using the same FONT_*/justify idioms; the delegate extends
//! WatchUi.BehaviorDelegate and maps Confirm/back onto startSync()/pop. It reuses
//! StubViews.drawCentered for the simple one-line states.

class SyncConfigView extends WatchUi.View {

    //! Build a SyncConfigView. Holds no state of its own — the pending count is
    //! pulled live from the SyncController in onUpdate so the screen always
    //! reflects the current manifest.
    function initialize() {
        View.initialize();
    }

    //! Render the current pending-sync state. Reads the count + fetch-fallback
    //! flag live from the SyncController each frame. Fully null-guarded and wrapped
    //! so sync-config mode can never crash (Req 8.5); nothing sensitive is shown
    //! (Req 7.3).
    function onUpdate(dc as Graphics.Dc) as Void {
        var title = WatchUi.loadResource(Rez.Strings.SyncTitle) as String;

        var controller = null;
        try {
            controller = getApp().getSyncController();
        } catch (ex) {
            controller = null;
        }

        // Neutral/empty state: the control channel failed to come up, so there is
        // no controller to read a pending manifest from (Req 8.5).
        if (controller == null) {
            StubViews.drawCentered(dc, title,
                WatchUi.loadResource(Rez.Strings.SyncEmpty) as String);
            return;
        }

        var count = 0;
        var needsFetch = false;
        try {
            count = controller.getPendingTrackCount();
            needsFetch = controller.needsFetchAtSyncStart();
        } catch (ex) {
            // Defensive: treat any read hiccup as "nothing known yet".
            count = 0;
            needsFetch = false;
        }

        if (count <= 0 && !needsFetch) {
            // No inline tracks and no fetch-at-sync-start fallback → genuinely
            // nothing queued.
            StubViews.drawCentered(dc, title,
                WatchUi.loadResource(Rez.Strings.SyncNothing) as String);
            return;
        }

        // There is a sync to run. Draw the count (or the fetch-fallback "ready"
        // state) plus the confirm prompt, following PairingView's richer layout.
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_BLACK);
        dc.clear();

        var cx = dc.getWidth() / 2;
        var cy = dc.getHeight() / 2;

        // Title at the top.
        dc.drawText(cx, cy - 60, Graphics.FONT_XTINY, title,
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);

        if (count > 0) {
            // The pending count, rendered prominently.
            dc.drawText(cx, cy - 18, Graphics.FONT_NUMBER_MEDIUM, count.toString(),
                Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
            // Unit label under the number.
            dc.drawText(cx, cy + 20, Graphics.FONT_XTINY,
                WatchUi.loadResource(Rez.Strings.SyncTracksLabel) as String,
                Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        } else {
            // Fetch-fallback: inline count is 0 but a large-library sync will fetch
            // its authoritative list at sync start. Show a neutral "ready" line
            // rather than "0 tracks".
            dc.drawText(cx, cy, Graphics.FONT_MEDIUM,
                WatchUi.loadResource(Rez.Strings.SyncReady) as String,
                Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        }

        // Confirm prompt near the bottom.
        dc.drawText(cx, cy + 56, Graphics.FONT_XTINY,
            WatchUi.loadResource(Rez.Strings.SyncPrompt) as String,
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
    }
}

//! SyncConfigDelegate — input delegate paired with SyncConfigView (design §5,
//! §8.1; Req 4.1).
//!
//! Confirm (onSelect — the ENTER/START key or a center tap) TRIGGERS THE SYSTEM
//! SYNC by calling Communications.startSync(), the SDK function that exits this
//! foreground config mode and relaunches the app in sync mode so the system can
//! drive Wi-Fi and call GsoundSyncDelegate.onStartSync() (design §8.1). The native
//! sync UI takes over from there, so after triggering we also pop this view so it
//! doesn't linger underneath. back/menu cancel out of config without syncing.
//!
//! Everything is wrapped so sync-config input can never crash the launch mode
//! (Req 8.5); nothing sensitive is read or logged (Req 7.3).
class SyncConfigDelegate extends WatchUi.BehaviorDelegate {

    //! Build a SyncConfigDelegate. Stateless — it reaches the SyncController (if it
    //! needs to) through the app, and triggers the sync via the module-level
    //! Communications.startSync().
    function initialize() {
        BehaviorDelegate.initialize();
    }

    //! CONFIRM: start the system sync (Req 4.1). Call Communications.startSync()
    //! wrapped in try/catch so a trigger failure can't crash input handling
    //! (Req 8.5), then pop this view since the native sync flow takes over.
    //! Returns true so the behavior is consumed.
    function onSelect() as Boolean {
        try {
            Communications.startSync();
        } catch (ex) {
            // A startSync hiccup (e.g. no sync needed, or unsupported launch
            // context) must not crash the config mode (Req 8.5).
        }
        popView();
        return true;
    }

    //! CANCEL via BACK: leave sync-config without triggering a sync (design §8.1).
    //! Returns true so the behavior is consumed.
    function onBack() as Boolean {
        popView();
        return true;
    }

    //! CANCEL via MENU: same as back — leave without syncing.
    function onMenu() as Boolean {
        popView();
        return true;
    }

    //! Pop the config view off the stack, wrapped so a pop with nothing to pop
    //! can't crash (Req 8.5).
    private function popView() as Void {
        try {
            WatchUi.popView(WatchUi.SLIDE_RIGHT);
        } catch (ex) {
            // No-op: nothing to pop / UI not in a poppable state.
        }
    }
}
