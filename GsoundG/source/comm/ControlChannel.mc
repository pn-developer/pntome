import Toybox.Lang;
import Toybox.Communications;

//! ControlChannel — the single phone<->watch control-message gateway
//! (design.md §6; Req 2.6, 8.4; overview §5.1).
//!
//! Task 4.2 (this module): own the Connect IQ Mobile SDK message channel end to
//! end. Everything that enters or leaves the control channel passes through here:
//!   - register()            — hook Communications.registerForPhoneAppMessages so
//!                             every inbound phone-app message lands in onPhoneMessage.
//!   - onPhoneMessage(msg)    — the inbound pipeline: structural validation
//!                             (Protocol.validate), the PAIRING-IDENTITY FILTER
//!                             (drop messages from an unpaired peer, with the sole
//!                             exception of an initial PAIR_REQUEST — Req 2.6, 8.4),
//!                             then DISPATCH BY `type` to the registered handler.
//!   - transmit(dict, ...)    — the outbound helper wrapping Communications.transmit
//!                             for PAIR_CONFIRM / SYNC_STATUS / STATS / PONG.
//!
//! Why handler HOOKS instead of direct calls: the dispatch targets — PairingManager
//! (tasks 5), SyncController (task 6), StatsStore (task 8) — do not exist yet. Rather
//! than stub out those modules (which belong to other tasks) or hard-reference
//! symbols that won't compile, ControlChannel exposes `setHandler(type, callback)`
//! hooks. Each inbound message is routed to the callback registered for its type;
//! when no handler is registered (as now) dispatch is a safe no-op. Tasks 5/6/8 wire
//! their handlers in via setHandler without touching this file. This keeps the
//! channel compiling and testable today (task 4.3: paste JSON, see dispatch + PONG)
//! while the behavior lands incrementally.
//!
//! Resilience: the inbound path never throws out to the SDK callback — a malformed
//! or hostile payload is validated away (Protocol returns codes, never throws) and
//! any handler exception is caught, so no message can crash a launch (Req 8.4, 8.5).
//! Pairing identity is read fresh from Repo.loadPairing() on every message so an
//! unpair/re-pair takes effect immediately without the channel caching stale state.
//! Nothing here logs message contents (a PAIR_REQUEST/SYNC_MANIFEST carries the
//! shared secret — Req 7.3).
class ControlChannel {

    //! type (String) -> handler Method(msg as Dictionary) as Void. Populated by
    //! PairingManager/SyncController/StatsStore via setHandler (tasks 5/6/8); empty
    //! until then, which makes dispatch a no-op rather than an error.
    private var _handlers as Dictionary<String, Method>;

    function initialize() {
        _handlers = ({}) as Dictionary<String, Method>;
    }

    // --- registration ----------------------------------------------------------

    //! Subscribe to inbound phone-app messages. After this, every message the
    //! phone sends over the control channel is delivered to onPhoneMessage. Safe
    //! to call once at app start (design.md §6). Wrapped so a platform without the
    //! phone-message API can't crash the app (Req 8.5).
    function register() as Void {
        try {
            Communications.registerForPhoneAppMessages(method(:onPhoneMessage));
        } catch (ex) {
            // Phone-message registration unavailable; the app still runs in other
            // modes (Req 8.5). Nothing logged (no message contents involved).
        }
    }

    //! Register (or replace) the handler invoked when a validated, pairing-checked
    //! message of `type` arrives. `type` is one of the Messages.TYPE_* constants;
    //! `handler` is a Method taking the message Dictionary. Tasks 5/6/8 call this
    //! to wire PairingManager / SyncController / StatsStore in.
    function setHandler(type as String, handler as Method) as Void {
        _handlers.put(type, handler);
    }

    //! Remove the handler for `type`, if any (e.g. on teardown). Harmless when no
    //! handler is registered.
    function clearHandler(type as String) as Void {
        _handlers.remove(type);
    }

    // --- inbound pipeline -------------------------------------------------------

    //! Communications.registerForPhoneAppMessages callback. Receives a
    //! Communications.PhoneAppMessage whose `data` is the decoded JSON payload (a
    //! Monkey C Dictionary for our contract). Runs the full inbound pipeline:
    //!   1. structural validation (Protocol.validate) — reject non-dicts, wrong
    //!      `v`, unknown `type`, missing pairingId (Req 8.4),
    //!   2. pairing-identity filter — drop messages from an unpaired peer unless it
    //!      is an initial PAIR_REQUEST (Req 2.6),
    //!   3. dispatch by `type` to the registered handler.
    //! Never throws back into the SDK: any handler error is caught (Req 8.5).
    function onPhoneMessage(message as Communications.PhoneAppMessage) as Void {
        try {
            var msg = message.data;
            if (!accept(msg)) {
                return;
            }
            dispatch(msg as Dictionary);
        } catch (ex) {
            // A bad payload or handler fault must never crash the message callback
            // (Req 8.4, 8.5). Swallow without logging contents (Req 7.3).
        }
    }

