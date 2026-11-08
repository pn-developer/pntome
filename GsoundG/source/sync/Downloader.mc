import Toybox.Lang;
import Toybox.Communications;
import Toybox.PersistedContent;
import Toybox.Media;
import Toybox.Timer;
import Toybox.System;

//! Downloader — sequential per-track media download for a sync
//! (design.md §8.3–§8.4; Req 4.2–4.9, 8.1–8.3).
//!
//! SEAM (task 7.1): this is the handoff target GsoundSyncDelegate.onStartSync hands
//! the resolved manifest + pairing to. Its full behaviour is tasks 7.3–7.8:
//!   - 7.3 (THIS task) dedupe by `id` against Repo.loadIndex(); build the ordered
//!         WORK SET of remaining tracks; download each one SEQUENTIALLY via
//!         Communications.makeWebRequest to GET {serverUrl}{track.path} with
//!         { "X-Gsound-Secret" => secret }, GET method, audio response type, and
//!         the mapped :mediaEncoding (task 7.2 MimeEncoding). onDownloaded is the
//!         makeWebRequest callback and advances the sequential cursor.
//!   - 7.4 onDownloaded(code, data, context) per-code handling: 200 → Repo.putTrack
//!         + progress; 401 → stop + auth error (re-pair prompt); 404 → skip + count;
//!         other → bounded retry then record + continue.
//!   - 7.5 periodic SYNC_STATUS progress to the phone via control.transmit.
//!   - 7.6 Reconciler.deleteMissing(manifest) for index ids not in the manifest.
//!   - 7.7 completion: notifySyncComplete + final SYNC_STATUS (done==total, no
//!         error) + STATS.
//!   - 7.8 cancel(): stop after the in-flight track, keep completed tracks, final
//!         SYNC_STATUS.
//!
//! Task 7.3 implemented the DEDUPE + SEQUENTIAL REQUEST-ISSUING loop. start()
//! computes the work set (manifest tracks minus ids already in the index), resets
//! the cursor/counters, and issues the request for the first track; each
//! onDownloaded callback drives the next one until the work set drains → finish
//! (minimal 7.7).
//!
//! Task 7.4 (this iteration) replaces the "advance on every callback" seam with
//! real per-response-code handling inside onDownloaded:
//!   - 200 → extract the persisted media handle from `data` (a
//!           PersistedContent.Iterator; its first item's getId() is the mediaId
//!           playback rebuilds a Media.ContentRef from — design §14), store it via
//!           Repo.putTrack(id, { mediaId, folder, title, artist, album, mime }),
//!           count it, notifySyncProgress, advance.
//!   - 401 → stop the whole sync + SYNC_STATUS(error) so the phone prompts re-pair
//!           (Req 8.2); done stays < total (an error stop).
//!   - 404 → record a failure + count, continue (Req 8.3).
//!   - other/transport → bounded retry (MAX_ATTEMPTS) with a linear backoff Timer,
//!           then record a failure + continue (Req 4.7).
//! The pairing secret is held only to build the download header and is NEVER
//! logged, nor is any URL (Req 7.3). The whole callback is wrapped so a malformed
//! response can't crash the sync process (Req 8.5).
class Downloader {

    //! The resolved manifest { serverUrl, tracks:[ cleaned ManifestTrack ] } to
    //! download from. Both the inline and the GET /manifest paths produce this
    //! same shape (design §6.1).
    private var _manifest as Dictionary;

    //! The pairing record { pairingId, peerName, secret, createdAt }. The secret
    //! becomes the X-Gsound-Secret download header; the pairingId addresses
    //! SYNC_STATUS/STATS to the phone (tasks 7.5/7.7). Never logged.
    private var _pairing as Dictionary;

    //! The shared control channel for transmitting SYNC_STATUS/STATS to the phone,
    //! or null when it didn't come up in this sync process. Progress to the phone
    //! is best-effort; a null channel simply means no phone-side updates (the
    //! system still sees progress via notifySyncProgress).
    private var _control as ControlChannel?;

    //! Set by cancel() so the download loop stops after the in-flight track
    //! (Req 4.8). Checked before issuing each next request.
    private var _cancelled as Boolean;

    //! The ordered WORK SET for this sync: the cleaned ManifestTrack dicts that
    //! survived dedupe (their `id` was NOT already in Repo.loadIndex(), Req 4.4).
    //! Downloaded strictly in order, one at a time. Empty until start() runs.
    private var _work as Array<Object?>;

    //! Cursor into `_work`: index of the track whose request is currently
    //! in-flight (or about to be issued). Advanced by one on every onDownloaded
    //! callback so the next track's request is issued only after the current
    //! resolves — the sequential guarantee (design §8.3).
    private var _cursor as Number;

    //! Count of tracks processed to a terminal outcome so far (stored, skipped,
    //! or failed). Used for progress/completion in 7.4/7.5/7.7; incremented here
    //! as the cursor advances so the counter is wired for those tasks.
    private var _done as Number;

    //! Total number of tracks this sync intends to download — the work-set size
    //! (post-dedupe). design §8.4's terminal signal is done == total; 7.5/7.7 may
    //! refine what `total` means, but for a lone sync it is the work-set size.
    private var _total as Number;

    //! Count of tracks skipped because their MIME had no SDK encoding
    //! (MimeEncoding.encodingFor returned null → can't set :mediaEncoding, Req 4.2
    //! / task 7.2). A reporting bucket 7.4/7.5 surface; incremented here where the
    //! skip decision is made (in the loop).
    private var _skippedUnsupported as Number;

