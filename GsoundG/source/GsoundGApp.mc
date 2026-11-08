import Toybox.Application;
import Toybox.Lang;
import Toybox.WatchUi;
import Toybox.Media;
import Toybox.Communications;
import Toybox.Graphics;
import Toybox.System;

//! GsoundGApp — Audio Content Provider application entry point.
//!
//! Task 2.1: the AudioContentProviderApp lifecycle. onStart() loads persisted
//! state (pairing, stats, track index) via Repo; onStop() saves state back
//! (design.md §5, §10; Req 7.2, 8.5). The in-memory state restored here is held
//! on the app instance so the mode-routing hooks and the rest of the app can read
//! it regardless of which ACP mode the system launched us in.
//!
//! Task 2.2 (this file): the ACP mode-routing hooks. The system launches an
//! Audio Content Provider app in one of several modes and calls a different hook
//! for each (design.md §5). The hook set is fixed by the SDK's
//! Application.AudioContentProviderApp base class (verified against the installed
//! connectiq-sdk-lin-9.2.0 api.mir and the official ACP template
//! bin/templates/audio-content-provider-app/simple/source/App.mc):
//!   - getSyncConfigurationView()     — sync-config mode   -> [view, delegate].
//!   - getPlaybackConfigurationView() — playback-config    -> [view, delegate].
//!   - getContentDelegate(args)       — playback           -> GsoundContentDelegate.
//!   - getSyncDelegate()              — system sync        -> GsoundSyncDelegate.
//!   - getProviderIconInfo()          — music-apps list    -> provider icon.
//! IMPORTANT: AudioContentProviderApp does NOT define getInitialView(); an ACP app
//! is never launched through a standalone initial view, and overriding AppBase's
//! getInitialView() here is non-standard and crashed the app at launch
//! ("Null Reference Error / Failed invoking <symbol>"). It has been removed to
//! match the SDK contract. The two configuration-view hooks MUST return a
//! [view, delegate] PAIR (the SDK/template contract); returning a view-only array
//! leaves the system without an input delegate.
//! For this task the hooks return simple STUB views/delegates (StubViews.mc,
//! StubDelegates.mc) so every launch mode works without crashing (Req 8.5); the
//! real views/delegates are implemented in tasks 7 and 9. Every hook is wrapped
//! defensively so no mode can crash the app.
//!
//! Task 2.3 (verification): built the .prg with the Connect IQ 9.2.0 toolchain for
//! fenix6pro and fenix847mm at strict type-check level 3 (-l 3) -> BUILD SUCCESSFUL,
//! zero warnings. This type-checks every ACP mode entry point (getInitialView /
//! getSyncConfigurationView / getPlaybackConfigurationView / getContentDelegate /
//! getSyncDelegate / getProviderIconInfo) and the stubs/delegates they return.
//! Each hook is try/catch-wrapped with a safe fallback (or returns a throw-free
//! stub), so no launch mode can crash (Req 8.5). NOT verified in this environment:
//! an actual simulator launch per mode — the Connect IQ simulator is a GUI tool
//! that requires a display and libwebkit2gtk-4.0 (only 4.1 is present here), so it
//! cannot run headlessly. Simulator per-mode launch should be confirmed on a
//! workstation with the SDK GUI, and on hardware as part of tasks 9.5 / 11.
class GsoundGApp extends Application.AudioContentProviderApp {

    //! Restored pairing record { pairingId, peerName, secret, createdAt }, or null
    //! when unpaired. Never log the secret (Req 7.3).
    private var _pairing as Dictionary?;

    //! Restored stats { fileCount, folderCount, lastSyncAt }, or null before first sync.
    private var _stats as Dictionary?;

    //! Restored track-id index (array of track ids); empty when nothing synced.
    private var _index as Array;

    //! The single control-channel gateway (comm/ControlChannel.mc). Created and
    //! registered at app start so inbound phone-app messages are validated, the
    //! pairing-identity filter is applied, and messages are dispatched by type
    //! (design.md §6; Req 2.6, 8.4). Held on the app instance so it lives for the
    //! whole app lifetime and so handlers can transmit replies through it.
    private var _control as ControlChannel?;

