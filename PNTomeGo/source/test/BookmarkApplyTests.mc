import Toybox.Application;
import Toybox.Lang;
import Toybox.Media;
import Toybox.Test;
//! Tests for BookmarkApply (spec gsound-player 10.2). Uses the "zzt-book" fixture (chapters
//! zzt-c1..c3, seq 0..2) and a temporary pairing, restored afterwards.
(:test)
module BaT {
    var savedPairing as Dictionary? = null;

    function setup() as Void {
        savedPairing = Repo.loadPairing();
        Repo.savePairing({ "pairingId" => "zzt-pid" } as Dictionary);
        T.seedTestBook();
        Repo.saveBookFolders(["zzt-book"] as Array<Object?>);
        Repo.saveBookFids({ "zzt-book" => "zzt-fid" } as Dictionary);
        Repo.deleteBookmark("zzt-book");
    }

    function teardown() as Void {
        Repo.clearBookmarkRx("zzt-book");
        T.cleanupTestBook();
        Repo.saveBookFolders([] as Array<Object?>);
        Repo.saveBookFids({} as Dictionary);
        Repo.savePairing(savedPairing);
    }

    function msg(folder as String, fid as String?, trk as String, at as Number) as Dictionary {
        return Messages.buildBookmark("zzt-pid", folder, fid, trk, 99, 42, true, at);
    }
}

(:test)
function testBookmarkApplyTakesNewerAndRecordsRx(logger as Test.Logger) as Boolean {
    BaT.setup();
    var ok = true;
    var r = BookmarkApply.apply(BaT.msg("zzt-book", "zzt-fid", "zzt-c2", 1000), 2000);
    var bm = Repo.loadBookmark("zzt-book");
    ok = T.check(logger, r == BookmarkApply.RESULT_TAKEN, "taken (none locally)") && ok;
    ok = T.check(logger, T.str(bm, "trk").equals("zzt-c2") && T.num(bm, "pos") == 42
        && T.num(bm, "at") == 1000 && bm.get("fin") == true, "stored fields + incoming at") && ok;
    ok = T.check(logger, T.num(bm, "idx") == 1, "idx recomputed locally, not 99") && ok;
    ok = T.check(logger, Repo.loadBookmarkRx("zzt-book") == 1000, "rx recorded") && ok;
    r = BookmarkApply.apply(BaT.msg("zzt-book", "zzt-fid", "zzt-c3", 1500), 2000);
    ok = T.check(logger, r == BookmarkApply.RESULT_TAKEN && T.str(Repo.loadBookmark("zzt-book"), "trk").equals("zzt-c3"),
        "newer replaces") && ok;
    BaT.teardown();
    return ok;
}

(:test)
function testBookmarkApplyIgnoresOlderAndEqual(logger as Test.Logger) as Boolean {
    BaT.setup();
    Repo.saveBookmarkAt("zzt-book", "zzt-c1", 5, 0, false, 1000);
    var r1 = BookmarkApply.apply(BaT.msg("zzt-book", "zzt-fid", "zzt-c2", 900), 2000);
    var r2 = BookmarkApply.apply(BaT.msg("zzt-book", "zzt-fid", "zzt-c2", 1000), 2000);
    var ok = T.check(logger, r1 == BookmarkApply.RESULT_KEPT_LOCAL && r2 == BookmarkApply.RESULT_KEPT_LOCAL, "kept");
    ok = T.check(logger, T.str(Repo.loadBookmark("zzt-book"), "trk").equals("zzt-c1"), "local unchanged") && ok;
    ok = T.check(logger, Repo.loadBookmarkRx("zzt-book") == null, "no rx when kept") && ok;
    BaT.teardown();
    return ok;
}

(:test)
function testBookmarkApplyUnknownFolderAndTrack(logger as Test.Logger) as Boolean {
    BaT.setup();
    var ok = T.check(logger, BookmarkApply.apply(BaT.msg("zzt-nope", "zzt-nofid", "zzt-c1", 1000), 2000)
        == BookmarkApply.RESULT_UNKNOWN_FOLDER, "unknown folder");
    ok = T.check(logger, BookmarkApply.apply(BaT.msg("zzt-book", "zzt-fid", "zzt-zz", 1000), 2000)
        == BookmarkApply.RESULT_UNKNOWN_TRACK, "unknown track") && ok;
    ok = T.check(logger, Repo.loadBookmark("zzt-book") == null, "nothing stored") && ok;
    BaT.teardown();
    return ok;
}

