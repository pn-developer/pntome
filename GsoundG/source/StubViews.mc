import Toybox.Lang;
import Toybox.WatchUi;
import Toybox.Graphics;

//! StubViews — minimal placeholder Views + input delegates used by the
//! configuration-view hooks (task 2.2).
//!
//! TEMPORARY STUBS. The two ACP configuration modes each need a
//! [View, InputDelegate] PAIR (the SDK/template contract) so the system can show
//! *something* and route input without the app crashing (Req 8.5). These
//! placeholders are replaced by the real views/delegates in later tasks:
//!   - SyncConfigStubView / SyncConfigStubDelegate -> sync/SyncConfigView.mc       [task 7]
//!   - FolderListStubView / FolderListStubDelegate -> playback/FolderListView.mc,
//!                                                    PlaybackConfigView.mc        [task 9]
//!
//! The delegates extend WatchUi.BehaviorDelegate (matching the official ACP
//! template's Configure*Delegate) and add no behavior yet. Keep the views
//! dependency-free (no Repo/Media/Comm calls) so they can never throw during a
//! launch, honoring the "no mode can crash" guarantee.

//! Shared drawing helper: center a title + subtitle on screen. Kept tiny and
//! exception-free so stub rendering can't crash any launch mode.
module StubViews {
    function drawCentered(dc as Graphics.Dc, title as String, subtitle as String) as Void {
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_BLACK);
        dc.clear();
        var cx = dc.getWidth() / 2;
        var cy = dc.getHeight() / 2;
        dc.drawText(cx, cy - 18, Graphics.FONT_MEDIUM, title,
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        dc.drawText(cx, cy + 18, Graphics.FONT_XTINY, subtitle,
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
    }
}

//! Sync-configuration placeholder. Replaced by SyncConfigView in task 7.
class SyncConfigStubView extends WatchUi.View {
    function initialize() {
        View.initialize();
    }

    function onUpdate(dc as Graphics.Dc) as Void {
        StubViews.drawCentered(dc, "Sync", "config (stub)");
    }
}

//! Input delegate paired with SyncConfigStubView. Extends BehaviorDelegate (as in
//! the official ACP template) and adds no behavior yet. Replaced in task 7.
class SyncConfigStubDelegate extends WatchUi.BehaviorDelegate {
    function initialize() {
        BehaviorDelegate.initialize();
    }
}

//! Playback-configuration placeholder. Replaced by FolderListView /
//! PlaybackConfigView in task 9.
class FolderListStubView extends WatchUi.View {
    function initialize() {
        View.initialize();
    }

    function onUpdate(dc as Graphics.Dc) as Void {
        StubViews.drawCentered(dc, "Music", "folders (stub)");
    }
}

//! Input delegate paired with FolderListStubView. Extends BehaviorDelegate (as in
//! the official ACP template) and adds no behavior yet. Replaced in task 9.
class FolderListStubDelegate extends WatchUi.BehaviorDelegate {
    function initialize() {
        BehaviorDelegate.initialize();
    }
}