    //! Count of tracks that reached a terminal FAILURE outcome — distinct from the
    //! unsupported-format skips (task 7.4): a 404 (source gone, Req 8.3), a 200
    //! whose `data` yielded no usable media handle, a per-track setup error, or a
    //! transport/other error that exhausted its retry budget (Req 4.7). Kept
    //! separate from `_skippedUnsupported` so tasks 7.5/7.7 can report "failed"
    //! vs. "skipped (unsupported)" independently. These tracks still count toward
    //! `_done` (they are terminally processed), so done reaches total on the
    //! happy + skip + fail paths; only a 401 auth stop leaves done < total.
    private var _failed as Number;

    //! Retry attempt counter for the track at the CURRENT cursor (Req 4.7). Starts
    //! at 0 for each new track and is incremented before each re-issue of a
    //! retriable (transport/other) failure. When it reaches MAX_ATTEMPTS the
    //! failure is recorded and the loop advances. Reset to 0 whenever the cursor
    //! moves to a new track so every track gets its own full retry budget.
    private var _attempts as Number;

    //! The last integer progress percent emitted to the phone in a periodic
    //! SYNC_STATUS (task 7.5). Throttle gate: a new PROGRESS status is only sent
    //! when the integer percent actually changes, so a large library doesn't spam
    //! the phone one message per track. Starts at -1 so the initial "sync has
    //! begun" status (0%) is always sent once. The final done==total status is
    //! task 7.7's job, so periodic emits here are suppressed once done reaches
    //! total to avoid duplicating that completion message.
    private var _lastProgressPct as Number;

    //! The backoff timer used to space out retries of the current track (Req 4.7).
    //! Held so cancel() can stop a pending retry before it fires — a retry must
    //! never re-issue a request after the sync has been cancelled (Req 4.8, 8.5).
    //! Null whenever no retry is scheduled.
    private var _retryTimer as Timer.Timer?;

    //! Diagnostic: how many stored handles have been verified via getCachedContentObj so far
    //! (capped to keep the debug log small). See verifyStoredHandle.
    private var _diagVerified as Number = 0;

    //! Set once finish() has run, so the sync is completed (notifySyncComplete) exactly once.
    private var _finished as Boolean = false;

    //! Diagnostic: media cache size before the current download, and the last logged progress step.
    private var _cacheBefore as Long = 0l;
    private var _lastProgressLogged as Number = -1;

    //! Maximum download attempts for a single track before its failure is recorded
    //! and the loop moves on (Req 4.7). A small fixed bound: the initial try plus
    //! up to (MAX_ATTEMPTS - 1) retries. Kept low so a persistently failing track
    //! can't stall the whole sync.
    private const MAX_ATTEMPTS = 3;

    //! Base backoff delay (ms) between retries of the current track. The delay
    //! grows with the attempt number (attempt * BACKOFF_BASE_MS) for a simple
    //! linear backoff — enough to let a transient condition clear without adding a
    //! dependency on wall-clock scheduling.
    private const BACKOFF_BASE_MS = 1000;

    //! Build a Downloader for one sync. Does not start downloading — start() is a
    //! separate call (mirrors the register/registerHandlers pattern elsewhere).
    function initialize(
            manifest as Dictionary,
            pairing as Dictionary,
            control as ControlChannel?) {
        _manifest = manifest;
        _pairing = pairing;
        _control = control;
        _cancelled = false;
        _work = [] as Array<Object?>;
        _cursor = 0;
        _done = 0;
        _total = 0;
        _skippedUnsupported = 0;
        _failed = 0;
        _attempts = 0;
        _lastProgressPct = -1;
        _retryTimer = null;
    }

    //! Start the download. Computes the dedupe work set, resets the cursor/
    //! counters, then issues the first track's request; each onDownloaded callback
    //! drives the next one (sequential, design §8.3). Wrapped so a setup failure
    //! can't crash the sync process — on failure the sync completes with an error
    //! (Req 8.5).
    function start() as Void {
        try {
            _work = buildWorkSet();
            _cursor = 0;
            _done = 0;
            _total = _work.size();
            _skippedUnsupported = 0;
            _failed = 0;
            _attempts = 0;
            _lastProgressPct = -1;

            // Nothing to download (all deduped away, or an empty manifest) →
            // finish immediately. 7.6 will still reconcile deletions; 7.7 refines.
            if (_total == 0) {
                finish(null);
                return;
            }

            // Let the phone know the sync has begun (done=0) before the first
            // request goes out, so its progress UI updates immediately rather than
            // only after the first track lands (task 7.5, Req 4.3). `state` is a
            // human label; the phone decides terminal state from the counts (§8.4).
            emitProgress();

            issueNext();
        } catch (ex) {
            // A setup failure (malformed manifest/pairing) must still release the
            // system sync rather than hang it (Req 8.5). Never surface the secret.
            finish("download failed to start");
        }
    }

    //! Build the ordered WORK SET: every cleaned ManifestTrack from the manifest
    //! whose `id` is NOT already present in the persisted track index
    //! (Repo.loadIndex()). Already-synced, unchanged tracks are skipped — dedupe
    //! by id (Req 4.4). A track with no usable id is dropped defensively so a
    //! malformed manifest entry can't generate a request for a nameless track
    //! (Req 8.5). Order of the surviving tracks is preserved from the manifest.
    private function buildWorkSet() as Array<Object?> {
        var work = [] as Array<Object?>;

        var tracks = _manifest.get(Messages.FIELD_TRACKS);
        if (!(tracks instanceof Array)) {
            return work;
        }
        var all = tracks as Array<Object?>;
        var index = Repo.loadIndex();

        for (var i = 0; i < all.size(); i++) {
            var track = all[i];
            if (!(track instanceof Dictionary)) {
                continue;
            }
            var id = (track as Dictionary).get(Messages.FIELD_ID);
            if (!(id instanceof String) || (id as String).equals("")) {
                continue;
            }
            // Dedupe by id against the index (Req 4.4): skip if already stored.
            if (Repo.containsValue(index, id as String)) {
                continue;
            }
            work.add(track);
        }
        return work;
    }

