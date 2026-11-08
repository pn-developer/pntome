import Toybox.Lang;
import Toybox.System;
import Toybox.Communications;
//! BookmarkSender — sends the watch's audiobook bookmarks to the phone (spec gsound-player
//! PL-4.1, PL-4.2, PL-4.6, PL-7.2; design §6).
//!
//! Modelled on DebugLog.LogSender: one small flat BOOKMARK message per book, strictly ONE IN
//! FLIGHT — the next book is sent only from the previous transmit's onComplete; onError stops
//! the chain (the phone gets the rest on the next "Refresh stats"). Memory: only the folder
//! name array and a cursor are held; each message dictionary is built just before its transmit.
//!
//! `transmit` is injected (ControlChannel.method(:transmit) in the app; a fake in tests) with
//! the ControlChannel.transmit shape (payload, options, listener).
//! A module-level guard prevents two overlapping chains; a chain whose callbacks never arrive
//! is considered stale after STALE_MS so it can't block forever. Never throws.
module BookmarkSender {
    //! After this long an unfinished chain no longer blocks a new one.
    const STALE_MS = 60000;
    //! The running sender (null when idle); also keeps it referenced while transmits pend.
    var active as BookmarkChain? = null;
    //! Start sending every stored bookmark. Returns true when a chain was started.
    function sendAll(transmit as Method, pairingId as String) as Boolean {
        try {
            var running = active;
            if (running != null && !running.isStale()) {
                return false;
            }
            active = null;
            var folders = Repo.bookFolders();
            if (folders.size() == 0) {
                return false;
            }
            var chain = new BookmarkChain(transmit, pairingId, folders);
            active = chain;
            chain.next();
            return true;
        } catch (ex) {
            active = null;
            return false;
        }
    }
    //! Build the BOOKMARK message for `folder` from its stored bookmark, or null when it has none.
    function buildFor(pairingId as String, folder as String) as Dictionary? {
        var bm = Repo.loadBookmark(folder);
        if (bm == null) {
            return null;
        }
        var trk = Protocol.getString(bm, "trk");
        if (trk == null) {
            return null;
        }
        var idx = Protocol.getNumber(bm, "idx");
        var pos = Protocol.getNumber(bm, "pos");
        var at = Protocol.getNumber(bm, "at");
        // Skip a bookmark that is exactly the one received from the phone (same `at`): sending
        // it back is an echo. The phone ignores an equal `at` anyway (merge keeps local), so
        // the echo would be harmless but wasteful. A newer local bookmark has a larger `at`
        // and is still sent.
        var rx = Repo.loadBookmarkRx(folder);
        if (rx != null && at != null && rx == at) {
            return null;
        }
        return Messages.buildBookmark(pairingId, folder, Repo.folderFid(folder), trk,
            (idx == null) ? 0 : idx, (pos == null) ? 0 : pos,
            Protocol.getBooleanOr(bm, "fin", false), (at == null) ? 0 : at);
    }
}
//! One bookmark-sending run (see BookmarkSender).
class BookmarkChain extends Communications.ConnectionListener {
    private var _transmit as Method;
    private var _pairingId as String;
    private var _folders as Array<Object?>;
    private var _index as Number;
    private var _startedAt as Number;
    function initialize(transmit as Method, pairingId as String, folders as Array<Object?>) {
        ConnectionListener.initialize();
        _transmit = transmit;
        _pairingId = pairingId;
        _folders = folders;
        _index = 0;
        _startedAt = System.getTimer();
    }
    function isStale() as Boolean {
        return (System.getTimer() - _startedAt) > BookmarkSender.STALE_MS;
    }
    //! The previous message was delivered: send the next.
    function onComplete() as Void {
        _index += 1;
        next();
    }
    //! Delivery failed: stop; remaining bookmarks go next time.
    function onError() as Void {
        finish();
    }
    //! Transmit the next book that has a bookmark, or finish.
    function next() as Void {
        try {
            while (_index < _folders.size()) {
                var folder = _folders[_index];
                var msg = (folder instanceof String)
                    ? BookmarkSender.buildFor(_pairingId, folder as String) : null;
                if (msg != null) {
                    _transmit.invoke(msg, null, self);
                    return;
                }
                _index += 1;
            }
        } catch (ex) {
        }
        finish();
    }
    private function finish() as Void {
        if (BookmarkSender.active == self) {
            BookmarkSender.active = null;
        }
    }
}