(:test)
function testBookmarkApplyFidMatchAndNameFallback(logger as Test.Logger) as Boolean {
    BaT.setup();
    // fid wins over a wrong display name
    var ok = T.check(logger, BookmarkApply.apply(BaT.msg("Renamed", "zzt-fid", "zzt-c1", 1000), 2000)
        == BookmarkApply.RESULT_TAKEN, "fid match");
    Repo.deleteBookmark("zzt-book");
    // no fid -> name fallback
    ok = T.check(logger, BookmarkApply.apply(BaT.msg("zzt-book", null, "zzt-c1", 1000), 2000)
        == BookmarkApply.RESULT_TAKEN, "name fallback") && ok;
    Repo.deleteBookmark("zzt-book");
    // unknown fid -> name fallback
    ok = T.check(logger, BookmarkApply.apply(BaT.msg("zzt-book", "other", "zzt-c1", 1000), 2000)
        == BookmarkApply.RESULT_TAKEN, "unknown fid falls back to name") && ok;
    BaT.teardown();
    return ok;
}

(:test)
function testBookmarkApplyBadPairingAndFields(logger as Test.Logger) as Boolean {
    BaT.setup();
    var bad = Messages.buildBookmark("other-pid", "zzt-book", "zzt-fid", "zzt-c1", 0, 1, false, 1000);
    var ok = T.check(logger, BookmarkApply.apply(bad, 2000) == BookmarkApply.RESULT_BAD_PAIRING, "bad pairing");
    var noTrk = BaT.msg("zzt-book", "zzt-fid", "zzt-c1", 1000);
    noTrk.remove("trk");
    ok = T.check(logger, BookmarkApply.apply(noTrk, 2000) == BookmarkApply.RESULT_BAD_FIELDS, "missing trk") && ok;
    var noFin = BaT.msg("zzt-book", "zzt-fid", "zzt-c1", 1000);
    noFin.remove("fin");
    ok = T.check(logger, BookmarkApply.apply(noFin, 2000) == BookmarkApply.RESULT_TAKEN
        && Repo.loadBookmark("zzt-book").get("fin") == false, "fin defaults to false") && ok;
    BaT.teardown();
    return ok;
}

