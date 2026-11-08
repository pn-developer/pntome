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

## Sync over the phone's hotspot

No Wi-Fi network nearby? GsoundA can sync while the watch is connected to **the phone's own
hotspot** (spec `.kiro/specs/gsound-hotspot/`). Nothing changes in the watch app or the
protocol.

Watch-side setup:

1. Turn on the phone's hotspot and note its name (SSID) and password.
2. In **Garmin Connect** on the phone, save the hotspot as a Wi-Fi network for the watch
   (the same place you add a home network).
3. Use the **2.4 GHz** band for the hotspot. The fēnix 6 Wi-Fi is believed to be 2.4 GHz
   only (*to be confirmed on fēnix 6*).
4. Use **WPA2-Personal** security, not WPA3-only (*to be confirmed on fēnix 6*).
5. Keep **mobile data on**. The hotspot normally needs it, because the watch may check for
   internet access before it stays connected.
6. Make sure the watch has joined the hotspot, then tap Sync in GsoundA. The Sync screen
   shows `Hotspot · <address>:<port>` while the sync runs.

Wi-Fi is preferred:
- When the phone is connected to a Wi-Fi network, GsoundA always serves on that Wi-Fi
  address. The hotspot is used only when the phone has no usable Wi-Fi address.
- The phone can't know which network the watch joined, and there is no setting to prefer
  the hotspot.

Caveat when both are active:
- If the watch is on the phone's hotspot while the phone itself is also on Wi-Fi, the sync
  fails, because the server is bound to the Wi-Fi address and the watch can't reach it.
  **Turn the phone's Wi-Fi off**, keep the hotspot on, and sync again.

If neither a Wi-Fi network nor a hotspot is available, the sync is refused with: "Connect
this phone and your watch to the same Wi-Fi network, or connect the watch to this phone's
hotspot."
