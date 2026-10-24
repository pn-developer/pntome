import Toybox.Lang;
import Toybox.Time;
import Toybox.Timer;

//! PairingManager — the watch-side pairing state machine (design.md §7; Req 2.1–2.7).
//!
//! Task 5.1 (this module): own the pairing lifecycle and the inbound control
//! handlers for the three pairing message types. The state machine is:
//!
//!     IDLE ──PAIR_REQUEST──▶ AWAITING_CONFIRM ──confirm()──▶ PAIRED
//!       ▲                          │
//!       └──────reject()/timeout────┘  (temp values discarded)
//!
//! On `PAIR_REQUEST` we stash a TEMP record `{code, pairingId, secret, peerName}`
//! and move to AWAITING_CONFIRM; the PairingView (task 5.2) reads the current code
//! via getCurrentCode()/getPeerName() and offers Confirm / Reject. On Confirm we
//! send PAIR_CONFIRM {pairingId} through the ControlChannel and persist a
//! PairingRecord `{pairingId, peerName, secret, createdAt}` via Repo.savePairing;
//! on Reject or a ~120 s timeout we discard the temp values and fall back to IDLE.
//! An inbound `UNPAIR` deletes the persisted record (Repo.deletePairing) and resets.
//!
//! v1 supports only ONE active pairing at a time (Req 2.7): a second PAIR_REQUEST
//! while one is already AWAITING_CONFIRM replaces the pending temp values (the
//! latest request wins) rather than tracking two concurrent flows.
//!
//! Resilience & privacy: every handler body is wrapped so a malformed message
//! can't crash the dispatch path (Req 8.4/8.5), and the pairing secret is NEVER
//! logged or printed anywhere in this module (Req 7.3). The view-facing accessors
//! expose only the 6-digit code and the peer name — never the secret.
class PairingManager {

    //! State: no pending or active pairing on this side.
    public static const STATE_IDLE = 0;

    //! State: a PAIR_REQUEST has been received and the user is being asked to
    //! confirm the code. Temp values are held; a timeout timer is armed.
    public static const STATE_AWAITING_CONFIRM = 1;

    //! State: the user confirmed and a PairingRecord has been persisted.
    public static const STATE_PAIRED = 2;

    //! Pairing confirmation window in milliseconds (~120 s; Req 2.4). When it
    //! elapses in AWAITING_CONFIRM the temp values are discarded and we return to
    //! IDLE.
    public static const CONFIRM_TIMEOUT_MS = 120000;

    //! The ControlChannel used to send PAIR_CONFIRM and (optionally) initiate
    //! pairing. Held for the manager's lifetime.
    private var _control as ControlChannel;

    //! Current state (one of STATE_*). Starts IDLE; reflects PAIRED immediately
    //! when constructed with a pairing already persisted so the rest of the app
    //! sees a consistent state after a restart.
    private var _state as Number;

    //! Temp values held only while AWAITING_CONFIRM: the 6-digit code shown to the
    //! user, the proposed pairingId, the shared secret, and the peer name. Cleared
    //! on confirm/reject/timeout. Null in every other state. The secret lives here
    //! transiently and is never logged (Req 7.3).
    private var _tempCode as String?;
    private var _tempPairingId as String?;
    private var _tempSecret as String?;
    private var _tempPeerName as String?;

    //! Timer that fires once after CONFIRM_TIMEOUT_MS to abort an unconfirmed
    //! pairing (Req 2.4). Armed on entering AWAITING_CONFIRM, cancelled on
    //! confirm/reject and before re-arming for a replacing request.
    private var _timeoutTimer as Timer.Timer?;

    //! Build a PairingManager bound to `control`. If a PairingRecord is already
    //! persisted we start in STATE_PAIRED so a relaunch reflects the stored
    //! pairing; otherwise IDLE. Registering the inbound handlers is a separate
    //! step (registerHandlers) the app calls once the channel is ready.
    function initialize(control as ControlChannel) {
        _control = control;
        _tempCode = null;
        _tempPairingId = null;
        _tempSecret = null;
        _tempPeerName = null;
        _timeoutTimer = null;

        var existing = Repo.loadPairing();
        if (existing != null) {
            _state = STATE_PAIRED;
        } else {
            _state = STATE_IDLE;
        }
    }