(:test)
function testBookmarkApplyNotEchoedBySender(logger as Test.Logger) as Boolean {
    BaT.setup();
    BookmarkApply.apply(BaT.msg("zzt-book", "zzt-fid", "zzt-c2", 1000), 2000);
    var f = new FakeTransmit();
    BookmarkSender.active = null;
    BookmarkSender.sendAll(f.method(:transmit), "zzt-pid");
    var ok = T.check(logger, f.sent.size() == 0, "applied bookmark not sent back");
    BookmarkSender.active = null;
    BaT.teardown();
    return ok;
}
(:test)
function testLiveMarkerRepoHelpers(logger as Test.Logger) as Boolean {
    Repo.clearLive();
    var ok = T.check(logger, Repo.loadLive() == null, "none initially");
    Repo.setLive("zzt-book", 500);
    var m = Repo.loadLive();
    ok = T.check(logger, m != null && T.str(m, "f").equals("zzt-book") && T.num(m, "at") == 500, "set/load") && ok;
    Repo.setLive("zzt-book", 560);
    ok = T.check(logger, T.num(Repo.loadLive(), "at") == 560, "refresh") && ok;
    Repo.clearLive();
    ok = T.check(logger, Repo.loadLive() == null, "cleared") && ok;
    return ok;
}
(:test)
function testBookmarkApplyLiveSuppression(logger as Test.Logger) as Boolean {
    BaT.setup();
    Repo.saveBookmarkAt("zzt-book", "zzt-c1", 5, 0, false, 1000);
    Repo.setLive("zzt-book", 1990);
    var ok = T.check(logger, BookmarkApply.isLive("zzt-book", 2000) && !BookmarkApply.isLive("other", 2000), "isLive only for marked folder");
    // live -> ignored, bookmark untouched
    var r = BookmarkApply.apply(BaT.msg("zzt-book", "zzt-fid", "zzt-c2", 1500), 2000);
    ok = T.check(logger, r == BookmarkApply.RESULT_LIVE && T.str(Repo.loadBookmark("zzt-book"), "trk").equals("zzt-c1")
        && Repo.loadBookmarkRx("zzt-book") == null, "live: ignored") && ok;
    // stale (>= 120 s) -> applied, marker cleared lazily
    r = BookmarkApply.apply(BaT.msg("zzt-book", "zzt-fid", "zzt-c2", 1500), 1990 + 120);
    ok = T.check(logger, r == BookmarkApply.RESULT_TAKEN && Repo.loadLive() == null, "stale: applied + cleared") && ok;
    // just inside the limit is still live
    Repo.setLive("zzt-book", 3000);
    ok = T.check(logger, BookmarkApply.isLive("zzt-book", 3119), "119 s still live") && ok;
    // other folder live -> applied
    Repo.setLive("other-book", 4000);
    r = BookmarkApply.apply(BaT.msg("zzt-book", "zzt-fid", "zzt-c3", 1600), 4001);
    ok = T.check(logger, r == BookmarkApply.RESULT_TAKEN, "other folder live: applied") && ok;
    // cleared -> applied
    Repo.clearLive();
    r = BookmarkApply.apply(BaT.msg("zzt-book", "zzt-fid", "zzt-c2", 1700), 4002);
    ok = T.check(logger, r == BookmarkApply.RESULT_TAKEN, "cleared: applied") && ok;
    Repo.clearLive();
    BaT.teardown();
    return ok;
}
(:test)
function testBookSessionSetsAndClearsLive(logger as Test.Logger) as Boolean {
    var ids = T.seedTestBook();
    Repo.clearLive();
    var s = new BookSession("zzt-book", ids, 0, 0);
    s.onSong(9001, Media.SONG_EVENT_START, 0);
    var m = Repo.loadLive();
    var ok = T.check(logger, m != null && T.str(m, "f").equals("zzt-book"), "START sets live");
    s.onSong(9001, Media.SONG_EVENT_PAUSE, 10);
    ok = T.check(logger, Repo.loadLive() != null, "PAUSE keeps live") && ok;
    s.onSong(9001, Media.SONG_EVENT_STOP, 10);
    ok = T.check(logger, Repo.loadLive() == null, "STOP clears live") && ok;
    Repo.deleteBookmark("zzt-book");
    T.cleanupTestBook();
    return ok;
}

//! --- Task 10.4 additions ---

(:test)
function testBookmarkApplyResumeLabelAndRewind(logger as Test.Logger) as Boolean {
    BaT.setup();
    var ids = FolderTracks.orderedIds("zzt-book");
    Repo.saveBookmarkAt("zzt-book", "zzt-c1", 5, 0, false, 500);
    var ok = T.check(logger, "Ch 1 · 0:05".equals(Bookmarks.resumeLabel("zzt-book", ids)), "label before apply");
    // phone bookmark: chapter 2 (zzt-c2), pos 42 (BaT.msg), fin = true -> make a non-fin one
    var m = Messages.buildBookmark("zzt-pid", "zzt-book", "zzt-fid", "zzt-c2", 1, 190, false, 1000);
    ok = T.check(logger, BookmarkApply.apply(m, 2000) == BookmarkApply.RESULT_TAKEN, "taken") && ok;
    var label = Bookmarks.resumeLabel("zzt-book", ids);
    ok = T.check(logger, "Ch 2 · 3:10".equals(label), "label after apply: " + label) && ok;
    var r = Bookmarks.resolve("zzt-book", ids);
    ok = T.check(logger, r != null && (r.get("index") as Number) == 1 && (r.get("pos") as Number) == 185,
        "resolve: chapter 2, 5 s rewind") && ok;
    BaT.teardown();
    return ok;
}

(:test)
function testBookmarkApplyFinishedThenResume(logger as Test.Logger) as Boolean {
    BaT.setup();
    var ids = FolderTracks.orderedIds("zzt-book");
    Repo.saveBookmarkAt("zzt-book", "zzt-c2", 30, 1, false, 500);
    var m = Messages.buildBookmark("zzt-pid", "zzt-book", "zzt-fid", "zzt-c3", 2, 60, true, 1000);
    var ok = T.check(logger, BookmarkApply.apply(m, 2000) == BookmarkApply.RESULT_TAKEN, "finished taken");
    ok = T.check(logger, Bookmarks.resolve("zzt-book", ids) == null, "resolve null -> start from chapter 1") && ok;
    ok = T.check(logger, Bookmarks.resumeLabel("zzt-book", ids) == null, "no Resume label when finished") && ok;
    BaT.teardown();
    return ok;
}

