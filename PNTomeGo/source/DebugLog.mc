import Toybox.Lang;
import Toybox.System;
import Toybox.Application;
import Toybox.Communications;
import Toybox.Media;
import Toybox.Timer;

//! DebugLog — watch diagnostic log, readable on the PHONE (debugging aid).
//!
//! Every line is:
//!   1. printed with System.println → simulator console, or on a real watch the file
//!      /GARMIN/APPS/LOGS/<PRGNAME>.TXT (only if that file already exists), and
//!   2. appended to a small ring buffer in Application.Storage so it survives across PNTomeGo's
//!      separate processes (sync / playback / control channel).
//!
//! Forwarding to the phone (sendPending): triggered from the PING handler (the phone's
//! "Refresh stats" button). Unsent lines are transmitted ONE LINE PER MESSAGE as a flat
//! { v, type:"LOG", pairingId, line } dictionary — only strings, no arrays — and strictly one at a
//! time: the next line is sent only from the previous transmit's onComplete. A line is marked sent
//! only after its transmit completes, so an error just stops the chain and the rest go next time.
//! The phone writes each line to logcat under the tag `PNTome.Watch`.
//!
//! Why this shape: an earlier version sent one LOG message carrying an Array of 20 lines, at the
//! end of a sync (just before that process exits), and the phone's Connect IQ SDK then crashed
//! deserializing a corrupt incoming message. So: small flat messages, never from the short-lived
//! sync process, never more than one in flight.
//!
//! BUILD FLAVORS (Monkey C's built-in annotations; `monkeyc -r` strips `(:debug)` code):
//!   - release (`./build.sh`, default): println only. No Storage ring, no forwarding to the phone,
//!     no MediaDiag cache walk — the code isn't even in the .prg.
//!   - debug (`./build.sh --debug`): everything described above. The UI footer shows "dbg".
//!
//! Lines are `#<seq> t=<ms> <TAG>: <message>`; `seq` is monotonic across processes.
//! Never log the pairing secret or server URL (Req 7.3).
module DebugLog {

    //! Storage key: ring buffer of formatted lines (Array<String>).
    const KEY_LINES = "dbg";

    //! Storage key: last assigned line number (Number).
    const KEY_SEQ = "dbg:seq";

    //! Storage key: highest line number confirmed delivered to the phone (Number).
    const KEY_SENT = "dbg:sent";

    //! Max retained lines (40 x ~120 chars keeps the stored value well under ~8 KB).
    const MAX_LINES = 40;

    //! Max characters per line (truncated beyond this, keeps each message small).
    const MAX_LEN = 120;

    //! Max lines forwarded per "Refresh stats" press.
    const SEND_BATCH = 30;

    //! Delay before the first log message, so it doesn't collide with the PONG/STATS that the
    //! PING handler has just queued.
    const SEND_DELAY_MS = 1500;

    //! The in-flight sender (null when idle). Prevents two overlapping send chains and keeps the
    //! sender object referenced while transmits are pending.
    (:debug)
    var activeSender as LogSender? = null;

    //! Record one diagnostic line. Never throws.
    //! Both builds: System.println (TXT file / simulator console — a no-op on the watch unless the
    //! TXT file was created by hand). Debug builds only: also stored for forwarding (store()).
    function log(tag as String, msg as String) as Void {
        var body = tag + ": " + msg;
        try {
            System.println("PNTome." + body);
        } catch (ex) {
        }
        store(body);
    }

    //! Whether this is a debug build (stored log + phone forwarding + cache snapshots).
    (:debug)
    function isDebugBuild() as Boolean {
        return true;
    }

    (:release)
    function isDebugBuild() as Boolean {
        return false;
    }

    //! Release build: no stored log.
    (:release)
    function store(body as String) as Void {
    }

    //! Release build: nothing stored, nothing to forward.
    (:release)
    function sendPending(control as ControlChannel?, pairingId as String?) as Void {
    }

