# Gsound

Gsound syncs music folders from your Android phone to a Garmin watch over your local
Wi-Fi network. This repository contains **GsoundA**, the Android companion app.

- You pick music folders on the phone with the system folder picker.
- You pair the phone with the watch app (GsoundG) using a 6-digit confirmation code.
- When you sync, the phone starts a small local HTTP server and the watch pulls the
  selected tracks down over Wi-Fi.

Control messages (pairing, manifest, progress) travel over the Garmin Connect IQ Mobile
SDK channel; the bulk audio transfer goes over the local HTTP data channel.

## Repository layout

```
pn_gsound/
├── GsoundA/                      # Android app (Gradle project, package dev.gsound.a)
│   ├── app/                      # :app module
│   │   ├── src/main/             # application code + AndroidManifest + resources
│   │   ├── src/test/             # JVM unit tests (incl. Robolectric, Ktor test-host)
│   │   └── src/androidTest/      # instrumented tests (Room, SAF scanner)
│   ├── docs/
│   │   └── manual-e2e-checklist.md   # manual hardware test runbook
│   ├── gradle/                   # version catalog + wrapper
│   └── gradlew / gradlew.bat     # Gradle wrapper (Gradle 8.9)
├── .kiro/specs/gsound-android/   # requirements, design, and tasks for the app
├── README.md                     # this file
└── INSTALL.md                    # build & run instructions (Ubuntu / Manjaro)
```

## Features

- **Folder selection & management** — add folders via the Storage Access Framework with
  persisted read access; dedupe, remove-with-confirm, and restore across restarts.
- **Scanning** — recursive enumeration, format filtering (MP3 required; AAC/M4A, WAV,
  FLAC optional via settings), metadata extraction with filename fallback, stable track
  ids, and live progress.
- **Pairing** — Connect IQ discovery, 6-digit code with dual confirmation (local + watch),
  ~120 s timeout, single active pairing, unpair.
- **Local HTTP server** — Ktor CIO bound to the Wi-Fi interface; `GET /health`,
  `/manifest`, `/media/{trackId}` with `X-Gsound-Secret` auth, `Range` support, and 404
  for unknown/missing tracks.
- **Sync** — manifest build, server lifecycle, `SYNC_MANIFEST`/`SYNC_STATUS` handling,
  summary + last-sync timestamp, a foreground service with a progress notification and
  Cancel, a Wi-Fi availability guard, and a single-sync guard.
- **Watch stats** — file/folder counts and last-sync time from `STATS`, "unknown" when
  unavailable, refresh via `PING`.
- **Hardening** — minimal permissions, a redacting logger (secrets never logged), and
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
cd GsoundA
./gradlew assembleDebug
# APK at app/build/outputs/apk/debug/app-debug.apk
```

Full environment setup (JDK, Android SDK, command-line tools) for **Ubuntu** and
**Manjaro** is in [INSTALL.md](INSTALL.md).

## Testing

```bash
cd GsoundA
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
- The **GsoundG** watch app (a separate project, not in this repo) installed on the
  watch/simulator.
- The GsoundG app UUID wired into `app/src/main/java/dev/gsound/a/net/garmin/GsoundAppId.kt`.
  It currently holds a placeholder (`00000000000000000000000000000000`); until it matches
  the GsoundG manifest id, the SDK reports the app as not installed.
- Both devices on the **same Wi-Fi/LAN**.

See [`GsoundA/docs/manual-e2e-checklist.md`](GsoundA/docs/manual-e2e-checklist.md) for the
full manual test runbook.

## Spec

The app was built from a spec under `.kiro/specs/gsound-android/` — see `requirements.md`,
`design.md`, and `tasks.md` for the detailed behavior and rationale.
