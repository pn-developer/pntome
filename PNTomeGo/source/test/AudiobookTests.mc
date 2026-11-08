import Toybox.Application;
import Toybox.Lang;
import Toybox.Media;
import Toybox.Test;

//! Unit tests for the audiobook logic (spec gsound-audiobooks). Compiled only into unit-test
//! builds (`monkeyc --unit-test`), run in the simulator with `monkeydo <prg> <device> -t`.
//! Everything here is (:test)-annotated so normal builds don't contain it. Tests that touch
//! storage use names prefixed "zzt-" and clean up after themselves.




//! Helpers for the tests below. The MODULE is (:test)-annotated so it is excluded from
//! normal builds, while its functions are not themselves run as tests.
(:test)
module T {

    //! Log and return whether `cond` held (keeps each test a list of small, typed checks).
    function check(logger as Test.Logger, cond as Boolean, what as String) as Boolean {
        if (!cond) {
            logger.error("FAILED: " + what);
        }
        return cond;
    }

    //! A stored Number field, or -1.
    function num(d as Dictionary?, key as String) as Number {
        if (d == null) {
            return -1;
        }
        var v = d.get(key);
        return (v instanceof Number) ? (v as Number) : -1;
    }

    //! A stored String field, or "".
    function str(d as Dictionary?, key as String) as String {
        if (d == null) {
            return "";
        }
        var v = d.get(key);
        return (v instanceof String) ? (v as String) : "";
    }

    //! Seed three chapters of book "zzt-book" (seq 0..2) with fake media ids 9001..9003.
    function seedTestBook() as Array<String> {
        var ids = ["zzt-c1", "zzt-c2", "zzt-c3"] as Array<String>;
        for (var i = 0; i < ids.size(); i++) {
            Repo.putTrack(ids[i], { "mediaId" => 9001 + i, "folder" => "zzt-book", "title" => "Ch" + i.toString(),
                "seq" => i, "book" => true });
        }
        return ids;
    }

    //! Remove the seeded book (bookkeeping only — there is no real cached media for it).
    function cleanupTestBook() as Void {
        var ids = ["zzt-c1", "zzt-c2", "zzt-c3"] as Array<String>;
        for (var i = 0; i < ids.size(); i++) {
            Application.Storage.deleteValue(Repo.trackKey(ids[i]));
        }
        var index = Repo.loadIndex();
        var kept = [] as Array<Object?>;
        for (var i = 0; i < index.size(); i++) {
            var id = index[i];
            if (!(id instanceof String) || (id as String).find("zzt-") != 0) {
                kept.add(id);
            }
        }
        Repo.saveIndex(kept);
        Repo.rebuildFolders();
        Repo.deleteBookmark("zzt-book");
    }
}

(:test)
function testSortedByKeyIsStableAndOrdered(logger as Test.Logger) as Boolean {
    var ids = ["c", "a", "x", "b", "y"] as Array<String>;
    var keys = [2, 0, FolderTracks.NO_SEQ, 1, FolderTracks.NO_SEQ] as Array<Number>;
    var out = FolderTracks.sortedByKey(ids, keys);
    logger.debug("sorted=" + out.toString());
    // seq order first, then the unsequenced ones in their original order (stable).
    return T.check(logger, out.toString().equals("[a, b, c, x, y]"), "stable seq order");
}

(:test)
function testSortedByKeyLargeInput(logger as Test.Logger) as Boolean {
    var ids = [] as Array<String>;
    var keys = [] as Array<Number>;
    for (var i = 0; i < 300; i++) {
        ids.add("t" + i.toString());
        keys.add(299 - i);
    }
    var out = FolderTracks.sortedByKey(ids, keys);
    var ok = T.check(logger, out.size() == 300, "size 300");
    ok = T.check(logger, out[0].equals("t299"), "first is t299") && ok;
    ok = T.check(logger, out[299].equals("t0"), "last is t0") && ok;
    return ok;
}

(:test)
function testFormatTime(logger as Test.Logger) as Boolean {
    var ok = T.check(logger, Bookmarks.formatTime(0).equals("0:00"), "0:00");
    ok = T.check(logger, Bookmarks.formatTime(65).equals("1:05"), "1:05") && ok;
    ok = T.check(logger, Bookmarks.formatTime(760).equals("12:40"), "12:40") && ok;
    ok = T.check(logger, Bookmarks.formatTime(3725).equals("1:02:05"), "1:02:05") && ok;
    return ok;
}