    //! Debug build: append the line to the Application.Storage ring buffer. Never throws.
    (:debug)
    function store(body as String) as Void {
        try {
            var seq = storedNumber(KEY_SEQ) + 1;
            var line = "#" + seq.toString() + " t=" + System.getTimer().toString() + " " + body;
            if (line.length() > MAX_LEN) {
                var cut = line.substring(0, MAX_LEN);
                if (cut != null) {
                    line = cut;
                }
            }

            var lines = [] as Array<Object?>;
            var raw = Application.Storage.getValue(KEY_LINES);
            if (raw instanceof Array) {
                lines = raw as Array<Object?>;
            }
            lines.add(line);
            if (lines.size() > MAX_LINES) {
                lines = lines.slice(lines.size() - MAX_LINES, null) as Array<Object?>;
            }
            Application.Storage.setValue(KEY_LINES, lines as Application.Storage.ValueType);
            Application.Storage.setValue(KEY_SEQ, seq as Application.Storage.ValueType);
        } catch (ex) {
            // Logging must never break the caller.
        }
    }

    //! Start forwarding unsent lines to the phone (one per message). No-op when a chain is already
    //! running, when nothing is pending, or without a control channel / pairingId. Never throws.
    (:debug)
    function sendPending(control as ControlChannel?, pairingId as String?) as Void {
        if (control == null || pairingId == null || activeSender != null) {
            return;
        }
        try {
            var pending = pendingLines();
            if (pending.size() == 0) {
                return;
            }
            var sender = new LogSender(control, pairingId, pending);
            activeSender = sender;
            sender.start();
        } catch (ex) {
            activeSender = null;
        }
    }

    //! Lines not yet confirmed delivered (seq > dbg:sent), oldest first, at most SEND_BATCH.
    (:debug)
    function pendingLines() as Array<String> {
        var out = [] as Array<String>;
        try {
            var sent = storedNumber(KEY_SENT);
            var raw = Application.Storage.getValue(KEY_LINES);
            if (raw instanceof Array) {
                var all = raw as Array<Object?>;
                for (var i = 0; i < all.size() && out.size() < SEND_BATCH; i++) {
                    var item = all[i];
                    if (item instanceof String && seqOf(item as String) > sent) {
                        out.add(item as String);
                    }
                }
            }
        } catch (ex) {
        }
        return out;
    }

    //! Record that the line numbered `seq` reached the phone.
    (:debug)
    function markSent(seq as Number) as Void {
        try {
            if (seq > storedNumber(KEY_SENT)) {
                Application.Storage.setValue(KEY_SENT, seq as Application.Storage.ValueType);
            }
        } catch (ex) {
        }
    }

    //! Parse the line number from "#<seq> ...", or 0 when malformed.
    (:debug)
    function seqOf(line as String) as Number {
        try {
            var space = line.find(" ");
            if (line.length() > 1 && space != null && space > 1) {
                var digits = line.substring(1, space);
                if (digits != null) {
                    var n = digits.toNumber();
                    if (n != null) {
                        return n;
                    }
                }
            }
        } catch (ex) {
        }
        return 0;
    }

    //! A stored Number, or 0 when absent/invalid.
    function storedNumber(key as String) as Number {
        var raw = Application.Storage.getValue(key);
        return (raw instanceof Number) ? (raw as Number) : 0;
    }

    //! Compact description of an arbitrary value for diagnostics. Never throws.
    function shape(obj as Object?) as String {
        try {
            if (obj == null) {
                return "null";
            }
            if (obj instanceof Number) {
                return "Number(" + (obj as Number).toString() + ")";
            }
            if (obj instanceof Long) {
                return "Long(" + (obj as Long).toString() + ")";
            }
            if (obj instanceof String) {
                return "String(" + (obj as String) + ")";
            }
            if (obj instanceof Dictionary) {
                return "Dictionary";
            }
            if (obj instanceof Array) {
                return "Array(" + (obj as Array).size().toString() + ")";
            }
            var s = "obj";
            if (obj has :next) {
                s += "+next";
            }
            if (obj has :getId) {
                s += "+getId";
            }
            if (obj has :getContentRef) {
                s += "+getContentRef";
            }
            return s + " " + obj.toString();
        } catch (ex) {
            return "?";
        }
    }

    //! Memory usage "mem=used/totalB free=N" of the current process. Never throws.
    function mem() as String {
        try {
            var s = System.getSystemStats();
            return "mem=" + s.usedMemory.toString() + "/" + s.totalMemory.toString() + "B free="
                + s.freeMemory.toString();
        } catch (ex) {
            return "mem=?";
        }
    }

