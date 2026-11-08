# INSTALL — Building PNTomeGo (Garmin Connect IQ)

This guide sets up the Connect IQ toolchain and builds the PNTomeGo watch app (`.prg`) on
**Ubuntu** (apt / Debian family) and **Manjaro** (pacman / Arch family). PNTomeGo is a
Monkey C **Audio Content Provider** app; it is built with `monkeyc` and run in the Connect
IQ simulator or sideloaded to a music-capable fēnix.

> For an overview of the whole repo and the PNTome (Android) build guide, see the
> top-level [`../INSTALL.md`](../INSTALL.md).

> PNTomeGo is **not** a Gradle project. It has no wrapper — you build it with the `monkeyc`
> compiler shipped in the Connect IQ SDK.

## 1. Requirements

| Component            | Version / note |
|----------------------|----------------|
| JDK                  | **17** (the Connect IQ SDK tools run on the JVM) |
| Connect IQ SDK       | **9.2.0** (`connectiq-sdk-lin-9.2.0`); provides `monkeyc`, `monkeydo`, `sdkmanager`, device definitions |
| Connect IQ SDK Manager | GUI/CLI used once to download the SDK + device profiles |
| Developer key        | a 4096-bit RSA key used to sign every `.prg` |
| Git                  | to clone the repo |
| Internet access      | first SDK Manager run downloads the SDK and device bundles |

Environment variables the build relies on:

- `JAVA_HOME` → your JDK 17 install
- The SDK `bin/` directory on `PATH` (so `monkeyc` / `monkeydo` resolve). The SDK Manager
  installs under `~/.Garmin/ConnectIQ/Sdks/connectiq-sdk-lin-9.2.0-*/`.

---

## 2. Ubuntu (apt)

Tested on Ubuntu 22.04 / 24.04.

### 2.1 Install JDK 17 and Git

> This project does not auto-install system packages. Run the install commands yourself
> (they need `sudo`).

```bash
sudo apt update
sudo apt install openjdk-17-jdk git unzip
```

Point `JAVA_HOME` at the JDK 17 install:

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
java -version   # should report 17.x
```

### 2.2 Install the Connect IQ SDK

The SDK is distributed by Garmin, not through apt. Download the **Connect IQ SDK Manager**
from the Garmin developer site, then use it to install SDK **9.2.0** and the fēnix device
profiles.

```bash
# 1. Download the SDK Manager from:
#    https://developer.garmin.com/connect-iq/sdk/
#    (extract the zip; it contains the `sdkmanager` GUI app)
unzip connectiq-sdk-manager-linux.zip -d "$HOME/garmin-sdkmanager"

# 2. Run it once (needs a free Garmin developer account), then in the UI:
#      - install SDK 9.2.0
#      - download the device profiles you target (fenix6pro, fenix847mm, ...)
"$HOME/garmin-sdkmanager/sdkmanager"
```

The SDK Manager installs the SDK under `~/.Garmin/ConnectIQ/Sdks/` and device definitions
under `~/.Garmin/ConnectIQ/Devices/`. Put the active SDK's `bin/` on `PATH`:

```bash
# The SDK Manager installs into a dated folder, e.g.
#   connectiq-sdk-lin-9.2.0-2026-06-09-<hash>
# Point CIQ_SDK at whatever it created:
export CIQ_SDK="$(ls -d "$HOME"/.Garmin/ConnectIQ/Sdks/connectiq-sdk-lin-9.2.0* | sort | tail -1)"
export PATH="$CIQ_SDK/bin:$PATH"
monkeyc --version   # confirms the compiler resolves
```

> `build.sh` and `ciq-sim.sh` autodetect this folder (via
> `~/.Garmin/ConnectIQ/current-sdk.cfg`), so you only need `CIQ_SDK` for running `monkeyc`
> by hand.

> **Simulator caveat (Ubuntu 25/26):** the simulator links `libwebkit2gtk-4.0`, which is
> gone from the newest Ubuntu releases. **Building does not need webkit** (`monkeyc` is a
> JVM tool). Only the *simulator GUI* needs it. On Ubuntu 22.04/24.04 install it with
> `sudo apt install libwebkit2gtk-4.0-37`. On Ubuntu 26 the package is unavailable; run the
> simulator inside an Ubuntu 22.04 container instead (see
> `.kiro/skills/connectiq-simulator/`). The `.prg` itself still builds natively.

### 2.3 Generate a developer key

Every `.prg` must be signed. Generate a key once with `openssl` (or via the VS Code Monkey C
extension). Store it at `~/.Garmin/developer_key.der` — that is where the SDK Manager keeps
its config and where this repo's `build.sh` / `ciq-sim.sh` look by default:

```bash
openssl genrsa -out "$HOME/.Garmin/developer_key.pem" 4096
openssl pkcs8 -topk8 -inform PEM -outform DER -nocrypt \
  -in "$HOME/.Garmin/developer_key.pem" \
  -out "$HOME/.Garmin/developer_key.der"
