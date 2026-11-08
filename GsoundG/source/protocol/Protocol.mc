import Toybox.Lang;

//! Protocol — control-channel protocol version + inbound-message validation
//! (design.md §6; Req 2.6, 8.4; overview §5.1).
//!
//! Task 4.1 (this module): the pure, side-effect-free protocol layer. Every
//! control-channel message is a Monkey C dictionary (decoded JSON) of the shape
//!   { "v": 1, "type": <string>, "pairingId": <uuid>, ... }
//! (overview §5.1). This module owns:
//!   - PROTOCOL_VERSION — the single supported wire version (v=1).
//!   - validate(dict)   — structural gate run on EVERY inbound message before it
//!                        is dispatched: correct `v`, a KNOWN `type`, and (except
//!                        for an initial PAIR_REQUEST) a present `pairingId`.
//!   - field extractors — null-safe getters that coerce storage/JSON Objects to
//!                        the expected Monkey C types, returning null on mismatch.
//!
//! Everything here returns null / a ValidationResult rather than throwing, so a
//! malformed or hostile message can never crash the dispatch path (Req 8.4, 8.5).
//! Pairing-identity enforcement (does this message's pairingId match the STORED
//! record?) is intentionally NOT done here — that needs the persisted pairing and
//! belongs to ControlChannel/PairingManager (task 4.2 / 5). This module only
//! answers "is this a structurally well-formed v1 message of a known type?".
//! The message TYPE string constants and the outgoing-dictionary builders live in
//! Messages.mc; this module references Messages for the type set so there is a
//! single source of truth for type names.
module Protocol {

    //! The only control-channel wire version this build understands (overview
    //! §5.1: every message carries `"v": 1`). Inbound messages with any other
    //! `v` are rejected by validate().
    const PROTOCOL_VERSION = 1;

    //! Dictionary key names shared by every control message (overview §5.1).
    const KEY_VERSION = "v";
    const KEY_TYPE = "type";
    const KEY_PAIRING_ID = "pairingId";

    // --- validation result codes ----------------------------------------------
    //
    // validate() returns one of these Numbers so callers can both branch on
    // OK vs. not and, when rejecting, report WHY (useful for logging/diagnostics
    // without echoing the message contents, which may reference a secret).

    //! Message is a well-formed v1 message of a known type with the required
    //! pairingId present. Safe to dispatch (subject to pairing-identity checks
    //! performed elsewhere).
    const VALID_OK = 0;

    //! Not a dictionary at all (e.g. null, a string, a number). Reject.
    const INVALID_NOT_DICT = 1;

    //! Missing `v`, or `v` is not PROTOCOL_VERSION. Reject (Req 8.4).
    const INVALID_VERSION = 2;

    //! Missing `type`, or `type` is not one of the known message types. Reject.
    const INVALID_TYPE = 3;

    //! `pairingId` is required for this type but is missing/empty. Reject (Req 2.6).
    //! The sole exception is an initial PAIR_REQUEST, which legitimately arrives
    //! before either side has a shared pairingId relationship.
    const INVALID_PAIRING_ID = 4;

    //! Validate an inbound message dictionary structurally and return one of the
    //! VALID_/INVALID_ codes above.
    //!
    //! Checks, in order:
    //!   1. `msg` is a Dictionary (Req 8.4 — ignore non-dict payloads).
    //!   2. `v` equals PROTOCOL_VERSION (overview §5.1).
    //!   3. `type` is a known message type (Messages.isKnownType).
    //!   4. `pairingId` is a non-empty String UNLESS the type is PAIR_REQUEST,
    //!      which is the one message allowed to arrive without an established
    //!      pairingId (Req 2.6 "except an initial PAIR_REQUEST").
    //!
    //! Returns VALID_OK only when all applicable checks pass. Never throws.
    function validate(msg as Object?) as Number {
        if (!(msg instanceof Dictionary)) {
            return INVALID_NOT_DICT;
        }
        var dict = msg as Dictionary;

        var version = dict.get(KEY_VERSION);
        if (!(version instanceof Number) || (version as Number) != PROTOCOL_VERSION) {
            return INVALID_VERSION;
        }

        var type = dict.get(KEY_TYPE);
        if (!(type instanceof String) || !Messages.isKnownType(type as String)) {
            return INVALID_TYPE;
        }

        // pairingId is mandatory for every type except an initial PAIR_REQUEST.
        if (!(type as String).equals(Messages.TYPE_PAIR_REQUEST)) {
            var pairingId = dict.get(KEY_PAIRING_ID);
            if (!(pairingId instanceof String) || (pairingId as String).equals("")) {
                return INVALID_PAIRING_ID;
            }
        }

        return VALID_OK;
    }

