import Toybox.Lang;

//! AppVersion — the GsoundG app version, surfaced in the watch UI.
//!
//! [SEMVER] is the hand-maintained semantic version (bump it on releases).
//! [COMMIT] is the short git commit the build was produced from; `build.sh`
//! rewrites the COMMIT line from `git rev-parse --short HEAD` at build time (and
//! restores this file afterward so the working tree isn't dirtied). The value
//! committed here is the fallback used when the app is built directly with
//! `monkeyc` (no build.sh) or outside a git checkout — kept as "dev" so it is
//! obviously a non-release build.
module AppVersion {

    //! Semantic version, maintained by hand (MAJOR.MINOR.PATCH). Bump on release.
    const SEMVER = "0.1.0";

    //! Short git commit hash the build was cut from. Overwritten by build.sh at
    //! build time; "dev" means a direct/non-scripted build.
    const COMMIT = "dev";

    //! Human-readable combined version string, e.g. "v0.1.0 (e5c511d)", with a " dbg" suffix on
    //! debug builds (`./build.sh --debug`, see DebugLog). Used by the UI footers. Pure string
    //! assembly — no SDK calls.
    function display() as String {
        return "v" + SEMVER + " (" + COMMIT + ")" + (DebugLog.isDebugBuild() ? " dbg" : "");
    }
}
