import Toybox.Lang;
import Toybox.System;

//! SyncController — SYNC_MANIFEST intake + pending-manifest holder
//! (design.md §8.0–§8.1; Req 3.1, 3.3, 8.4).
//!
//! Task 6.2 (this slice): own the inbound `SYNC_MANIFEST` handler and turn a
//! hostile-until-proven phone payload into a clean, validated, in-memory manifest
//! the rest of sync (tasks 6.3/7) can rely on. A SYNC_MANIFEST (A→G) looks like
//!   { "v":1, "type":"SYNC_MANIFEST", "pairingId":"<uuid>",
//!     "serverUrl":"http://host:port", "tracks":[ ManifestTrack, ... ] }
//! where each ManifestTrack is
//!   { "id", "folder", "title", "artist"?, "album"?, "durationMs",
//!     "sizeBytes", "mime", "path" }
//! (design.md §8.0). SYNC_MANIFEST carries NO secret — the watch already holds it
//! from pairing (Repo.loadPairing().secret) and uses it only in the download HTTP
//! header (task 7); nothing in this message is sensitive, but we still never log
//! payload contents (Req 7.3).
//!
//! `onManifest(msg)` parses defensively through the null-safe Protocol extractors
//! (getString / getArray / getNumber, and a direct Dictionary check per array
//! element) so a malformed or adversarial payload can never throw out of the
//! dispatch path (Req 8.4/8.5): a bad element is dropped, not fatal. It:
//!   - reads `serverUrl`; a missing/empty url makes the whole manifest unusable,
//!     so we hold nothing and return,
//!   - reads `tracks` (null → empty list),
//!   - cleans each element, DROPPING any entry missing a non-empty `id`, `path`,
//!     or `mime` (the three fields the downloader must have — design §8.3),
//!   - keeps the optional fields (folder/title/artist/album/durationMs/sizeBytes)
//!     present-or-absent.
//! The result is a parsed pending manifest `{ serverUrl, tracks:[...] }` of
//! cleaned ManifestTrack dicts, held in memory and exposed via
//! getPendingManifest() / getPendingTrackCount().
//!
//! Task 6.3 (this slice adds): after a successful parse, onManifest also PERSISTS
//! the pending manifest via Repo.savePendingManifest so a system-launched sync (a
//! separate ACP process) can download without the phone resending it (design §8.1,
//! §10; Req 7.1, 7.2). Repo chunks the track list under flat "pm:*" keys to stay
//! under the ~8 KB per-value limit; the persistence call is best-effort (wrapped)
//! so a storage hiccup never crashes dispatch, and the manifest is still held in
//! memory. The handler is registered at app start (GsoundGApp.onStart) via
//! registerHandlers(), mirroring PairingManager.
//!
//! Scope boundary: the "fetch at sync start" HTTP fallback when `tracks` is
//! empty/truncated (task 6.4 / design §6.1) remains a separate follow-on task; a
//! reserved "pm:fetch" key in Repo leaves the seam for it.
class SyncController {

    //! The ControlChannel this controller registers its SYNC_MANIFEST handler on.
    //! Held for the controller's lifetime; used by later tasks to transmit
    //! SYNC_STATUS. (App-level handler wiring happens in task 6.3.)
    private var _control as ControlChannel;

    //! The most recently parsed, validated pending manifest, or null when none has
    //! been received yet (or the last one was unusable). Shape:
    //!   { FIELD_SERVER_URL => String, FIELD_TRACKS => Array<Dictionary> }
    //! Each track dict is a cleaned ManifestTrack keyed by Messages.FIELD_*.
    //! Task 6.3 persists this; task 7 consumes it to drive the download.
    private var _pendingManifest as Dictionary?;

    //! Task 6.4 fallback state (design §6.1). True when the last usable manifest
    //! arrived with an empty/truncated inline `tracks` list, so task 7's
    //! onStartSync must fetch the authoritative list from GET {serverUrl}/manifest
    //! rather than trusting the inline tracks. Mirrors Repo's persisted "pm:fetch"
    //! flag so an in-process reader sees the same decision the persisted flag
    //! records for the separate system-launched sync process (Req 7.2).
    private var _needsFetchAtSyncStart as Boolean;

    //! The phone-reported expected track count when the fetch-at-sync-start flag is
    //! set and a count was available, else null. The v1 SYNC_MANIFEST wire shape
    //! carries no count field (GsoundA ControlMessage.SyncManifest), so this stays
    //! null in practice; it is kept for forward-compatibility and to let task 7
    //! surface an expectation if the phone ever adds one.
    private var _expectedTrackCount as Number?;

