import Toybox.Lang;
import Toybox.Communications;
import Toybox.System;

//! GsoundSyncDelegate — the system-driven sync entry point
//! (design.md §8.2; Req 4.1, 4.2, 8.5). Replaces GsoundSyncDelegateStub.
//!
//! The Audio Content Provider framework launches a dedicated sync process, calls
//! isSyncNeeded() to decide whether to offer/run a sync, and — once the user
//! confirms and the system has brought up Wi-Fi — calls onStartSync(). This class
//! owns the DECISION LOGIC and the setup that precedes downloading:
//!   - isSyncNeeded(): is there pending work (manifest differs from the stored
//!     index — additions or deletions), or a fetch-at-sync-start fallback pending?
//!   - onStartSync(): validate we still hold a pairing/secret (abort immediately
//!     with an error if not), resolve the authoritative manifest (inline from Repo
//!     or by fetching GET {serverUrl}/manifest for the §6.1 fallback), then hand
//!     the work off to the Downloader.
//!   - onCancelSync(): minimal safe override (full behaviour is task 7.8).
//!
//! Scope boundary (task 7.1): this file performs manifest RESOLUTION + pairing
//! validation + the handoff to the Downloader. It also owns the GET /manifest
//! HTTP fetch for the fallback path (it is the "fetch at sync start" owner). It
//! does NOT implement the per-track download loop, the 200/401/404 handling,
//! reconciliation, progress transmission, or completion — those are the
//! Downloader's job (tasks 7.3–7.8). The Downloader here is a thin SEAM those
//! tasks flesh out (see Downloader.mc).
//!
//! Resilience: every SDK callback body (isSyncNeeded/onStartSync/onCancelSync and
//! the HTTP response callback) is wrapped so no exception escapes into the SDK and
//! crashes the sync process (Req 8.5). Nothing logs the pairing secret (Req 7.3).
class GsoundSyncDelegate extends Communications.SyncDelegate {

    //! The Downloader for the in-progress sync, created in onStartSync once the
    //! manifest + pairing are resolved. Held so onCancelSync can reach it to
    //! forward the cancel (Req 4.8). Null before onStartSync and between syncs.
    private var _downloader as Downloader?;

    function initialize() {
        SyncDelegate.initialize();
        _downloader = null;
    }

    // --- isSyncNeeded ----------------------------------------------------------

    //! Return true when there is sync work to do (design §8.2): a pending manifest
    //! exists AND it differs from the stored track index — either the manifest has
    //! ids not yet in the index (additions) or the index has ids no longer in the
    //! manifest (deletions, to be reconciled by task 7.6). When the §6.1
    //! fetch-at-sync-start fallback flag is set, the inline manifest is only
    //! serverUrl (the authoritative list lives behind GET /manifest), so we can't
    //! fully diff it here — treat "fetch flag set + a usable serverUrl" as
    //! sync-needed and let onStartSync fetch and decide.
    //!
    //! Wrapped so a storage/parse problem degrades to "no sync needed" rather than
    //! crashing the sync process (Req 8.5).
    //! The decision lives in SyncNeed so the auto-start in SyncController makes the SAME call:
    //! when this returns false the system does NOT call onStartSync, so we must never trigger a
    //! sync in that case (that left the watch stuck on "Synchronization in progress").
    function isSyncNeeded() as Lang.Boolean {
        var needed = SyncNeed.isNeeded();
        DebugLog.log("SYNC", "isSyncNeeded -> " + needed.toString());
        return needed;
    }

    // --- onStartSync -----------------------------------------------------------