    //! The pairing state machine (pairing/PairingManager.mc). Created at app start
    //! once the control channel exists and held for the app lifetime so its inbound
    //! handlers (PAIR_REQUEST/PAIR_CONFIRM/UNPAIR) stay registered and the pairing
    //! view (task 5.2) can read the current code through it (design.md §7; Req 2).
    private var _pairingManager as PairingManager?;

    //! The SYNC_MANIFEST intake controller (sync/SyncController.mc). Created at app
    //! start once the control channel exists and held for the app lifetime so its
    //! inbound SYNC_MANIFEST handler stays registered and the sync-config view
    //! (task 7) can read the pending manifest through it (design.md §8.1; Req 3.1,
    //! 7.1, 7.2).
    private var _syncController as SyncController?;

    function initialize() {
        AudioContentProviderApp.initialize();
        _pairing = null;
        _stats = null;
        _index = [];
        _control = null;
        _pairingManager = null;
        _syncController = null;
    }

    //! Called on application start up. Restore persisted state via Repo so the app
    //! is consistent no matter which mode the system launched it in (Req 7.2).
    //! Wrapped so a storage/parse problem can't crash any launch mode (Req 8.5).
    function onStart(state as Dictionary?) as Void {
        DebugLog.log("APP", "onStart " + AppVersion.display());
        try {
            _pairing = Repo.loadPairing();
            _stats = Repo.loadStats();
            _index = Repo.loadIndex();
            // Diagnostic: confirm whether a pairing record survived a reboot. Non-sensitive — we
            // log only whether a record exists and its pairingId (never the secret, Req 7.3).
            // Visible in the simulator console / `monkeydo` output as "Gsound: onStart ...".
            if (_pairing != null) {
                var pid = _pairing.get(Protocol.KEY_PAIRING_ID);
                System.println("Gsound: onStart pairing RESTORED pairingId=" + (pid != null ? pid.toString() : "?"));
            } else {
                System.println("Gsound: onStart NO pairing persisted (unpaired)");
            }
        } catch (ex) {
            // Corrupt/unavailable storage must not crash launch; fall back to empty state.
            System.println("Gsound: onStart storage read FAILED -> treating as unpaired");
            _pairing = null;
            _stats = null;
            _index = [];
        }

        // Bring up the control channel so inbound phone-app messages are
        // validated, pairing-filtered, and dispatched by type (design.md §6).
        // Register a minimal PING handler that replies with a PONG; the richer
        // handlers (PairingManager/SyncController/StatsStore) wire in via
        // setHandler in tasks 5/6/8. Wrapped so a comm-init problem can't crash
        // any launch mode (Req 8.5).
        try {
            var control = new ControlChannel();
            control.setHandler(Messages.TYPE_PING, method(:onPing));
            control.setHandler(Messages.TYPE_BOOKMARK, method(:onBookmark));

            // Bring up pairing: the PairingManager registers its own PAIR_REQUEST/
            // PAIR_CONFIRM/UNPAIR handlers on the channel and drives the pairing
            // state machine (design.md §7; Req 2). Built before register() so the
            // handlers are in place for the first inbound message.
            var pairingManager = new PairingManager(control);
            pairingManager.registerHandlers();

            // Bring up sync intake: the SyncController registers its own
            // SYNC_MANIFEST handler on the channel and, on receipt, persists the
            // pending manifest via Repo (design.md §8.1; Req 3.1, 7.1, 7.2). Built
            // before register() so the handler is in place for the first inbound
            // message, mirroring PairingManager.
            var syncController = new SyncController(control);
            syncController.registerHandlers();

            control.register();
            _control = control;
            _pairingManager = pairingManager;
            _syncController = syncController;
            // Diagnostic: the pairing manager restores STATE_PAIRED in its initialize() when a
            // record was persisted, so this line tells you the status the UI will show.
            System.println("Gsound: onStart control channel UP; paired=" + pairingManager.isPaired());
        } catch (ex) {
            System.println("Gsound: onStart control channel FAILED to come up -> UI shows unpaired");
            _control = null;
            _pairingManager = null;
            _syncController = null;
        }
    }

    //! BOOKMARK handler: thin delegator (the message already passed validation and the pairing
    //! filter in ControlChannel). See BookmarkApply.
    function onBookmark(msg as Dictionary) as Void {
        BookmarkApply.handle(msg);
    }