(:test)
function testManifestParserCarriesSeqAndBook(logger as Test.Logger) as Boolean {
    var raw = { "id" => "a", "path" => "/media/a", "mime" => "audio/mpeg", "seq" => 3l, "book" => true };
    var t = ManifestParser.cleanTrack(raw);
    var legacy = ManifestParser.cleanTrack({ "id" => "b", "path" => "/media/b", "mime" => "audio/mpeg" });
    var ok = T.check(logger, T.num(t, "seq") == 3, "Long seq normalised to Number 3");
    ok = T.check(logger, t != null && Repo.isTrue(t.get("book")), "book carried") && ok;
    ok = T.check(logger, legacy != null && legacy.get("seq") == null, "legacy: no seq") && ok;
    ok = T.check(logger, legacy != null && legacy.get("book") == null, "legacy: no book") && ok;
    return ok;
}

(:test)
function testMetaUpdaterApplyTo(logger as Test.Logger) as Boolean {
    var entry = { "folder" => "F", "title" => "ID3 title", "mediaId" => 1 };
    var changed = MetaUpdater.applyTo(entry, { "id" => "a", "seq" => 2, "book" => true, "title" => "01 Intro" });
    var again = MetaUpdater.applyTo(entry, { "id" => "a", "seq" => 2, "book" => true, "title" => "01 Intro" });
    var ok = T.check(logger, changed, "first apply changes");
    ok = T.check(logger, !again, "second apply is a no-op") && ok;
    ok = T.check(logger, T.str(entry, "title").equals("01 Intro"), "title updated") && ok;
    var unmark = MetaUpdater.applyTo(entry, { "id" => "a", "book" => false });
    ok = T.check(logger, unmark, "unmark changes") && ok;
    ok = T.check(logger, entry.get("book") == null, "book removed") && ok;
    ok = T.check(logger, T.num(entry, "seq") == 2, "seq kept") && ok;
    return ok;
}



(:test)
function testBookFolderListedAndOrdered(logger as Test.Logger) as Boolean {
    var ok = true;
    try {
        T.seedTestBook();
        var ordered = FolderTracks.orderedIds("zzt-book");
        logger.debug("ordered=" + ordered.toString());
        ok = T.check(logger, Repo.isBookFolder("zzt-book"), "is a book folder") && ok;
        ok = T.check(logger, ordered.toString().equals("[zzt-c1, zzt-c2, zzt-c3]"), "chapter order") && ok;
    } finally {
        T.cleanupTestBook();
    }
    ok = T.check(logger, !Repo.isBookFolder("zzt-book"), "book folder gone after cleanup") && ok;
    return ok;
}

(:test)
function testResolveRewindsAndFallsBack(logger as Test.Logger) as Boolean {
    var ok = true;
    try {
        var ids = T.seedTestBook();

        Repo.saveBookmark("zzt-book", "zzt-c2", 120, 1, false);
        var r = Bookmarks.resolve("zzt-book", ids);
        ok = T.check(logger, T.num(r, "index") == 1, "resume chapter index 1") && ok;
        ok = T.check(logger, T.num(r, "pos") == 115, "rewind 5 s") && ok;

        Repo.saveBookmark("zzt-book", "zzt-c2", 3, 1, false);
        ok = T.check(logger, T.num(Bookmarks.resolve("zzt-book", ids), "pos") == 0, "rewind clamped at 0") && ok;

        // Bookmarked chapter deleted: the chapter now at its old index, from 0 (AB-5.5).
        Repo.saveBookmark("zzt-book", "zzt-gone", 500, 1, false);
        var r3 = Bookmarks.resolve("zzt-book", ids);
        ok = T.check(logger, T.num(r3, "index") == 1, "missing chapter -> same index") && ok;
        ok = T.check(logger, T.num(r3, "pos") == 0, "missing chapter -> from 0") && ok;

        // Finished book: no Resume (AB-5.3).
        Repo.saveBookmark("zzt-book", "zzt-c3", 0, 2, true);
        ok = T.check(logger, Bookmarks.resolve("zzt-book", ids) == null, "finished: no resolve") && ok;
        ok = T.check(logger, Bookmarks.resumeLabel("zzt-book", ids) == null, "finished: no label") && ok;

        Repo.saveBookmark("zzt-book", "zzt-c2", 760, 1, false);
        var label = Bookmarks.resumeLabel("zzt-book", ids);
        logger.debug("label=" + label);
        ok = T.check(logger, label != null && label.equals("Ch 2 · 12:40"), "resume label") && ok;
    } finally {
        T.cleanupTestBook();
    }
    return ok;
}