    //! Issue the request for the track at the current cursor, advancing past any
    //! tracks that must be SKIPPED without a request:
    //!   - cancellation requested (Req 4.8) → stop issuing, finish.
    //!   - cursor past the end → work set drained, finish (minimal 7.7).
    //!   - unsupported MIME (MimeEncoding.encodingFor == null) → can't set
    //!     :mediaEncoding, so skip + count and move to the next track WITHOUT a
    //!     request (task 7.2).
    //! Otherwise builds the per-track options block (design §8.3) and fires one
    //! Communications.makeWebRequest; the response arrives at onDownloaded, which
    //! advances the cursor and calls back here — the sequential pump. Wrapped so a
    //! bad track entry can't crash the sync (Req 8.5): on a per-track setup error
    //! the track is skipped and the loop continues.
    private function issueNext() as Void {
        // Stop issuing if cancelled — keep whatever was already downloaded
        // (Req 4.8; 7.8 refines the final SYNC_STATUS).
        if (_cancelled) {
            finish(null);
            return;
        }

        // Skip over any tracks with no SDK-supported encoding, then check for end.
        while (_cursor < _work.size()) {
            var candidate = _work[_cursor];
            if (!(candidate instanceof Dictionary)) {
                // Defensive: a non-dict slipped through → count as processed, skip.
                advanceCursor();
                _done++;
                continue;
            }
            var mime = (candidate as Dictionary).get(Messages.FIELD_MIME);
            var mimeStr = (mime instanceof String) ? (mime as String) : null;
            if (MimeEncoding.encodingFor(mimeStr) == null) {
                // Unsupported format: can't set :mediaEncoding → skip + count
                // without issuing a request (task 7.2, Req 4.2).
                _skippedUnsupported++;
                advanceCursor();
                _done++;
                continue;
            }
            // Found a downloadable track at the cursor.
            break;
        }

        if (_cursor >= _work.size()) {
            // Work set drained (minimal 7.7 completion; 7.6/7.7 add reconcile +
            // final SYNC_STATUS + STATS).
            finish(null);
            return;
        }

        try {
            var track = _work[_cursor] as Dictionary;
            requestTrack(track);
        } catch (ex) {
            // A per-track setup failure must not crash the whole sync (Req 8.5):
            // record it as a failure, advance, and keep going with the next track.
            _failed++;
            _done++;
            advanceCursor();
            issueNext();
        }
    }

    //! Advance the sequential cursor to the next track and reset the per-track
    //! retry counter so the new track gets its own full MAX_ATTEMPTS budget
    //! (Req 4.7). Centralized so every place that moves to a new track resets the
    //! attempt count consistently.
    private function advanceCursor() as Void {
        _cursor++;
        _attempts = 0;
    }

    //! Issue the single Communications.makeWebRequest for one track (design §8.3).
    //! GET {serverUrl}{track.path} with the pairing secret in the X-Gsound-Secret
    //! HEADER (never in the URL, never logged — Req 7.3), an audio response type,
    //! and the :mediaEncoding mapped from the track's MIME. The track id is passed
    //! as :context so onDownloaded knows which track resolved. The caller
    //! (issueNext) has already verified the encoding is non-null.
    private function requestTrack(track as Dictionary) as Void {
        var serverUrl = _manifest.get(Messages.FIELD_SERVER_URL) as String;
        var path = track.get(Messages.FIELD_PATH) as String;
        var trackId = track.get(Messages.FIELD_ID) as String;
        var secret = _pairing.get(Messages.FIELD_SECRET) as String;
        var encoding = MimeEncoding.encodingFor(track.get(Messages.FIELD_MIME) as String?);

        var options = {
            :method => Communications.HTTP_REQUEST_METHOD_GET,
            :headers => { "X-Gsound-Secret" => secret },
            :responseType => Communications.HTTP_RESPONSE_CONTENT_TYPE_AUDIO,
            :mediaEncoding => encoding,
            :context => trackId,
            // Diagnostic: the SDK reports bytes RECEIVED by the watch for this file download.
            :fileDownloadProgressCallback => method(:onDownloadProgress)
        };

        // Diagnostic: record the media cache size before the download so handleOk can log how many
        // bytes this track actually added (observed: new downloads seemed to add 0 bytes).
        _cacheBefore = cacheSize();
        _lastProgressLogged = -1;
        var expected = track.get(Messages.FIELD_SIZE_BYTES);
        DebugLog.log("DL", "GET idx=" + _cursor.toString() + " mime=" + DebugLog.shape(track.get(Messages.FIELD_MIME))
            + " enc=" + DebugLog.shape(encoding) + " expectBytes=" + DebugLog.shape(expected)
            + " cacheBefore=" + _cacheBefore.toString());

        Communications.makeWebRequest(serverUrl + path, null, options, method(:onDownloaded));
    }

    //! Media cache "size" in bytes from Media.getCacheStatistics(), or -1 when unavailable.
    private function cacheSize() as Long {
        try {
            return Media.getCacheStatistics().size;
        } catch (ex) {
            return -1l;
        }
    }

    //! :fileDownloadProgressCallback — bytes received so far for the current file and its size (may
    //! be null). Logged at most once per 25% step (or per 1 MB when the size is unknown) to keep the
    //! log small. Public so the SDK can invoke it by symbol. Never throws.
    function onDownloadProgress(totalBytesTransferred as Number, fileSize as Number or Null) as Void {
        try {
            var step;
            if (fileSize != null && fileSize > 0) {
                step = (totalBytesTransferred * 4) / fileSize; // 0..4 quarters
            } else {
                step = totalBytesTransferred / 1048576;        // per MB
            }
            if (step != _lastProgressLogged) {
                _lastProgressLogged = step;
                DebugLog.log("DL", "progress " + totalBytesTransferred.toString() + "/"
                    + (fileSize == null ? "?" : fileSize.toString()) + "B");
            }
        } catch (ex) {
        }
    }