    //! Begin the sync (design §8.2). Order of operations:
    //!   1. Validate pairing/secret — if there is no stored pairing or it carries
    //!      no non-empty secret, ABORT IMMEDIATELY: finish with an error (the
    //!      downloader needs the secret for the X-Gsound-Secret header; Req 4.2).
    //!   2. Resolve the manifest:
    //!        - fetch flag set → fetch GET {serverUrl}/manifest over HTTP (§6.1)
    //!          and continue in the HTTP callback,
    //!        - otherwise → use the inline pending manifest from Repo directly.
    //!   3. Hand the resolved manifest + pairing to the Downloader (tasks 7.3+).
    //!
    //! Wrapped so no exception escapes into the SDK (Req 8.5). Never logs the
    //! secret (Req 7.3).
    function onStartSync() as Void {
        DebugLog.log("SYNC", "onStartSync called by system");
        try {
            var pairing = Repo.loadPairing();
            var secret = pairingSecret(pairing);
            if (secret == null) {
                // No pairing/secret → cannot authenticate any download. Abort now
                // with an error; the system sync UI surfaces the failure, and (if
                // a pairingId happens to be known) the phone is told via a
                // SYNC_STATUS carrying a non-null error. When unpaired there is no
                // pairingId to address the phone, so we only notify the system.
                abortNoPairing(pairing);
                return;
            }

            var manifest = Repo.loadPendingManifest();
            if (manifest == null) {
                // isSyncNeeded() gates this, but a manifest could have been cleared
                // between the decision and the start. Nothing to do → complete with
                // no error (not a failure).
                finishNoError();
                return;
            }

            var serverUrl = manifest.get(Messages.FIELD_SERVER_URL);
            if (!(serverUrl instanceof String) || (serverUrl as String).equals("")) {
                // A manifest with no server base is unusable.
                finishNoError();
                return;
            }

            var fetchFlag = Repo.loadPendingFetchFlag();
            var needsFetch = (fetchFlag.get("set") instanceof Boolean)
                && (fetchFlag.get("set") as Boolean);

            if (needsFetch) {
                // §6.1 fallback: the inline tracks are empty/truncated; fetch the
                // authoritative list from GET {serverUrl}/manifest, then start the
                // download in the response callback. This delegate is the owner of
                // the fetch-at-sync-start HTTP request.
                fetchManifestThenDownload(serverUrl as String, pairing as Dictionary);
            } else {
                // Normal inline path: download directly from the held manifest.
                startDownload(manifest, pairing as Dictionary);
            }
        } catch (ex) {
            // Any unexpected failure during setup must not crash the sync process;
            // report a generic error and finish (Req 8.5).
            finishWithError("sync setup failed");
        }
    }

    //! Resolve the pairing secret, or null when there is no pairing or no usable
    //! (non-empty String) secret. Never logs or returns the secret elsewhere
    //! (Req 7.3) — callers only ever pass the whole pairing dict to the Downloader.
    private function pairingSecret(pairing as Dictionary?) as String? {
        if (pairing == null) {
            return null;
        }
        var secret = pairing.get(Messages.FIELD_SECRET);
        if (secret instanceof String && !(secret as String).equals("")) {
            return secret as String;
        }
        return null;
    }

    // --- GET /manifest fallback (design §6.1) ----------------------------------

    //! Kick off the GET {serverUrl}/manifest HTTP fetch for the §6.1 fallback,
    //! authenticated with the X-Gsound-Secret header, expecting a JSON body of the
    //! shape { "tracks": [ ManifestTrack, ... ] } (same shape the inline manifest
    //! carries). The response is handled in onManifestFetched, which parses the
    //! tracks defensively (ManifestParser) and starts the Downloader. The server
    //! base url + pairing are carried to the callback via instance fields since
    //! makeWebRequest's callback signature is fixed.
    //!
    //! Never logs the secret (Req 7.3). Wrapped so a request-build failure finishes
    //! with an error instead of crashing (Req 8.5).
    private function fetchManifestThenDownload(serverUrl as String, pairing as Dictionary) as Void {
        var secret = pairingSecret(pairing);
        if (secret == null) {
            abortNoPairing(pairing);
            return;
        }

        _pendingServerUrl = serverUrl;
        _pendingPairing = pairing;

        var options = {
            :method => Communications.HTTP_REQUEST_METHOD_GET,
            :headers => { "X-Gsound-Secret" => secret },
            :responseType => Communications.HTTP_RESPONSE_CONTENT_TYPE_JSON
        };

        try {
            Communications.makeWebRequest(
                serverUrl + "/manifest",
                null,
                options,
                method(:onManifestFetched));
        } catch (ex) {
            finishWithError("manifest fetch failed to start");
        }
    }

    //! Server base url for an in-flight GET /manifest fetch, bridged to the HTTP
    //! callback (makeWebRequest's callback signature is fixed). Null when no fetch
    //! is in flight.
    private var _pendingServerUrl as String?;

    //! Pairing record for an in-flight GET /manifest fetch, bridged to the HTTP
    //! callback. Holds the secret used for the download header (never logged).
    private var _pendingPairing as Dictionary?;