//! Player-initiated start (args null) after a book "resume" selection resumes from the
//! bookmark (AB-6.2): exercises PNTomeGoApp.getContentDelegate → PNTomeContentDelegate →
//! FolderIterator end to end. The seeded media ids are fake, so the iterator serves its
//! fallback Content; what matters is that it starts at the bookmarked chapter.
(:test)
function testPlayerStartResumesBook(logger as Test.Logger) as Boolean {
    var ok = true;
    var savedSelection = Repo.loadPlaybackSelection();
    try {
        T.seedTestBook();
        Repo.saveBookmark("zzt-book", "zzt-c3", 90, 2, false);
        PlaybackStarterTestHook.saveResume("zzt-book");

        var delegate = (Application.getApp() as PNTomeGoApp).getContentDelegate(null);
        var it = delegate.getContentIterator() as FolderIterator;
        ok = T.check(logger, it.ids().size() == 3, "iterator has the 3 chapters") && ok;
        var content = it.get();
        ok = T.check(logger, content != null, "get() returns content") && ok;
        // get() served the cursor's chapter; peekNext must be null (chapter 3 is the last).
        ok = T.check(logger, it.peekNext() == null, "started at the last chapter (bookmark)") && ok;
        ok = T.check(logger, it.peekPrevious() != null, "previous chapter exists") && ok;
    } finally {
        T.cleanupTestBook();
        if (savedSelection != null) {
            Repo.savePlaybackArgs(savedSelection);
        }
    }
    return ok;
}

//! Test-only access to the resume args shape without starting playback.
(:test)
module PlaybackStarterTestHook {
    function saveResume(folder as String) as Void {
        Repo.savePlaybackArgs({ "folder" => folder, "mode" => PlaybackStarter.MODE_RESUME });
    }
}

(:test)
function testSessionEventsUpdateBookmark(logger as Test.Logger) as Boolean {
    var ok = true;
    try {
        var ids = T.seedTestBook();
        // Resumed at chapter 2 (index 1), 600 s.
        var s = new BookSession("zzt-book", ids, 1, 600);
        s.onSong(9002, Media.SONG_EVENT_START, 0);
        var bm = Repo.loadBookmark("zzt-book");
        ok = T.check(logger, T.str(bm, "trk").equals("zzt-c2"), "start: chapter 2") && ok;
        ok = T.check(logger, T.num(bm, "pos") == 600, "start: keeps resume offset") && ok;

        // Paused right away; the player may report RELATIVE (3) or ABSOLUTE (603): both → 603.
        s.onSong(9002, Media.SONG_EVENT_PAUSE, 3);
        ok = T.check(logger, T.num(Repo.loadBookmark("zzt-book"), "pos") == 603, "relative pos corrected") && ok;
        s.onSong(9002, Media.SONG_EVENT_RESUME, 0);
        s.onSong(9002, Media.SONG_EVENT_PAUSE, 603);
        ok = T.check(logger, T.num(Repo.loadBookmark("zzt-book"), "pos") == 603, "absolute pos kept") && ok;

        // Chapter completes → next chapter at 0.
        s.onSong(9002, Media.SONG_EVENT_COMPLETE, 0);
        bm = Repo.loadBookmark("zzt-book");
        ok = T.check(logger, T.str(bm, "trk").equals("zzt-c3"), "complete: next chapter") && ok;
        ok = T.check(logger, T.num(bm, "pos") == 0, "complete: next at 0") && ok;

        // The next chapter starts at 0 (no offset): positions are taken as reported.
        s.onSong(9003, Media.SONG_EVENT_START, 0);
        s.onSong(9003, Media.SONG_EVENT_PAUSE, 42);
        ok = T.check(logger, T.num(Repo.loadBookmark("zzt-book"), "pos") == 42, "later chapter: plain pos") && ok;

        // Last chapter completes → finished.
        s.onSong(9003, Media.SONG_EVENT_COMPLETE, 0);
        bm = Repo.loadBookmark("zzt-book");
        ok = T.check(logger, bm != null && Repo.isTrue(bm.get("fin")), "last chapter: finished") && ok;
    } finally {
        T.cleanupTestBook();
    }
    return ok;
}

// --- BOOKMARK protocol (spec gsound-player PL-4.2, PL-7.5, design §5) -------------------------