    //! Build a SyncController bound to `control`. Does not register the handler —
    //! registerHandlers() is a separate step the app calls once the channel is
    //! ready (mirrors PairingManager).
    function initialize(control as ControlChannel) {
        _control = control;
        _pendingManifest = null;
        _needsFetchAtSyncStart = false;
        _expectedTrackCount = null;
    }

    //! Register this controller's inbound handler on the control channel: it
    //! handles SYNC_MANIFEST (design.md §8.1). Called once at app start after the
    //! channel exists. ControlChannel has already structurally validated and
    //! pairing-filtered anything it dispatches here. (App-start wiring is finished
    //! in task 6.3; this method exists now so the registration is colocated with
    //! the handler and the module compiles standalone.)
    function registerHandlers() as Void {
        _control.setHandler(Messages.TYPE_SYNC_MANIFEST, method(:onManifest));
    }

    // --- inbound SYNC_MANIFEST handler -----------------------------------------

    //! Handle an inbound SYNC_MANIFEST (A→G): parse + validate the payload into a
    //! clean pending manifest and hold it (design.md §8.1; Req 3.1, 3.3). The
    //! entire body is wrapped try/catch so a hostile payload shape can't crash the
    //! dispatch path (Req 8.4/8.5); on any failure we simply hold nothing new.
    //!
    //! Returns the parsed pending manifest (also stored and reachable via
    //! getPendingManifest), or null when the message is unusable (no serverUrl) or
    //! parsing failed. Returning it lets task 6.3 persist the result without
    //! re-reading internal state.
    function onManifest(msg as Dictionary) as Dictionary? {
        try {
            // serverUrl is mandatory — without it no track can be fetched, so the
            // whole manifest is unusable. Use it verbatim (design §8.0: the port
            // is not always 8080). Hold nothing and bail on missing/empty.
            var serverUrl = Protocol.getString(msg, Messages.FIELD_SERVER_URL);
            if (serverUrl == null || serverUrl.equals("")) {
                return null;
            }

            // tracks: null (absent/wrong type) degrades to an empty list rather
            // than an error (Req 8.4). An empty/truncated list is still a valid
            // "hold serverUrl" case — task 6.4 adds the HTTP fetch-at-sync-start
            // fallback for that; here we simply carry whatever survives cleaning.
            var rawTracks = Protocol.getArray(msg, Messages.FIELD_TRACKS);
            var cleanTracks = ManifestParser.cleanTracks(rawTracks);

            var manifest = {
                Messages.FIELD_SERVER_URL => serverUrl,
                Messages.FIELD_TRACKS => cleanTracks
            };
            _pendingManifest = manifest;

            // Diagnostic: confirm the watch RECEIVED and parsed a SYNC_MANIFEST. Visible in the
            // simulator console / `monkeydo` output. Non-sensitive: track count only, never the
            // serverUrl host or any payload content (Req 7.3). This is the watch-side counterpart
            // to the phone's "SYNC_MANIFEST sent" log — together they prove delivery end to end.
            System.println("Gsound: SYNC_MANIFEST received, tracks=" + cleanTracks.size());

            // Persist the new pending manifest so a system-launched sync (a
            // separate ACP process, Req 7.2) can download without the phone
            // resending it. Repo chunks the track list under flat pm: keys to stay
            // under the ~8 KB per-value limit (design §8.1, §10; Req 7.1). A
            // brand-new valid manifest replaces any previously persisted one.
            // Wrapped so a storage hiccup can't crash the dispatch path (Req 8.5);
            // we still hold the manifest in memory on a persistence failure.
            try {
                Repo.savePendingManifest(manifest);
            } catch (persistEx) {
                // Best-effort persistence; swallow without logging contents (Req 7.3).
            }

            // Large-manifest HTTP fallback decision (design §6.1). The control
            // channel has a payload ceiling, so the phone may send a manifest with
            // an empty `tracks` list (serverUrl only) for a large library. An empty
            // cleaned list after a VALID serverUrl must not be read as "nothing to
            // sync": we flag that task 7's onStartSync has to fetch the authoritative
            // list from GET {serverUrl}/manifest instead. A non-empty list is the
            // normal inline path and clears any stale flag.
            //
            // GsoundA's SYNC_MANIFEST carries no expected-count/total field (only
            // pairingId/serverUrl/tracks/v — GsoundA ControlMessage.SyncManifest),
            // so the only detectable trigger in v1 is an EMPTY list; truncation
            // below an expected total can't be distinguished without a count the
            // phone does not send. We still read an optional count defensively so
            // the seam is ready if the wire ever grows one.
            applyFetchFallback(msg, cleanTracks);

            return manifest;
        } catch (ex) {
            // A malformed/hostile payload must never crash the message callback
            // (Req 8.4/8.5). Swallow without logging contents (Req 7.3). Leave any
            // previously held manifest untouched.
            return null;
        }
    }

