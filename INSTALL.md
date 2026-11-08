# INSTALL — Gsound

Gsound has two apps that are built with different toolchains. Pick the guide for the app
you want to build; both cover **Ubuntu** (apt / Debian family) and **Manjaro**
(pacman / Arch family).

| App | Platform | Build with | Guide |
|-----|----------|-----------|-------|
| **GsoundA** | Android (Kotlin + Jetpack Compose) | Gradle wrapper (`./gradlew`), JDK 17, Android SDK 35 | [`GsoundA/INSTALL.md`](GsoundA/INSTALL.md) |
| **GsoundG** | Garmin Connect IQ (Monkey C) | `monkeyc` from the Connect IQ SDK 9.2.0, JDK 17 | [`GsoundG/INSTALL.md`](GsoundG/INSTALL.md) |

## Shared prerequisites

Both toolchains need **JDK 17** and **Git**. Install commands differ per distro and are
listed in each guide. This repo does not auto-install system packages — the guides give you
the exact `sudo apt` / `sudo pacman` commands to run yourself.

## Quick start

GsoundA (Android APK):

```bash
cd GsoundA
./gradlew assembleDebug
# APK at app/build/outputs/apk/debug/app-debug.apk
```

GsoundG (watch `.prg`, after installing the Connect IQ SDK):

```bash
cd GsoundG
./build.sh                 # fenix6spro -> build/GsoundG-fenix6spro.prg
```

## Pairing & sync (needs both apps)

Building and unit-testing either app needs nothing from the other. A real end-to-end
pair/sync needs both installed, the shared app UUID matched between them
(GsoundG `manifest.xml` `id` ↔ GsoundA `GsoundAppId.kt`), Garmin Connect Mobile on the
phone, and both devices on the same Wi-Fi/LAN. The full manual runbook is in
[`GsoundA/docs/manual-e2e-checklist.md`](GsoundA/docs/manual-e2e-checklist.md).