```

Persist the env vars (append to `~/.bashrc` or `~/.zshrc`). `CIQ_DEVKEY` is only needed if
your key is somewhere other than the default `~/.Garmin/developer_key.der`:

```bash
echo 'export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64' >> ~/.bashrc
echo 'export CIQ_SDK=$HOME/.Garmin/ConnectIQ/Sdks/connectiq-sdk-lin-9.2.0' >> ~/.bashrc
echo 'export PATH=$CIQ_SDK/bin:$PATH' >> ~/.bashrc
source ~/.bashrc
```

Continue at [Section 4 — Build](#4-build).

---

## 3. Manjaro (pacman)

Tested on current Manjaro / Arch.

### 3.1 Install JDK 17 and Git

```bash
sudo pacman -Syu
sudo pacman -S jdk17-openjdk git unzip openssl
```

Select JDK 17 as the active Java (Arch ships `archlinux-java`):

```bash
archlinux-java status            # list installed JVMs
sudo archlinux-java set java-17-openjdk
java -version                    # should report 17.x
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk
```

### 3.2 Install the Connect IQ SDK

Garmin ships no pacman package; the install path is the same SDK Manager as on Ubuntu.

```bash
# 1. Download + extract the Connect IQ SDK Manager (Linux) from:
#    https://developer.garmin.com/connect-iq/sdk/
unzip connectiq-sdk-manager-linux.zip -d "$HOME/garmin-sdkmanager"

# 2. Run it once and install SDK 9.2.0 + the fēnix device profiles you target.
"$HOME/garmin-sdkmanager/sdkmanager"

export CIQ_SDK="$HOME/.Garmin/ConnectIQ/Sdks/connectiq-sdk-lin-9.2.0"
export PATH="$CIQ_SDK/bin:$PATH"
monkeyc --version
```

> The SDK Manager GUI needs GTK/webkit libraries. On Arch/Manjaro install
> `webkit2gtk` (and `gtk3`) from the official repos if the GUI fails to start:
> `sudo pacman -S webkit2gtk gtk3`. As on Ubuntu, **building the `.prg` does not need
> webkit** — only the SDK Manager and simulator GUIs do.

> The AUR carries community `garmin-connectiq` packages as an alternative to the manual
> SDK Manager download; the official SDK Manager above avoids an AUR helper and matches the
> version this project pins (9.2.0).

### 3.3 Generate a developer key

Store the key at `~/.Garmin/developer_key.der` (the default `build.sh` / `ciq-sim.sh` path):

```bash
openssl genrsa -out "$HOME/.Garmin/developer_key.pem" 4096
openssl pkcs8 -topk8 -inform PEM -outform DER -nocrypt \
  -in "$HOME/.Garmin/developer_key.pem" \
  -out "$HOME/.Garmin/developer_key.der"
```

Persist the env vars (append to `~/.bashrc` or `~/.zshrc`):

```bash
echo 'export JAVA_HOME=/usr/lib/jvm/java-17-openjdk' >> ~/.bashrc
echo 'export CIQ_SDK=$HOME/.Garmin/ConnectIQ/Sdks/connectiq-sdk-lin-9.2.0' >> ~/.bashrc
echo 'export PATH=$CIQ_SDK/bin:$PATH' >> ~/.bashrc
source ~/.bashrc
```

Continue below.

---

## 4. Build

Clone (if you haven't), then use the bundled `build.sh` wrapper. It autodetects the
installed SDK and the developer key, so no flags are needed for the common case:

```bash
git clone <your-repo-url> pn_gsound
cd pn_gsound/PNTomeGo

