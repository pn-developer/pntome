# PNTome

PNTome syncs audiobooks and music from your Android phone to a Garmin watch over your
local Wi-Fi network. This repository contains **PNTome**, the Android app, and
**PNTomeGo**, the Garmin Connect IQ watch app.

- You pick folders on the phone with the system folder picker.
- You pair the phone with the watch app (PNTomeGo) using a 6-digit confirmation code.
- When you sync, the phone starts a small local HTTP server and the watch pulls the
  selected tracks down over Wi-Fi.
- On the watch you play music and audiobooks, with bookmarks and resume.

Control messages (pairing, manifest, progress) travel over the Garmin Connect IQ Mobile
SDK channel; the bulk audio transfer goes over the local HTTP data channel. No data
leaves your local network. See [PRIVACY.md](PRIVACY.md).

## Install

| Channel | App | Notes |
|---------|-----|-------|
| Google Play | PNTome | Store listing text lives in [`store/play/`](store/play/). |
| F-Droid repo (self-hosted) | PNTome | Setup in [`fdroid/README.md`](fdroid/README.md). |
| Connect IQ Store | PNTomeGo | Submission notes in [`store/garmin/`](store/garmin/). |
| Build from source | both | See [INSTALL.md](INSTALL.md). |

PNTomeGo requires PNTome on the phone and Garmin Connect Mobile. Release builds are
described in [`docs/RELEASE_CHECKLIST.md`](docs/RELEASE_CHECKLIST.md). The release
procedure, CI secrets and manual steps are in [`docs/RELEASING.md`](docs/RELEASING.md).

## Repository layout

```
pn_gsound/
├── PNTome/                       # Android app (Gradle project, package dev.pntome.app)
│   ├── app/                      # :app module
│   │   ├── src/main/             # application code + AndroidManifest + resources
│   │   ├── src/test/             # JVM unit tests (incl. Robolectric, Ktor test-host)
│   │   └── src/androidTest/      # instrumented tests (Room, SAF scanner)
│   ├── docs/
│   │   └── manual-e2e-checklist.md   # manual hardware test runbook
│   ├── fastlane/                 # F-Droid / Play metadata (descriptions, changelogs)
│   ├── gradle/                   # version catalog + wrapper
│   ├── gradlew / gradlew.bat     # Gradle wrapper (Gradle 8.9)
│   └── INSTALL.md                # build & run PNTome (Ubuntu / Manjaro)
├── PNTomeGo/                     # Garmin Connect IQ app (Monkey C, audio content provider)
│   ├── manifest.xml              # app id, device list, permissions
│   ├── monkey.jungle             # build config
│   ├── source/ resources/        # Monkey C source + resources
│   ├── build.sh                  # monkeyc wrapper (autodetects SDK + dev key; --export for .iq)
│   ├── run_in_simulator.sh       # build + run a .prg in the simulator (via container)
│   ├── sim/                      # Ubuntu 22.04 webkit-4.0 container utility
│   └── INSTALL.md                # build & run PNTomeGo (Ubuntu / Manjaro)
├── store/
│   ├── play/                     # Google Play listing, data safety, permissions
│   └── garmin/                   # Connect IQ Store listing, images, submission checklist
├── fdroid/                       # self-hosted F-Droid repo: metadata + config example
├── docs/
│   ├── RELEASING.md              # release procedure, CI secrets, manual steps
│   └── RELEASE_CHECKLIST.md      # release steps
├── .kiro/specs/                  # specs (see "Specs" below)
├── CHANGELOG.md                  # release notes (Keep a Changelog)
├── PRIVACY.md                    # privacy policy
├── THIRD_PARTY.md                # third-party dependencies and licenses
├── LICENSE                       # Apache-2.0
├── README.md                     # this file
└── INSTALL.md                    # install index, links to PNTome / PNTomeGo guides
```

## Features

- **Folder selection & management**: add folders via the Storage Access Framework with
  persisted read access; dedupe, remove-with-confirm, and restore across restarts.
- **Scanning**: recursive enumeration, format filtering (MP3 required; AAC/M4A, WAV,
  FLAC optional via settings), metadata extraction with filename fallback, stable track
  ids, and live progress.