    //! Register this manager's inbound handlers on the control channel: it handles
    //! PAIR_REQUEST, PAIR_CONFIRM, and UNPAIR (design.md §7). Called once at app
    //! start after the channel exists. ControlChannel has already validated and
    //! pairing-filtered anything it dispatches here.
    function registerHandlers() as Void {
        _control.setHandler(Messages.TYPE_PAIR_REQUEST, method(:onPairRequest));
        _control.setHandler(Messages.TYPE_PAIR_CONFIRM, method(:onPairConfirm));
        _control.setHandler(Messages.TYPE_UNPAIR, method(:onUnpair));
    }

    // --- inbound handlers ------------------------------------------------------

    //! Handle an inbound PAIR_REQUEST (A→G): extract {code, pairingId, secret,
    //! peerName} defensively via the Protocol extractors, store them as the temp
    //! pending pairing, arm the confirmation timeout, and move to
    //! AWAITING_CONFIRM so the view can show the code (Req 2.2).
    //!
    //! A request missing its required fields (code / pairingId / secret) is
    //! ignored rather than entering a half-populated AWAITING_CONFIRM (Req 8.4).
    //! A request arriving while already AWAITING_CONFIRM replaces the pending temp
    //! values — the latest request wins (one active pairing at a time, Req 2.7).
    //! Wrapped so a malformed payload can't crash the dispatch path (Req 8.4/8.5).
    //! Never logs the secret (Req 7.3).
    function onPairRequest(msg as Dictionary) as Void {
        try {
            var code = Protocol.getString(msg, Messages.FIELD_CODE);
            var pairingId = Protocol.pairingIdOf(msg);
            var secret = Protocol.getString(msg, Messages.FIELD_SECRET);
            var peerName = Protocol.getString(msg, Messages.FIELD_PEER_NAME);

            // Required fields must be present and non-empty; otherwise ignore the
            // request (a confirm with a missing secret/pairingId can't succeed).
            if (code == null || code.equals("")
                    || pairingId == null || pairingId.equals("")
                    || secret == null || secret.equals("")) {
                return;
            }

            // Replace any in-flight pending pairing (latest request wins; Req 2.7)
            // and re-arm the timeout cleanly.
            cancelTimeout();

            _tempCode = code;
            _tempPairingId = pairingId;
            _tempSecret = secret;
            _tempPeerName = peerName;
            _state = STATE_AWAITING_CONFIRM;

            armTimeout();
        } catch (ex) {
            // Malformed/hostile payload must not crash dispatch (Req 8.4/8.5).
            // Leave any prior state untouched; nothing logged (Req 7.3).
        }
    }

    //! Handle an inbound PAIR_CONFIRM (A→G) — the phone confirming a pairing it
    //! initiated. When we have matching pending temp values this completes the
    //! pairing exactly as a local confirm would (persist the record, go PAIRED).
    //! Guarded so a stray/duplicate confirm with no matching pending pairing is a
    //! safe no-op. Wrapped against malformed input (Req 8.4/8.5).
    function onPairConfirm(msg as Dictionary) as Void {
        try {
            if (_state != STATE_AWAITING_CONFIRM || _tempPairingId == null) {
                return;
            }
            var pairingId = Protocol.pairingIdOf(msg);
            if (pairingId == null || !pairingId.equals(_tempPairingId)) {
                return;
            }
            persistPendingPairing();
        } catch (ex) {
            // Never crash dispatch; leave state as-is (Req 8.4/8.5).
        }
    }

    //! Handle an inbound UNPAIR (A→G): delete the persisted PairingRecord and
    //! reset to IDLE, discarding any pending temp values and cancelling the
    //! timeout (Req 2.5). ControlChannel has already confirmed the pairingId
    //! matches the stored record before dispatching here. Wrapped defensively.
    function onUnpair(msg as Dictionary) as Void {
        try {
            Repo.deletePairing();
            resetToIdle();
        } catch (ex) {
            // A storage hiccup must not crash dispatch (Req 8.5).
        }
    }

    // --- user actions (driven by the view/delegate in task 5.2) ----------------

    //! User confirmed the code on the watch (Req 2.3): send PAIR_CONFIRM
    //! {pairingId} to the phone and persist the PairingRecord. A no-op unless we
    //! are AWAITING_CONFIRM with valid temp values. Returns true when the pairing
    //! was persisted, false otherwise so the view can react.
    function confirm() as Boolean {
        if (_state != STATE_AWAITING_CONFIRM || _tempPairingId == null
                || _tempSecret == null) {
            return false;
        }
        var pairingId = _tempPairingId as String;

        // Tell the phone we accepted before persisting locally; the record is the
        // source of truth going forward regardless of transmit outcome.
        var confirmMsg = Messages.buildPairConfirm(pairingId);
        _control.transmit(confirmMsg, null, null);

        persistPendingPairing();
        return true;
    }