./build.sh                 # release, fenix6spro -> build/PNTomeGo-fenix6spro.prg
./build.sh --debug         # debug build (diagnostic log, see below)
./build.sh fenix847mm      # fenix 8    -> build/PNTomeGo-fenix847mm.prg
./build.sh fenix6pro /tmp/PNTomeGo.prg   # explicit output path
```

`build.sh [--debug] [device] [output.prg]` (defaults: release, `fenix6spro`,
`build/PNTomeGo-<device>.prg`).

**Release vs debug.** The default is a release build (`monkeyc -r`), which strips all
`(:debug)`-annotated code. Only plain `println` output remains, and that only reaches
`GARMIN/APPS/LOGS/PNTOMEGO-<DEVICE>.TXT` if you create that file by hand. `--debug` keeps the
diagnostics:

- every log line is also kept in a 40-line ring buffer in app storage, and
- the lines are forwarded to the phone when you press **Refresh stats** in PNTome. They show
  up in logcat under the tag `PNTome.Watch`.
- media-cache snapshots (size and cached ids) are logged after each sync.

A debug build shows `dbg` after the version in the watch footer, e.g. `v0.1.0 (632f42c) dbg`.
Build for the exact device you sideload to: a `.prg` built for another model (e.g. `fenix6pro`
on a fēnix 6S Pro) can misbehave or crash. The watch's model is the `Part-Number` in
`GARMIN/APPS/LOGS/CIQ_LOG.YAML`; match it against `partNumbers` in
`~/.Garmin/ConnectIQ/Devices/<id>/compiler.json` (`006-B3288-00` = `fenix6spro`).
Override the SDK or key via `CIQ_SDK` / `CIQ_DEVKEY` env vars. Supported device ids come
from `manifest.xml`: `fenix6spro`, `fenix6pro`, `fenix6xpro`, `fenix843mm`, `fenix847mm`,
`fenix8pro47mm`, `fenix8solar47mm`, `fenix8solar51mm`. (Installed device ids live under
`~/.Garmin/ConnectIQ/Devices`.)

Under the hood this just calls `monkeyc`; the equivalent raw command is:

```bash
monkeyc -f monkey.jungle -d fenix6pro -y ~/.Garmin/developer_key.der -o build/PNTomeGo-fenix6pro.prg -r   # drop -r for debug
```

> To build **and** run in the simulator in one step, see `./run_in_simulator.sh` in
> Section 5 — it calls `build.sh` for you.

---

## 5. Run in the simulator

The simulator loads a device profile and runs the `.prg` via `monkeydo`; it can inject
phone messages and simulate Wi-Fi, so it is the primary iteration loop.

### 5.1 The webkit problem (and why this repo containerizes the simulator)

The Connect IQ simulator hard-links `libwebkit2gtk-4.0.so.37`. Modern Linux no longer ships
that library:

- **Ubuntu 25/26** → only `libwebkit2gtk-4.1`.
- **Manjaro / Arch (current)** → `webkit2gtk-4.1` only; there is no 4.0 package.

So on either host the simulator fails to start with a missing-library error, even though
**building the `.prg` works fine** (`monkeyc` is a pure JVM tool). The fix is to run only
the *simulator GUI + monkeydo* inside an **Ubuntu 22.04 container** that still has webkit
4.0, while the container shares your `$HOME` (so the SDK and dev key are seen unchanged)
and the host X display (so the GUI renders on your desktop).

The repo ships a self-contained utility for this under `PNTomeGo/sim/`:

```
PNTomeGo/
├── run_in_simulator.sh     # build/attach a .prg to the simulator (main entry point)
└── sim/
    ├── common.sh           # shared helpers (podman/distrobox, X auth, SDK/key detect)
    └── setup_container.sh   # create + provision the Ubuntu 22.04 webkit-4.0 container
