#!/usr/bin/env bash
# setup_container.sh — create + provision the Ubuntu 22.04 container that runs
# the Connect IQ simulator. Idempotent: safe to re-run. Works on any host with
# podman (Manjaro / Arch, Ubuntu, etc.).
#
# What it does:
#   1. Ensures podman (and, if present, distrobox) exist.
#   2. Creates the container if missing, sharing $HOME + the X display so the
#      SDK/dev key are visible and the GUI renders on the host desktop.
#   3. Installs the libwebkit2gtk-4.0 stack + libusb INSIDE the container
#      (never touches the host — the host "don't auto-install packages" rule is
#      about the host, not a throwaway container).
#   4. Verifies webkit 4.0 + the shared SDK are visible from inside.
#
# Usage:
#   GsoundG/sim/setup_container.sh            # create/provision + verify
#   GsoundG/sim/setup_container.sh --verify   # verify only, make no changes
#   GsoundG/sim/setup_container.sh --recreate # destroy + rebuild from scratch
set -euo pipefail

cd "$(dirname "$0")"
. "./common.sh"

MODE="${1:-setup}"

verify() {
  local ok=1
  info "verifying container '$CIQ_CONTAINER'"
  if ! container_exists; then info "  container: MISSING"; return 1; fi
  if container_running; then info "  container: running"; else info "  container: stopped (start with: podman start $CIQ_CONTAINER)"; ok=0; fi

  if container_running; then
    if cexec bash -c "ldconfig -p 2>/dev/null | grep -q libwebkit2gtk-4.0"; then
      info "  webkit 4.0: present"
    else
      info "  webkit 4.0: MISSING (re-run without --verify to install)"; ok=0
    fi
    local sdk; sdk="$(cexec bash -c 'ls -d $HOME/.Garmin/ConnectIQ/Sdks/connectiq-sdk-lin-* 2>/dev/null | sort | tail -1' 2>/dev/null || true)"
    if [ -n "$sdk" ]; then info "  shared SDK: $sdk"; else info "  shared SDK: NOT VISIBLE (is \$HOME shared?)"; ok=0; fi
  fi
  [ "$ok" = 1 ]
}

create_container() {
  if container_exists; then
    info "container '$CIQ_CONTAINER' already exists"
    return 0
  fi
  info "creating container '$CIQ_CONTAINER' from $CIQ_IMAGE (host: $(host_family))"
  if have distrobox; then
    # distrobox shares $HOME and the display by default — ideal here.
    distrobox create --yes --name "$CIQ_CONTAINER" --image "$CIQ_IMAGE"
    # Materialize the container now so later `podman exec` works.
    distrobox enter "$CIQ_CONTAINER" -- true
  else
    # Raw podman fallback: share $HOME, the X socket, and the Wayland cookie dir.
    local xauth; xauth="$(detect_xauth)"
    podman create --name "$CIQ_CONTAINER" \
      --userns=keep-id \
      --network=host \
      -v "$HOME:$HOME" \
      -v /tmp/.X11-unix:/tmp/.X11-unix \
      -v "/run/user/$(id -u):/run/user/$(id -u)" \
      -e "HOME=$HOME" \
      -e "DISPLAY=${DISPLAY:-:0}" \
      -e "XAUTHORITY=$xauth" \
      -w "$HOME" \
      "$CIQ_IMAGE" sleep infinity >/dev/null
    podman start "$CIQ_CONTAINER" >/dev/null
  fi
}

provision_container() {
  container_running || podman start "$CIQ_CONTAINER" >/dev/null
  info "installing webkit-4.0 stack inside the container (first run downloads packages)"
  cexec bash -lc "export DEBIAN_FRONTEND=noninteractive; sudo apt-get update -qq"
  cexec bash -lc "export DEBIAN_FRONTEND=noninteractive; sudo apt-get install -y -qq ${CIQ_CONTAINER_PKGS[*]}"
}

recreate() {
  if container_exists; then
    info "removing existing container '$CIQ_CONTAINER'"
    podman rm -f "$CIQ_CONTAINER" >/dev/null 2>&1 || true
    have distrobox && distrobox rm -f "$CIQ_CONTAINER" >/dev/null 2>&1 || true
  fi
}

main() {
  require_engine
  case "$MODE" in
    --verify)
      verify && { info "OK — container ready."; exit 0; } || { info "NOT ready."; exit 1; }
      ;;
    --recreate)
      recreate
      create_container
      provision_container
      ;;
    setup|"")
      create_container
      # Install packages if webkit 4.0 isn't already there (keeps re-runs fast).
      if cexec bash -c "ldconfig -p 2>/dev/null | grep -q libwebkit2gtk-4.0"; then
        info "webkit 4.0 already installed in container"
      else
        provision_container
      fi
      ;;
    *)
      die "unknown option '$MODE' (use: --verify | --recreate | no args)"
      ;;
  esac

  if verify; then
    info "OK — '$CIQ_CONTAINER' is ready. Run an app with: GsoundG/run_in_simulator.sh"
  else
    die "setup finished but verification failed; check the messages above."
  fi
}

main
