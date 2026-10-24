import Toybox.Lang;
import Toybox.Time;

//! StatsStore — the single source of truth for the watch-side library stats
//! (design.md §8.4, §10; Req 5.1, 5.4).
//!
//! Task 8.1 (this module): stats are three numbers derived from what is actually
//! persisted — `fileCount` (the track-index size), `folderCount` (the distinct-
//! folders list size), and `lastSyncAt` (the epoch-seconds timestamp of the last
//! completed sync). Before task 8 these were computed + persisted INLINE inside
//! Downloader.updateAndSendStats(); that helper was written explicitly so this
//! task could lift its body here and have the Downloader call us. This module is
//! now the ONE place that computes and persists stats; the Downloader keeps only
//! the STATS *transmit* (it owns the pairingId + control channel). Task 8.2 will
//! generalize STATS sending and can reuse these same functions.
//!
//! Stored shape (design §10 — `stats` → { fileCount, folderCount, lastSyncAt }):
//! a small flat dict under Repo's `stats` key whose keys are the SAME
//! `Messages.FIELD_FILE_COUNT` / `FIELD_FOLDER_COUNT` / `FIELD_LAST_SYNC_AT`
//! spellings the STATS message uses, so StatsView (task 8.3) and the PING→STATS
//! path (task 8.2) read back exactly what a sync wrote and the shapes never drift.
//!
//! Everything here is DEFENSIVE (never throws — Req 8.5) and NON-LOGGING (never
//! prints; Req 7.3): a missing/malformed stored value degrades to a sensible
//! default (0 counts, absent lastSyncAt) rather than crashing a launch or a sync.
module StatsStore {

    //! Recompute the current library stats from what Repo has persisted, PERSIST
    //! them via Repo.saveStats, and return the computed fields (design §8.4). This
    //! is called at the end of a successful sync (hence `lastSyncAt = now`), and is
    //! the authoritative compute+persist path the Downloader delegates to.
    //!
    //! Fields (design §8.4):
    //!   - fileCount   = Repo.loadIndex().size()   (number of stored tracks)
    //!   - folderCount = Repo.loadFolders().size() (distinct folder names)
    //!   - lastSyncAt  = Time.now().value()        (current epoch seconds)
    //! `lastSyncAt` is always non-null here: this runs immediately after a sync, so
    //! "now" is the correct last-sync time. (A load() that predates the first sync
    //! may legitimately have NO lastSyncAt — see load()/lastSyncAtOf().)
    //!
    //! The persisted dict uses the Messages FIELD_* keys so the stored shape and
    //! the STATS wire shape agree (StatsView / PING-STATS read it back). Wrapped so
    //! a storage or Repo failure can never crash sync completion (Req 8.5); on such
    //! a failure the returned dict still carries best-effort values.
    //!
    //! @return { fileCount, folderCount, lastSyncAt } keyed by the Messages FIELD_*
    //!         constants — the exact fields the caller needs to build a STATS msg.
    function updateFromRepo() as Dictionary {
        var fileCount = 0;
        var folderCount = 0;
        var lastSyncAt = Time.now().value();

        try {
            fileCount = Repo.loadIndex().size();
            folderCount = Repo.loadFolders().size();

            // Persist (design §8.4). Keys match the STATS field spelling so a later
            // load()/StatsView/PING-STATS read agrees on shape.
            Repo.saveStats({
                Messages.FIELD_FILE_COUNT   => fileCount,
                Messages.FIELD_FOLDER_COUNT => folderCount,
                Messages.FIELD_LAST_SYNC_AT => lastSyncAt
            });
        } catch (ex) {
            // Never crash the caller (Req 8.5). Return best-effort values; a failed
            // persist just means the next successful sync rewrites the stats.
        }

        return {
            Messages.FIELD_FILE_COUNT   => fileCount,
            Messages.FIELD_FOLDER_COUNT => folderCount,
            Messages.FIELD_LAST_SYNC_AT => lastSyncAt
        };
    }

    //! Load the persisted stats dict, or null when none has been written yet
    //! (before the first sync). The returned dict (when present) is in the stored
    //! shape — read fields out of it with fileCountOf / folderCountOf /
    //! lastSyncAtOf, which coerce defensively. Note a loaded dict may legitimately
    //! have NO lastSyncAt (the pre-first-sync case StatsView 8.3 shows as an empty
    //! state), whereas updateFromRepo always sets one. Wrapped so a malformed
    //! stored value degrades to null rather than crashing a launch (Req 7.2, 8.5).
    function load() as Dictionary? {
        try {
            return Repo.loadStats();
        } catch (ex) {
            return null;
        }
    }

    //! Read `fileCount` out of a stored/returned stats dict, defaulting to 0 when
    //! the dict is null or the field is absent / not a Number. Used by StatsView
    //! (8.3) and the STATS send (8.2) so they never trust the stored shape blindly.
    function fileCountOf(stats as Dictionary?) as Number {
        return numberField(stats, Messages.FIELD_FILE_COUNT);
    }

    //! Read `folderCount` out of a stored/returned stats dict, defaulting to 0 when
    //! the dict is null or the field is absent / not a Number.
    function folderCountOf(stats as Dictionary?) as Number {
        return numberField(stats, Messages.FIELD_FOLDER_COUNT);
    }

    //! Read `lastSyncAt` (epoch seconds) out of a stored/returned stats dict, or
    //! null when the dict is null or the field is absent / not a Number. Null is a
    //! MEANINGFUL result here — it is the "never synced yet" state StatsView (8.3)
    //! renders as empty, and the value Messages.buildStats omits from a STATS msg.
    function lastSyncAtOf(stats as Dictionary?) as Number? {
        if (stats == null) {
            return null;
        }
        var value = stats.get(Messages.FIELD_LAST_SYNC_AT);
        return (value instanceof Number) ? (value as Number) : null;
    }

    //! Coerce a Number field out of a stats dict defensively, defaulting to 0.
    //! Shared by fileCountOf / folderCountOf so a null dict or a non-Number value
    //! can never crash a caller (Req 8.5).
    function numberField(stats as Dictionary?, key as String) as Number {
        if (stats == null) {
            return 0;
        }
        var value = stats.get(key);
        return (value instanceof Number) ? (value as Number) : 0;
    }
}