    //! User rejected the code on the watch (Req 2.4): discard the temp values,
    //! cancel the timeout, and return to IDLE. Safe to call in any state.
    function reject() as Void {
        resetToIdle();
    }

    //! Begin unpairing from the watch side (Req 2.5): send an UNPAIR to the phone
    //! (best effort) and delete the local record, resetting to IDLE. A no-op
    //! transmit is fine when there's no record/pairingId.
    function unpair() as Void {
        var record = Repo.loadPairing();
        if (record != null) {
            var stored = record.get(Protocol.KEY_PAIRING_ID);
            if (stored instanceof String && !(stored as String).equals("")) {
                _control.transmit(Messages.buildUnpair(stored as String), null, null);
            }
        }
        Repo.deletePairing();
        resetToIdle();
    }

    // --- view-facing accessors (read by PairingView in task 5.2) ---------------

    //! Current state (one of STATE_*). The view uses this to decide whether to
    //! show the confirm prompt.
    function getState() as Number {
        return _state;
    }

    //! True when a pairing confirmation is pending (there is a code to show).
    function isAwaitingConfirm() as Boolean {
        return _state == STATE_AWAITING_CONFIRM;
    }

    //! The 6-digit code the user must confirm, or null when none is pending. This
    //! is the value the PairingView displays (task 5.2).
    function getCurrentCode() as String? {
        return _tempCode;
    }

    //! The peer (phone) name associated with the pending pairing, or null. Shown
    //! by the view for context; never includes the secret (Req 7.3).
    function getPeerName() as String? {
        return _tempPeerName;
    }

    //! True when a PairingRecord is currently persisted / the manager is PAIRED.
    function isPaired() as Boolean {
        return _state == STATE_PAIRED;
    }

    // --- internals -------------------------------------------------------------

    //! Persist the pending temp values as a PairingRecord `{pairingId, peerName,
    //! secret, createdAt}` (the shape Repo expects), move to PAIRED, cancel the
    //! timeout, and clear the temp values. `createdAt` is the current epoch
    //! second. The secret is written to Application.Storage only — never logged
    //! (Req 7.3).
    private function persistPendingPairing() as Void {
        if (_tempPairingId == null || _tempSecret == null) {
            return;
        }
        var peerName = _tempPeerName;
        if (peerName == null) {
            peerName = "";
        }
        var record = {
            Protocol.KEY_PAIRING_ID => _tempPairingId as String,
            Messages.FIELD_PEER_NAME => peerName,
            Messages.FIELD_SECRET => _tempSecret as String,
            "createdAt" => Time.now().value()
        };
        Repo.savePairing(record);

        cancelTimeout();
        clearTemp();
        _state = STATE_PAIRED;
    }

    //! Discard temp values, cancel the timeout, and return to IDLE (used by
    //! reject, timeout, and unpair). Leaves any already-persisted record alone —
    //! callers that must also delete the record do so before calling this.
    private function resetToIdle() as Void {
        cancelTimeout();
        clearTemp();
        _state = STATE_IDLE;
    }

    //! Clear all pending temp values (code/pairingId/secret/peerName).
    private function clearTemp() as Void {
        _tempCode = null;
        _tempPairingId = null;
        _tempSecret = null;
        _tempPeerName = null;
    }

    //! Arm the one-shot confirmation timeout (~120 s). Any existing timer is
    //! cancelled first so we never leak a second timer.
    private function armTimeout() as Void {
        cancelTimeout();
        var timer = new Timer.Timer();
        timer.start(method(:onTimeout), CONFIRM_TIMEOUT_MS, false);
        _timeoutTimer = timer;
    }

    //! Cancel and drop the confirmation timeout if one is armed.
    private function cancelTimeout() as Void {
        var timer = _timeoutTimer;
        if (timer != null) {
            timer.stop();
            _timeoutTimer = null;
        }
    }

    //! Timeout callback (Req 2.4): if still AWAITING_CONFIRM, discard the temp
    //! values and return to IDLE. Public so the Timer can invoke it by symbol.
    function onTimeout() as Void {
        if (_state == STATE_AWAITING_CONFIRM) {
            resetToIdle();
        }
    }
}
