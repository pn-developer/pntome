import Toybox.Lang;

//! Messages — control-channel message TYPE constants + outgoing-dictionary
//! builders (design.md §6; overview §5.1).
//!
//! Task 4.1 (this module): the message vocabulary and the constructors for every
//! message GsoundG sends. A control message is a Monkey C dictionary (serialized
//! to JSON by Communications.transmit) of the shape
//!   { "v": 1, "type": <string>, "pairingId": <uuid>, ... }
//! (overview §5.1). This module owns:
//!   - TYPE_* string constants for each message type in the shared contract, so
//!     the type names are defined in exactly ONE place (Protocol.validate and
//!     ControlChannel dispatch both read them from here).
//!   - isKnownType(type) — membership test over the full type set, used by
//!     Protocol.validate to reject unknown types.
//!   - build* helpers — construct each OUTGOING message dict with `v` + `type` +
//!     `pairingId` + its payload fields, in the exact shapes of overview §5.1.
//!
//! GsoundG sends PAIR_CONFIRM, SYNC_STATUS, STATS, and PONG; it may also initiate
//! pairing/unpairing, so PAIR_REQUEST and UNPAIR builders are provided too. The
//! inbound-only messages (SYNC_MANIFEST, PING) have no builder here — they are
//! parsed via Protocol's extractors — but their TYPE constants exist so
//! validation and dispatch recognize them.
//!
//! These helpers are pure dictionary constructors: no transmit, no storage, no
//! logging (so the pairing secret is never written to a log here — Req 7.3). The
//! actual Communications.transmit call lives in ControlChannel (task 4.2).
module Messages {

    // --- message type constants (overview §5.1) --------------------------------

    //! Begin pairing: payload code, pairingId, secret, peerName. A → G or G → A.
    const TYPE_PAIR_REQUEST = "PAIR_REQUEST";

    //! User confirmed pairing on this side: payload pairingId. Either direction.
    const TYPE_PAIR_CONFIRM = "PAIR_CONFIRM";

    //! Remove the pairing: payload pairingId. Either direction.
    const TYPE_UNPAIR = "UNPAIR";

    //! Full track list + where to fetch: payload serverUrl, tracks[]. A → G.
    const TYPE_SYNC_MANIFEST = "SYNC_MANIFEST";

    //! Sync progress report: payload state, done, total, bytesDone, error?. G → A.
    const TYPE_SYNC_STATUS = "SYNC_STATUS";

    //! Watch-side stats: payload fileCount, folderCount, lastSyncAt. G → A.
    const TYPE_STATS = "STATS";

    //! Liveness probe (no payload). Either direction.
    const TYPE_PING = "PING";

    //! Liveness reply (no payload). Either direction.
    const TYPE_PONG = "PONG";

    // --- payload field key names (overview §5.1) -------------------------------
    //
    // Centralized so builders and the Protocol extractors agree on spelling.

    const FIELD_CODE = "code";
    const FIELD_SECRET = "secret";
    const FIELD_PEER_NAME = "peerName";
    const FIELD_SERVER_URL = "serverUrl";
    const FIELD_TRACKS = "tracks";
    const FIELD_STATE = "state";
    const FIELD_DONE = "done";
    const FIELD_TOTAL = "total";
    const FIELD_BYTES_DONE = "bytesDone";
    const FIELD_ERROR = "error";
    const FIELD_FILE_COUNT = "fileCount";
    const FIELD_FOLDER_COUNT = "folderCount";
    const FIELD_LAST_SYNC_AT = "lastSyncAt";

    // --- per-track manifest field keys (ManifestTrack, design §8.0) ------------
    //
    // Keys of each entry in a SYNC_MANIFEST `tracks[]` element. Shared by manifest
    // intake (task 6) and the downloader (task 7) so spelling matches GsoundA's
    // ManifestTrack wire shape (@SerialName keys, GsoundA ManifestTrack.kt).

    const FIELD_ID = "id";
    const FIELD_FOLDER = "folder";
    const FIELD_TITLE = "title";
    const FIELD_ARTIST = "artist";
    const FIELD_ALBUM = "album";
    const FIELD_DURATION_MS = "durationMs";
    const FIELD_SIZE_BYTES = "sizeBytes";
    const FIELD_MIME = "mime";
    const FIELD_PATH = "path";

