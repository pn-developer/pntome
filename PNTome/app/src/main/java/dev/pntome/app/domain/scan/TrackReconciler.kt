package dev.gsound.a.domain.scan

import dev.gsound.a.domain.model.Track

/**
 * Pure reconcile/aggregate logic for caching a folder scan in Room (task 5.3), extracted from
 * [dev.gsound.a.domain.folders.ScanAndCacheUseCase] so the diff is unit-testable on the JVM
 * without Android or a database (consistent with [TrackIdHasher]/[ScanPaths]).
 *
 * ## Incremental rescan by stable id (Req 3.5, design §5.2/§7)
 * A [Track.id] is `sha1(relativePath | sizeBytes | lastModified)` (see [TrackIdHasher]), so an
 * unchanged file always yields the same id across scans while any change to its path, size, or
 * modification time yields a new id. That makes reconciliation a plain set-diff on ids rather
 * than a field-by-field comparison:
 *
 * - **unchanged** files appear in both the cached and freshly-scanned sets (same id) — no churn,
 * - **new/changed** files appear only in the scan (a changed file is a *new* id because its
 *   lastModified/size changed, so it is upserted and its stale id is removed), and
 * - **deleted** files appear only in the cache (stale ids) — removed.
 *
 * The result therefore mirrors the current folder contents exactly: upsert everything the scan
 * found, delete every previously-cached id the scan no longer reports.
 *
 * ## Aggregates (Req 2.1)
 * [trackCount] and [totalBytes] are computed from the freshly-scanned tracks so the owning
 * folder row reflects the eligible tracks that are now cached, which the folder list (task 4.2)
 * shows.
 */
object TrackReconciler {

    /**
     * The outcome of diffing a folder's cached tracks against a fresh scan.
     *
     * @property toUpsert every freshly-scanned track; written to the cache. New and changed
     *   tracks are genuinely new rows (keyed by id); unchanged tracks upsert over themselves,
     *   which is a cheap no-op that keeps the write path uniform.
     * @property idsToRemove ids present in the cache but absent from the scan — files deleted or
     *   changed (a changed file's old id is stale) since the last scan. These rows are deleted.
     * @property addedCount freshly-scanned tracks whose id was not already cached — genuinely
     *   new or changed files (a changed file carries a new id). Distinct from [toUpsert] size,
     *   which also counts unchanged files that merely re-upsert over themselves.
     * @property removedCount size of [idsToRemove]; stale rows deleted.
     * @property trackCount number of eligible tracks now cached for the folder (= [toUpsert] size).
     * @property totalBytes sum of [Track.sizeBytes] across the freshly-scanned tracks.
     */
    data class Reconciliation(
        val toUpsert: List<Track>,
        val idsToRemove: List<String>,
        val addedCount: Int,
        val removedCount: Int,
        val trackCount: Int,
        val totalBytes: Long
    )

    /**
     * Diff [cached] against [scanned] for a single folder and compute the aggregates.
     *
     * Only ids are compared (Req 3.5): the id already encodes path/size/lastModified, so two
     * tracks sharing an id are, by construction, the same unchanged file. Ordering of the inputs
     * does not matter; [idsToRemove] preserves the order the stale ids appear in [cached] for
     * deterministic, test-friendly output.
     *
     * @param cached the tracks currently persisted for the folder.
     * @param scanned the tracks the latest scan discovered for the folder.
     */
    fun reconcile(cached: List<Track>, scanned: List<Track>): Reconciliation {
        val cachedIds = cached.mapTo(HashSet(cached.size)) { it.id }
        val scannedIds = scanned.mapTo(HashSet(scanned.size)) { it.id }

        val idsToRemove = cached.asSequence()
            .map { it.id }
            .filter { it !in scannedIds }
            .distinct()
            .toList()

        // New/changed files: a scanned id not previously cached (a changed file carries a new
        // id, so it counts as added here while its old id shows up in idsToRemove).
        val addedCount = scanned.asSequence()
            .map { it.id }
            .filter { it !in cachedIds }
            .distinct()
            .count()

        return Reconciliation(
            toUpsert = scanned,
            idsToRemove = idsToRemove,
            addedCount = addedCount,
            removedCount = idsToRemove.size,
            trackCount = scanned.size,
            totalBytes = scanned.sumOf { it.sizeBytes }
        )
    }
}
