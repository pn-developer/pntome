#!/usr/bin/env bash
# ciq-sim.sh — run the Garmin Connect IQ simulator + monkeydo from the HOST,
# executing the GUI binaries inside a persistent Ubuntu 22.04 podman/distrobox
# container that has the libwebkit2gtk-4.0 stack the simulator needs.
#
# Why: the host (Ubuntu 26) only ships libwebkit2gtk-4.1; the Connect IQ
# simulator hard-links libwebkit2gtk-4.0.so.37. The container provides 4.0
# while sharing $HOME (so the SDK at ~/.Garmin and the dev key are visible)
# and DISPLAY (so the GUI renders to the host desktop).
#
# Usage:
#   ciq-sim.sh start                      # launch the simulator GUI (detached)
#   ciq-sim.sh run <app.prg> <device> [monkeydo args]  # e.g. -t for (:test) unit tests
#   ciq-sim.sh build <jungle> <device> <out.prg>   # build on host (no container)
#   ciq-sim.sh test <jungle> <device>     # build + start + run, one shot
#   ciq-sim.sh stop                       # kill the simulator
#   ciq-sim.sh status                     # show sim/container state
#   ciq-sim.sh log                        # tail the simulator log
#
# Env overrides:
#   CIQ_CONTAINER   container name            (default: ciq2204)
#   CIQ_SDK         SDK dir                   (default: autodetected newest under ~/.Garmin/ConnectIQ/Sdks)
#   CIQ_DEVKEY      developer key .der        (default: ~/.Garmin/developer_key.der)
set -euo pipefail

CIQ_CONTAINER="${CIQ_CONTAINER:-ciq2204}"
CIQ_DEVKEY="${CIQ_DEVKEY:-$HOME/.Garmin/developer_key.der}"
SIM_LOG="/tmp/ciq_sim.log"

die() { echo "ciq-sim: $*" >&2; exit 1; }

detect_sdk() {
  if [ -n "${CIQ_SDK:-}" ]; then echo "$CIQ_SDK"; return; fi
  local cfg="$HOME/.Garmin/ConnectIQ/current-sdk.cfg"
  if [ -f "$cfg" ]; then
    local p; p="$(tr -d '[:space:]' < "$cfg")"
    [ -d "$p" ] && { echo "${p%/}"; return; }
  fi
  ls -d "$HOME"/.Garmin/ConnectIQ/Sdks/connectiq-sdk-lin-* 2>/dev/null | sort | tail -1
}

detect_xauth() {
  # Wayland/mutter Xwayland cookie first, then classic ~/.Xauthority.
  local x; x="$(ls -1 /run/user/$(id -u)/.mutter-Xwaylandauth.* 2>/dev/null | head -1 || true)"
  [ -n "$x" ] && { echo "$x"; return; }
  [ -n "${XAUTHORITY:-}" ] && { echo "$XAUTHORITY"; return; }
  echo "$HOME/.Xauthority"
}

require_container() {
  podman ps --filter "name=^${CIQ_CONTAINER}$" --format '{{.Names}}' | grep -q "$CIQ_CONTAINER" \
    || die "container '$CIQ_CONTAINER' is not running (start it with: distrobox enter $CIQ_CONTAINER -- true)"
}

# podman exec with a clean env + X access. LD_LIBRARY_PATH is blanked so a
# host snap path can't leak in (causes LIBSYSTEMD_254 / nohup failures).
cexec() {
  local xauth; xauth="$(detect_xauth)"
  podman exec -e DISPLAY="${DISPLAY:-:0}" -e XAUTHORITY="$xauth" -e LD_LIBRARY_PATH= \
    "$CIQ_CONTAINER" "$@"
}
cexec_d() {
  local xauth; xauth="$(detect_xauth)"
  podman exec -d -e DISPLAY="${DISPLAY:-:0}" -e XAUTHORITY="$xauth" -e LD_LIBRARY_PATH= \
    "$CIQ_CONTAINER" "$@"
}

sim_pid() { cexec bash -c "pgrep -x simulator | head -1" 2>/dev/null || true; }

cmd_build() {
  local jungle="$1" device="$2" out="$3"
  local sdk; sdk="$(detect_sdk)"; [ -d "$sdk" ] || die "SDK not found"
  [ -f "$CIQ_DEVKEY" ] || die "developer key not found at $CIQ_DEVKEY"
  echo "ciq-sim: building $jungle ($device) -> $out"
  "$sdk/bin/monkeyc" -f "$jungle" -d "$device" -o "$out" -y "$CIQ_DEVKEY"
}

cmd_start() {
  require_container
  local sdk; sdk="$(detect_sdk)"; [ -d "$sdk" ] || die "SDK not found"
  # allow local X clients (host side)
  command -v xhost >/dev/null 2>&1 && xhost +local: >/dev/null 2>&1 || true
  if [ -n "$(sim_pid)" ]; then echo "ciq-sim: simulator already running (pid $(sim_pid))"; return; fi
  cexec_d bash -c "rm -f '$SIM_LOG'; exec '$sdk/bin/simulator' >'$SIM_LOG' 2>&1 < /dev/null" >/dev/null
  # wait up to ~15s for the process to come up
  for i in $(seq 1 15); do
    [ -n "$(sim_pid)" ] && { echo "ciq-sim: simulator started (pid $(sim_pid))"; return; }
    sleep 1
  done
  echo "ciq-sim: simulator did not start; log:"; cexec bash -c "tail -20 '$SIM_LOG'" || true
  return 1
}

cmd_run() {
  local prg="$1" device="$2"; shift 2
  # Any further args go to monkeydo verbatim, e.g. `-t` to run (:test) unit tests from a
  # `monkeyc --unit-test` build.
  local extra=""; for a in "$@"; do extra+=" '$a'"; done
  require_container
  [ -n "$(sim_pid)" ] || die "simulator not running; run 'ciq-sim.sh start' first"
  local sdk; sdk="$(detect_sdk)"
  echo "ciq-sim: monkeydo $prg ($device)$extra"
  # monkeydo stays attached while the app runs; it prints app crashes/exit here.
  cexec bash -c "'$sdk/bin/monkeydo' '$prg' '$device'$extra"
}

cmd_stop() {
  cexec bash -c "pkill -x simulator 2>/dev/null; true"
  echo "ciq-sim: simulator stopped"
}

cmd_status() {
  echo "container: $(podman ps --filter name=^${CIQ_CONTAINER}$ --format '{{.Names}} {{.Status}}' || echo 'NOT RUNNING')"
  echo "sdk:       $(detect_sdk)"
  echo "xauth:     $(detect_xauth)"
  local p; p="$(sim_pid)"
  if [ -n "$p" ]; then echo "simulator: running (pid $p)"; else echo "simulator: stopped"; fi
}

cmd_log() { cexec bash -c "tail -n ${1:-40} '$SIM_LOG'"; }

cmd_test() {
  local jungle="$1" device="$2"
  local out="/tmp/ciq_test_$(date +%s).prg"
  cmd_build "$jungle" "$device" "$out"
  cmd_start
  sleep 2
  cmd_run "$out" "$device"
}

case "${1:-}" in
  build)  shift; cmd_build "$@";;
  start)  shift; cmd_start "$@";;
  run)    shift; cmd_run "$@";;
  stop)   shift; cmd_stop "$@";;
  status) shift; cmd_status "$@";;
  log)    shift; cmd_log "$@";;
  test)   shift; cmd_test "$@";;
  *) echo "usage: ciq-sim.sh {build|start|run|stop|status|log|test} ..." >&2; exit 2;;
esac
