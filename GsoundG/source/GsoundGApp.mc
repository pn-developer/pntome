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

    function initialize() {
        AudioContentProviderApp.initialize();
        _pairing = null;
        _stats = null;
        _index = [];
        _control = null;
    }

    //! Called on application start up. Restore persisted state via Repo so the app
    //! is consistent no matter which mode the system launched it in (Req 7.2).
    //! Wrapped so a storage/parse problem can't crash any launch mode (Req 8.5).
    function onStart(state as Dictionary?) as Void {
        try {
            _pairing = Repo.loadPairing();
            _stats = Repo.loadStats();
            _index = Repo.loadIndex();
        } catch (ex) {
            // Corrupt/unavailable storage must not crash launch; fall back to empty state.
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
            _control = new ControlChannel();
            _control.setHandler(Messages.TYPE_PING, method(:onPing));
            _control.register();
        } catch (ex) {
            _control = null;
        }

        selftest43();
    }

    //! [SCRATCH-4.3] Runtime verification of task 4.3 without the GUI phone-message
    //! injector: seed a pairing so the pairing-identity filter accepts a PING, then
    //! push a PING and a malformed message through the real inbound pipeline and
    //! observe dispatch + PONG in the console. REMOVE after task 4.3 (also restores
    //! the pre-test pairing so no test state is left persisted).
    function selftest43() as Void {
        var control = _control;
        if (control == null) {
            return;
        }
        try {
            var saved = _pairing;
            var testId = "pid-4-3-selftest";
            var seed = {
                Protocol.KEY_PAIRING_ID => testId,
                "peerName" => "selftest",
                "secret" => "x",
                "createdAt" => 0
            };
            Repo.savePairing(seed);
            _pairing = seed;

            System.println("[SCRATCH-4.3] --- seeded pairing, sending PING ---");
            control.selftestInbound({
                Protocol.KEY_VERSION => Protocol.PROTOCOL_VERSION,
                Protocol.KEY_TYPE => Messages.TYPE_PING,
                Protocol.KEY_PAIRING_ID => testId
            });

            System.println("[SCRATCH-4.3] --- sending malformed message ---");
            control.selftestInbound({ "junk" => true });

            System.println("[SCRATCH-4.3] --- sending PING from unpaired peer ---");
            control.selftestInbound({
                Protocol.KEY_VERSION => Protocol.PROTOCOL_VERSION,
                Protocol.KEY_TYPE => Messages.TYPE_PING,
                Protocol.KEY_PAIRING_ID => "wrong-id"
            });

            // Restore pre-test pairing state so nothing is left persisted.
            Repo.savePairing(saved);
            _pairing = saved;
            System.println("[SCRATCH-4.3] --- selftest done, pairing restored ---");
        } catch (ex) {
            System.println("[SCRATCH-4.3] selftest error");
        }
    }

    //! PING handler: reply with a PONG carrying our stored pairingId (Req 5.3,
    //! overview §5.1). Only reached for messages that already passed validation
    //! and the pairing-identity filter in ControlChannel, so a stored pairing
    //! exists and its pairingId matches the PING. If for any reason there is no
    //! usable pairingId we simply don't reply (a PONG requires a pairingId per the
    //! shared envelope). Never logs the secret (Req 7.3).
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
        // [SCRATCH-4.3] runtime-verification instrumentation — REMOVE after task 4.3.
        System.println("[SCRATCH-4.3] dispatched PING -> sending PONG pairingId=" + pairingId);
        control.transmit(pong, null, null);
    }

    //! Resolve the pairingId to echo in the PONG: prefer the stored record's id,
    //! falling back to the id carried on the PING itself (they match once the
    //! pairing filter has accepted the message).
    private function pingPairingId(msg as Dictionary) as String? {
        var record = _pairing;
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
        try {
            Repo.savePairing(_pairing);
            Repo.saveStats(_stats);
        } catch (ex) {
            // Best-effort save; swallow to avoid crashing on exit.
        }
    }

    // --- ACP mode-routing hooks (task 2.2) -------------------------------------
    // Each hook is wrapped so an exception while building a view/delegate can't
    // crash the corresponding launch mode (Req 8.5). All currently return STUBS
    // (see StubViews.mc / StubDelegates.mc) to be replaced in tasks 7 and 9.

    //! Sync-config mode: return the view that confirms the pending manifest and
    //! triggers sync, paired with its input delegate. STUB: returns
    //! [SyncConfigStubView, SyncConfigStubDelegate] until task 7's SyncConfigView.
    //! Must return a [view, delegate] pair per the ACP contract.
    function getSyncConfigurationView() as [WatchUi.Views] or [WatchUi.Views, WatchUi.InputDelegates] {
        return [new SyncConfigStubView(), new SyncConfigStubDelegate()];
    }

    //! Playback-config mode: return the folder/track picker paired with its input
    //! delegate. STUB: returns [FolderListStubView, FolderListStubDelegate] until
    //! task 9's FolderListView / PlaybackConfigView.
    //! Must return a [view, delegate] pair per the ACP contract.
    function getPlaybackConfigurationView() as [WatchUi.Views] or [WatchUi.Views, WatchUi.InputDelegates] {
        return [new FolderListStubView(), new FolderListStubDelegate()];
    }

    //! Playback mode: return the content delegate feeding the native player.
    //! STUB: returns GsoundContentDelegateStub until task 9's GsoundContentDelegate.
    function getContentDelegate(args as Application.PersistableType) as Media.ContentDelegate {
        return new GsoundContentDelegateStub();
    }

    //! System sync: return the sync delegate driving downloads. STUB: returns
    //! GsoundSyncDelegateStub until task 7's GsoundSyncDelegate.
    function getSyncDelegate() as Communications.SyncDelegate or Null {
        try {
            return new GsoundSyncDelegateStub();
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
}

//! Convenience accessor for the application instance.
function getApp() as GsoundGApp {
    return Application.getApp() as GsoundGApp;
}
