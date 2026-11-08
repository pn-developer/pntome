package dev.pntome.app.net.server

/**
 * Pure validation/sanitization for the `/media/{trackId}` path segment (design §8: "Validate and
 * sanitize `trackId` path segments (no traversal; IDs are opaque hashes)").
 *
 * ## Threat model
 * `trackId` arrives from the network and is therefore untrusted. In PNTome a track id is an
 * **opaque hash** produced by [dev.pntome.app.domain.scan.TrackIdHasher]
 * (`sha1(path|size|lastModified)` → lowercase hex). The server already resists path traversal
 * structurally: [MediaSource.openTrack] looks the id up as a **map key** against the current
 * manifest and never concatenates it into a filesystem path, so a value like `../../etc/passwd`
 * simply misses the map and yields `404`.
 *
 * This validator makes that safety **explicit and testable**: a syntactically invalid id (one
 * that could not be a legitimate opaque hash — empty, over-long, or containing a path separator,
 * traversal sequence, or whitespace) is rejected up front so it never reaches the lookup. The
 * route maps a rejected id to `404`, consistent with the "unknown id → 404" rule (Req 5.5) and
 * without touching storage.
 *
 * ## Accepted shape
 * An id is valid iff it is non-empty, at most [MAX_LENGTH] characters, and composed solely of the
 * URL-safe id alphabet `[A-Za-z0-9_-]`. This admits SHA-1 hex (the current scheme) and the
 * Base64Url family, while excluding `/`, `\`, `.` (so `..` traversal and dotted paths cannot
 * pass), spaces, control characters, and NUL.
 */
object TrackId {

    /**
     * Upper bound on a valid id length. Comfortably above any real hash (SHA-1 hex = 40,
     * SHA-256 hex = 64) while bounding work and rejecting absurd input early.
     */
    const val MAX_LENGTH: Int = 128

    /** The only characters a legitimate opaque id may contain (URL-safe; no path metacharacters). */
    private val VALID = Regex("^[A-Za-z0-9_-]+$")

    /**
     * Whether [id] is a syntactically valid opaque track id safe to look up (design §8).
     *
     * Returns `false` for `null`, empty, longer than [MAX_LENGTH], or any id containing a
     * character outside `[A-Za-z0-9_-]` — which rejects path separators (`/`, `\`), traversal
     * dots (`.`, so `..`), whitespace, and control/NUL bytes before the manifest lookup.
     *
     * @param id the raw `{trackId}` path parameter (already URL-decoded by the server), or `null`.
     * @return `true` iff [id] could be a legitimate opaque hash and is safe to resolve.
     */
    fun isValidTrackId(id: String?): Boolean {
        if (id == null || id.isEmpty() || id.length > MAX_LENGTH) return false
        return VALID.matches(id)
    }
}