    //! PING handler: reply with a PONG carrying our stored pairingId, then send a
    //! STATS message alongside it so the phone always has current library stats
    //! whenever it pings (Req 5.3, overview §5.1). Only reached for messages that
    //! already passed validation and the pairing-identity filter in ControlChannel,
    //! so a stored pairing exists and its pairingId matches the PING. If for any
    //! reason there is no usable pairingId we simply don't reply (a PONG requires a
    //! pairingId per the shared envelope).
    //!
    //! Ordering: the PONG goes out FIRST (it is the liveness reply the phone is
    //! waiting on), then STATS is sent best-effort — a stats read/transmit failure
    //! must never prevent the PONG or crash the PING handler / app (Req 8.5). The
    //! STATS send uses the SAME pairingId the PONG carries. Never logs the secret
    //! (Req 7.3).
    function onPing(msg as Dictionary) as Void {
        var control = _control;
        if (control == null) {
            return;
        }
        var pairingId = pingPairingId(msg);
        if (pairingId == null) {
            return;
        }
        var pong = Messages.buildPong(pairingId);
        control.transmit(pong, null, null);

        // Best-effort STATS alongside the PONG — never let a failure here affect
        // the PONG that already went out (Req 5.3, 8.5).
        sendStats(control, pairingId);

        // Audiobook bookmarks, one BOOKMARK message per book, strictly one in flight
        // (PL-4.1). Best-effort; never throws.
        BookmarkSender.sendAll(control.method(:transmit), pairingId);

        // Debug aid: forward unsent watch log lines to the phone, one per message (logcat tag
        // Gsound.Watch). Only from here — the phone's "Refresh stats" PING — never from the
        // short-lived sync process. See DebugLog.
        DebugLog.sendPending(control, pairingId);
    }

    //! Send a STATS message carrying the CURRENT persisted library stats to the
    //! phone (Req 5.3). Unlike the after-sync path (Downloader.updateAndSendStats),
    //! this does NOT recompute — a PING is not a sync — so it reads what the last
    //! sync wrote via StatsStore.load() and its defensive accessors. Before the
    //! first sync load() returns null, which yields fileCount/folderCount 0 and a
    //! null lastSyncAt; Messages.buildStats omits lastSyncAt when null so the phone
    //! shows "never synced" (Req 5.4). Wrapped so a stats read/transmit failure can
    //! never crash the PING handler or the app (Req 8.5). Never logs the secret
    //! (Req 7.3).
    private function sendStats(control as ControlChannel, pairingId as String) as Void {
        try {
            var stats = StatsStore.load();
            var fileCount = StatsStore.fileCountOf(stats);
            var folderCount = StatsStore.folderCountOf(stats);
            var lastSyncAt = StatsStore.lastSyncAtOf(stats);
            var msg = Messages.buildStats(pairingId, fileCount, folderCount, lastSyncAt);
            control.transmit(msg, null, null);
        } catch (ex) {
            // Best-effort — a stats read/transmit failure must not affect the PONG
            // already sent, nor crash the PING handler (Req 8.5).
        }
    }

    //! Resolve the pairingId to echo in the PONG: prefer the stored record's id,
    //! falling back to the id carried on the PING itself (they match once the
    //! pairing filter has accepted the message).
    //!
    //! Reads the pairing LIVE from Repo (not the onStart `_pairing` snapshot) so a pairing
    //! established after launch is reflected — the snapshot is no longer kept in sync (see
    //! onStop). Falls back to the id on the PING itself, which the control channel has already
    //! matched against the stored record.
    private function pingPairingId(msg as Dictionary) as String? {
        var record = Repo.loadPairing();
        if (record != null) {
            var stored = record.get(Protocol.KEY_PAIRING_ID);
            if (stored instanceof String && !(stored as String).equals("")) {
                return stored as String;
            }
        }
        return Protocol.pairingIdOf(msg);
    }

