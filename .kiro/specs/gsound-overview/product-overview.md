# Gsound — Product Overview & Shared Architecture

## 1. Summary

Gsound is a two-part system that lets a user keep selected music folders on their
Android phone in sync with their Garmin fēnix watch, and play that music on the
watch using the native Garmin media player.

| Component | Platform | Role |
|-----------|----------|------|
| **GsoundA** | Android (Kotlin + Jetpack Compose) | Pick folders, manage folder list, pair with watch, run a local HTTP server, trigger sync. |
| **GsoundG** | Garmin Connect IQ (Monkey C) | Audio Content Provider app. Pairs with phone, syncs music over Wi-Fi/HTTP, shows stats, plays music via the native media player. |

The two apps communicate over two channels:

1. **Control channel** — Garmin Connect IQ Mobile SDK message channel (phone ↔ watch,
   routed through Garmin Connect Mobile over Bluetooth). Used for pairing, sending the
   track manifest, and sync coordination. Small JSON messages only.
2. **Data channel** — HTTP over the local Wi-Fi network. GsoundA hosts a local HTTP
   server; GsoundG downloads audio files from it during sync. Large binary transfer.

## 2. Why this architecture (platform constraints)

These constraints are dictated by the Garmin Connect IQ platform and drive the whole design:

- A Connect IQ app **cannot** drop raw files into the native music library. Audio must
  flow through the **Audio Content Provider** framework (`Toybox.Media`,
  `Toybox.Application.AudioContentProviderApp`). Downloaded media lives in an **encrypted
  per-app sandbox** and is played only through the native media player via a
  `ContentDelegate` / `ContentIterator`.
  (API Level 3.0+; rephrased from Garmin developer docs for compliance.)
- The documented sync path downloads each track with
  `Communications.makeWebRequest(...)` using an **audio response type**, over **Wi-Fi**.
  This is why the phone runs an HTTP server rather than pushing bytes over Bluetooth.
- The Bluetooth **message channel** (Mobile SDK `sendMessage` / `Communications.transmit`)
  is for small data, not bulk file transfer. We use it only for control messages.

## 3. Device compatibility

Audio Content Provider apps run **only on music-capable** devices.

- **fēnix 6 family:** supported **only** on the Pro/Music editions —
  fēnix 6S Pro, 6 Pro, 6X Pro, 6 Pro Solar. The base fēnix 6 / 6S / 6X have **no music
  hardware** and cannot run GsoundG.
- **fēnix 8 family:** all fēnix 8 (AMOLED and Solar) are music-capable.
- Target Connect IQ API level: **3.0 minimum** (audio content provider), developed
  against current SDK (API 5.x), with `minApiLevel` set so fēnix 6 Pro is included.

## 4. Pairing model (shared)

Goal: a one-time, user-confirmed binding between one GsoundA install and one GsoundG install.

1. User starts pairing in GsoundA (or GsoundG).
2. A **6-digit pairing code** is generated. Generation is deterministic/agreed via the
   control channel: the initiator generates a random code + a random `pairingId` (UUID)
   and sends them to the peer over the control channel.
3. **Both** apps display the same 6-digit code. The user visually confirms they match and
   taps **Confirm** on **both** devices.
4. On confirmation each side persists a **PairingRecord**:
   - `pairingId` (UUID, shared)
   - `peerName` (watch model / phone name)
   - `sharedSecret` (random token exchanged during pairing, used to authenticate HTTP
     requests on the data channel)
   - `createdAt`
5. Persistence: Android → DataStore/Room; Garmin → `Application.Storage`.
6. Only a paired peer is accepted on either channel. Unpairing clears the record on the
   side that initiates and sends an `UNPAIR` control message to the peer.

## 5. Sync protocol (shared contract)

### 5.1 Control channel messages (JSON)

All messages: `{ "v": 1, "type": <string>, "pairingId": <uuid>, ... }`.