    //! Compact description of a caught exception (class + message). Never throws.
    function describe(ex as Lang.Exception) as String {
        try {
            var msg = ex.getErrorMessage();
            return ex.toString() + " msg=" + (msg == null ? "null" : msg);
        } catch (e2) {
            return "exception";
        }
    }
}

//! LogSender — sends a batch of log lines to the phone strictly ONE AT A TIME: each line is
//! transmitted only after the previous transmit reported onComplete. On onError the chain stops
//! (remaining lines stay unsent in storage and go on the next "Refresh stats").
//! Debug builds only.
(:debug)
class LogSender extends Communications.ConnectionListener {

    private var _control as ControlChannel;
    private var _pairingId as String;
    private var _lines as Array<String>;
    private var _index as Number;

    //! Short delay before the first message (see DebugLog.SEND_DELAY_MS); kept referenced.
    private var _timer as Timer.Timer?;

    function initialize(control as ControlChannel, pairingId as String, lines as Array<String>) {
        ConnectionListener.initialize();
        _control = control;
        _pairingId = pairingId;
        _lines = lines;
        _index = 0;
        _timer = null;
    }

    //! Begin the chain after a short delay (immediately if a Timer can't be created).
    function start() as Void {
        try {
            var timer = new Timer.Timer();
            timer.start(method(:onStartTimer), DebugLog.SEND_DELAY_MS, false);
            _timer = timer;
        } catch (ex) {
            sendCurrent();
        }
    }

    //! Timer callback: send the first line.
    function onStartTimer() as Void {
        _timer = null;
        sendCurrent();
    }

    //! The previous line was delivered: mark it sent and send the next one.
    function onComplete() as Void {
        if (_index < _lines.size()) {
            DebugLog.markSent(DebugLog.seqOf(_lines[_index]));
        }
        _index += 1;
        sendCurrent();
    }

    //! Delivery failed: stop here; the unsent lines are retried next time.
    function onError() as Void {
        DebugLog.activeSender = null;
    }

    //! Transmit the line at the cursor, or finish the chain when all are sent.
    private function sendCurrent() as Void {
        if (_index >= _lines.size()) {
            DebugLog.activeSender = null;
            return;
        }
        try {
            _control.transmit(Messages.buildLogLine(_pairingId, _lines[_index]), null, self);
        } catch (ex) {
            DebugLog.activeSender = null;
        }
    }
}

//! MediaDiag — snapshots of what the SYSTEM media cache actually holds for this app, to compare with
//! what we stored. If the cache is empty/tiny after a "successful" sync, the audio never persisted;
//! if it holds ids that differ in type/value from our stored mediaIds, the handle we saved is wrong.
module MediaDiag {

    //! Max cached refs to enumerate in one snapshot.
    const MAX_REFS = 12;

    //! Release build: no cache walk.
    (:release)
    function snapshot(tag as String) as Void {
    }

    //! Debug build: log cache size/capacity and the ids the system reports for cached audio.
    //! Never throws.
    (:debug)
    function snapshot(tag as String) as Void {
        try {
            var stats = Media.getCacheStatistics();
            DebugLog.log(tag, "cache size=" + stats.size.toString() + "B cap=" + stats.capacity.toString() + "B");
        } catch (ex) {
            DebugLog.log(tag, "getCacheStatistics FAILED " + DebugLog.describe(ex));
        }
        try {
            var it = Media.getContentRefIter({ :contentType => Media.CONTENT_TYPE_AUDIO });
            var n = 0;
            var ref = it.next();
            while (ref != null && n < MAX_REFS) {
                if (n < 3) {
                    DebugLog.log(tag, "cached[" + n.toString() + "] id=" + DebugLog.shape(ref.getId()));
                }
                n += 1;
                ref = it.next();
            }
            DebugLog.log(tag, "cached audio refs=" + n.toString() + (ref != null ? "+" : ""));
        } catch (ex) {
            DebugLog.log(tag, "getContentRefIter FAILED " + DebugLog.describe(ex));
        }
    }
}
