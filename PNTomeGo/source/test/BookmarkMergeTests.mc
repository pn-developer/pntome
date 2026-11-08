import Toybox.Lang;
import Toybox.Test;
//! Tests for BookmarkMerge (spec gsound-player 9.3, PL-6.1-6.5, design §8).
//!
//! The numbered cases C01..C13 and their constants are IDENTICAL to
//! GsoundA/app/src/test/java/dev/gsound/a/domain/bookmarks/BookmarkMergeTest.kt
//! (c01_noLocal .. c13_noLocalWithFutureAt); keep both files in sync.
//!
//!   NOW = 10000, LIMIT = NOW + 300 = 10300, L = 5000 unless stated.
//!   C01 no local                       in=4000          -> take 4000
//!   C02 incoming newer                 L,  in=L+1       -> take L+1
//!   C03 equal keeps local              L,  in=L         -> keep
//!   C04 older keeps local              L,  in=L-1       -> keep
//!   C05 far future, local < LIMIT      L,  in=NOW+1e6   -> take LIMIT
//!   C06 far future, local == LIMIT     LIMIT, in=NOW+1e6 -> keep
//!   C07 far future, local > LIMIT      LIMIT+100, far   -> keep
//!   C08 finished local, newer unfin.   L, in=L+1000     -> take  (flags do not matter to decide)
//!   C09 older finished incoming        L, in=L-1000     -> keep  (flags do not matter to decide)
//!   C10 idempotent repeat              take, apply as local, same incoming again -> keep
//!   C11 boundary NOW+300 exactly       L,  in=LIMIT     -> take LIMIT
//!   C12 NOW+301 clamped                L,  in=LIMIT+1   -> take LIMIT
//!   C13 no local, future at            null, in=NOW+1e6 -> take LIMIT
//! Plus D01..D04 for decideFor (Repo.loadBookmark-shaped dictionary).
(:test)
module BmMergeT {
    const NOW = 10000;
    const LIMIT = 10300;
    const L = 5000;
    const FAR = 1010000;

    //! Expect `decide` to return exactly `want` (null = keep local).
    function expect(logger as Test.Logger, id as String, localAt as Number?, inAt as Number,
            want as Number?) as Boolean {
        var got = BookmarkMerge.decide(localAt, inAt, NOW);
        var ok = (want == null) ? (got == null) : (got != null && got == want);
        if (!ok) {
            logger.error("FAILED " + id + ": got " + got + " want " + want);
        }
        return ok;
    }
}

(:test)
function testBmMergeC01NoLocal(logger as Test.Logger) as Boolean {
    return BmMergeT.expect(logger, "C01", null, 4000, 4000);
}

(:test)
function testBmMergeC02IncomingNewer(logger as Test.Logger) as Boolean {
    return BmMergeT.expect(logger, "C02", BmMergeT.L, BmMergeT.L + 1, BmMergeT.L + 1);
}

(:test)
function testBmMergeC03EqualKeepsLocal(logger as Test.Logger) as Boolean {
    return BmMergeT.expect(logger, "C03", BmMergeT.L, BmMergeT.L, null);
}

(:test)
function testBmMergeC04OlderKeepsLocal(logger as Test.Logger) as Boolean {
    return BmMergeT.expect(logger, "C04", BmMergeT.L, BmMergeT.L - 1, null);
}

(:test)
function testBmMergeC05FarFutureClampedTaken(logger as Test.Logger) as Boolean {
    return BmMergeT.expect(logger, "C05", BmMergeT.L, BmMergeT.FAR, BmMergeT.LIMIT);
}

(:test)
function testBmMergeC06FarFutureLocalEqualsLimitKeeps(logger as Test.Logger) as Boolean {
    return BmMergeT.expect(logger, "C06", BmMergeT.LIMIT, BmMergeT.FAR, null);
}

(:test)
function testBmMergeC07FarFutureLocalAboveLimitKeeps(logger as Test.Logger) as Boolean {
    return BmMergeT.expect(logger, "C07", BmMergeT.LIMIT + 100, BmMergeT.FAR, null);
}