    //! Called when the application is exiting. Persist current in-memory state via Repo.
    //! Wrapped so a storage error on shutdown can't crash the app (Req 8.5).
    function onStop(state as Dictionary?) as Void {
        // Intentionally does NOT re-save pairing/stats here.
        //
        // The app-level `_pairing`/`_stats` fields are a read-only SNAPSHOT taken at onStart. The
        // authoritative writers persist to Repo at the moment of change:
        //   - PairingManager.persistPendingPairing()/unpair() -> Repo.savePairing/deletePairing,
        //   - the Downloader / StatsStore                     -> Repo.saveStats,
        // all on their own (possibly separate ACP) process. Re-saving the stale snapshot here
        // would CLOBBER those writes: e.g. pairing on the watch updates Repo but not this field,
        // so an onStop(Repo.savePairing(_pairing==null)) would wipe a freshly-paired record. Repo
        // is the single source of truth; onStop must not overwrite it (Req 7.1, 7.2). Left as a
        // no-op (wrapped-call removed) so a relaunch always reflects what was actually persisted.
    }

    // --- ACP mode-routing hooks (task 2.2) -------------------------------------
    // Each hook is wrapped so an exception while building a view/delegate can't
    // crash the corresponding launch mode (Req 8.5). All currently return STUBS
    // (see StubViews.mc / StubDelegates.mc) to be replaced in tasks 7 and 9.

    //! Sync-config mode: return the view that shows the pending-sync count and,
    //! on Confirm, triggers the system sync (Communications.startSync()), paired
    //! with its input delegate (task 7.9's SyncConfigView/SyncConfigDelegate,
    //! which replaced the stub pair). Must return a [view, delegate] pair per the
    //! ACP contract (design §5). Wrapped so a construction failure can't crash the
    //! sync-config launch mode — fall back to the throw-free stub so the mode
    //! still shows something and routes input (Req 8.5).
    function getSyncConfigurationView() as [WatchUi.Views] or [WatchUi.Views, WatchUi.InputDelegates] {
        try {
            return [new SyncConfigView(), new SyncConfigDelegate()];
        } catch (ex) {
            // A view/delegate construction failure must not crash the sync-config
            // launch mode: fall back to the throw-free SafeFallbackView + a plain
            // BehaviorDelegate so the mode still shows something and routes input
            // (Req 8.5).
            return [new SafeFallbackView(), new WatchUi.BehaviorDelegate()];
        }
    }

    //! Playback-config mode: return the folder picker paired with its input
    //! delegate (task 9.1's FolderListView / FolderListDelegate, which replaced the
    //! stub pair). The view lists the synced folders (Repo.loadFolders()) with an
    //! empty state when nothing is synced (Req 6.6); selecting a folder navigates
    //! toward playback (PlaybackConfigView, task 9.2). Must return a [view,
    //! delegate] pair per the ACP contract (design §5) — the raw-Dc View +
    //! BehaviorDelegate pair satisfies that contract cleanly. Wrapped so a
    //! construction failure can't crash the playback-config launch mode (Req 8.5).
    function getPlaybackConfigurationView() as [WatchUi.Views] or [WatchUi.Views, WatchUi.InputDelegates] {
        try {
            var view = new FolderListView();
            return [view, new FolderListDelegate(view)];
        } catch (ex) {
            // A view/delegate construction failure must not crash the
            // playback-config launch mode: fall back to the throw-free
            // SafeFallbackView + a plain BehaviorDelegate so the mode still shows
            // something and routes input (Req 8.5).
            return [new SafeFallbackView(), new WatchUi.BehaviorDelegate()];
        }
    }

