#!/usr/bin/env bash
# run_in_simulator.sh — build (or take) a GsoundG .prg, start the Connect IQ
# simulator in the Ubuntu 22.04 container, and attach the app via monkeydo.
#
# The simulator needs libwebkit2gtk-4.0, which modern hosts (Ubuntu 25/26,
# current Manjaro/Arch) no longer ship. This script runs the GUI + monkeydo
# inside the container set up by sim/setup_container.sh; the build still runs
# natively on the host.
#
# Usage:
#   ./run_in_simulator.sh [device] [app.prg]
#
#   device    Connect IQ device id (default: fenix6spro). Must be one of the
#             ids in manifest.xml.
#   app.prg   a prebuilt .prg to run. If omitted, the script builds one with
#             ./build.sh for the chosen device.
#
# Options:
#   --build-only   build the .prg, do not start the simulator.
#   --no-build     require an existing .prg (error if missing); never build.
#   --keep         leave the simulator running after monkeydo returns
#                  (default: leave it running so you can re-run quickly).
#   --stop         stop the simulator and exit.
#   -h|--help      show this help.
#
# Examples:
#   ./run_in_simulator.sh                      # build fenix6spro, run it
#   ./run_in_simulator.sh fenix847mm           # build + run for fenix 8
#   ./run_in_simulator.sh fenix6pro /tmp/g.prg # run an existing prg
#   ./run_in_simulator.sh --stop               # stop the simulator
set -euo pipefail

cd "$(dirname "$0")"
. "./sim/common.sh"

DEVICE="fenix6spro"
PRG=""
BUILD_ONLY=0
NO_BUILD=0

usage() { sed -n '2,40p' "$0" | sed 's/^# \{0,1\}//'; exit "${1:-0}"; }

# --- parse args ------------------------------------------------------------
while [ $# -gt 0 ]; do
  case "$1" in
    --build-only) BUILD_ONLY=1;;
    --no-build)   NO_BUILD=1;;
    --keep)       :;;            # kept for clarity; leaving sim up is the default
    --stop)
      require_container
      cexec bash -c "pkill -x simulator 2>/dev/null; true"
      info "simulator stopped"; exit 0;;
    -h|--help) usage 0;;
    -*) die "unknown option '$1' (see --help)";;
    *.prg) PRG="$1";;
    *) DEVICE="$1";;
  esac
  shift
done

# --- build if needed -------------------------------------------------------
if [ -z "$PRG" ]; then
  PRG="build/GsoundG-${DEVICE}.prg"
  if [ "$NO_BUILD" = 1 ]; then
    [ -f "$PRG" ] || die "--no-build set but $PRG does not exist"
  else
    info "building $PRG for $DEVICE"
    # Debug flavor: simulator runs are for diagnosing, keep the (:debug) diagnostics.
    ./build.sh --debug "$DEVICE" "$PRG"
  fi
else
  [ -f "$PRG" ] || die "prg not found: $PRG"
fi

[ "$BUILD_ONLY" = 1 ] && { info "build-only: $PRG"; exit 0; }

# monkeydo runs inside the container with a different CWD, so pass an absolute
# path. ($HOME is shared into the container, so an absolute host path resolves.)
PRG="$(cd "$(dirname "$PRG")" && pwd)/$(basename "$PRG")"

# --- ensure the container is ready -----------------------------------------
require_engine
# If the container exists but is merely STOPPED, start it (cheap) before probing
# — a stopped container makes the cexec webkit probe fail and would otherwise
# trigger a full (and here needless) re-provision. Only fall back to the setup
# script when the container is missing or genuinely lacks webkit 4.0.
container_exists && ! container_running && start_container || true
if ! ( container_exists && container_running && \
       cexec bash -c "ldconfig -p 2>/dev/null | grep -q libwebkit2gtk-4.0" ); then
  info "simulator container not ready — running sim/setup_container.sh"
  ./sim/setup_container.sh
fi

SDK="$(detect_sdk)"
[ -n "$SDK" ] && [ -d "$SDK" ] || die "Connect IQ SDK not found (set CIQ_SDK)"

# --- start the simulator (if not already up) -------------------------------
allow_local_x
if [ -z "$(sim_pid)" ]; then
  info "starting simulator GUI in container '$CIQ_CONTAINER'"
  cexec_d bash -c "rm -f '$CIQ_SIM_LOG'; exec '$SDK/bin/simulator' >'$CIQ_SIM_LOG' 2>&1 < /dev/null" >/dev/null
  for _ in $(seq 1 20); do
    [ -n "$(sim_pid)" ] && break
    sleep 1
  done
  [ -n "$(sim_pid)" ] || { info "simulator did not start; last log lines:"; cexec bash -c "tail -20 '$CIQ_SIM_LOG'" || true; exit 1; }
  # Give the GUI a moment to open its listening socket before monkeydo connects.
  sleep 2
else
  info "simulator already running (pid $(sim_pid))"
fi

# --- attach the app --------------------------------------------------------
info "monkeydo $PRG ($DEVICE)"
echo "ciq: --- app output (Ctrl-C to detach; simulator stays up) ---" >&2
# monkeydo stays attached while the app runs and prints app output / crashes.
cexec bash -c "'$SDK/bin/monkeydo' '$PRG' '$DEVICE'"