    //! Convenience predicate: true when validate(msg) == VALID_OK. Use when the
    //! caller only needs a yes/no gate and doesn't care which check failed.
    function isValid(msg as Object?) as Boolean {
        return validate(msg) == VALID_OK;
    }

    // --- field extractors ------------------------------------------------------
    //
    // Decoded-JSON and Application.Storage values come back as generic Objects, so
    // the rest of the app reads fields through these null-safe, type-coercing
    // getters instead of touching the dictionary directly. Each returns null when
    // the key is absent or the stored value is the wrong type, so a malformed
    // entry degrades to "field not present" rather than a type error (Req 8.4).

    //! Extract the message `type` string, or null if absent/not a string.
    function typeOf(msg as Object?) as String? {
        return getString(msg, KEY_TYPE);
    }

    //! Extract the `pairingId` string, or null if absent/not a string.
    function pairingIdOf(msg as Object?) as String? {
        return getString(msg, KEY_PAIRING_ID);
    }

    //! Read a String field from a message dict, or null if the message is not a
    //! dict, the key is absent, or the value is not a String.
    function getString(msg as Object?, key as String) as String? {
        if (!(msg instanceof Dictionary)) {
            return null;
        }
        var value = (msg as Dictionary).get(key);
        if (value instanceof String) {
            return value as String;
        }
        return null;
    }

    //! Read a Number field from a message dict, or null if the message is not a
    //! dict, the key is absent, or the value is not a Number.
    function getNumber(msg as Object?, key as String) as Number? {
        if (!(msg instanceof Dictionary)) {
            return null;
        }
        var value = (msg as Dictionary).get(key);
        if (value instanceof Number) {
            return value as Number;
        }
        return null;
    }

    //! Read a Boolean field from a message dict, or null if the message is not a
    //! dict, the key is absent, or the value is not a Boolean.
    function getBoolean(msg as Object?, key as String) as Boolean? {
        if (!(msg instanceof Dictionary)) {
            return null;
        }
        var value = (msg as Dictionary).get(key);
        if (value instanceof Boolean) {
            return value as Boolean;
        }
        return null;
    }

    //! Read a Boolean field, returning `dflt` when absent or of the wrong type
    //! (BOOKMARK `fin` defaults to false).
    function getBooleanOr(msg as Object?, key as String, dflt as Boolean) as Boolean {
        var value = getBoolean(msg, key);
        return (value == null) ? dflt : (value as Boolean);
    }

    //! Read an Array field (e.g. `tracks`) from a message dict, or null if the
    //! message is not a dict, the key is absent, or the value is not an Array.
    function getArray(msg as Object?, key as String) as Array? {
        if (!(msg instanceof Dictionary)) {
            return null;
        }
        var value = (msg as Dictionary).get(key);
        if (value instanceof Array) {
            return value as Array;
        }
        return null;
    }

    //! Read a nested Dictionary field from a message dict, or null if absent/not
    //! a dictionary.
    function getDict(msg as Object?, key as String) as Dictionary? {
        if (!(msg instanceof Dictionary)) {
            return null;
        }
        var value = (msg as Dictionary).get(key);
        if (value instanceof Dictionary) {
            return value as Dictionary;
        }
        return null;
    }
}