- **Pairing**: Connect IQ discovery, 6-digit code with dual confirmation (local + watch),
  ~120 s timeout, single active pairing, unpair.
- **Local HTTP server**: Ktor CIO bound to the Wi-Fi interface; `GET /health`,
  `/manifest`, `/media/{trackId}` with `X-PNTome-Secret` auth, `Range` support, and 404
  for unknown/missing tracks.
- **Sync**: manifest build, server lifecycle, `SYNC_MANIFEST`/`SYNC_STATUS` handling,
  summary + last-sync timestamp, a foreground service with a progress notification and
  Cancel, a Wi-Fi availability guard, and a single-sync guard. Hotspot sync is supported.
- **Audiobooks**: mark a folder as one audiobook; chapters, resume and bookmarks on the watch.
- **Watch stats**: file/folder counts and last-sync time from `STATS`, "unknown" when
  unavailable, refresh via `PING`.
- **Hardening**: minimal permissions, a redacting logger (secrets never logged), and
  `trackId` sanitization against path traversal.

## Tech stack

| Area            | Choice |
|-----------------|--------|
| Language        | Kotlin 2.0.21 |
| UI              | Jetpack Compose + Material 3 |
| DI              | Hilt (Dagger) |
| Persistence     | Room (folders/tracks) + Proto DataStore (pairing/settings/summary) |
| HTTP server     | Ktor CIO (embedded) |
| Serialization   | kotlinx.serialization |
| Watch link      | Garmin Connect IQ Mobile SDK (`ciq-companion-app-sdk`) |
| Build           | Gradle 8.9 (wrapper), Android Gradle Plugin 8.7.3, JDK 17 |
| SDK levels      | compileSdk 35, targetSdk 35, minSdk 26 (Android 8.0) |

## Quick start

```bash
cd PNTome
./gradlew assembleDebug
# APK at app/build/outputs/apk/debug/app-debug.apk
```

Full environment setup for **Ubuntu** and **Manjaro** is split per app:
[PNTome/INSTALL.md](PNTome/INSTALL.md) (Android) and
[PNTomeGo/INSTALL.md](PNTomeGo/INSTALL.md) (Garmin). The top-level
[INSTALL.md](INSTALL.md) is an index linking to both.

## Testing

```bash
cd PNTome
./gradlew testDebugUnitTest          # full JVM unit suite (no device needed)
./gradlew assembleDebugAndroidTest   # compile instrumented tests
./gradlew connectedDebugAndroidTest  # run instrumented tests (needs a device/emulator)
./gradlew lintDebug                  # Android lint
```

## Prerequisites to actually pair & sync

The automated build and unit tests run with no extra hardware. Running a real
end-to-end sync additionally requires:

- **Garmin Connect Mobile** installed and signed in on the phone.
- A paired Garmin watch, or the **Connect IQ simulator** with a device profile.
- The **PNTomeGo** watch app (in this repo under `PNTomeGo/`, built per
  [PNTomeGo/INSTALL.md](PNTomeGo/INSTALL.md)) installed on the watch/simulator.
- Both devices on the **same Wi-Fi/LAN** (or the watch on the phone's hotspot).

The Connect IQ app id is shared by both apps (PNTomeGo `manifest.xml` `id`, PNTome
`PNTomeAppId.kt`) and already matches.

See [`PNTome/docs/manual-e2e-checklist.md`](PNTome/docs/manual-e2e-checklist.md) for the
full manual test runbook.

## Specs

The current release work is specified under `.kiro/specs/pntome-store-release/`.

The `.kiro/specs/gsound-*` specs are historical. They predate the rename to PNTome /
PNTomeGo and still use the old names and paths.

## License

PNTome and PNTomeGo are licensed under **Apache-2.0**
(SPDX: `Apache-2.0`). See [LICENSE](LICENSE).

- [PRIVACY.md](PRIVACY.md): privacy policy
- [CHANGELOG.md](CHANGELOG.md): release notes
- [THIRD_PARTY.md](THIRD_PARTY.md): third-party dependencies and their licenses