    //! makeWebRequest callback — one track per call (design §8.3), with the full
    //! per-response-code handling (task 7.4). Branches on `responseCode` BEFORE
    //! deciding whether to advance the cursor:
    //!   - 200 → the system has already persisted the downloaded audio into the
    //!           media sandbox; extract its media handle from `data` (a
    //!           PersistedContent.Iterator), store it via Repo.putTrack(trackId,
    //!           { mediaId, folder, title, artist, album, mime }) using the
    //!           current track's cleaned fields, count it (_done++), report
    //!           progress (notifySyncProgress), then advance + issue the next.
    //!           If no usable handle comes back, treat it as a per-track failure.
    //!   - 401 → auth/pairing is stale (Req 8.2): STOP the whole sync, send a
    //!           SYNC_STATUS carrying a non-null error so the phone prompts a
    //!           re-pair, and finish with an error. Do NOT advance to the rest.
    //!   - 404 → the source file vanished / id unknown (Req 8.3): record it as a
    //!           failure, count it, and continue with the next track.
    //!   - other / transport error → bounded retry with backoff (Req 4.7): re-issue
    //!           the SAME track up to MAX_ATTEMPTS, then record a failure and move
    //!           on. The cursor does NOT advance across a retry.
    //!
    //! `context` is the trackId passed to makeWebRequest; the stored metadata is
    //! pulled from the track dict at `_cursor` (both identify the same track in
    //! this strictly-sequential loop). The whole body is wrapped so a malformed
    //! callback can't crash the sync process (Req 8.5). Neither the secret nor the
    //! download URL is ever logged (Req 7.3) — nothing here prints at all.
    //!
    //! @param responseCode the HTTP status (or a negative Communications error).
    //! @param data the response payload: for a 200 audio download this is the
    //!             persisted-media handle the system hands back — a
    //!             PersistedContent.Iterator over the just-added content, whose
    //!             first item's getId() is the mediaId playback (§9) rebuilds a
    //!             Media.ContentRef from. See extractMediaId + the ASSUMPTION note
    //!             there (confirm in the task 7.10 simulator/hardware E2E).
    //! @param context the :context passed to makeWebRequest — the trackId.
    function onDownloaded(
            responseCode as Number,
            data as Dictionary or String or PersistedContent.Iterator or Null,
            context as Object) as Void {
        // Diagnostic: the HTTP status of every track download (non-200 codes explain skipped tracks;
        // negative codes are Connect IQ transport errors such as BLE_CONNECTION_UNAVAILABLE).
        DebugLog.log("DL", "resp code=" + responseCode.toString() + " idx=" + _cursor.toString());
        try {
            if (responseCode == 200) {
                // Pass as a plain Object? so the handle-extraction stays duck-typed
                // and does not pull in the PersistedContent permission (see
                // extractMediaId). `data as Object?` erases the SDK union type.
                handleOk(data as Object?);
            } else if (responseCode == 401) {
                handleAuthError();
            } else if (responseCode == 404) {
                handleNotFound();
            } else {
                handleRetriable();
            }
        } catch (ex) {
            // Never let a bad callback crash the sync process (Req 8.5): record the
            // current track as failed, advance, and try to keep the loop moving; if
            // even that fails, release the system sync.
            try {
                _failed++;
                _done++;
                advanceCursor();
                issueNext();
            } catch (ex2) {
                finish("download failed");
            }
        }
    }

    //! 200 handler: the audio was persisted into the sandbox by the system. Pull
    //! the media handle out of `data`, store the track entry keyed by its id, bump
    //! progress, and advance. A missing handle is treated as a per-track failure
    //! (the download "succeeded" but we can't address it for playback, so it is
    //! useless) rather than a crash.
    private function handleOk(data as Object?) as Void {
        var track = currentTrack();
        // Diagnostic (non-sensitive): capture the RUNTIME SHAPE of the 200 audio response and the
        // resolved media handle. The mediaId round-trip has never been verified on hardware (see
        // extractMediaId's "HARDWARE ASSUMPTION"); this tells us whether `data` is the expected
        // PersistedContent.Iterator and what getId() actually yields, which decides whether a
        // non-playing track is a format problem or a bad handle.
        DebugLog.log("DL", "200 data=" + DebugLog.shape(data));
        var mediaId = extractMediaId(data);
        DebugLog.log("DL", "mediaId=" + DebugLog.shape(mediaId));
        if (track == null || mediaId == null) {
            // No usable handle / no track dict → can't record a playable entry.
            // Count it as a failure and move on (Req 4.7 spirit; never crash).
            DebugLog.log("DL", "track NOT stored (track null?=" + (track == null).toString()
                + " mediaId null?=" + (mediaId == null).toString() + ")");
            _failed++;
            _done++;
            advanceCursor();
            issueNext();
            return;
        }

        // Store { mediaId, folder, title, artist, album, mime } under trk:<id>.
        // Repo.putTrack also adds the id to the chunked index and the distinct
        // folders list (design §8.5). Keys match what Repo.getTrack / playback
        // (§9) read back ("mediaId", "folder", ...).
        var entry = {
            "mediaId" => mediaId,
            "folder"  => stringField(track, Messages.FIELD_FOLDER),
            "title"   => stringField(track, Messages.FIELD_TITLE),
            "artist"  => stringField(track, Messages.FIELD_ARTIST),
            "album"   => stringField(track, Messages.FIELD_ALBUM),
            "mime"    => stringField(track, Messages.FIELD_MIME)
        };
        var trackId = stringField(track, Messages.FIELD_ID);
        // Storage-full guard (Req 4.9): Repo.putTrack writes the track entry (and
        // its index/folders updates) via Application.Storage.setValue, which the
        // SDK documents as throwing Toybox.Lang.StorageFullException when there is
        // not enough remaining space in the Object Store for the value. When that
        // happens we STOP the whole sync and report a storage error, but we KEEP
        // everything already stored — the tracks persisted before the device
        // filled up remain playable (design §8.4). Any other storage write error
        // (e.g. an unexpected setValue failure) is treated the same way: a
        // terminal storage error, keep-what-you-have. We never advance into the
        // rest of the work set after running out of space. The terminal error
        // SYNC_STATUS is emitted by finish() alone (task 7.7), so these branches
        // just hand finish() the error string rather than transmitting here.
        try {
            Repo.putTrack(trackId, entry);
        } catch (ex instanceof Lang.StorageFullException) {
            // Terminal storage error: finish() owns the final error SYNC_STATUS
            // (single emission point — task 7.7), so we do NOT transmit here.
            finish("storage full");
            return;
        } catch (ex) {
            finish("storage error");
            return;
        }

        // Verify RIGHT NOW (for the first few tracks) that the system can resolve the handle we
        // just stored. If this throws, the stored id is not a playable reference and no later
        // playback attempt can work — which pinpoints the bug to the download/handle step.
        verifyStoredHandle(mediaId);

        // Diagnostic: how many bytes this track actually added to the media cache. 0 means the
        // download "succeeded" (200 + a media handle) but no audio was stored.
        var cacheAfter = cacheSize();
        DebugLog.log("DL", "stored idx=" + _cursor.toString() + " cacheAfter=" + cacheAfter.toString()
            + " added=" + (cacheAfter - _cacheBefore).toString() + "B");

        _done++;
        Communications.notifySyncProgress(progressPercent());
        // Mirror progress to the phone via a periodic SYNC_STATUS, throttled to
        // integer-percent changes (task 7.5, Req 4.3). The system side already saw
        // notifySyncProgress above; this is the phone-facing counterpart.
        emitProgress();
        advanceCursor();
        issueNext();
    }

