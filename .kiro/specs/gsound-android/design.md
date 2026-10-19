# GsoundA (Android) — Technical Design

## 1. Overview

GsoundA is a single-module Android app (Kotlin, Jetpack Compose) implementing the Gsound
phone side: folder selection via SAF, folder/track scanning, pairing with GsoundG via the
Garmin Connect IQ Mobile SDK, a local HTTP server for serving audio over Wi-Fi, and sync
orchestration. This document realizes `requirements.md` and conforms to
`../gsound-overview/product-overview.md`.

## 2. Technology stack

| Concern | Choice | Rationale |
|---------|--------|-----------|
| Language | Kotlin (2.0+) | Modern Android default. |
| UI | Jetpack Compose + Material 3 | Modern declarative UI, requested "fairly new stack". |
| Architecture | MVVM + unidirectional data flow | Testable, Compose-friendly. |
| Async | Kotlin Coroutines + Flow | Structured concurrency for scan/sync/server. |
| DI | Hilt | Standard, low boilerplate. |
| Navigation | Navigation-Compose | Type-safe routes. |
| Persistence (prefs/pairing) | Jetpack DataStore (Proto) | Small structured state, no SQL overhead. |
| Persistence (folders/tracks) | Room | Relational, queryable track cache, survives restarts. |
| HTTP server | Ktor server (CIO engine, embedded) | Coroutine-native, Range support, modern; alt: NanoHTTPD. |
| Garmin link | Connect IQ Mobile SDK for Android (AAR from Maven Central) | Only supported phone↔watch channel. |
| Metadata | `MediaMetadataRetriever` | Built-in, no extra dep. |
| JSON | kotlinx.serialization | Matches Kotlin stack, used for control messages + manifest. |
| Foreground work | Foreground Service (mediaSync type) | Keep server/sync alive when backgrounded. |

**SDK levels:** `compileSdk = 35`, `targetSdk = 35`, `minSdk = 26` (Android 8.0). minSdk 26
gives persisted SAF URIs, modern APIs, and covers the vast majority of phones paired with
fēnix watches.

## 3. Module & package layout

```
GsoundA/
├── app/
│   └── src/main/java/dev/gsound/a/
│       ├── GsoundApplication.kt          (Hilt @HiltAndroidApp)
│       ├── MainActivity.kt               (Compose host, nav)
│       ├── di/                           (Hilt modules)
│       ├── ui/
│       │   ├── theme/
│       │   ├── folders/                  (FolderListScreen + ViewModel)
│       │   ├── pairing/                   (PairingScreen + ViewModel)
│       │   ├── sync/                     (SyncScreen + ViewModel)
│       │   └── components/
│       ├── domain/
│       │   ├── model/                    (Folder, Track, PairingRecord, SyncSummary)
│       │   ├── scan/                     (FolderScanner, FormatFilter, TrackIdHasher)
│       │   └── sync/                     (SyncOrchestrator, Manifest builder)
│       ├── data/
│       │   ├── folders/                  (Room entities/DAO, FolderRepository)
│       │   ├── pairing/                   (DataStore PairingStore, PairingRepository)
│       │   └── settings/                 (DataStore app settings)
│       ├── net/
│       │   ├── server/                   (GsoundHttpServer — Ktor)
│       │   └── garmin/                   (ConnectIqManager, ControlChannel, messages)
│       └── service/                      (SyncForegroundService)
└── build.gradle.kts (+ version catalog libs.versions.toml)
```

## 4. Domain model

```kotlin
data class Folder(
    val id: String,            // stable hash of treeUri
    val treeUri: String,       // persisted SAF content:// URI
    val displayName: String,
    val trackCount: Int,
    val totalBytes: Long,
    val addedAt: Long
)

data class Track(
    val id: String,            // stable content/path hash (overview §5.1)
    val folderId: String,
    val documentUri: String,   // SAF doc URI used to open the stream
    val relativeFolder: String,// playlist/folder name on watch
    val title: String,
    val artist: String?,
    val album: String?,
    val durationMs: Long,
    val sizeBytes: Long,
    val mime: String
)

data class PairingRecord(
    val pairingId: String,     // UUID
    val peerName: String,      // watch model/name
    val sharedSecret: String,
    val createdAt: Long
)

data class SyncSummary(
    val syncedCount: Int, val skippedCount: Int, val removedCount: Int,
    val lastSyncAt: Long, val error: String?
)
```

