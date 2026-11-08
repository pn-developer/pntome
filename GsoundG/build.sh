#!/usr/bin/env bash
# build.sh — build the GsoundG Connect IQ watch app (.prg).
#
# Thin wrapper around `monkeyc`. Autodetects the installed Connect IQ SDK and the
# developer key, so a plain `./build.sh` just works on this machine.
#
# Usage:
#   ./build.sh [--debug] [device] [output.prg]
#
#   --debug     debug build: keeps the (:debug) diagnostics — the watch log stored in
#               Application.Storage and forwarded to the phone on "Refresh stats" (logcat tag
#               Gsound.Watch), plus media-cache snapshots. The watch footer shows "dbg".
#               Without it (default) the build is a RELEASE build (`monkeyc -r`): that code is
#               stripped and only plain println output remains.
#
#   device      Connect IQ device id (default: fenix6spro — the fēnix 6S Pro this project is
#               tested on; a build only runs correctly on the device it was built for).
#               Valid ids come from manifest.xml:
#               fenix6spro fenix6pro fenix6xpro
#               fenix843mm fenix847mm fenix8pro47mm fenix8solar47mm fenix8solar51mm
#   output.prg  output path (default: build/GsoundG-<device>.prg)
#
# Env overrides:
#   CIQ_SDK     SDK dir      (default: autodetected from ~/.Garmin/ConnectIQ)
#   CIQ_DEVKEY  dev key .der (default: ~/.Garmin/developer_key.der)
#
# Examples:
#   ./build.sh                       # release, fenix6spro -> build/GsoundG-fenix6spro.prg
#   ./build.sh --debug               # debug,   fenix6spro -> build/GsoundG-fenix6spro.prg
#   ./build.sh fenix847mm            # fenix 8  -> build/GsoundG-fenix847mm.prg
#   ./build.sh fenix6pro /tmp/g.prg  # explicit output
set -euo pipefail

# Run from the directory this script lives in, so relative paths (monkey.jungle) resolve.
cd "$(dirname "$0")"

# Flags may appear anywhere; everything else is positional (device, output).
FLAVOR="release"
POSITIONAL=()
for arg in "$@"; do
  case "$arg" in
    --debug) FLAVOR="debug" ;;
    --release) FLAVOR="release" ;;
    -h|--help) sed -n '2,/^set -euo/p' "$0" | sed '$d'; exit 0 ;;
    -*) echo "build.sh: unknown option $arg" >&2; exit 2 ;;
    *) POSITIONAL+=("$arg") ;;
  esac
done

DEVICE="${POSITIONAL[0]:-fenix6spro}"
OUTPUT="${POSITIONAL[1]:-build/GsoundG-${DEVICE}.prg}"
JUNGLE="monkey.jungle"

die() { echo "build.sh: $*" >&2; exit 1; }

detect_sdk() {
  if [ -n "${CIQ_SDK:-}" ]; then echo "${CIQ_SDK%/}"; return; fi
  local cfg="$HOME/.Garmin/ConnectIQ/current-sdk.cfg"
  if [ -f "$cfg" ]; then
    local p; p="$(tr -d '[:space:]' < "$cfg")"
    [ -d "$p" ] && { echo "${p%/}"; return; }
  fi
  ls -d "$HOME"/.Garmin/ConnectIQ/Sdks/connectiq-sdk-lin-* 2>/dev/null | sort | tail -1
}

CIQ_DEVKEY="${CIQ_DEVKEY:-$HOME/.Garmin/developer_key.der}"

SDK="$(detect_sdk)"
[ -n "$SDK" ] && [ -d "$SDK" ] || die "Connect IQ SDK not found. Install it with the SDK Manager, or set CIQ_SDK."
[ -x "$SDK/bin/monkeyc" ] || die "monkeyc not found under $SDK/bin"
[ -f "$CIQ_DEVKEY" ] || die "developer key not found at $CIQ_DEVKEY (generate one or set CIQ_DEVKEY)"
[ -f "$JUNGLE" ] || die "$JUNGLE not found (run this from the GsoundG/ directory)"

mkdir -p "$(dirname "$OUTPUT")"