    //! Diagnostic: ask the system for the persisted Content behind `mediaId`, for the first few
    //! tracks only (keeps the log small). Logs success or the exact exception. Never throws.
    private function verifyStoredHandle(mediaId as Object) as Void {
        if (_diagVerified >= 3) {
            return;
        }
        _diagVerified += 1;
        try {
            var ref = new Media.ContentRef(mediaId, Media.CONTENT_TYPE_AUDIO);
            var content = Media.getCachedContentObj(ref);
            DebugLog.log("DL", "verify OK getCachedContentObj -> " + DebugLog.shape(content));
        } catch (ex) {
            DebugLog.log("DL", "verify FAILED " + DebugLog.shape(mediaId) + " " + DebugLog.describe(ex));
        }
    }

    //! 401 handler: the pairing secret is stale (Req 8.2). Stop the whole sync —
    //! do not touch the remaining tracks — and finish with an auth error. finish()
    //! sends the single final SYNC_STATUS carrying this non-null error so the phone
    //! prompts the user to re-pair (task 7.7 consolidated the final-status emission
    //! into finish() — see the note there). `done` stays below `total` here on
    //! purpose: this is an error stop, not a normal completion.
    private function handleAuthError() as Void {
        finish("unauthorized");
    }

    //! 404 handler: the track's source is gone or its id is unknown mid-sync
    //! (Req 8.3). Record it as a failure, count it toward done, and continue with
    //! the next track — one missing file must not abort the whole sync.
    private function handleNotFound() as Void {
        _failed++;
        _done++;
        advanceCursor();
        issueNext();
    }

    //! Transport / other-HTTP-code handler: bounded retry with backoff (Req 4.7).
    //! While the current track still has retry budget, schedule a re-issue of the
    //! SAME track after a short linear backoff (without advancing the cursor). On
    //! exhausting the budget, record a failure and move on. A retry is never
    //! scheduled once the sync is cancelled (Req 4.8).
    private function handleRetriable() as Void {
        if (_cancelled) {
            // Cancelled mid-flight: don't retry, just let the loop finish.
            issueNext();
            return;
        }
        if (_attempts < MAX_ATTEMPTS - 1) {
            _attempts++;
            scheduleRetry();
            return;
        }
        // Retries exhausted → record the failure and continue with the next track.
        _failed++;
        _done++;
        advanceCursor();
        issueNext();
    }

    //! Schedule a retry of the current track after a linear backoff
    //! (_attempts * BACKOFF_BASE_MS). Uses a one-shot Timer so the sync process
    //! isn't blocked while waiting. The timer handle is kept so cancel() can stop
    //! it before it fires (Req 4.8, 8.5).
    private function scheduleRetry() as Void {
        try {
            stopRetryTimer();
            _retryTimer = new Timer.Timer();
            _retryTimer.start(method(:onRetry), _attempts * BACKOFF_BASE_MS, false);
        } catch (ex) {
            // If the timer can't be scheduled, fall back to an immediate re-issue
            // rather than stalling (still bounded by MAX_ATTEMPTS).
            onRetry();
        }
    }

    //! Timer callback: re-issue the current track's request, unless the sync was
    //! cancelled while the backoff was pending (Req 4.8). Clears the timer handle
    //! first so a cancelled retry can never resurrect the loop.
    function onRetry() as Void {
        stopRetryTimer();
        if (_cancelled) {
            finish(null);
            return;
        }
        issueNext();
    }

    //! Stop and clear any pending retry timer. Safe to call when none is active.
    private function stopRetryTimer() as Void {
        if (_retryTimer != null) {
            try {
                _retryTimer.stop();
            } catch (ex) {
                // Ignore — a stopped/expired timer that can't be stopped is harmless.
            }
            _retryTimer = null;
        }
    }

    //! The track dict currently at the cursor, or null if the cursor is out of
    //! range (defensive — a callback should always land on a valid in-flight
    //! track in this sequential loop).
    private function currentTrack() as Dictionary? {
        if (_cursor < 0 || _cursor >= _work.size()) {
            return null;
        }
        var track = _work[_cursor];
        return (track instanceof Dictionary) ? (track as Dictionary) : null;
    }