`TrackIdHasher`: `id = sha1(relativePath + "|" + sizeBytes + "|" + lastModified)` — stable
across scans (Req 3.5), cheap (no full-content hashing by default).

## 5. Key components

### 5.1 Folder selection & permissions (Req 1, 2, 9)
- `rememberLauncherForActivityResult(OpenDocumentTree)` launches the SAF picker.
- On result: `contentResolver.takePersistableUriPermission(uri, FLAG_GRANT_READ)`.
- Store tree URI in Room; dedupe by normalized URI (Req 1.4).
- On remove: `releasePersistableUriPermission(...)` then delete row (Req 2.2) after a
  confirm dialog (Req 2.3).
- Use `DocumentFile.fromTreeUri` + `DocumentsContract` queries for recursive enumeration.

### 5.2 Folder scanner (Req 3)
- `FolderScanner` walks the tree with `DocumentsContract.buildChildDocumentsUriUsingTree`
  for efficient bulk cursor queries (avoids slow `DocumentFile.listFiles` recursion).
- `FormatFilter` whitelists by extension+MIME: mp3 (required); m4a/aac, wav, flac (optional,
  gated by a settings flag defaulting to mp3-only for safe watch compatibility).
- Metadata via `MediaMetadataRetriever.setDataSource(fd)` using a content FD from the
  resolver; fall back to filename for title (Req 3.4).
- Runs on `Dispatchers.IO`, emits progress via `Flow<ScanProgress>` (Req 3.6).
- Results cached in Room `tracks` table keyed by `Track.id` for incremental rescans.