(:test)
function testBookmarkApplyFutureAtClamped(logger as Test.Logger) as Boolean {
    BaT.setup();
    var r = BookmarkApply.apply(BaT.msg("zzt-book", "zzt-fid", "zzt-c2", 2000 + 1000000), 2000);
    var ok = T.check(logger, r == BookmarkApply.RESULT_TAKEN, "taken");
    ok = T.check(logger, T.num(Repo.loadBookmark("zzt-book"), "at") == 2300, "stored at = now+300") && ok;
    ok = T.check(logger, Repo.loadBookmarkRx("zzt-book") == 2300, "rx = clamped at") && ok;
    BaT.teardown();
    return ok;
}

(:test)
function testBookmarkApplyNoBooksAtAll(logger as Test.Logger) as Boolean {
    BaT.setup();
    Repo.saveBookFolders([] as Array<Object?>);
    Repo.saveBookFids({} as Dictionary);
    var r = BookmarkApply.apply(BaT.msg("zzt-book", "zzt-fid", "zzt-c1", 1000), 2000);
    var ok = T.check(logger, r == BookmarkApply.RESULT_UNKNOWN_FOLDER, "unknown folder");
    ok = T.check(logger, Repo.loadBookmark("zzt-book") == null, "nothing stored") && ok;
    BaT.teardown();
    return ok;
}

(:test)
function testBookmarkApplyWrongTypedFields(logger as Test.Logger) as Boolean {
    BaT.setup();
    var m = BaT.msg("zzt-book", "zzt-fid", "zzt-c1", 1000);
    m.put(Messages.FIELD_BM_POS, "42");
    var ok = T.check(logger, BookmarkApply.apply(m, 2000) == BookmarkApply.RESULT_BAD_FIELDS, "pos string");
    var m2 = BaT.msg("zzt-book", "zzt-fid", "zzt-c1", 1000);
    m2.put(Messages.FIELD_BM_AT, "1000");
    ok = T.check(logger, BookmarkApply.apply(m2, 2000) == BookmarkApply.RESULT_BAD_FIELDS, "at string") && ok;
    var m3 = BaT.msg("zzt-book", "zzt-fid", "zzt-c1", 1000);
    m3.put(Messages.FIELD_BM_TRK, 5);
    ok = T.check(logger, BookmarkApply.apply(m3, 2000) == BookmarkApply.RESULT_BAD_FIELDS, "trk number") && ok;
    ok = T.check(logger, Repo.loadBookmark("zzt-book") == null, "nothing stored") && ok;
    BaT.teardown();
    return ok;
}

(:test)
function testBookmarkApplyIdempotent(logger as Test.Logger) as Boolean {
    BaT.setup();
    var m = BaT.msg("zzt-book", "zzt-fid", "zzt-c2", 1000);
    var r1 = BookmarkApply.apply(m, 2000);
    var before = Repo.loadBookmark("zzt-book");
    var r2 = BookmarkApply.apply(m, 2000);
    var after = Repo.loadBookmark("zzt-book");
    var ok = T.check(logger, r1 == BookmarkApply.RESULT_TAKEN && r2 == BookmarkApply.RESULT_KEPT_LOCAL, "taken then kept");
    ok = T.check(logger, T.str(after, "trk").equals(T.str(before, "trk")) && T.num(after, "pos") == T.num(before, "pos")
        && T.num(after, "at") == T.num(before, "at") && T.num(after, "idx") == T.num(before, "idx"), "unchanged") && ok;
    BaT.teardown();
    return ok;
}

(:test)
function testBookmarkApplyTrackOfOtherBook(logger as Test.Logger) as Boolean {
    BaT.setup();
    Repo.putTrack("zzt-o1", { "mediaId" => 9101, "folder" => "zzt-other", "title" => "O1", "seq" => 0, "book" => true });
    var r = BookmarkApply.apply(BaT.msg("zzt-book", "zzt-fid", "zzt-o1", 1000), 2000);
    var ok = T.check(logger, r == BookmarkApply.RESULT_UNKNOWN_TRACK, "track of another folder");
    ok = T.check(logger, Repo.loadBookmark("zzt-book") == null, "nothing stored") && ok;
    Application.Storage.deleteValue(Repo.trackKey("zzt-o1"));
    BaT.teardown();
    return ok;
}
