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
#   PNTomeGo/sim/setup_container.sh            # create/provision + verify
#   PNTomeGo/sim/setup_container.sh --verify   # verify only, make no changes
#   PNTomeGo/sim/setup_container.sh --recreate # destroy + rebuild from scratch
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
    # It may exist but be STOPPED. Start it now so the subsequent webkit check /
    # provisioning `cexec` doesn't fail with "container state improper".
    if ! container_running; then
      info "  (stopped) starting it"
      start_container || die "container '$CIQ_CONTAINER' exists but could not be started (try: podman start $CIQ_CONTAINER)"
    fi
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
  start_container || die "cannot provision: container '$CIQ_CONTAINER' is not running and could not be started"
  info "installing webkit-4.0 stack inside the container (first run downloads packages)"

  # Notes on the exec form:
  #  - The container's exec user is already root, so no `sudo` is needed (and a
  #    missing/odd sudo config would otherwise hang).
  #  - Use `bash -c`, NOT `bash -lc`: a login shell sources profile files from the
  #    shared $HOME and can leak a host snap LD_LIBRARY_PATH, breaking tools inside.
  #  - `apt-get update` can stall at the end on this image's unattended-upgrades /
  #    apt daemon trigger even though the package lists downloaded fine. We run it
  #    under a timeout and DON'T treat a stall as fatal — cached lists are enough
  #    for the install. The install step is the real success gate.
  local upd_timeout="${CIQ_APT_UPDATE_TIMEOUT:-120}"
  # `timeout` guarantees we never block forever; apt-get caches the fetched lists
  # even when the post-run trigger stalls, so a timeout here is non-fatal.
  if cexec bash -c "timeout ${upd_timeout} env DEBIAN_FRONTEND=noninteractive apt-get update -qq"; then
    info "apt-get update: ok"
  else
    info "apt-get update timed out/failed — this image stalls on an apt trigger; continuing with cached lists"
  fi

  cexec bash -c "DEBIAN_FRONTEND=noninteractive apt-get install -y -qq ${CIQ_CONTAINER_PKGS[*]}" \
    || die "apt-get install failed inside the container. Check network, or run 'podman exec $CIQ_CONTAINER apt-get update' manually."
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
    info "OK — '$CIQ_CONTAINER' is ready. Run an app with: PNTomeGo/run_in_simulator.sh"
  else
    die "setup finished but verification failed; check the messages above."
  fi
}

main