(:test)
function testBmMergeC08FinishedLocalNewerUnfinishedIncomingTakes(logger as Test.Logger) as Boolean {
    return BmMergeT.expect(logger, "C08", BmMergeT.L, BmMergeT.L + 1000, BmMergeT.L + 1000);
}

(:test)
function testBmMergeC09OlderFinishedIncomingKeeps(logger as Test.Logger) as Boolean {
    return BmMergeT.expect(logger, "C09", BmMergeT.L, BmMergeT.L - 1000, null);
}

(:test)
function testBmMergeC10IdempotentRepeat(logger as Test.Logger) as Boolean {
    var at = BookmarkMerge.decide(BmMergeT.L, BmMergeT.L + 50, BmMergeT.NOW);
    var ok = T.check(logger, at != null && at == BmMergeT.L + 50, "C10 first take");
    return BmMergeT.expect(logger, "C10", at, BmMergeT.L + 50, null) && ok;
}

(:test)
function testBmMergeC11BoundaryNowPlus300(logger as Test.Logger) as Boolean {
    return BmMergeT.expect(logger, "C11", BmMergeT.L, BmMergeT.LIMIT, BmMergeT.LIMIT);
}

(:test)
function testBmMergeC12NowPlus301Clamped(logger as Test.Logger) as Boolean {
    return BmMergeT.expect(logger, "C12", BmMergeT.L, BmMergeT.LIMIT + 1, BmMergeT.LIMIT);
}

(:test)
function testBmMergeC13NoLocalFutureAt(logger as Test.Logger) as Boolean {
    return BmMergeT.expect(logger, "C13", null, BmMergeT.FAR, BmMergeT.LIMIT);
}

//! D01: Repo.loadBookmark-shaped dict (trk/idx/pos/fin/at) behaves like decide with local = at.
(:test)
function testBmMergeD01DecideForRepoShape(logger as Test.Logger) as Boolean {
    var local = { "trk" => "t1", "idx" => 2, "pos" => 120, "fin" => false, "at" => BmMergeT.L } as Dictionary;
    var ok = T.check(logger, BookmarkMerge.decideFor(local, BmMergeT.L, BmMergeT.NOW) == null, "D01 equal keeps");
    var up = BookmarkMerge.decideFor(local, BmMergeT.L + 1, BmMergeT.NOW);
    ok = T.check(logger, up != null && up == BmMergeT.L + 1, "D01 newer takes") && ok;
    var far = BookmarkMerge.decideFor(local, BmMergeT.FAR, BmMergeT.NOW);
    ok = T.check(logger, far != null && far == BmMergeT.LIMIT, "D01 far clamped") && ok;
    return ok;
}

//! D02: null local -> take.
(:test)
function testBmMergeD02DecideForNull(logger as Test.Logger) as Boolean {
    var r = BookmarkMerge.decideFor(null, 4000, BmMergeT.NOW);
    var f = BookmarkMerge.decideFor(null, BmMergeT.FAR, BmMergeT.NOW);
    return T.check(logger, r != null && r == 4000, "D02 null local takes")
        && T.check(logger, f != null && f == BmMergeT.LIMIT, "D02 null local far clamped");
}

//! D03: dict without 'at' -> treated as no local.
(:test)
function testBmMergeD03DecideForMissingAt(logger as Test.Logger) as Boolean {
    var r = BookmarkMerge.decideFor({ "trk" => "t1" } as Dictionary, 4000, BmMergeT.NOW);
    return T.check(logger, r != null && r == 4000, "D03 missing at takes");
}

//! D04: non-numeric 'at' -> treated as no local.
(:test)
function testBmMergeD04DecideForNonNumericAt(logger as Test.Logger) as Boolean {
    var r = BookmarkMerge.decideFor({ "at" => "abc" } as Dictionary, 4000, BmMergeT.NOW);
    var r2 = BookmarkMerge.decideFor({ "at" => null } as Dictionary, 4000, BmMergeT.NOW);
    return T.check(logger, r != null && r == 4000, "D04 string at takes")
        && T.check(logger, r2 != null && r2 == 4000, "D04 null at takes");
}