    //! Extract the storable media handle (mediaId) from a 200 audio download's
    //! `data`, or null when none is available.
    //!
    //! SDK shape (design §14): for HTTP_RESPONSE_CONTENT_TYPE_AUDIO the system
    //! persists the downloaded audio into the media sandbox and hands the callback
    //! a PersistedContent.Iterator over the newly-added content. The first item's
    //! getId() is the persistent handle; playback (§9) rebuilds a playable
    //! Media.ContentRef via `new Media.ContentRef(mediaId, CONTENT_TYPE_AUDIO)`,
    //! so getId() is exactly the value to store as `mediaId`.
    //!
    //! ASSUMPTION (confirm in task 7.10 E2E): the audio-download `data` is a
    //! PersistedContent.Iterator whose first next() exposes getId(). The SDK also
    //! types `data` as Dictionary-or-String for other response types, so this
    //! method is defensive: it tries the iterator path first, then falls back to a
    //! dictionary that carries an explicit id/mediaId, and otherwise returns null
    //! (→ the 200 is treated as a per-track failure rather than crashing). The
    //! exact runtime shape must be verified in the simulator/on hardware.
    //! NOTE: this uses DUCK TYPING (`has :next` / `has :getId`) rather than a hard
    //! `instanceof PersistedContent.Iterator`. Referencing the PersistedContent
    //! namespace as a real value would require the app to declare the
    //! `PersistedContent` permission in the manifest — an app-wide addition that is
    //! not implied by the audio-content-provider-app type and is unnecessary here:
    //! we only need the handle's getId(), which the iterator's item exposes
    //! structurally. Playback (§9) rebuilds the handle via Media.ContentRef, which
    //! lives under the already-granted Media capability.
    private function extractMediaId(data as Object?) as Object? {
        if (data == null) {
            return null;
        }
        // Primary path: an iterator (PersistedContent.Iterator) over the just-added
        // content — call next() to get the first item, then its getId(). The calls
        // go through the duck-typed MediaHandleSource interface so the strict type
        // checker (-l 3) can resolve them WITHOUT referencing the permissioned
        // PersistedContent namespace.
        if (data has :next) {
            var item = (data as MediaHandleSource).next();
            DebugLog.log("DL", "iterator.next() -> " + DebugLog.shape(item));
            if (item != null && (item has :getId)) {
                var itemId = (item as MediaHandleSource).getId();
                DebugLog.log("DL", "item.getId() -> " + DebugLog.shape(itemId));
                return itemId;
            }
            return null;
        }
        // Defensive fallback: a shape that itself exposes getId() directly.
        if (data has :getId) {
            return (data as MediaHandleSource).getId();
        }
        // Defensive fallback: some shapes may hand back a dict with an id field.
        if (data instanceof Dictionary) {
            var dict = data as Dictionary;
            var id = dict.get("mediaId");
            if (id == null) {
                id = dict.get("id");
            }
            return id;
        }
        return null;
    }

    //! Overall sync progress as an integer percent 0..100 for notifySyncProgress,
    //! computed from processed tracks over the total. Guards total == 0 (returns
    //! 100 — nothing to do is fully done) and clamps to 100.
    private function progressPercent() as Number {
        if (_total <= 0) {
            return 100;
        }
        var pct = (_done * 100) / _total;
        if (pct > 100) {
            pct = 100;
        }
        return pct;
    }

    //! Emit a periodic PROGRESS SYNC_STATUS to the phone (task 7.5, Req 4.3),
    //! throttled so a large library doesn't produce one message per track. We send
    //! only when the integer progress percent actually changes since the last emit
    //! (plus the initial 0% at start, since _lastProgressPct begins at -1). The
    //! final done==total completion status is task 7.7's job, so this suppresses
    //! the 100%/done==total case to avoid duplicating that terminal message — these
    //! are strictly IN-PROGRESS updates. `state` is a human label only ("syncing");
    //! the phone decides terminal state from the counts/error (§8.4). Reuses
    //! transmitStatus (error=null for progress), which is itself best-effort and
    //! never logs the secret (Req 7.3, 8.5).
    private function emitProgress() as Void {
        // Don't emit a terminal (done==total) progress status — 7.7 owns the final
        // SYNC_STATUS. Only the in-flight updates belong here.
        if (_total <= 0 || _done >= _total) {
            return;
        }
        var pct = progressPercent();
        if (pct == _lastProgressPct) {
            return;
        }
        _lastProgressPct = pct;
        transmitStatus("syncing", null);
    }

    //! Best-effort SYNC_STATUS to the phone with the current counts and an optional
    //! error (Req 4.3; the 401 path uses this for the re-pair prompt, Req 8.2).
    //! No-op when there is no control channel or no usable pairingId. Wrapped so a
    //! transmit failure can't break the sync (Req 8.5). Never logs (Req 7.3).
    private function transmitStatus(state as String, error as String?) as Void {
        try {
            var control = _control;
            if (control == null) {
                return;
            }
            var pairingId = _pairing.get(Protocol.KEY_PAIRING_ID);
            if (!(pairingId instanceof String) || (pairingId as String).equals("")) {
                return;
            }
            var msg = Messages.buildSyncStatus(
                pairingId as String, state, _done, _total, 0, error);
            control.transmit(msg, null, null);
        } catch (ex) {
            // Progress to the phone is best-effort (Req 8.5).
        }
    }

    //! Read a String field from a track dict via the shared FIELD_* key, or null
    //! when absent / not a String. Used to build the stored track entry without
    //! trusting the manifest shape (Req 8.5).
    private function stringField(track as Dictionary, key as String) as String? {
        var value = track.get(key);
        return (value instanceof String) ? (value as String) : null;
    }

