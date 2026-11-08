import Toybox.Lang;
//! BookmarkMerge — the bookmark merge rule (spec gsound-player PL-6, design §8). Pure.
//!
//! The newer `at` wins; equal or older keeps the local bookmark. `fin` is not special: it only
//! wins by being newer. Incoming `at` is clamped to now + 300 s against bad future values.
//!
//! Shared decision table (identical in GsoundA .../bookmarks/BookmarkMerge.kt); all times are
//! epoch seconds (Time.now().value() here, same as Repo.saveBookmark's "at"),
//! inAt = min(incomingAt, now + 300):
//!
//!   | local at | incoming at       | finished flags   | result                          |
//!   |----------|-------------------|------------------|---------------------------------|
//!   | none     | any               | any              | take, effective at = inAt       |
//!   | L        | > L               | any              | take (newer wins)               |
//!   | L        | == L              | any              | keep local (equal keeps local)  |
//!   | L        | < L               | any              | keep local                      |
//!   | L        | far future        | any              | clamp to now+300, then as above |
//!   | L        | newer, local fin  | incoming not fin | take (finished is not special)  |
//!   | L        | older, inc. fin   | local not fin    | keep local (fin only wins newer)|
//!   | L        | repeat of a taken | any              | keep (idempotent: taken at = L) |
module BookmarkMerge {
    //! Maximum tolerated lead of an incoming `at` over the local clock, seconds.
    const FUTURE_CLAMP_SEC = 300;

    //! Core decision. `localAt` is null when there is no local bookmark.
    //! Returns the effective (clamped) `at` to store when the incoming bookmark is taken,
    //! or null to keep the local one.
    function decide(localAt as Number?, incomingAt as Number, now as Number) as Number? {
        var limit = now + FUTURE_CLAMP_SEC;
        var inAt = (incomingAt > limit) ? limit : incomingAt;
        if (localAt == null || inAt > localAt) {
            return inAt;
        }
        return null;
    }

    //! For the stored bookmark of `folder` (Repo.loadBookmark shape, may be null) and an incoming
    //! `at`: the effective `at` to store when the incoming bookmark should be applied, else null.
    function decideFor(local as Dictionary?, incomingAt as Number, now as Number) as Number? {
        var localAt = null;
        if (local != null) {
            var v = local.get("at");
            if (v instanceof Number) {
                localAt = v as Number;
            }
        }
        return decide(localAt, incomingAt, now);
    }
}
