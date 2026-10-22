#!/usr/bin/env bash
# build.sh — build the GsoundG Connect IQ watch app (.prg).
#
# Thin wrapper around `monkeyc`. Autodetects the installed Connect IQ SDK and the
# developer key, so a plain `./build.sh` just works on this machine.
#
# Usage:
#   ./build.sh [device] [output.prg]
#
#   device      Connect IQ device id (default: fenix6pro).
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
#   ./build.sh                       # fenix6pro -> build/GsoundG-fenix6pro.prg
#   ./build.sh fenix847mm            # fenix 8  -> build/GsoundG-fenix847mm.prg
#   ./build.sh fenix6pro /tmp/g.prg  # explicit output
set -euo pipefail

# Run from the directory this script lives in, so relative paths (monkey.jungle) resolve.
cd "$(dirname "$0")"

DEVICE="${1:-fenix6pro}"
OUTPUT="${2:-build/GsoundG-${DEVICE}.prg}"
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

echo "build.sh: SDK    $SDK"
echo "build.sh: key    $CIQ_DEVKEY"
echo "build.sh: device $DEVICE"
echo "build.sh: output $OUTPUT"

"$SDK/bin/monkeyc" -f "$JUNGLE" -d "$DEVICE" -y "$CIQ_DEVKEY" -o "$OUTPUT"

echo "build.sh: built $OUTPUT"
