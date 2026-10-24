import Toybox.Lang;
import Toybox.WatchUi;

//! PairingDelegate — input delegate paired with PairingView (design.md §7;
//! Req 2.3–2.4).
//!
//! Task 5.2 (this delegate): map the user's Confirm / Reject input onto the
//! PairingManager and pop the view afterwards so the user returns to wherever the
//! pairing screen was pushed from. Matches the StubDelegates/StubViews convention
//! of extending WatchUi.BehaviorDelegate.
//!
//! Input mapping (works across button and touch fēnix models):
//!   - CONFIRM  → onSelect() (the ENTER/START key or a center tap) → manager.confirm()
//!                which sends PAIR_CONFIRM and persists the PairingRecord (Req 2.3).
//!   - REJECT   → onBack() (the BACK/DOWN key) and onMenu() (MENU) → manager.reject()
//!                which discards the temp values and returns to IDLE (Req 2.4).
//! In every case the view is popped so the pairing screen doesn't linger.
//!
//! Resilience & privacy: a null PairingManager (control channel failed to init,
//! Req 8.5) makes every action a safe pop-only no-op — nothing crashes. The
//! delegate never reads or logs the secret (Req 7.3); it only triggers
//! manager.confirm()/reject().
class PairingDelegate extends WatchUi.BehaviorDelegate {

    //! The pairing state machine driven by this delegate. Nullable so a failed
    //! control-channel init can't crash input handling (Req 8.5); actions become
    //! pop-only no-ops in that case.
    private var _manager as PairingManager?;

    //! Build a PairingDelegate bound to `manager` (nullable — see class doc).
    function initialize(manager as PairingManager?) {
        BehaviorDelegate.initialize();
        _manager = manager;
    }

    //! CONFIRM: the user accepted the code (Req 2.3). Drive manager.confirm()
    //! (sends PAIR_CONFIRM + persists the record), then pop the view. Returns true
    //! so the behavior is consumed. Guarded so a null manager just pops.
    function onSelect() as Boolean {
        var manager = _manager;
        if (manager != null) {
            try {
                manager.confirm();
            } catch (ex) {
                // A confirm hiccup must not crash input handling (Req 8.5).
            }
        }
        popView();
        return true;
    }

    //! REJECT via BACK: discard the pending pairing (Req 2.4) and pop the view.
    //! Returns true so the behavior is consumed (we intentionally handle the back
    //! gesture rather than letting it bubble).
    function onBack() as Boolean {
        rejectAndPop();
        return true;
    }

    //! REJECT via MENU: same as back — discard and pop (Req 2.4).
    function onMenu() as Boolean {
        rejectAndPop();
        return true;
    }

    //! Shared reject path: drive manager.reject() defensively, then pop the view.
    private function rejectAndPop() as Void {
        var manager = _manager;
        if (manager != null) {
            try {
                manager.reject();
            } catch (ex) {
                // Never crash input handling (Req 8.5).
            }
        }
        popView();
    }

    //! Pop the pairing view off the stack, wrapped so a pop with nothing to pop
    //! can't crash (Req 8.5).
    private function popView() as Void {
        try {
            WatchUi.popView(WatchUi.SLIDE_RIGHT);
        } catch (ex) {
            // No-op: nothing to pop / UI not in a poppable state.
        }
    }
}
