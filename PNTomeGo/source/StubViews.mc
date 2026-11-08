import Toybox.Lang;
import Toybox.WatchUi;
import Toybox.Graphics;

//! StubViews — the shared drawing helper for the configuration-view surfaces.
//!
//! Originally this file also held TEMPORARY placeholder Views + input delegates
//! for the ACP configuration modes (so each mode could show *something* and route
//! input without crashing, Req 8.5). Those placeholders have all been replaced by
//! the real views/delegates:
//!   - sync-config: sync/SyncConfigView.mc (SyncConfigView / SyncConfigDelegate),
//!     task 7.9.
//!   - playback-config: playback/FolderListView.mc (FolderListView /
//!     FolderListDelegate), task 9.1.
//! What remains here is the small, exception-free drawCentered helper, which the
//! real views (SyncConfigView, FolderListView, StatsView) reuse for their simple
//! one-line / empty states. (Note: the playback content delegate/iterator
//! placeholders in StubDelegates.mc stay until tasks 9.3/9.4.)

//! Shared drawing helper: center a title + subtitle on screen. Kept tiny and
//! exception-free so the config views' simple states can't crash any launch mode.
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

//! SafeFallbackView — the last-resort configuration-mode surface used ONLY when a
//! real config view/delegate constructor throws (PNTomeGoApp's
//! getSyncConfigurationView / getPlaybackConfigurationView fallback, Req 8.5).
//!
//! It holds no state and draws nothing but two literal lines via the exception-free
//! StubViews.drawCentered — no Repo reads, no WatchUi.loadResource, no construction
//! that can throw — so building and drawing it cannot itself crash the launch mode.
//! Paired with a plain WatchUi.BehaviorDelegate (back/menu pop out normally), it
//! guarantees that even a total view-construction failure still yields a working,
//! input-routing [view, delegate] pair instead of a crashed config mode.
class SafeFallbackView extends WatchUi.View {
    function initialize() {
        View.initialize();
    }

    function onUpdate(dc as Graphics.Dc) as Void {
        StubViews.drawCentered(dc, "PNTomeGo", "Unavailable");
    }
}