    //! Playback mode: return the content delegate feeding the native player.
    //! `args` is the PersistableType passed to Media.startPlayback from the
    //! playback-config track list (PlaybackConfigView) — a Dictionary carrying the
    //! selected folder under "folder" and the chosen start index under "startIndex".
    //! Parse it null-safely into GsoundContentDelegate (task 9.4). Defensive: if
    //! args is null / not a Dictionary / has no String "folder", build a delegate
    //! with a null folder so it yields the safe empty iterator and the mode still
    //! launches (Req 6.6, 8.5).
    function getContentDelegate(args as Application.PersistableType) as Media.ContentDelegate {
        DebugLog.log("PB", "getContentDelegate args=" + DebugLog.shape(args) + " " + DebugLog.mem());
        // No MediaDiag.snapshot here: the playback process is memory-constrained and the cache
        // walk allocates; the sync process already logs the same snapshot after each sync.
        // args is null when the native player starts/resumes playback by itself (its own Play
        // button, after the headphone prompt, ...). Returning an empty iterator there means
        // "nothing to play" — observed on the device log: getContentDelegate args=null →
        // "returning EMPTY iterator". Fall back to the last selection (which for a book may be
        // "resume", AB-6.2), then to the first synced folder.
        var selection = (args instanceof Dictionary) ? (args as Dictionary) : null;
        if (selection == null || !(selection.get("folder") instanceof String)) {
            try {
                selection = Repo.loadPlaybackSelection();
                if (selection != null) {
                    DebugLog.log("PB", "args null -> last selection");
                } else {
                    var folders = Repo.loadFolders();
                    if (folders.size() > 0 && folders[0] instanceof String) {
                        selection = { "folder" => folders[0], "startIndex" => 0 };
                        DebugLog.log("PB", "args null -> first synced folder");
                    }
                }
            } catch (ex) {
                DebugLog.log("PB", "args-null fallback FAILED " + DebugLog.describe(ex));
            }
        }

        var folder = null;
        var startIndex = 0;
        var startPos = 0;
        var isBook = false;
        if (selection != null) {
            var f = selection.get("folder");
            if (f instanceof String && !(f as String).equals("")) {
                folder = f as String;
                isBook = Repo.isBookFolder(folder);
            }
            var s = selection.get("startIndex");
            if (s instanceof Number) {
                startIndex = s as Number;
            }
            // Book resume (AB-6.1): chapter + position from the bookmark (with the rewind).
            // No usable bookmark → the book from the start.
            if (folder != null && isBook && PlaybackStarter.MODE_RESUME.equals(selection.get("mode"))) {
                var r = Bookmarks.resolve(folder, FolderTracks.orderedIds(folder));
                if (r != null) {
                    startIndex = r.get("index") as Number;
                    startPos = r.get("pos") as Number;
                } else {
                    startIndex = 0;
                }
                DebugLog.log("PB", "resume -> idx=" + startIndex.toString() + " pos=" + startPos.toString());
            }
        }
        DebugLog.log("PB", "delegate folder=" + (folder == null ? "null" : folder) + " start=" + startIndex.toString()
            + " pos=" + startPos.toString() + " book=" + isBook.toString());
        return new GsoundContentDelegate(folder, startIndex, startPos, isBook);
    }

    //! System sync: return the sync delegate driving downloads (task 7.1's
    //! GsoundSyncDelegate, which replaced GsoundSyncDelegateStub). Wrapped so a
    //! construction failure can't crash the sync launch mode — fall back to null,
    //! which the system treats as "no sync provider" (Req 8.5).
    function getSyncDelegate() as Communications.SyncDelegate or Null {
        try {
            return new GsoundSyncDelegate();
        } catch (ex) {
            return null;
        }
    }

    //! Provider icon shown in the device's music-apps / Music Providers list.
    //! Uses the ProviderIcon drawable added in task 1 (resources/drawables).
    //! Returns null if the icon can't be built so the mode still launches (Req 8.5).
    function getProviderIconInfo() as Media.ProviderIconInfo or Null {
        try {
            return new Media.ProviderIconInfo(Rez.Drawables.ProviderIcon, Graphics.COLOR_BLUE);
        } catch (ex) {
            return null;
        }
    }

    //! Current pairing record, or null when unpaired.
    function getPairing() as Dictionary? {
        return _pairing;
    }

    //! Current stats, or null before the first sync.
    function getStats() as Dictionary? {
        return _stats;
    }

    //! Current in-memory track-id index.
    function getIndex() as Array {
        return _index;
    }

    //! The pairing state machine, or null if the control channel failed to come
    //! up. The PairingView/Delegate (task 5.2) read the current code and drive
    //! confirm/reject through this.
    function getPairingManager() as PairingManager? {
        return _pairingManager;
    }

    //! The SYNC_MANIFEST intake controller, or null if the control channel failed
    //! to come up. The sync-config view/delegate (task 7) read the pending
    //! manifest and its track count through this.
    function getSyncController() as SyncController? {
        return _syncController;
    }

    //! The control-channel gateway, or null if it failed to come up at app start.
    //! GsoundSyncDelegate (task 7) uses this to transmit SYNC_STATUS to the phone
    //! (e.g. a non-null `error` when a sync aborts for a missing pairing/secret).
    //! Mirrors getSyncController(); added in task 7.1.
    function getControl() as ControlChannel? {
        return _control;
    }
}

//! Convenience accessor for the application instance.
function getApp() as GsoundGApp {
    return Application.getApp() as GsoundGApp;
}