# --- Stamp the git commit into source/Version.mc for this build -----------------------------
# The app shows "v<SEMVER> (<COMMIT>)" in the UI. SEMVER is hand-maintained in Version.mc; the
# COMMIT is injected here from git so the installed .prg always carries the real build commit.
# We edit the file in place, build, then RESTORE it so the working tree isn't left dirty (the
# committed COMMIT value is the "dev" fallback). A trap guarantees the restore even on failure.
VERSION_FILE="source/Version.mc"

# --- Guard: the semantic version must match between Version.mc and manifest.xml -------------
# AppVersion.SEMVER (shown in the watch UI footer) and the manifest's <iq:application version=...>
# (app metadata / store tooling) are two separate literals. Fail the build if they disagree so a
# release bump can never silently update one but not the other. Skipped only when a file is
# absent (e.g. a partial checkout), with a warning rather than a hard stop.
if [ -f "$VERSION_FILE" ] && [ -f "$JUNGLE" ] && [ -f manifest.xml ]; then
  SEMVER_CODE="$(sed -n 's/.*const SEMVER = "\([^"]*\)".*/\1/p' "$VERSION_FILE" | head -1)"
  # Match the APPLICATION version (a dotted semver like 0.1.0), NOT the manifest-schema
  # `version="3"` on <iq:manifest>. Requiring a dot (N.N...) excludes the single-digit schema
  # version regardless of attribute order.
  SEMVER_MANIFEST="$(grep -o 'version="[0-9][0-9]*\.[0-9][^"]*"' manifest.xml | head -1 | sed 's/version="\([^"]*\)"/\1/')"
  if [ -z "$SEMVER_CODE" ]; then
    die "could not read AppVersion.SEMVER from $VERSION_FILE"
  fi
  if [ -z "$SEMVER_MANIFEST" ]; then
    die "manifest.xml has no <iq:application version=\"...\"> attribute (add one matching $VERSION_FILE's SEMVER=$SEMVER_CODE)"
  fi
  if [ "$SEMVER_CODE" != "$SEMVER_MANIFEST" ]; then
    die "version mismatch: Version.mc SEMVER=$SEMVER_CODE but manifest.xml version=$SEMVER_MANIFEST — bump both together."
  fi
fi

VERSION_BACKUP=""
restore_version() {
  if [ -n "$VERSION_BACKUP" ] && [ -f "$VERSION_BACKUP" ]; then
    mv -f "$VERSION_BACKUP" "$VERSION_FILE"
  fi
}
if [ -f "$VERSION_FILE" ]; then
  GIT_COMMIT="$(git rev-parse --short HEAD 2>/dev/null || echo dev)"
  if git rev-parse --git-dir >/dev/null 2>&1 && ! git diff --quiet 2>/dev/null; then
    GIT_COMMIT="${GIT_COMMIT}-dirty"
  fi
  VERSION_BACKUP="$(mktemp)"
  cp "$VERSION_FILE" "$VERSION_BACKUP"
  trap restore_version EXIT
  # Replace the COMMIT constant value with the resolved git commit.
  sed -i "s/const COMMIT = \"[^\"]*\";/const COMMIT = \"${GIT_COMMIT}\";/" "$VERSION_FILE"
  echo "build.sh: version $(sed -n 's/.*const SEMVER = "\([^"]*\)".*/\1/p' "$VERSION_FILE") (${GIT_COMMIT})"
fi

echo "build.sh: SDK    $SDK"
echo "build.sh: key    $CIQ_DEVKEY"
echo "build.sh: device $DEVICE"
echo "build.sh: output $OUTPUT"
echo "build.sh: flavor $FLAVOR"

MONKEYC_FLAGS=()
if [ "$FLAVOR" = "release" ]; then
  MONKEYC_FLAGS+=(-r)   # release: strips (:debug)-annotated code
fi

"$SDK/bin/monkeyc" -f "$JUNGLE" -d "$DEVICE" -y "$CIQ_DEVKEY" -o "$OUTPUT" "${MONKEYC_FLAGS[@]}"

echo "build.sh: built $OUTPUT ($FLAVOR)"
