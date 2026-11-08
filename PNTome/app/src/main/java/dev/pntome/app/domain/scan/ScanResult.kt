package dev.pntome.app.domain.scan

import dev.pntome.app.domain.model.Track

/**
 * Terminal result of a completed folder scan (task 5.2).
 *
 * [dev.pntome.app.domain.model.ScanProgress] is intentionally a lightweight, framework-free
 * progress signal for the UI (Req 3.6) and does not carry the discovered tracks, so the
 * scanner delivers the full outcome here: the eligible [tracks] (Req 3.2) and the number of
 * files skipped because they were unsupported (Req 3.3).
 *
 * Pure domain type — no Android dependencies, so orchestration/caching layers (task 5.3) and
 * tests can consume it directly.
 *
 * @property tracks the eligible tracks discovered, each with a stable id (Req 3.5) and
 *   metadata filled in with a filename fallback where missing (Req 3.4).
 * @property skippedCount number of files skipped because they were an unsupported/disabled
 *   format (Req 3.3).
 */
data class ScanResult(
    val tracks: List<Track>,
    val skippedCount: Int
)