| type | Direction | Payload | Purpose |
|------|-----------|---------|---------|
| `PAIR_REQUEST` | A → G or G → A | `code`, `pairingId`, `secret`, `peerName` | Begin pairing. |
| `PAIR_CONFIRM` | both | `pairingId` | User confirmed on this side. |
| `UNPAIR` | either | `pairingId` | Remove pairing. |
| `SYNC_MANIFEST` | A → G | `serverUrl`, `tracks[]` | Full list of tracks to sync + where to fetch. |
| `SYNC_STATUS` | G → A | `state`, `done`, `total`, `bytesDone`, `error?` | Progress reporting. |
| `STATS` | G → A | `fileCount`, `folderCount`, `lastSyncAt` | Watch-side stats for display in A. |
| `PING` / `PONG` | both | — | Liveness / connectivity check. |

`serverUrl` example: `http://192.168.1.42:8080`. Each `track` entry:

```json
{
  "id": "sha1-or-stable-hash",
  "folder": "Workout",
  "title": "Song Title",
  "artist": "Artist",
  "album": "Album",
  "durationMs": 215000,
  "sizeBytes": 5123456,
  "mime": "audio/mpeg",
  "path": "/media/<trackId>"
}
```

### 5.2 Data channel (HTTP)

GsoundA hosts endpoints (all require header `X-Gsound-Secret: <sharedSecret>`):

| Method | Path | Returns |
|--------|------|---------|
| `GET` | `/health` | `200 {"ok":true}` liveness. |
| `GET` | `/manifest` | JSON manifest (same shape as `SYNC_MANIFEST.tracks`). |
| `GET` | `/media/{trackId}` | Audio bytes, `Content-Type: audio/mpeg`, supports HTTP `Range`. |

GsoundG, during `SyncDelegate.onStartSync()`, iterates manifest tracks and calls
`makeWebRequest` with an audio response type against `/media/{trackId}`, storing each via
`Media.getContentObtainedWithId` / content-provider APIs into its encrypted sandbox.

### 5.3 Garmin-supported audio formats

GsoundA must only advertise/sync files the watch can play. Target set: **MP3** primarily;
optionally **AAC/M4A, WAV, FLAC** subject to device support. GsoundA filters by extension
+ MIME and skips unsupported files, reporting the skip count.

## 6. Sync lifecycle

1. User triggers **Sync** in GsoundA.
2. GsoundA scans selected folders, builds the manifest (hash/dedupe), starts the HTTP server.
3. GsoundA sends `SYNC_MANIFEST` (with its LAN `serverUrl` + secret) over the control channel.
4. GsoundG receives it, prompts the native sync flow, and in `onStartSync` downloads each
   track over Wi-Fi, reporting progress via `notifySyncProgress` and `SYNC_STATUS`.
5. GsoundG deletes tracks no longer in the manifest (reconciliation).
6. GsoundG sends final `STATS`; GsoundA displays "last synced" info.

## 7. Repository layout

```
pn_gsound/
├── .kiro/specs/
│   ├── gsound-overview/product-overview.md   (this file)
│   ├── gsound-android/{requirements,design,tasks}.md
│   └── gsound-garmin/{requirements,design,tasks}.md
├── GsoundA/        (Android Gradle project — created during implementation)
└── GsoundG/        (Connect IQ Monkey C project — created during implementation)
```

## 8. Development & deployment loop

### Android (GsoundA)
- Build with Gradle, deploy via `adb install` / Android Studio to the phone.

### Garmin (GsoundG)
- Build device `.prg` with `monkeyc` (or VS Code Monkey C "Build for Device"), signed with a developer key.
- Primary iteration in the **Connect IQ simulator** (supports simulated phone messages and Wi-Fi).
- On-hardware testing: connect fēnix via USB (mass storage), copy `.prg` into
  `GARMIN/APPS/`, copy any `.set` into `GARMIN/APPS/SETTINGS/`, disconnect. Remove
  sideloaded apps via Garmin Express or the Connect IQ mobile app.

## 9. Out of scope (v1)
- Streaming (download-only, offline playback).
- DRM/protected content.
- Multiple simultaneous paired watches.
- iOS companion.
- Playlists beyond "folder = playlist".