    //! Finish the sync — the completion contract (task 7.7, design §8.4/§14,
    //! Req 4.6). This is the SINGLE place that emits the terminal SYNC_STATUS, so
    //! the phone always gets exactly one final status for a sync (the error paths —
    //! 401 handleAuthError, the storage guards in handleOk — were deliberately
    //! changed to NOT transmit their own status and instead hand their error
    //! string here, avoiding a double error-status send).
    //!
    //! design §8.4 ordering on the clean path is: reconcile → stats → completion.
    //!
    //! ON SUCCESS (error == null, not cancelled — a full clean pass):
    //!   1. Reconcile deletions (task 7.6, Req 4.5): index ids no longer in the
    //!      manifest are removed, so a track dropped on the phone is dropped here.
    //!   2. Send the FINAL SYNC_STATUS that is the phone's success signal: the
    //!      phone's SyncOrchestrator resolves "Completed" from done >= total with
    //!      no error (§8.4/§14) — NOT from the human `state` label. On the normal
    //!      drain every track is terminally processed so _done == _total; to be
    //!      robust we send _total for BOTH done and total on the success status so
    //!      the counts unambiguously signal completion even if _done somehow lagged
    //!      (we do NOT mutate the internal _done counter — just the transmitted
    //!      value, via transmitFinalSuccess()).
    //!   3. Update + send STATS reflecting the post-sync library.
    //!
    //! ON FAILURE (error != null — 401 "unauthorized", "storage full"/"storage
    //! error", or a setup failure): send ONE SYNC_STATUS carrying the non-null
    //! error (the phone's re-pair / failure signal, Req 8.2). No reconcile and no
    //! STATS — the manifest was only partially processed, so deleting "missing"
    //! tracks could wipe still-valid music (keep what was stored, Req 4.9/8.2).
    //!
    //! ON CANCEL (_cancelled, error == null passed in — see the cancel contract,
    //! Req 4.8): the loop stopped after the in-flight track (issueNext/onRetry see
    //! _cancelled and route here). We KEEP every track stored before the cancel —
    //! NO reconcile (reconcile would delete index ids "missing" from the manifest,
    //! but a partial sync's index is incomplete, so that could wipe valid music),
    //! NO STATS. The final SYNC_STATUS carries the non-null "cancelled" label as
    //! its error so the phone can TERMINALIZE: GsoundA's SyncOrchestrator resolves
    //! a terminal state only from error != null or done >= total, and a cancel has
    //! done < total, so a no-error status would leave the phone hanging in
    //! SyncState.Running. We deliberately do NOT fake done==total (that would
    //! report a partial cancel as a full success). notifySyncComplete is still
    //! called with null (a user/system cancel is not a system-level sync error).
    //!
    //! Everything is wrapped so completion itself can never crash the sync process
    //! (Req 8.5): reconcile / status / stats failures are swallowed and the system
    //! sync is still released via notifySyncComplete. The secret is never logged
    //! (Req 7.3) — nothing here prints.
    //! Complete the sync now as a cancel (called from GsoundSyncDelegate.onStopSync after
    //! cancel() + cancelAllRequests). Keeps stored tracks and sends the "cancelled" status.
    function finishCancelled() as Void {
        _cancelled = true;
        stopRetryTimer();
        finish(null);
    }

    private function finish(error as String?) as Void {
        // Idempotent: onStopSync completes the sync directly, and a request callback that lands
        // afterwards would otherwise call notifySyncComplete a second time.
        if (_finished) {
            return;
        }
        _finished = true;
        try {
            if (error != null) {
                // Failure: one final SYNC_STATUS with the non-null error. No
                // reconcile, no stats (keep what was stored — Req 4.9/8.2).
                transmitStatus("error", error);
            } else if (_cancelled) {
                // Cancel (task 7.8): stop after the in-flight track, keep every
                // track stored before the cancel (Req 4.8 — no reconcile, no
                // deletion, no STATS). The final SYNC_STATUS carries a NON-NULL
                // error label ("cancelled") so the phone can reach a terminal
                // state. GsoundA's SyncOrchestrator.collectStatuses terminalizes
                // ONLY on error != null (→ SyncState.Error) or done >= total (→
                // SyncState.Completed); a cancel has done < total, so a status
                // with no error would land in the "else" branch and leave the
                // phone parked in SyncState.Running forever (§8.4: the phone
                // treats `state` as opaque and resolves from counts/error). We do
                // NOT fake done==total (that would misreport a partial cancel as a
                // full success and persist a wrong summary), so the cancellation
                // is signalled as the error label instead — the only choice that
                // both terminalizes the phone and tells the truth about the
                // partial sync. The label is a short, non-sensitive string; the
                // secret is never logged (Req 7.3).
                transmitStatus("cancelled", "cancelled");
            } else {
                // Clean completion (design §8.4 order: reconcile → stats →
                // completion).
                var reconcile = Reconciler.deleteMissing(_manifest);
                DebugLog.log("DL", "reconcile deleted=" + DebugLog.shape(reconcile.get("deleted"))
                    + " errored=" + DebugLog.shape(reconcile.get("errored")));
                // Now that the index matches the manifest, remove every cached audio file the
                // library no longer references (clears leftovers from earlier syncs).
                Repo.sweepOrphanedMedia();
                transmitFinalSuccess();
                updateAndSendStats();
            }
            // Diagnostic wrap-up BEFORE releasing the sync: outcome counts and what the SYSTEM
            // media cache really holds (size + cached ids). Compare with the stored mediaIds above.
            DebugLog.log("DL", "finish err=" + (error == null ? "none" : error)
                + " done=" + _done.toString() + "/" + _total.toString()
                + " failed=" + _failed.toString() + " unsupported=" + _skippedUnsupported.toString());
            MediaDiag.snapshot("DL");

            Communications.notifySyncComplete(error);
        } catch (ex) {
            // Never let completion crash the sync process (Req 8.5). A reconcile /
            // status / stats failure must still release the system sync.
            try {
                Communications.notifySyncComplete(error);
            } catch (ex2) {
                // Give up silently rather than crash the sync process.
            }
        }
    }

