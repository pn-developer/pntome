import Toybox.Lang;
import Toybox.WatchUi;
import Toybox.Graphics;

//! PairingView — the watch-side screen shown while a PAIR_REQUEST awaits the
//! user's confirmation (design.md §7; Req 2.2–2.4).
//!
//! Task 5.2 (this view): render the 6-digit code prominently, plus the peer
//! (phone) name for context and a short confirm/reject prompt. The code and peer
//! name are pulled LIVE from the PairingManager on every onUpdate so the screen
//! always reflects the current state — e.g. if the confirmation times out, or an
//! UNPAIR/reject fires, the next redraw falls back to a neutral empty state
//! rather than showing a stale code.
//!
//! Resilience & privacy:
//!   - The whole draw path is defensive: a null PairingManager (the control
//!     channel failed to come up — Req 8.5) simply yields the neutral empty
//!     state instead of crashing. Every value read is null-checked.
//!   - The view only ever shows the 6-digit code and the peer name. It NEVER
//!     reads or renders the pairing secret (Req 7.3) — PairingManager exposes no
//!     accessor for it, and this view does not log anything.
//!
//! Matches the StubViews.mc conventions: extends WatchUi.View, draws centered
//! text in onUpdate against the raw Dc (no layout XML), WHITE-on-BLACK, using the
//! same font/justify idioms. The paired PairingDelegate (PairingDelegate.mc)
//! maps Confirm/Reject input onto PairingManager.confirm()/reject().
class PairingView extends WatchUi.View {

    //! Present the pairing screen for the given PairingManager by pushing a
    //! PairingView + PairingDelegate onto the UI stack. This is the single,
    //! ready-to-use entry point for surfacing pairing — whatever triggers it (a
    //! foreground config view, or a future standalone entry) calls this one method.
    //!
    //! Why this is a helper rather than an auto-push from PairingManager: an ACP
    //! app is launched by the system in several modes (sync / playback / config),
    //! and PairingManager.onPairRequest runs on the control-channel DISPATCH path,
    //! which can fire while the app is in a non-UI/background sync mode. Calling
    //! WatchUi.pushView from there is unsafe and risks the launch contract the
    //! app docs warn about. So the view/delegate are built and ready, and this
    //! presenter is invoked only from a known foreground UI context. The push is
    //! wrapped so it can never crash a mode that has no view stack (Req 8.5).
    static function present(manager as PairingManager?) as Void {
        try {
            WatchUi.pushView(
                new PairingView(manager),
                new PairingDelegate(manager),
                WatchUi.SLIDE_LEFT);
        } catch (ex) {
            // No view stack available in this launch mode; ignore (Req 8.5).
        }
    }

    //! Present the pairing screen when a PAIR_REQUEST is awaiting confirmation,
    //! at most once per pending request — the shared hook every foreground view
    //! (SyncConfigView, FolderListView, PlaybackConfigView) calls from its
    //! onUpdate so pairing pops up regardless of which screen the user is on
    //! (Req 2.2-2.4).
    //!
    //! Why a shared helper: a PAIR_REQUEST arrives asynchronously on the
    //! control-channel dispatch path, which can fire while ANY foreground mode is
    //! up. Each view's onUpdate runs on the UI thread — the "known foreground
    //! context" [present] requires (pushing from the dispatch path is unsafe). This
    //! method owns the manager lookup + awaiting check + one-shot latch so no view
    //! re-implements it (and they can't drift).
    //!
    //! Latch contract: the caller owns a single Boolean it threads through here.
    //! Pass the previous latch value; the returned value is the new latch to store.
    //! While AWAITING_CONFIRM the view is presented exactly once (latch goes true);
    //! once no longer awaiting (confirmed / rejected / timed out) the latch resets
    //! to false so a later re-pair is presented again. Fully wrapped so a missing
    //! app/manager or a push failure can never crash the calling mode (Req 8.5);
    //! nothing sensitive is read here (the code/peer name live inside the pushed
    //! PairingView; the secret is never touched — Req 7.3).
    //!
    //! @param alreadyPresented the caller's current latch value.
    //! @return the new latch value to store on the caller.
    static function presentIfAwaiting(alreadyPresented as Boolean) as Boolean {
        try {
            var manager = getApp().getPairingManager();
            if (manager == null) {
                return false;
            }
            if (manager.isAwaitingConfirm()) {
                if (!alreadyPresented) {
                    present(manager);
                }
                return true;
            }
            // No longer awaiting: drop the latch so a subsequent re-pair presents.
            return false;
        } catch (ex) {
            // A missing app/manager or push failure must not crash the caller
            // (Req 8.5). Leave the latch as it was.
            return alreadyPresented;
        }
    }

    //! The pairing state machine this view renders. May be null when the control
    //! channel failed to initialize (Req 8.5); the view degrades to the neutral
    //! empty state in that case. Held for the view's lifetime.
    private var _manager as PairingManager?;

    //! Build a PairingView bound to `manager` (nullable — a null manager renders
    //! the neutral empty state so a failed control-channel init can't crash the
    //! view, Req 8.5).
    function initialize(manager as PairingManager?) {
        View.initialize();
        _manager = manager;
    }

    //! Render the current pairing state. Reads the code + peer name live from the
    //! PairingManager each frame so the view reflects confirm/reject/timeout
    //! transitions immediately. Fully null-guarded (Req 8.5) and never touches the
    //! secret (Req 7.3).
    function onUpdate(dc as Graphics.Dc) as Void {
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_BLACK);
        dc.clear();

        var cx = dc.getWidth() / 2;
        var cy = dc.getHeight() / 2;

        var manager = _manager;
        var code = null;
        var peerName = null;
        var awaiting = false;
        if (manager != null) {
            awaiting = manager.isAwaitingConfirm();
            code = manager.getCurrentCode();
            peerName = manager.getPeerName();
        }

        if (!awaiting || code == null) {
            // Neutral/empty state: no pending pairing (idle, already paired,
            // timed out, or no manager). Nothing sensitive to show.
            dc.drawText(cx, cy, Graphics.FONT_SMALL,
                WatchUi.loadResource(Rez.Strings.PairIdle) as String,
                Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
            return;
        }

        // Title at the top.
        dc.drawText(cx, cy - 70, Graphics.FONT_XTINY,
            WatchUi.loadResource(Rez.Strings.PairTitle) as String,
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);

        // The 6-digit code, rendered prominently (Req 2.2).
        dc.drawText(cx, cy - 20, Graphics.FONT_NUMBER_MEDIUM, code,
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);

        // Peer (phone) name for context, when provided.
        if (peerName != null && !peerName.equals("")) {
            dc.drawText(cx, cy + 22, Graphics.FONT_XTINY, peerName,
                Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        }

        // Short confirm prompt + action hints near the bottom.
        dc.drawText(cx, cy + 48, Graphics.FONT_XTINY,
            WatchUi.loadResource(Rez.Strings.PairPrompt) as String,
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        dc.drawText(cx, cy + 72, Graphics.FONT_XTINY,
            (WatchUi.loadResource(Rez.Strings.PairConfirm) as String)
                + "  /  "
                + (WatchUi.loadResource(Rez.Strings.PairReject) as String),
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
    }
}