    //! HTTP response callback for the §6.1 GET /manifest fetch. On 200, parse the
    //! JSON body's `tracks` array with the shared ManifestParser (identical
    //! cleaning to the inline path), build a { serverUrl, tracks:[...] } manifest,
    //! and start the Downloader. On any non-200 or an unusable body, finish with
    //! an error (a non-200 for the manifest means we can't determine the work set;
    //! the per-track 401/404 nuances belong to the download loop, task 7.4).
    //!
    //! Wrapped so a hostile/malformed body can't crash the sync process (Req 8.5).
    //! Never logs contents (Req 7.3).
    function onManifestFetched(responseCode as Number, data as Dictionary or String or Null) as Void {
        try {
            var serverUrl = _pendingServerUrl;
            var pairing = _pendingPairing;
            _pendingServerUrl = null;
            _pendingPairing = null;

            if (serverUrl == null || pairing == null) {
                finishWithError("manifest fetch lost context");
                return;
            }

            if (responseCode != 200 || !(data instanceof Dictionary)) {
                // Could not fetch the authoritative list → can't compute the work
                // set. Report as an error so the phone/system see the failure.
                finishWithError("manifest fetch http " + responseCode.toString());
                return;
            }

            var rawTracks = Protocol.getArray(data as Dictionary, Messages.FIELD_TRACKS);
            var cleanTracks = ManifestParser.cleanTracks(rawTracks);
            // Same metadata refresh as the inline-manifest path (AB-3.4, AB-3.5).
            MetaUpdater.apply(cleanTracks);

            var manifest = {
                Messages.FIELD_SERVER_URL => serverUrl,
                Messages.FIELD_TRACKS => cleanTracks
            };

            startDownload(manifest, pairing);
        } catch (ex) {
            finishWithError("manifest fetch parse failed");
        }
    }

    // --- Downloader handoff ----------------------------------------------------

    //! Hand the resolved manifest + pairing to the Downloader (tasks 7.3+). The
    //! Downloader dedupes against the index, downloads each remaining track
    //! sequentially, reconciles deletions, reports progress, and signals
    //! completion — none of which is implemented in task 7.1 (this is the seam).
    //! Held on _downloader so onCancelSync (task 7.8) can reach it.
    private function startDownload(manifest as Dictionary, pairing as Dictionary) as Void {
        var tracks = manifest.get(Messages.FIELD_TRACKS);
        var count = (tracks instanceof Array) ? (tracks as Array).size() : 0;
        System.println("Gsound: startDownload() work=" + count + " track(s)");
        var control = resolveControl();
        _downloader = new Downloader(manifest, pairing, control);
        _downloader.start();
    }

    //! Resolve the shared ControlChannel so the Downloader can transmit
    //! SYNC_STATUS to the phone. May be null if the control channel didn't come up
    //! in this process; the Downloader must tolerate a null control (progress to
    //! the phone is best-effort — the system progress via notifySyncProgress is
    //! independent).
    private function resolveControl() as ControlChannel? {
        try {
            return getApp().getControl();
        } catch (ex) {
            return null;
        }
    }

    // --- abort / completion helpers --------------------------------------------

    //! Abort the sync because there is no pairing/secret (Req 4.2, 8.2). Finish the
    //! system sync with an error; finishWithError() also tells the phone via a
    //! SYNC_STATUS carrying a non-null error (so it can prompt a re-pair) IF a
    //! pairingId is still available — which it is not when fully unpaired, so an
    //! unpaired abort legitimately notifies only the system. Never logs the secret
    //! (Req 7.3).
    private function abortNoPairing(pairing as Dictionary?) as Void {
        finishWithError("not paired");
    }

    //! The stored pairingId, or null when there is no pairing or it carries no
    //! usable id. Used only to address an error SYNC_STATUS to the phone. When the
    //! passed pairing is null (e.g. a failure path that didn't carry the record),
    //! falls back to Repo.loadPairing() so a still-paired watch can tell the phone
    //! about the failure (Req 8.1). Never logs the secret (Req 7.3).
    private function pairingIdOf(pairing as Dictionary?) as String? {
        var record = pairing;
        if (record == null) {
            try {
                record = Repo.loadPairing();
            } catch (ex) {
                record = null;
            }
        }
        if (record == null) {
            return null;
        }
        var id = record.get(Protocol.KEY_PAIRING_ID);
        if (id instanceof String && !(id as String).equals("")) {
            return id as String;
        }
        return null;
    }

    //! Best-effort: send a SYNC_STATUS with a non-null error to the phone so it can
    //! react (e.g. prompt re-pair). done/total/bytesDone are 0 — no work ran. Uses
    //! the shared ControlChannel; a null channel or transmit failure is swallowed
    //! (the system sync UI still shows the error via notifySyncComplete). Never
    //! logs contents (Req 7.3).
    private function sendErrorStatus(pairingId as String, error as String) as Void {
        try {
            var control = resolveControl();
            if (control == null) {
                return;
            }
            var status = Messages.buildSyncStatus(pairingId, "error", 0, 0, 0, error);
            control.transmit(status, null, null);
        } catch (ex) {
            // Best-effort phone notification; the system sync UI still reflects the
            // error through notifySyncComplete.
        }
    }

