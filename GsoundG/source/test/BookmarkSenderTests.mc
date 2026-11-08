import Toybox.Application;
import Toybox.Lang;
import Toybox.Communications;
import Toybox.Test;
//! Tests for BookmarkSender (spec gsound-player 8.1) with a fake transmit. Storage names are
//! prefixed "zzt-" and cleaned up.
(:test)
class FakeTransmit {
    var sent as Array<Dictionary> = [] as Array<Dictionary>;
    var listener as Communications.ConnectionListener? = null;
    //! Same shape as ControlChannel.transmit; completes only when the test says so.
    function transmit(payload as Dictionary, options as Dictionary?,
            l as Communications.ConnectionListener?) as Void {
        sent.add(payload);
        listener = l;
    }
}
(:test)
module BmT {
    function seed() as Void {
        Repo.saveBookFolders(["zzt-a", "zzt-none", "zzt-b"] as Array<Object?>);
        Repo.saveBookmark("zzt-a", "t1", 120, 2, false);
        Repo.saveBookmark("zzt-b", "t2", 5, 0, true);
        Repo.saveBookFids({ "zzt-a" => "fid-a" } as Dictionary);
        BookmarkSender.active = null;
    }
    function cleanup() as Void {
        Repo.deleteBookmark("zzt-a");
        Repo.deleteBookmark("zzt-b");
        Repo.saveBookFolders([] as Array<Object?>);
        Repo.saveBookFids({} as Dictionary);
        BookmarkSender.active = null;
    }
}
(:test)
function testBookmarkSenderChain(logger as Test.Logger) as Boolean {
    var ok = true;
    try {
        BmT.seed();
        var f = new FakeTransmit();
        ok = T.check(logger, BookmarkSender.sendAll(f.method(:transmit), "pid"), "started");
        ok = T.check(logger, f.sent.size() == 1, "one in flight") && ok;
        // double run is refused while in flight
        ok = T.check(logger, !BookmarkSender.sendAll(f.method(:transmit), "pid"), "double run refused") && ok;
        ok = T.check(logger, f.sent.size() == 1, "still one sent") && ok;
        var m = f.sent[0];
        ok = T.check(logger, T.str(m, "folder").equals("zzt-a") && T.str(m, "fid").equals("fid-a"), "folder+fid") && ok;
        ok = T.check(logger, T.str(m, "trk").equals("t1") && T.num(m, "pos") == 120 && T.num(m, "idx") == 2, "trk/pos/idx") && ok;
        ok = T.check(logger, m.get("fin") == false, "fin false") && ok;
        f.listener.onComplete();   // folder without bookmark is skipped
        ok = T.check(logger, f.sent.size() == 2, "chained after complete, skipping no-bookmark folder") && ok;
        ok = T.check(logger, T.str(f.sent[1], "folder").equals("zzt-b") && f.sent[1].get("fid") == null
            && f.sent[1].get("fin") == true, "second: no fid, fin true") && ok;
        f.listener.onComplete();
        ok = T.check(logger, f.sent.size() == 2 && BookmarkSender.active == null, "finished, idle") && ok;
    } catch (ex) {
        logger.error("threw");
        ok = false;
    }
    BmT.cleanup();
    return ok;
}
(:test)
function testBookmarkSenderStopsOnError(logger as Test.Logger) as Boolean {
    var ok = true;
    BmT.seed();
    var f = new FakeTransmit();
    BookmarkSender.sendAll(f.method(:transmit), "pid");
    f.listener.onError();
    ok = T.check(logger, f.sent.size() == 1 && BookmarkSender.active == null, "stopped on error");
    ok = T.check(logger, BookmarkSender.sendAll(f.method(:transmit), "pid"), "can restart afterwards") && ok;
    BmT.cleanup();
    return ok;
}
(:test)
function testBookmarkSenderNoBooksNoTransmit(logger as Test.Logger) as Boolean {
    // Old-watch-irrelevant / empty library: nothing to send, nothing transmitted, stays idle.
    BmT.cleanup();
    var f = new FakeTransmit();
    var ok = T.check(logger, !BookmarkSender.sendAll(f.method(:transmit), "pid"), "no books -> not started");
    ok = T.check(logger, f.sent.size() == 0 && BookmarkSender.active == null, "no transmit, idle") && ok;
    // Books but none with a bookmark: chain starts empty and finishes without transmit.
    Repo.saveBookFolders(["zzt-a"] as Array<Object?>);
    BookmarkSender.sendAll(f.method(:transmit), "pid");
    ok = T.check(logger, f.sent.size() == 0 && BookmarkSender.active == null, "books w/o bookmarks -> no transmit") && ok;
    BmT.cleanup();
    return ok;
}
(:test)
function testBookmarkSenderErrorLeavesRemainingForNextRun(logger as Test.Logger) as Boolean {
    BmT.seed();
    var f = new FakeTransmit();
    BookmarkSender.sendAll(f.method(:transmit), "pid");       // zzt-a in flight
    f.listener.onError();                                      // zzt-b must NOT be sent now
    var ok = T.check(logger, f.sent.size() == 1 && T.str(f.sent[0], "folder").equals("zzt-a"), "only a sent");
    BookmarkSender.sendAll(f.method(:transmit), "pid");       // next run starts again from the top
    ok = T.check(logger, f.sent.size() == 2 && T.str(f.sent[1], "folder").equals("zzt-a"), "next run restarts") && ok;
    f.listener.onComplete();
    ok = T.check(logger, f.sent.size() == 3 && T.str(f.sent[2], "folder").equals("zzt-b"), "remaining sent next run") && ok;
    BmT.cleanup();
    return ok;
}
(:test)
function testManifestFidStored(logger as Test.Logger) as Boolean {
    BmT.cleanup();
    var t = ManifestParser.cleanTrack({ "id" => "a", "path" => "/p", "mime" => "audio/mpeg",
        "folder" => "zzt-a", "fid" => "F1", "book" => true });
    MetaUpdater.storeFids([t] as Array<Dictionary>);
    var ok = T.check(logger, "F1".equals(Repo.folderFid("zzt-a")), "fid stored");
    ok = T.check(logger, Repo.folderFid("zzt-other") == null, "unknown fid null") && ok;
    BmT.cleanup();
    return ok;
}
(:test)
function testSaveBookmarkAtExplicit(logger as Test.Logger) as Boolean {
    BmT.cleanup();
    Repo.saveBookmarkAt("zzt-a", "t1", 7, 1, false, 1234);
    var ok = T.check(logger, T.num(Repo.loadBookmark("zzt-a"), "at") == 1234, "explicit at stored");
    Repo.saveBookmarkAt("zzt-a", "t1", 7, 1, false, null);
    ok = T.check(logger, T.num(Repo.loadBookmark("zzt-a"), "at") > 1234, "null at -> now") && ok;
    BmT.cleanup();
    return ok;
}
(:test)
function testBookmarkSenderSkipsReceivedAt(logger as Test.Logger) as Boolean {
    BmT.seed();
    // zzt-a was received from the phone with this at: not echoed; zzt-b is still sent.
    Repo.saveBookmarkAt("zzt-a", "t1", 120, 2, false, 5000);
    Repo.saveBookmarkRx("zzt-a", 5000);
    var f = new FakeTransmit();
    BookmarkSender.sendAll(f.method(:transmit), "pid");
    var ok = T.check(logger, f.sent.size() == 1 && T.str(f.sent[0], "folder").equals("zzt-b"), "a skipped, b sent");
    f.listener.onComplete();
    BookmarkSender.active = null;
    // A newer local bookmark for zzt-a is sent again.
    Repo.saveBookmarkAt("zzt-a", "t1", 130, 2, false, 5100);
    f = new FakeTransmit();
    BookmarkSender.sendAll(f.method(:transmit), "pid");
    ok = T.check(logger, f.sent.size() >= 1 && T.str(f.sent[0], "folder").equals("zzt-a")
        && T.num(f.sent[0], "pos") == 130, "newer local sent") && ok;
    // Deleting the bookmark forgets the record.
    Repo.deleteBookmark("zzt-a");
    ok = T.check(logger, Repo.loadBookmarkRx("zzt-a") == null, "rx cleared on delete") && ok;
    Repo.clearBookmarkRx("zzt-b");
    BmT.cleanup();
    return ok;
}