    //! Decide and record the task-6.4 fetch-at-sync-start fallback (design §6.1)
    //! for a freshly parsed manifest, given the message `msg` and the cleaned
    //! track list. Sets the flag (and persists it via Repo) when the manifest is a
    //! "serverUrl only" / truncated case; clears it on the normal inline path.
    //!
    //! Trigger rules:
    //!   - EMPTY cleaned list → fallback ON (the phone sent serverUrl only for a
    //!     large library; the authoritative list is behind GET /manifest).
    //!   - An expected count IS present AND the cleaned list has fewer entries than
    //!     it → truncation → fallback ON, carrying the expected count. (This branch
    //!     is dormant in v1: SYNC_MANIFEST carries no count field — see below — so
    //!     `expected` is null and the branch never fires. It is wired so the seam is
    //!     ready if the wire grows a count.)
    //!   - Otherwise (non-empty, and complete when a count is known) → fallback OFF.
    //!
    //! Expected-count source: GsoundA's SYNC_MANIFEST does not send one (its wire
    //! shape is pairingId/serverUrl/tracks/v — ControlMessage.SyncManifest). We
    //! probe FIELD_TOTAL defensively rather than inventing a new wire key the phone
    //! never sends; in practice it is absent, so `expected` is null. Best-effort and
    //! wrapped so a persistence hiccup can't crash dispatch (Req 8.5); contents are
    //! never logged (Req 7.3).
    private function applyFetchFallback(msg as Dictionary, cleanTracks as Array<Dictionary>) as Void {
        var expected = Protocol.getNumber(msg, Messages.FIELD_TOTAL);
        var count = cleanTracks.size();

        var needsFetch = false;
        if (count == 0) {
            // Empty inline list behind a valid serverUrl → fetch at sync start.
            needsFetch = true;
        } else if (expected != null && count < (expected as Number)) {
            // Truncated below the phone-reported expected total (dormant in v1).
            needsFetch = true;
        }

        _needsFetchAtSyncStart = needsFetch;
        _expectedTrackCount = needsFetch ? expected : null;

        try {
            if (needsFetch) {
                Repo.savePendingFetchFlag(_expectedTrackCount);
            } else {
                // Complete inline manifest → no fetch needed; clear any stale flag.
                Repo.deletePendingFetchFlag();
            }
        } catch (ex) {
            // Best-effort persistence; swallow without logging (Req 7.3, 8.5).
        }
    }

    // --- accessors --------------------------------------------------------------

    //! The most recently parsed pending manifest `{ serverUrl, tracks:[...] }`, or
    //! null when none has been received/held. Task 6.3 persists this; task 7
    //! consumes it. The returned dict is the live held reference — callers that
    //! mutate it mutate the controller's state.
    function getPendingManifest() as Dictionary? {
        return _pendingManifest;
    }

    //! Number of validated tracks in the current pending manifest, or 0 when there
    //! is no pending manifest. Convenience for SyncConfigView's pending-count
    //! surface (design §8.1).
    function getPendingTrackCount() as Number {
        if (_pendingManifest == null) {
            return 0;
        }
        var tracks = _pendingManifest.get(Messages.FIELD_TRACKS);
        if (tracks instanceof Array) {
            return (tracks as Array).size();
        }
        return 0;
    }

    //! True when the current pending manifest was received with an empty/truncated
    //! inline `tracks` list, so task 7's onStartSync must fetch the authoritative
    //! list from GET {serverUrl}/manifest over HTTP rather than trusting the inline
    //! tracks (design §6.1). False on the normal inline path. Reflects the same
    //! decision persisted by Repo under "pm:fetch" (task 7 reads the persisted flag
    //! in its own process; this accessor serves an in-process reader such as
    //! SyncConfigView).
    function needsFetchAtSyncStart() as Boolean {
        return _needsFetchAtSyncStart;
    }

    //! The phone-reported expected track count when the fetch-at-sync-start flag is
    //! set and a count was supplied, else null. Null in v1 (SYNC_MANIFEST carries
    //! no count field — see applyFetchFallback); kept so task 7 can surface an
    //! expectation if the wire ever adds one.
    function getExpectedTrackCount() as Number? {
        return _expectedTrackCount;
    }
}