### 5.3 Garmin control channel (Req 4, 6, 7, 10)
- `ConnectIqManager` wraps the Connect IQ Mobile SDK:
  - initialize SDK (`ConnectIQ.getInstance(...).initialize(...)`),
  - resolve connected `IQDevice` and the GsoundG `IQApp` (by app UUID — shared constant
    with the watch app's `manifest.xml` id),
  - register for app messages (incoming control messages),
  - `sendMessage(device, app, payload, listener)` to transmit.
- `ControlChannel` serializes/deserializes JSON messages (kotlinx.serialization) per
  overview §5.1, validates `v`, `type`, `pairingId`, and drops messages from unpaired
  peers (Req 10.3). Outgoing messages retried with exponential backoff (Req 10.2).
- The SDK payload must be small (control only). All bulk data goes over HTTP.

> Note: the Connect IQ Mobile SDK message channel is routed through Garmin Connect Mobile.
> GsoundA must handle the SDK states (not installed, Bluetooth off, device not connected)
> and map them to the actionable errors in Req 4.2.

### 5.4 Pairing (Req 4)
State machine in `PairingViewModel`:
```
Idle → Discovering → (device+app found) → GeneratingCode
     → AwaitingConfirm (show 6-digit code; send PAIR_REQUEST)
     → (local Confirm tapped) + (PAIR_CONFIRM received) → Paired (persist record)
     → timeout/failure → Error
```
- Code: `Random.nextInt(0, 1_000_000)` zero-padded to 6 digits.
- `pairingId`: `UUID.randomUUID()`. `sharedSecret`: 32-byte random, Base64Url.
- Persist via `PairingStore` (Proto DataStore). Unpair sends `UNPAIR` then clears store.

### 5.5 Local HTTP server (Req 5, 10)
- `GsoundHttpServer` (Ktor CIO) bound to the Wi-Fi interface address (resolved via
  `ConnectivityManager`/`WifiManager`), ephemeral or fixed port (default 8080, fallback).
- Routes:
  - `GET /health` → `{"ok":true}`.
  - `GET /manifest` → serialized manifest from the current `SyncSession`.
  - `GET /media/{trackId}` → open SAF `documentUri` as a stream; respond with
    `Content-Type`, `Accept-Ranges: bytes`, and honor `Range` headers for partial content
    (Ktor `respondBytesWriter` / partial content support). Stream from disk, never buffer
    whole files (Req 10.4).
- Auth: an interceptor checks `X-Gsound-Secret == pairing.sharedSecret`, else `401`
  (Req 5.3). Unknown `trackId` → `404` (Req 5.5). Missing file mid-sync → `404` + continue
  (Req 10.1).
- Lifecycle owned by `SyncForegroundService`; stopped on completion/cancel (Req 5.6).

### 5.6 Sync orchestration (Req 6, 8)
- `SyncOrchestrator` (in the foreground service):
  1. Build manifest from Room track cache (fresh scan if stale).
  2. Determine LAN `serverUrl`; start `GsoundHttpServer`.
  3. Send `SYNC_MANIFEST { serverUrl, tracks, secret }` via `ControlChannel`.
  4. Collect `SYNC_STATUS` updates → expose `Flow<SyncState>` to `SyncViewModel`.
  5. On `STATS`/completion → write `SyncSummary`, stop server.
- `SyncForegroundService` (type `mediaSync`/`dataSync`) holds a WifiLock + notification
  with progress and a Cancel action (Req 6.5, 8.3). One active sync enforced via a mutex
  (Req 6.6).

### 5.7 Persistence (Req 8)
- Room DB `gsound.db`: `folders`, `tracks` tables. DAOs expose `Flow` for reactive UI.
- Proto DataStore: `PairingRecord`, `AppSettings` (format flags, port), `SyncSummary`.
- No secrets written to logs; a `Redacting` logger wraps sensitive fields.

## 6. UI / navigation

Three top-level destinations (bottom nav or tabs):
1. **Folders** — list with name/count/size, add (FAB → SAF), remove (swipe/confirm), empty
   state; Sync disabled when empty (Req 2.4).
2. **Pairing** — pairing status card; "Pair" launches the code flow; shows 6-digit code and
   Confirm; shows paired peer + Unpair.
3. **Sync** — Sync button (enabled only when paired + folders non-empty), live progress,
   last-sync summary, watch stats (from `STATS`), LAN address display (Req 5.8).

State: each screen has a `ViewModel` exposing an immutable `UiState` via `StateFlow`;
events via sealed `UiEvent`.

## 7. Threading & performance
- Scanning, metadata extraction, and file streaming on `Dispatchers.IO`.
- Server streams via buffered channels; chunk size ~64 KB.
- Track cache in Room avoids rescanning unchanged folders (compare lastModified).

## 8. Security
- `sharedSecret` gates every HTTP request; server binds to LAN only (not `0.0.0.0` publicly
  advertised beyond LAN). Consider HTTPS with a self-signed cert in a later iteration (watch
  side must trust it; v1 uses plain HTTP on local Wi-Fi as agreed, secret-authenticated).
- Validate and sanitize `trackId` path segments (no traversal; IDs are opaque hashes).
- Ignore control messages whose `pairingId` ≠ stored record.

## 9. Testing strategy
- Unit: `FormatFilter`, `TrackIdHasher`, manifest builder, `ControlChannel` (de)serialization,
  pairing state machine.
- Instrumented: Room DAOs, SAF scanning against a seeded tree, Ktor routes via Ktor test
  host (auth 401, range requests, 404).
- Garmin link: mock `ConnectIqManager` interface so sync/pairing logic is testable without
  hardware; manual E2E against the Connect IQ simulator and a real fēnix.

## 10. Open decisions / risks
- Connect IQ Mobile SDK reliability for message delivery has had reported regressions; keep
  the control-channel abstraction thin and swappable, and add retries + user-visible status.
- Some fēnix models restrict Wi-Fi while BT-connected; verify both devices share a LAN and
  surface guidance (Req 5.7).
- Non-mp3 formats depend on per-model codec support; default to mp3-only until validated.
