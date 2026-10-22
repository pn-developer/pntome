---
name: connectiq-simulator
description: >-
  Run the Garmin Connect IQ simulator and monkeydo on this Ubuntu 26 host by
  executing the GUI inside a persistent Ubuntu 22.04 podman/distrobox container
  (ciq2204) that provides the libwebkit2gtk-4.0 stack the simulator requires.
  Use this whenever a task says "verify in simulator", "simulator test",
  "monkeydo", or otherwise needs to run a .prg at runtime rather than just build it.
keywords:
  - connectiq
  - garmin
  - simulator
  - monkeydo
  - monkeyc
  - libwebkit2gtk
  - distrobox
  - podman
  - ciq2204
---

# Connect IQ simulator via container

## Why this exists

The host is **Ubuntu 26** and only ships `libwebkit2gtk-4.1`. The Connect IQ
simulator hard-links `libwebkit2gtk-4.0.so.37`, so it **cannot run directly on
the host**. Do **not** try to `apt install libwebkit2gtk-4.0-37` on the host —
that package is not in the Ubuntu 26 repos and the attempt is a dead end.

Instead, a persistent **Ubuntu 22.04** container named `ciq2204` (managed by
distrobox over podman) has the webkit 4.0 stack + libusb installed. It shares
`$HOME` (so the SDK under `~/.Garmin/ConnectIQ` and the dev key are visible
unchanged) and `DISPLAY=:0` (so the GUI renders to the host desktop). The host
OS stays untouched; everything webkit-4.0 is container-scoped.

**Build runs on the host** (`monkeyc` is a JVM tool, no webkit needed).
**Simulator + monkeydo run in the container.**

## Quick use

All commands go through the helper script:

```
.kiro/skills/connectiq-simulator/scripts/ciq-sim.sh <cmd>
```

- `status` — show container, SDK, xauth, and simulator state.
- `build <jungle> <device> <out.prg>` — build on the host.
- `start` — launch the simulator GUI (detached) in the container.
- `run <app.prg> <device>` — monkeydo a prg into the running simulator; prints
  app output and any crash/stack, then returns when the app exits.
- `test <jungle> <device>` — build + start + run in one shot.
- `stop` / `log` — kill the sim / tail its log.

Example (fēnix 6 Pro):

```
scripts/ciq-sim.sh build GsoundG/monkey.jungle fenix6pro /tmp/gsoundg.prg
scripts/ciq-sim.sh start
scripts/ciq-sim.sh run /tmp/gsoundg.prg fenix6pro
```

## Interpreting results

- `monkeydo` connecting and the app loading = simulator path works.
- A Monkey C runtime error (e.g. `Null Reference Error`, with a stack) is a
  **real app bug** the build/type-check could not catch — investigate it.
- GTK/dbind/canberra warnings in the log are harmless noise, ignore them.

## Gotchas (already handled by the script)

- **X authorization**: under Wayland the cookie is
  `/run/user/<uid>/.mutter-Xwaylandauth.*`, not `~/.Xauthority`. The script
  autodetects it and sets `XAUTHORITY`. It also runs `xhost +local:` on the host.
- **Snap library leak**: a login shell inside the container can leak a host snap
  path, producing `LIBSYSTEMD_254 ... version not found` / `nohup` failures. The
  script blanks `LD_LIBRARY_PATH` in every `podman exec`.
- **Detached launch**: start the sim with `podman exec -d` (not
  `distrobox enter ... &`, which dies when the calling shell exits).
- **Device IDs**: use SDK device ids like `fenix6pro`, `fenix847mm`. List
  available ids under `~/.Garmin/ConnectIQ/Devices`.

## One-time container setup (for reproducibility / rebuild)

If `ciq-sim.sh status` shows the container missing, recreate it:

```
distrobox create --name ciq2204 --image docker.io/library/ubuntu:22.04
distrobox enter ciq2204 -- sudo apt-get update
distrobox enter ciq2204 -- sudo apt-get install -y \
  libwebkit2gtk-4.0-37 libusb-1.0-0 libgtk-3-0 libnotify4 \
  libsecret-1-0 libgconf-2-4 libxss1 libasound2
```

(Installing packages *inside the container* is fine — it does not touch the host
and does not fall under the host "never auto-install system packages" rule.
Creating the container itself is a one-time step the user has already done.)