```

It needs **podman** (and **distrobox**, recommended) on the host:

- Ubuntu: `sudo apt install podman distrobox`
- Manjaro: `sudo pacman -S podman distrobox`

### 5.2 One-time container setup

```bash
cd PNTomeGo
./sim/setup_container.sh            # create the container + install webkit 4.0 inside it
./sim/setup_container.sh --verify   # check it's ready (no changes)
./sim/setup_container.sh --recreate # rebuild from scratch if it got into a bad state
```

This installs the webkit stack **inside the container only** — it never touches your host
packages. Override the container name or base image with `CIQ_CONTAINER` / `CIQ_IMAGE`.

### 5.3 Build + run an app

```bash
cd PNTomeGo
./run_in_simulator.sh                      # build fenix6spro, start the sim, attach it
./run_in_simulator.sh fenix847mm           # same, for fēnix 8
./run_in_simulator.sh fenix6pro /tmp/g.prg # attach an existing .prg instead of building
```

It auto-runs `setup_container.sh` if the container isn't ready, reuses an already-running
simulator, and leaves the simulator up between runs for fast iteration. Other flags:

```bash
./run_in_simulator.sh --build-only   # just build, don't launch the sim
./run_in_simulator.sh --no-build /tmp/g.prg   # require an existing .prg
./run_in_simulator.sh --stop         # stop the simulator
```

`monkeydo` connecting and the app loading means the toolchain is good — the app's `System.
println` output streams to your terminal. A Monkey C runtime error with a stack trace is a
real app bug worth investigating; GTK/dbind/canberra warnings in the log are harmless noise.

> On a host that *does* have webkit 4.0 (e.g. Ubuntu 22.04), you can skip the container and
> run the SDK tools directly: `connectiq &` then `monkeydo build/PNTomeGo-fenix6pro.prg fenix6pro`.

---

## 6. Install on hardware (sideload)

Music-capable fēnix only (6 Pro / 6X Pro / 6S Pro / 6 Pro Solar, fēnix 8 AMOLED/Solar):

1. Build the `.prg` for the exact device (Section 4).
2. Connect the watch via USB; it mounts as mass storage.
3. Copy `build/PNTomeGo-<device>.prg` into `GARMIN/APPS/` on the watch.
4. Copy any `.set` settings file into `GARMIN/APPS/SETTINGS/` (none required for v1).
5. Safely eject and disconnect.
6. On the watch, PNTomeGo appears under **Music > Music Providers**.

Remove a sideloaded app via Garmin Express or the Connect IQ mobile app.

---

## Export for the Connect IQ Store

Store uploads are a single `.iq` package containing every product in `manifest.xml`:

```bash
cd PNTomeGo
./build.sh --export        # -> dist/PNTomeGo-<version>.iq
```

`<version>` is `AppVersion.SEMVER` from `source/Version.mc`, which must match the version in
`manifest.xml` (the build fails if they differ). `--export` always builds the release flavor
(`monkeyc -e -r`) for all products. It refuses to run:

- with `--debug`, or with a device or output argument;
- outside a git checkout;
- when `git status --porcelain` reports uncommitted changes. Commit or stash first, so the
  package is built from a clean tree and carries no `-dirty` stamp.

**Keep the developer key.** The store ties updates of an app to the key that signed it. Back up
`~/.Garmin/developer_key.der` (or the file `CIQ_DEVKEY` points to) somewhere safe and reuse the
same key for every export. A package signed with a different key cannot update the existing
listing. Never commit the key.

The Connect IQ app id in `manifest.xml` stays unchanged. It must keep matching
`PNTome/app/src/main/java/dev/pntome/app/net/garmin/PNTomeAppId.kt`.

**Old and new pairing.** This release renames the apps (PNTome / PNTomeGo) and the pairing
secret header (`X-PNTome-Secret`). Old phone and watch builds do not pair with the new ones, so
replace both together: install the new PNTome on the phone and the new PNTomeGo on the watch,
then pair again.

---

## 7. Troubleshooting

- **`monkeyc: command not found`:** the SDK `bin/` isn't on `PATH`. Set `CIQ_SDK` to the
  installed SDK folder and add `$CIQ_SDK/bin` to `PATH`.
- **Wrong Java / `Unsupported class file`:** the SDK tools need JDK 17. Check
  `java -version` and `JAVA_HOME`. On Manjaro switch with
  `sudo archlinux-java set java-17-openjdk`; on Ubuntu use
  `sudo update-alternatives --config java`.
- **`Could not find device` / unknown `-d` id:** install that device's profile in the SDK
  Manager; confirm the id exists under `~/.Garmin/ConnectIQ/Devices`.
- **Signing errors / no key:** pass a valid DER key with `-y`. Regenerate with the `openssl`
  steps above if needed.
- **Simulator won't start (`libwebkit2gtk-4.0.so.37` missing):** expected on Ubuntu 25/26.
  Building still works; run the simulator in the Ubuntu 22.04 container via
  `.kiro/skills/connectiq-simulator/scripts/ciq-sim.sh`.
- **`SDK Manager` GUI fails to launch:** install the GTK/webkit libraries for your distro
  (`libwebkit2gtk-4.0-37` on Ubuntu 22/24, `webkit2gtk`+`gtk3` on Manjaro).

---

## 8. Pairing & sync prerequisites (beyond building)

A real pair/sync with the phone additionally needs:

- The **PNTome** Android app installed and built — see [`../PNTome/INSTALL.md`](../PNTome/INSTALL.md).
- PNTomeGo's `manifest.xml` `id` wired into PNTome's
  `app/src/main/java/dev/pntome/app/net/garmin/PNTomeAppId.kt` (they must be byte-for-byte
  identical, or the SDK reports the app as not installed).
- Garmin Connect Mobile installed and signed in on the phone, routing the control channel.
- The pairing secret: the watch presents it in the `X-PNTome-Secret` HTTP header on every
  request to the phone's server.
- Phone and watch on the same Wi-Fi/LAN.

The complete manual checklist is in
[`../PNTome/docs/manual-e2e-checklist.md`](../PNTome/docs/manual-e2e-checklist.md).