    //! True when `type` is one of the known control-message types above. Used by
    //! Protocol.validate to reject messages of an unrecognized type (Req 8.4).
    //! Null/non-string input is treated as unknown.
    function isKnownType(type as String?) as Boolean {
        if (type == null) {
            return false;
        }
        return type.equals(TYPE_PAIR_REQUEST)
            || type.equals(TYPE_PAIR_CONFIRM)
            || type.equals(TYPE_UNPAIR)
            || type.equals(TYPE_SYNC_MANIFEST)
            || type.equals(TYPE_SYNC_STATUS)
            || type.equals(TYPE_STATS)
            || type.equals(TYPE_PING)
            || type.equals(TYPE_PONG);
    }

    //! Build the common envelope { "v": PROTOCOL_VERSION, "type": type,
    //! "pairingId": pairingId } that every message shares (overview §5.1).
    //! Payload-specific builders start from this and add their own fields. When
    //! `pairingId` is null the key is omitted — the only legitimate null case is
    //! an initiating PAIR_REQUEST generated before a pairingId exists on this
    //! side; Protocol.validate allows PAIR_REQUEST without a pairingId.
    function envelope(type as String, pairingId as String?) as Dictionary {
        var msg = {
            Protocol.KEY_VERSION => Protocol.PROTOCOL_VERSION,
            Protocol.KEY_TYPE => type
        };
        if (pairingId != null) {
            msg.put(Protocol.KEY_PAIRING_ID, pairingId);
        }
        return msg;
    }

    // --- outgoing-message builders (overview §5.1) -----------------------------

    //! Build a PAIR_REQUEST { v, type, pairingId, code, secret, peerName } used
    //! when GsoundG initiates pairing (optional in v1; design.md §7). All payload
    //! fields are supplied by the caller (PairingManager generates code/secret).
    function buildPairRequest(
            pairingId as String,
            code as String,
            secret as String,
            peerName as String) as Dictionary {
        var msg = envelope(TYPE_PAIR_REQUEST, pairingId);
        msg.put(FIELD_CODE, code);
        msg.put(FIELD_SECRET, secret);
        msg.put(FIELD_PEER_NAME, peerName);
        return msg;
    }

    //! Build a PAIR_CONFIRM { v, type, pairingId } sent when the user confirms
    //! the code on the watch (Req 2.3).
    function buildPairConfirm(pairingId as String) as Dictionary {
        return envelope(TYPE_PAIR_CONFIRM, pairingId);
    }

    //! Build an UNPAIR { v, type, pairingId } sent when the user unpairs on the
    //! watch (Req 2.5).
    function buildUnpair(pairingId as String) as Dictionary {
        return envelope(TYPE_UNPAIR, pairingId);
    }

    //! Build a SYNC_STATUS { v, type, pairingId, state, done, total, bytesDone,
    //! error? } progress report (Req 4.3, 4.6, 4.7). `error` is included only
    //! when a non-null message is supplied, matching the optional `error?` field
    //! in the shared contract (overview §5.1).
    function buildSyncStatus(
            pairingId as String,
            state as String,
            done as Number,
            total as Number,
            bytesDone as Number,
            error as String?) as Dictionary {
        var msg = envelope(TYPE_SYNC_STATUS, pairingId);
        msg.put(FIELD_STATE, state);
        msg.put(FIELD_DONE, done);
        msg.put(FIELD_TOTAL, total);
        msg.put(FIELD_BYTES_DONE, bytesDone);
        if (error != null) {
            msg.put(FIELD_ERROR, error);
        }
        return msg;
    }

    //! Build a STATS { v, type, pairingId, fileCount, folderCount, lastSyncAt }
    //! message sent after sync and on a stats/PING request (Req 5.3). `lastSyncAt`
    //! may be null before the first sync, in which case the key is omitted.
    function buildStats(
            pairingId as String,
            fileCount as Number,
            folderCount as Number,
            lastSyncAt as Number?) as Dictionary {
        var msg = envelope(TYPE_STATS, pairingId);
        msg.put(FIELD_FILE_COUNT, fileCount);
        msg.put(FIELD_FOLDER_COUNT, folderCount);
        if (lastSyncAt != null) {
            msg.put(FIELD_LAST_SYNC_AT, lastSyncAt);
        }
        return msg;
    }

    //! Build a PONG { v, type, pairingId } liveness reply to an inbound PING.
    function buildPong(pairingId as String) as Dictionary {
        return envelope(TYPE_PONG, pairingId);
    }
}
