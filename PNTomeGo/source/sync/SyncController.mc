import Toybox.Lang;
import Toybox.System;
import Toybox.Communications;
import Toybox.Timer;

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
//! memory. The handler is registered at app start (PNTomeGoApp.onStart) via
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
    //! carries no count field (PNTome ControlMessage.SyncManifest), so this stays
    //! null in practice; it is kept for forward-compatibility and to let task 7
    //! surface an expectation if the phone ever adds one.
    private var _expectedTrackCount as Number?;

    //! Delay before the deferred auto-start fires (see maybeAutoStartSync). Short: just enough to
    //! return out of the phone-message callback before startSync() tears the app down.
    private const AUTO_START_DELAY_MS = 500;

    //! Pending one-shot timer for the deferred auto-start, held so it isn't garbage collected.
    private var _autoStartTimer as Timer.Timer?;

    //! Build a SyncController bound to `control`. Does not register the handler —
    //! registerHandlers() is a separate step the app calls once the channel is
    //! ready (mirrors PairingManager).
    function initialize(control as ControlChannel) {
        _control = control;
        _pendingManifest = null;
        _needsFetchAtSyncStart = false;
        _expectedTrackCount = null;
        _autoStartTimer = null;
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

            // Audiobook flag / chapter order / titles for tracks already on the watch change
            // without any id change, so apply them now — before SyncNeed decides there is
            // "nothing to download" (spec gsound-audiobooks AB-3.4).
            MetaUpdater.apply(cleanTracks);

            var manifest = {
                Messages.FIELD_SERVER_URL => serverUrl,
                Messages.FIELD_TRACKS => cleanTracks
            };
            _pendingManifest = manifest;

            // Diagnostic: confirm the watch RECEIVED and parsed a SYNC_MANIFEST. Visible in the
            // simulator console / `monkeydo` output. Non-sensitive: track count only, never the
            // serverUrl host or any payload content (Req 7.3). This is the watch-side counterpart
            // to the phone's "SYNC_MANIFEST sent" log — together they prove delivery end to end.
            System.println("PNTome: SYNC_MANIFEST received, tracks=" + cleanTracks.size());

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
            // PNTome's SYNC_MANIFEST carries no expected-count/total field (only
            // pairingId/serverUrl/tracks/v — PNTome ControlMessage.SyncManifest),
            // so the only detectable trigger in v1 is an EMPTY list; truncation
            // below an expected total can't be distinguished without a count the
            // phone does not send. We still read an optional count defensively so
            // the seam is ready if the wire ever grows one.
            applyFetchFallback(msg, cleanTracks);

            // AUTO-TRIGGER the system sync as soon as a usable manifest lands (design §8.1).
            //
            // Why: on an Audio Content Provider the DOWNLOAD only runs when the system launches
            // the app in sync mode and calls PNTomeSyncDelegate.onStartSync() — and the ONLY way
            // to enter that mode is Communications.startSync(). Without this, the phone's "Sync"
            // button delivers the manifest but the watch never downloads (observed: server up,
            // complete=0/23 bytes=0, zero HTTP requests) until the user manually navigates the
            // watch's Music Providers → sync-config → confirm. Triggering here makes "tap Sync on
            // the phone" actually start the transfer.
            //
            // startSync() exits this app instance and relaunches it in sync mode; onStartSync()
            // then reads the PERSISTED manifest (cross-process safe) and downloads. isSyncNeeded()
            // still gates it, so if there is genuinely nothing new the relaunched sync just
            // no-ops. Guarded so an unsupported launch context / transient failure can't crash the
            // message dispatch path (Req 8.5) — the user can still trigger the sync manually.
            maybeAutoStartSync(cleanTracks);

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
    //! Expected-count source: PNTome's SYNC_MANIFEST does not send one (its wire
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

    //! Kick off the system sync right after a fresh, usable manifest is persisted, so the phone's
    //! "Sync" starts the download without the user navigating the watch UI (design §8.1).
    //!
    //! Triggers only when there is something to sync — either inline tracks arrived OR the
    //! fetch-at-sync-start fallback is set (serverUrl-only manifest). Communications.startSync()
    //! relaunches the app in sync mode; PNTomeSyncDelegate.isSyncNeeded() then re-gates against
    //! the persisted index, so an already-synced library won't re-download. Wrapped end to end:
    //! startSync() can throw in an unsupported launch context, and that must never crash the
    //! SYNC_MANIFEST dispatch — the manual sync-config path remains as a fallback (Req 8.5).
    //!
    //! DEFERRED, not called inline: this runs inside the phone-message (comm) callback, and
    //! startSync() EXITS and relaunches the app. Doing that from within the comm callback produced
    //! a device "System Error: Error in comm callback" (CIQ_LOG.YAML, empty stack) and the IQ!
    //! icon, even though the relaunched sync then ran. So we return from the callback first and
    //! call startSync() from a short one-shot Timer — the same kind of UI-thread event as the
    //! button press SyncConfigDelegate.onSelect uses. If a Timer can't be created in this context
    //! we fall back to the direct call (the previous, working-but-logged behaviour).
    private function maybeAutoStartSync(cleanTracks as Array<Dictionary>) as Void {
        var hasWork = cleanTracks.size() > 0 || _needsFetchAtSyncStart;
        if (!hasWork) {
            return;
        }
        // Only start a sync the system will actually run. SyncNeed is the same check the sync
        // mode's isSyncNeeded() uses; if it says "nothing to do", the system would never call
        // onStartSync and the watch would sit on "Synchronization in progress". Instead, tell the
        // phone right away that every manifest track is already on the watch, so its sync
        // completes too.
        if (!SyncNeed.isNeeded()) {
            DebugLog.log("SYNC", "manifest received but nothing new -> no startSync, reporting up to date");
            reportUpToDate();
            return;
        }
        try {
            var timer = new Timer.Timer();
            timer.start(method(:onAutoStartSyncTimer), AUTO_START_DELAY_MS, false);
            _autoStartTimer = timer; // keep a reference so the timer isn't collected
            DebugLog.log("SYNC", "manifest received -> startSync() scheduled in " + AUTO_START_DELAY_MS.toString() + "ms");
        } catch (ex) {
            DebugLog.log("SYNC", "Timer unavailable (" + DebugLog.describe(ex) + ") -> startSync() now");
            startSyncNow();
        }
    }

    //! Send the phone a terminal SYNC_STATUS "N of N, no error" when the manifest it just sent is
    //! already fully on the watch. The phone resolves Completed from done >= total > 0. Best
    //! effort; never throws (Req 8.5). Never logs the secret (Req 7.3).
    private function reportUpToDate() as Void {
        try {
            var pairing = Repo.loadPairing();
            if (pairing == null) {
                return;
            }
            var pairingId = pairing.get(Protocol.KEY_PAIRING_ID);
            if (!(pairingId instanceof String) || (pairingId as String).equals("")) {
                return;
            }
            var n = SyncNeed.manifestTrackCount();
            if (n <= 0) {
                return;
            }
            _control.transmit(
                Messages.buildSyncStatus(pairingId as String, "uptodate", n, n, 0, null), null, null);

            // Also forward the watch debug log (one line per message, see DebugLog). Safe here:
            // nothing new means no startSync(), so this process is not about to exit mid-send.
            // (When a sync DOES start we skip this — the app is torn down 500 ms later.)
            DebugLog.sendPending(_control, pairingId as String);
        } catch (ex) {
            // Best effort.
        }
    }

    //! Timer callback for the deferred auto-start (see maybeAutoStartSync). Public so the Timer
    //! can invoke it by symbol.
    function onAutoStartSyncTimer() as Void {
        _autoStartTimer = null;
        startSyncNow();
    }

    //! Call Communications.startSync(), wrapped: it can throw in an unsupported launch context,
    //! and the manual Music Providers → sync-config path remains as the fallback (Req 8.5).
    private function startSyncNow() as Void {
        try {
            DebugLog.log("SYNC", "calling Communications.startSync()");
            Communications.startSync();
        } catch (ex) {
            DebugLog.log("SYNC", "startSync() FAILED " + DebugLog.describe(ex) + " (use manual sync on watch)");
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
    //!
    //! CROSS-PROCESS: the Audio Content Provider framework launches each mode in a SEPARATE
    //! process. The process that received the SYNC_MANIFEST (control channel) is NOT the one that
    //! renders the sync-config view, so the in-memory `_pendingManifest` is null there even
    //! though onManifest persisted it. We therefore fall back to the PERSISTED manifest
    //! (Repo.loadPendingManifest) when the in-memory copy is absent, so the count is correct in a
    //! freshly-launched config process (design §8.1, Req 7.2). Wrapped so a storage hiccup
    //! degrades to 0 rather than crashing the view (Req 8.5).
    function getPendingTrackCount() as Number {
        var manifest = _pendingManifest;
        if (manifest == null) {
            try {
                manifest = Repo.loadPendingManifest();
            } catch (ex) {
                manifest = null;
            }
        }
        if (manifest == null) {
            return 0;
        }
        var tracks = manifest.get(Messages.FIELD_TRACKS);
        if (tracks instanceof Array) {
            return (tracks as Array).size();
        }
        return 0;
    }

    //! True when the current pending manifest was received with an empty/truncated
    //! inline `tracks` list, so task 7's onStartSync must fetch the authoritative
    //! list from GET {serverUrl}/manifest over HTTP rather than trusting the inline
    //! tracks (design §6.1). False on the normal inline path.
    //!
    //! CROSS-PROCESS: like getPendingTrackCount, the in-memory flag is only set in the process
    //! that handled onManifest. A freshly-launched sync-config process must read the PERSISTED
    //! flag (Repo.loadPendingFetchFlag) so a "serverUrl-only" manifest still offers a sync there
    //! (Req 7.2). We OR the in-memory flag with the persisted one. Wrapped so a storage hiccup
    //! degrades to the in-memory value (Req 8.5).
    function needsFetchAtSyncStart() as Boolean {
        if (_needsFetchAtSyncStart) {
            return true;
        }
        try {
            var flag = Repo.loadPendingFetchFlag();
            var isSet = flag.get("set");
            return (isSet instanceof Boolean) && (isSet as Boolean);
        } catch (ex) {
            return false;
        }
    }

    //! The phone-reported expected track count when the fetch-at-sync-start flag is
    //! set and a count was supplied, else null. Null in v1 (SYNC_MANIFEST carries
    //! no count field — see applyFetchFallback); kept so task 7 can surface an
    //! expectation if the wire ever adds one.
    function getExpectedTrackCount() as Number? {
        return _expectedTrackCount;
    }
}