    //! The inbound gate. Returns true only when `msg` is a structurally valid v1
    //! message (Protocol.validate) AND passes the pairing-identity filter.
    //!
    //! Pairing-identity rule (Req 2.6, 8.4): a message is accepted only if its
    //! `pairingId` matches the stored PairingRecord. The single exception is an
    //! initial PAIR_REQUEST — pairing legitimately begins before a shared record
    //! exists, so PAIR_REQUEST is allowed through regardless of stored state
    //! (PairingManager decides whether to honor it). Every other type coming from
    //! an unpaired peer, or carrying a pairingId that doesn't match ours, is
    //! dropped here (returns false) and never dispatched.
    function accept(msg as Object?) as Boolean {
        // 1. structural validation — bad shape/version/type is dropped (Req 8.4).
        if (!Protocol.isValid(msg)) {
            return false;
        }

        var type = Protocol.typeOf(msg);
        if (type == null) {
            return false;
        }

        // 2. an initial PAIR_REQUEST is the one message allowed from an as-yet
        //    unpaired peer (Req 2.6). Let it through for PairingManager to handle.
        if (type.equals(Messages.TYPE_PAIR_REQUEST)) {
            return true;
        }

        // 3. every other type must match the stored pairing identity. Read the
        //    record fresh so unpair/re-pair takes effect immediately.
        return matchesStoredPairing(Protocol.pairingIdOf(msg));
    }

    //! True when `pairingId` is non-null and equals the pairingId in the persisted
    //! PairingRecord (Repo.loadPairing). Returns false when there is no stored
    //! record (unpaired peer), when the record has no pairingId, or when the ids
    //! differ — i.e. the message is from an unpaired/foreign peer and is dropped
    //! (Req 2.6, 8.4).
    function matchesStoredPairing(pairingId as String?) as Boolean {
        if (pairingId == null) {
            return false;
        }
        var record = Repo.loadPairing();
        if (record == null) {
            return false;
        }
        var stored = record.get(Protocol.KEY_PAIRING_ID);
        if (!(stored instanceof String)) {
            return false;
        }
        return (stored as String).equals(pairingId);
    }

    //! Route an already-accepted message to the handler registered for its `type`.
    //! When no handler is registered (the current state for every type until tasks
    //! 5/6/8 wire theirs in) dispatch is a deliberate no-op, so the channel is
    //! complete and correct now and gains behavior as handlers are added.
    //! The handler is invoked with the message Dictionary.
    function dispatch(msg as Dictionary) as Void {
        var type = Protocol.typeOf(msg);
        if (type == null) {
            return;
        }
        var handler = _handlers.get(type);
        if (handler == null) {
            // No handler wired for this type yet — safe no-op (see module note).
            return;
        }
        (handler as Method).invoke(msg);
    }

    // --- outbound helper --------------------------------------------------------

    //! Send an outgoing control message to the phone over the control channel
    //! (design.md §6): PAIR_CONFIRM, SYNC_STATUS, STATS, PONG, etc. Thin wrapper
    //! over Communications.transmit so callers (PairingManager/SyncController/
    //! StatsStore) build a dict via Messages.build* and hand it here without
    //! touching the SDK directly.
    //!
    //! `options` may be null to accept the platform defaults. `listener` receives
    //! the async delivery outcome; pass null and a no-op listener is supplied so
    //! the SDK (whose transmit requires a non-null ConnectionListener) is always
    //! satisfied. Wrapped so a transmit failure (phone disconnected, channel busy)
    //! can't crash the caller — delivery/failure is surfaced through the caller's
    //! `listener` when supplied (Req 8.1, 8.5). Message contents are never logged
    //! (the secret may be present — Req 7.3).
    function transmit(
            payload as Dictionary,
            options as Dictionary?,
            listener as Communications.ConnectionListener?) as Void {
        try {
            var sink = listener;
            if (sink == null) {
                sink = new NoopConnectionListener();
            }
            Communications.transmit(payload as Communications.TransmitType, options, sink);
        } catch (ex) {
            // Transmit unavailable/failed synchronously; the caller's listener (if
            // any) reports async outcomes. Never crash the caller (Req 8.5).
        }
    }
}

//! No-op ConnectionListener used when a caller sends a message without caring
//! about the async delivery outcome. Communications.transmit requires a non-null
//! ConnectionListener, so transmit() substitutes this when passed null. Both
//! callbacks are intentionally empty — success/failure of a fire-and-forget
//! control message (e.g. a PONG) needs no caller action here.
class NoopConnectionListener extends Communications.ConnectionListener {
    function initialize() {
        ConnectionListener.initialize();
    }

    function onComplete() as Void {
    }

    function onError() as Void {
    }
}