    //! Finish the sync reporting an error BOTH to the phone and to the system
    //! (Req 8.1). This is the single error-completion point for every web/HTTP /
    //! setup failure the delegate owns (abort-no-pairing, manifest-fetch non-200,
    //! fetch-failed-to-start, generic setup failure): the phone learns of the
    //! failure via a best-effort SYNC_STATUS carrying a non-null error (so its
    //! SyncOrchestrator terminalizes / can prompt a re-pair, Req 8.2) WHENEVER a
    //! pairingId is still obtainable (from the in-flight record or Repo), and the
    //! system sync UI learns of it via notifySyncComplete(error). When the watch is
    //! fully unpaired there is no pairingId to address, so only the system is
    //! notified — that is correct (the phone has no pairing to update). The error
    //! text is a short, non-sensitive label (never the secret — Req 7.3), and the
    //! phone send is wrapped so it can't prevent the system completion (Req 8.5).
    private function finishWithError(error as String) as Void {
        var pairingId = pairingIdOf(null);
        if (pairingId != null) {
            sendErrorStatus(pairingId, error);
        }
        try {
            Communications.notifySyncComplete(error);
        } catch (ex) {
            // Never let completion crash the sync process (Req 8.5).
        }
    }

    //! Finish the sync with no error (nothing to do / already in sync). Wrapped so
    //! completion can't crash the sync process (Req 8.5).
    private function finishNoError() as Void {
        try {
            Communications.notifySyncComplete(null);
        } catch (ex) {
        }
    }

    // --- onCancelSync (task 7.8 cancel contract) -------------------------------

    //! The user/system cancelled the sync (Req 4.8). This delegate's job is to
    //! forward the cancel to the in-progress Downloader; the Downloader owns the
    //! full cancel contract:
    //!   - STOP AFTER THE IN-FLIGHT TRACK: cancel() sets its _cancelled flag and
    //!     stops any pending retry backoff timer. An already-issued makeWebRequest
    //!     cannot be aborted mid-flight in the SDK, so its callback still lands —
    //!     a 200 is stored (keeping a completed track is correct), a 404/other is
    //!     handled — but NO new request is issued: every onDownloaded handler
    //!     routes back through issueNext()/onRetry(), which see _cancelled and go
    //!     straight to finish() instead of advancing to the next track.
    //!   - KEEP ALREADY-DOWNLOADED TRACKS: finish()'s cancel branch does NO
    //!     reconcile and NO deletion, so everything stored before the cancel stays
    //!     in the Repo (Req 4.8).
    //!   - FINAL SYNC_STATUS: finish() sends one SYNC_STATUS carrying a non-null
    //!     "cancelled" error label so the phone's SyncOrchestrator terminalizes
    //!     (it resolves a terminal state only from error != null or done >= total;
    //!     a cancel has done < total, so a no-error status would leave the phone
    //!     hanging). See Downloader.finish() for the full rationale.
    //! Wrapped so cancel can never crash the sync process (Req 8.5).
    //!
    //! NAME FIX: the SDK's SyncDelegate callback is `onStopSync()` ("Called when an active sync is
    //! cancelled ... the app is responsible for calling cancelAllRequests() ... and
    //! notifySyncComplete()"). This was previously named `onCancelSync()`, which the SDK never
    //! calls, so a cancel on the watch never released the sync and the watch stayed in sync mode.
    //! Now: flag the Downloader, cancel in-flight requests, and complete the sync right away
    //! (Downloader.finish() is idempotent, so a late request callback can't complete it twice).
    function onStopSync() as Void {
        DebugLog.log("SYNC", "onStopSync (user/system cancel) downloader=" + (_downloader != null).toString());
        try {
            var downloader = _downloader;
            if (downloader != null) {
                downloader.cancel();
            }
            Communications.cancelAllRequests();
            if (downloader != null) {
                downloader.finishCancelled();
            } else {
                Communications.notifySyncComplete(null);
            }
        } catch (ex) {
            // Never let cancel crash the sync process (Req 8.5); still release the sync.
            try {
                Communications.notifySyncComplete(null);
            } catch (ex2) {
            }
        }
    }
}