(:test)
function testBuildBookmarkShape(logger as Test.Logger) as Boolean {
    var m = Messages.buildBookmark("pid", "Dune", "f1", "t1", 2, 95, true, 1700000000);
    var ok = T.check(logger, m.size() == 10, "10 flat keys, got " + m.size().toString());
    ok = T.check(logger, T.num(m, "v") == Protocol.PROTOCOL_VERSION, "v") && ok;
    ok = T.check(logger, T.str(m, "type").equals("BOOKMARK"), "type") && ok;
    ok = T.check(logger, T.str(m, "pairingId").equals("pid"), "pairingId") && ok;
    ok = T.check(logger, T.str(m, "folder").equals("Dune"), "folder") && ok;
    ok = T.check(logger, T.str(m, "fid").equals("f1"), "fid") && ok;
    ok = T.check(logger, T.str(m, "trk").equals("t1"), "trk") && ok;
    ok = T.check(logger, T.num(m, "idx") == 2, "idx") && ok;
    ok = T.check(logger, T.num(m, "pos") == 95, "pos") && ok;
    ok = T.check(logger, m.get("fin") == true, "fin true") && ok;
    ok = T.check(logger, T.num(m, "at") == 1700000000, "at") && ok;
    // Flat: no nested containers.
    var keys = m.keys();
    for (var i = 0; i < keys.size(); i++) {
        var v = m.get(keys[i]);
        ok = T.check(logger, !(v instanceof Dictionary) && !(v instanceof Array), "flat: " + keys[i].toString()) && ok;
    }
    return ok;
}

(:test)
function testBuildBookmarkOmitsNullFidAndKeepsFin(logger as Test.Logger) as Boolean {
    var m = Messages.buildBookmark("pid", "Dune", null, "t1", 0, 0, false, 5);
    var ok = T.check(logger, !m.hasKey("fid"), "fid omitted when null");
    ok = T.check(logger, m.hasKey("fin") && m.get("fin") == false, "fin always present (false)") && ok;
    ok = T.check(logger, m.size() == 9, "9 keys, got " + m.size().toString()) && ok;
    return ok;
}

(:test)
function testBookmarkIsKnownTypeAndValidates(logger as Test.Logger) as Boolean {
    var ok = T.check(logger, Messages.isKnownType(Messages.TYPE_BOOKMARK), "BOOKMARK known");
    ok = T.check(logger, Messages.isKnownType("BOOKMARK"), "literal BOOKMARK known") && ok;
    ok = T.check(logger, Messages.isKnownType("STATS") && Messages.isKnownType("PING"), "old types still known") && ok;
    ok = T.check(logger, !Messages.isKnownType("BOOKMARKS"), "near-miss rejected") && ok;
    ok = T.check(logger, !Messages.isKnownType("NOPE") && !Messages.isKnownType("") && !Messages.isKnownType(null), "unknown rejected") && ok;
    var m = Messages.buildBookmark("pid", "Dune", null, "t1", 0, 0, false, 5);
    ok = T.check(logger, Protocol.validate(m) == Protocol.VALID_OK, "built bookmark validates") && ok;
    m.put("type", "FUTURE_TYPE");
    ok = T.check(logger, Protocol.validate(m) == Protocol.INVALID_TYPE, "unknown type -> INVALID_TYPE") && ok;
    return ok;
}

(:test)
function testProtocolGetBoolean(logger as Test.Logger) as Boolean {
    var d = { "t" => true, "f" => false, "s" => "true", "n" => 1 } as Dictionary;
    var ok = T.check(logger, Protocol.getBoolean(d, "t") == true, "true");
    ok = T.check(logger, Protocol.getBoolean(d, "f") == false, "false") && ok;
    ok = T.check(logger, Protocol.getBoolean(d, "s") == null, "string -> null") && ok;
    ok = T.check(logger, Protocol.getBoolean(d, "n") == null, "number -> null") && ok;
    ok = T.check(logger, Protocol.getBoolean(d, "missing") == null, "absent -> null") && ok;
    ok = T.check(logger, Protocol.getBoolean(null, "t") == null, "null msg -> null") && ok;
    ok = T.check(logger, Protocol.getBoolean("str", "t") == null, "non-dict -> null") && ok;
    return ok;
}

(:test)
function testProtocolGetBooleanOr(logger as Test.Logger) as Boolean {
    var d = { "t" => true, "f" => false, "s" => "true", "n" => 1 } as Dictionary;
    var ok = T.check(logger, Protocol.getBooleanOr(d, "t", false) == true, "true kept");
    ok = T.check(logger, Protocol.getBooleanOr(d, "f", true) == false, "false kept (not defaulted)") && ok;
    ok = T.check(logger, Protocol.getBooleanOr(d, "s", false) == false, "string -> default false") && ok;
    ok = T.check(logger, Protocol.getBooleanOr(d, "n", true) == true, "number -> default true") && ok;
    ok = T.check(logger, Protocol.getBooleanOr(d, "missing", false) == false, "absent -> default") && ok;
    ok = T.check(logger, Protocol.getBooleanOr(null, "t", true) == true, "null msg -> default") && ok;
    return ok;
}