    //! Send the FINAL success SYNC_STATUS — the phone's completion signal (design
    //! §8.4/§14, Req 4.6). The phone resolves "Completed" from done >= total with
    //! no error, so this status MUST carry done == total. On the normal drain path
    //! _done already equals _total, but we send _total for both counts defensively
    //! so a lagging _done can never prevent the phone from resolving completion. We
    //! build the message directly (rather than via transmitStatus, which uses the
    //! live _done) so the internal counter is left untouched. Best-effort and
    //! pairingId-guarded exactly like transmitStatus (Req 4.6): no channel or no
    //! usable pairingId → no-op; a transmit failure can't break the sync (Req 8.5);
    //! never logs (Req 7.3).
    private function transmitFinalSuccess() as Void {
        try {
            var control = _control;
            if (control == null) {
                return;
            }
            var pairingId = _pairing.get(Protocol.KEY_PAIRING_ID);
            if (!(pairingId instanceof String) || (pairingId as String).equals("")) {
                return;
            }
            // done == total → unambiguous success counts. Report the larger of the work-set size
            // and the MANIFEST size: when every track was already on the watch the work set is 0,
            // and a "0 of 0" status never completes on the phone (it falls back to its own count).
            // After a clean pass every manifest track is on the watch, so N of N is accurate.
            var count = _total;
            var manifestTracks = _manifest.get(Messages.FIELD_TRACKS);
            if (manifestTracks instanceof Array && (manifestTracks as Array).size() > count) {
                count = (manifestTracks as Array).size();
            }
            var msg = Messages.buildSyncStatus(
                pairingId as String, "done", count, count, 0, null);
            control.transmit(msg, null, null);
        } catch (ex) {
            // Progress to the phone is best-effort (Req 8.5).
        }
    }

    //! Recompute + persist the post-sync library stats, then send a STATS message
    //! to the phone (design §8.4 "update stored stats via StatsStore"; Req 4.6,
    //! 5.x). As of task 8.1 the COMPUTE + PERSIST is owned by StatsStore (the
    //! single source of truth): StatsStore.updateFromRepo() recomputes
    //! fileCount = index size, folderCount = distinct-folders size,
    //! lastSyncAt = now, persists them via Repo.saveStats, and returns the fields.
    //! This helper now only builds + transmits the STATS message from those
    //! returned fields — the Downloader still owns the SEND because it holds the
    //! pairingId + control channel (task 8.2 will generalize STATS sending and can
    //! reuse StatsStore the same way). Behaviour is unchanged from the old inline
    //! version: stats are persisted on completion and a STATS is sent to the phone.
    //!
    //! The STATS transmit carries the pairingId (Req 4.6 — the phone drops a
    //! message without the correct pairingId) and is best-effort / wrapped
    //! (Req 8.5). Never logs (Req 7.3).
    private function updateAndSendStats() as Void {
        try {
            // Compute + persist via StatsStore (the authoritative stats path). The
            // returned dict is in the Messages FIELD_* shape, so we read the fields
            // back through StatsStore's defensive accessors to build the message.
            var stats = StatsStore.updateFromRepo();
            var fileCount = StatsStore.fileCountOf(stats);
            var folderCount = StatsStore.folderCountOf(stats);
            var lastSyncAt = StatsStore.lastSyncAtOf(stats);

            // Send STATS to the phone (pairingId-guarded — Req 4.6).
            var control = _control;
            if (control == null) {
                return;
            }
            var pairingId = _pairing.get(Protocol.KEY_PAIRING_ID);
            if (!(pairingId instanceof String) || (pairingId as String).equals("")) {
                return;
            }
            var msg = Messages.buildStats(
                pairingId as String, fileCount, folderCount, lastSyncAt);
            control.transmit(msg, null, null);
        } catch (ex) {
            // Stats update/send is best-effort — never crash completion (Req 8.5).
        }
    }

    //! Request cancellation (Req 4.8). SEAM (task 7.8): the download loop checks
    //! this flag before issuing each next request (issueNext), so it stops after
    //! the in-flight track, keeps already-downloaded tracks, and (7.8) sends a
    //! final SYNC_STATUS. Here it records the request and stops any pending retry
    //! timer so a backoff can't re-issue a request after cancellation (Req 4.8,
    //! 8.5); issueNext acts on the flag for the in-flight track.
    function cancel() as Void {
        _cancelled = true;
        stopRetryTimer();
    }

    //! True once cancellation has been requested. Read by the download loop.
    function isCancelled() as Boolean {
        return _cancelled;
    }
}

//! Structural (duck-typed) view of the media handle a 200 audio download hands
//! back (design §14). The SDK may pass the callback a PersistedContent.Iterator
//! (which exposes next()) whose item exposes getId(); other builds may hand back
//! an object that exposes getId() directly. Declaring these shapes as a plain
//! interface lets extractMediaId() invoke next()/getId() under the strict type
//! checker (-l 3) WITHOUT referencing the Toybox.PersistedContent namespace —
//! which would otherwise require the app to declare the PersistedContent
//! permission in the manifest (an app-wide addition not implied by the
//! audio-content-provider-app type and not needed for playback, which rebuilds
//! the handle via Media.ContentRef). Guarded at the call site with `has :next` /
//! `has :getId`, so a value that implements neither is never cast through this.
typedef MediaHandleSource as interface {
    //! Advance a content iterator to its first/next item (PersistedContent.Iterator
    //! shape). Returns the item, or null at end of list.
    function next() as Object?;

    //! The persistent media handle to store as `mediaId` — playback (§9) rebuilds
    //! a Media.ContentRef from it.
    function getId() as Object?;
};
