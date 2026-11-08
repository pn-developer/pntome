#!/usr/bin/env bash
# common.sh — shared helpers for running the Connect IQ simulator inside an
# Ubuntu 22.04 container. Sourced by setup_container.sh and run_in_simulator.sh.
#
# Why a container: the Connect IQ simulator hard-links libwebkit2gtk-4.0.so.37.
# Modern hosts ship only webkit 4.1 (Ubuntu 25/26, current Manjaro/Arch), so the
# simulator cannot run natively. An Ubuntu 22.04 container provides the 4.0 stack
# while sharing $HOME (SDK + dev key visible unchanged) and the host X DISPLAY.
#
# This file defines variables + functions only; it runs nothing on its own.
#
# Env overrides (all optional):
#   CIQ_CONTAINER   container name          (default: ciq2204)
#   CIQ_IMAGE       base image              (default: docker.io/library/ubuntu:22.04)
#   CIQ_SDK         SDK dir                 (default: autodetected under ~/.Garmin)
#   CIQ_DEVKEY      developer key .der      (default: ~/.Garmin/developer_key.der)

# Guard against double-sourcing.
[ -n "${_CIQ_COMMON_SOURCED:-}" ] && return 0
_CIQ_COMMON_SOURCED=1

CIQ_CONTAINER="${CIQ_CONTAINER:-ciq2204}"
CIQ_IMAGE="${CIQ_IMAGE:-docker.io/library/ubuntu:22.04}"
CIQ_DEVKEY="${CIQ_DEVKEY:-$HOME/.Garmin/developer_key.der}"
CIQ_SIM_LOG="${CIQ_SIM_LOG:-/tmp/ciq_sim.log}"

# Packages the container needs to run the simulator + monkeydo (Ubuntu 22.04 names).
CIQ_CONTAINER_PKGS=(
  libwebkit2gtk-4.0-37
  libusb-1.0-0
  libgtk-3-0
  libnotify4
  libsecret-1-0
  libgconf-2-4
  libxss1
  libasound2
)

die()  { echo "ciq: $*" >&2; exit 1; }
info() { echo "ciq: $*" >&2; }

# --- host tooling ----------------------------------------------------------

# Detect the host OS family, for user-facing hints only. Echoes: debian|arch|other
host_family() {
  if [ -f /etc/os-release ]; then
    . /etc/os-release
    case " ${ID:-} ${ID_LIKE:-} " in
      *" debian "*|*" ubuntu "*) echo debian; return;;
      *" arch "*|*" manjaro "*)  echo arch;   return;;
    esac
  fi
  echo other
}

# The container engine: prefer distrobox (nicer $HOME/$DISPLAY sharing), fall
# back to raw podman. Both are checked so the utility works on Manjaro and Ubuntu.
have() { command -v "$1" >/dev/null 2>&1; }

require_engine() {
  have podman || die "podman not found. Install it (Ubuntu: 'sudo apt install podman', Manjaro: 'sudo pacman -S podman distrobox')."
  have distrobox || info "distrobox not found; using raw podman. (Manjaro: 'sudo pacman -S distrobox' is recommended.)"
}

# --- SDK + key -------------------------------------------------------------

detect_sdk() {
  if [ -n "${CIQ_SDK:-}" ]; then echo "${CIQ_SDK%/}"; return; fi
  local cfg="$HOME/.Garmin/ConnectIQ/current-sdk.cfg"
  if [ -f "$cfg" ]; then
    local p; p="$(tr -d '[:space:]' < "$cfg")"
    [ -d "$p" ] && { echo "${p%/}"; return; }
  fi
  ls -d "$HOME"/.Garmin/ConnectIQ/Sdks/connectiq-sdk-lin-* 2>/dev/null | sort | tail -1
}

# --- X authorization -------------------------------------------------------

# Under Wayland the cookie is /run/user/<uid>/.mutter-Xwaylandauth.*, not
# ~/.Xauthority. Autodetect so the container's GUI can reach the host display.
detect_xauth() {
  local x
  x="$(ls -1 /run/user/"$(id -u)"/.mutter-Xwaylandauth.* 2>/dev/null | head -1 || true)"
  [ -n "$x" ] && { echo "$x"; return; }
  [ -n "${XAUTHORITY:-}" ] && { echo "$XAUTHORITY"; return; }
  echo "$HOME/.Xauthority"
}

allow_local_x() {
  have xhost && xhost +local: >/dev/null 2>&1 || true
}

# --- container state + exec ------------------------------------------------

container_exists()  { podman container exists "$CIQ_CONTAINER" 2>/dev/null; }
container_running() { [ "$(podman inspect -f '{{.State.Running}}' "$CIQ_CONTAINER" 2>/dev/null)" = "true" ]; }

require_container() {
  container_exists  || die "container '$CIQ_CONTAINER' does not exist. Run: PNTomeGo/sim/setup_container.sh"
  container_running || start_container
  container_running || die "container '$CIQ_CONTAINER' exists but could not be started. Try: podman start $CIQ_CONTAINER"
}

# Start the container if it exists but is stopped. A `podman exec` against a
# stopped container fails with "can only create exec sessions on running
# containers: container state improper", so every path that is about to cexec
# must ensure the container is running first. Prefer `distrobox enter` to
# materialize it (sets up the shared $HOME/display the distrobox way) when
# distrobox is present; otherwise a raw `podman start`. Idempotent: a no-op when
# already running.
start_container() {
  container_exists || return 1
  container_running && return 0
  if have distrobox; then
    distrobox enter "$CIQ_CONTAINER" -- true >/dev/null 2>&1 || true
  fi
  container_running || podman start "$CIQ_CONTAINER" >/dev/null 2>&1 || true
  container_running
}

# podman exec with a clean env + X access.
# LD_LIBRARY_PATH is blanked so a host snap path can't leak in (causes
# LIBSYSTEMD_254 / nohup failures from a login shell inside the container).
cexec() {
  local xauth; xauth="$(detect_xauth)"
  podman exec \
    -e DISPLAY="${DISPLAY:-:0}" \
    -e XAUTHORITY="$xauth" \
    -e LD_LIBRARY_PATH= \
    "$CIQ_CONTAINER" "$@"
}

# Detached variant for launching the GUI (survives the calling shell exiting).
cexec_d() {
  local xauth; xauth="$(detect_xauth)"
  podman exec -d \
    -e DISPLAY="${DISPLAY:-:0}" \
    -e XAUTHORITY="$xauth" \
    -e LD_LIBRARY_PATH= \
    "$CIQ_CONTAINER" "$@"
}

sim_pid() { cexec bash -c "pgrep -x simulator | head -1" 2>/dev/null || true; }
