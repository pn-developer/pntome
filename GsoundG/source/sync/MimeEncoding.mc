import Toybox.Lang;
import Toybox.Media;

//! MimeEncoding — map a manifest track's `mime` to a `Media.ENCODING_*` value
//! (design.md §8.3, §14; Req 4.2).
//!
//! Task 7.2 (this module): the downloader (task 7.3) passes a `:mediaEncoding`
//! option to `Communications.makeWebRequest` telling the system how the audio
//! bytes are encoded. That value comes from `track.mime` in the manifest. This
//! module is the pure, side-effect-free lookup that turns a wire MIME string into
//! the matching `Media.ENCODING_*` enum, or into `null` when the format is not one
//! the installed SDK can decode — so the downloader can SKIP + COUNT an unsupported
//! track instead of crashing (design §14; Req 4.2).
//!
//! Encoding constants are read from the installed SDK, not guessed. The
//! `Toybox.Media.Encoding` enum in connectiq-sdk-lin-9.2.0 defines exactly:
//!   ENCODING_INVALID (0), ENCODING_ADTS (1), ENCODING_MP3 (2),
//!   ENCODING_M4A (3), ENCODING_WAV (4).
//! There is NO FLAC (and no distinct raw-AAC-vs-ADTS) constant, so any MIME the SDK
//! cannot represent — notably FLAC, which GsoundA can serve when its flag is on —
//! maps to `null` (skip + count), never a bogus constant. `ENCODING_INVALID` is the
//! SDK's own sentinel and is intentionally NOT returned; callers test for `null`.
//!
//! The MIME keys cover every spelling GsoundA's scanner recognises
//! (GsoundA FormatFilter.SUPPORTED_MIME_TYPES): mp3 is always served; m4a/aac/wav
//! are served only when their optional flag is enabled; flac likewise but has no
//! watch encoding. GsoundA copies the raw SAF-reported `mime` into the manifest
//! verbatim (ManifestBuilder), so the watch can receive any synonymous spelling and
//! possibly a parameterised value ("audio/mpeg; charset=binary"); `encodingFor`
//! normalises defensively — strip any `;` parameters, trim, lowercase — mirroring
//! FormatFilter.formatFromMime so both ends agree on the canonical form.
//!
//! MIME → encoding table (everything else → null):
//!   audio/mpeg, audio/mp3                    -> Media.ENCODING_MP3
//!   audio/mp4,  audio/x-m4a                  -> Media.ENCODING_M4A  (M4A container)
//!   audio/aac                                -> Media.ENCODING_ADTS (raw AAC/ADTS)
//!   audio/wav,  audio/x-wav, audio/wave      -> Media.ENCODING_WAV
//!   audio/flac, audio/x-flac                 -> null  (no SDK encoding; skip)
//!   null / empty / anything unmapped         -> null  (skip + count)
//!
//! Pure and side-effect-free: no logging, no storage, no I/O.
module MimeEncoding {

    //! Resolve the `Media.ENCODING_*` value for a manifest track's MIME string.
    //!
    //! The MIME is normalised defensively before lookup (parameters after `;`
    //! dropped, trimmed, lowercased) so parameterised/odd-cased values from the SAF
    //! provider still resolve. Returns one of the SDK's `Media.ENCODING_*` audio
    //! constants for a supported format, or `null` for a null/empty/unknown MIME or
    //! a format with no SDK encoding (e.g. FLAC) — the downloader treats `null` as
    //! "skip this track and count it" rather than a fatal error (design §8.3, §14).
    //!
    //! @param mime the track's declared MIME type (may be null, empty, or carry
    //!             parameters like "audio/mpeg; charset=binary").
    //! @return the matching `Media.ENCODING_*` Number, or `null` if unsupported.
    function encodingFor(mime as String?) as Number? {
        if (mime == null) {
            return null;
        }

        // Normalise: drop any ";" parameters, trim, lowercase — mirrors GsoundA's
        // FormatFilter.formatFromMime so both ends canonicalise identically.
        var base = mime;
        var semi = mime.find(";");
        if (semi != null) {
            var cut = mime.substring(0, semi);
            base = (cut == null) ? "" : cut;
        }
        var norm = trim(base).toLower();
        if (norm.equals("")) {
            return null;
        }

        if (norm.equals("audio/mpeg") || norm.equals("audio/mp3")) {
            return Media.ENCODING_MP3;
        }
        if (norm.equals("audio/mp4") || norm.equals("audio/x-m4a")) {
            return Media.ENCODING_M4A;
        }
        if (norm.equals("audio/aac")) {
            return Media.ENCODING_ADTS;
        }
        if (norm.equals("audio/wav") || norm.equals("audio/x-wav") || norm.equals("audio/wave")) {
            return Media.ENCODING_WAV;
        }

        // audio/flac, audio/x-flac, and every other MIME: no SDK encoding → skip.
        return null;
    }

    //! Trim leading/trailing ASCII whitespace. Monkey C `String` has no built-in
    //! trim, so we do it here to keep `encodingFor` self-contained and pure.
    function trim(s as String) as String {
        var chars = s.toCharArray();
        var n = chars.size();
        var start = 0;
        while (start < n && isSpace(chars[start])) {
            start++;
        }
        var end = n;
        while (end > start && isSpace(chars[end - 1])) {
            end--;
        }
        if (start == 0 && end == n) {
            return s;
        }
        var cut = s.substring(start, end);
        return (cut == null) ? "" : cut;
    }

    //! Whether a char is ASCII whitespace (space, tab, CR, LF).
    function isSpace(c as Char) as Boolean {
        return c == ' ' || c == '\t' || c == '\r' || c == '\n';
    }
}
