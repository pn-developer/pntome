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

## Audiobooks

A synced folder can be marked as **one audiobook** (spec `.kiro/specs/gsound-audiobooks/`):

1. In GsoundA → Folders, tap the **Audiobook** chip on the folder's row, then sync. A
   change of the flag alone needs no download; the watch updates on the next sync.
2. Chapters play in **natural file-name order**, so name files with a numeric prefix:
   `01 …`, `02 …`, `10 …` or `1-01 …`, `1-02 …`, `2-01 …`. ID3 tags are not used for the
   order. Chapter titles on the watch are the file names without extension.
   Music folders use the same file-name order.
3. On the watch, the book appears as `Book · <name>`. Opening it offers:
   - **Resume**: "Ch N · m:ss", shown only when the book has an unfinished bookmark.
   - **Play from start**.
   - **Chapters**: start any chapter from 0:00.

   Playback always continues through the following chapters. It stops after the last
   chapter, and Resume is then hidden.
4. Resume rewinds 5 s for context. During book playback the player also offers ±30 s skip
   controls on screen (the size is fixed on fēnix 6).

Limitations:
- The watch reports the position only on player events (pause, stop, skip, chapter end).
  If the watch powers off or the app is killed mid-chapter, the bookmark keeps the chapter
  but loses the position since the last pause.
- One book per folder. Nested folders are not supported.
