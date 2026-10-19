# GsoundA (Android) — Requirements

## Introduction

GsoundA is the Android companion app for Gsound. It lets the user select music folders
on the phone, manage that folder list, pair with the Garmin watch app (GsoundG), host a
local HTTP server that serves the selected music over Wi-Fi, and trigger a sync that the
watch pulls down. See `../gsound-overview/product-overview.md` for the shared architecture,
pairing model, and sync protocol.

## Glossary

- **SAF**: Storage Access Framework — the Android system folder/document picker.
- **Tree URI**: a persistable `content://` URI granting access to a folder subtree.
- **Control channel**: Garmin Connect IQ Mobile SDK Bluetooth message channel.
- **Data channel**: the local HTTP server over Wi-Fi.
- **Manifest**: the list of syncable tracks with metadata (see overview §5.1).

## Requirements

### Requirement 1 — Select folders via the system browser

**User story:** As a user, I want to pick music folders using the Android system folder
browser, so that GsoundA can access and sync their contents.

#### Acceptance Criteria
1. WHEN the user taps "Add folder" THEN GsoundA SHALL launch the SAF folder picker
   (`ACTION_OPEN_DOCUMENT_TREE`).
2. WHEN the user selects a folder THEN GsoundA SHALL take a persistable read permission on
   the returned tree URI so access survives app and device restarts.
3. WHEN a folder is selected THEN GsoundA SHALL add it to the folder list and persist it.
4. IF the user selects a folder already in the list THEN GsoundA SHALL NOT create a
   duplicate and SHALL inform the user.
5. IF the user cancels the picker THEN GsoundA SHALL leave the folder list unchanged.

### Requirement 2 — Manage the folder list

**User story:** As a user, I want to see, add, and remove folders, so that I control what
music is eligible for sync.

#### Acceptance Criteria
1. WHEN the folder-list screen is shown THEN GsoundA SHALL display each folder's display
   name, derived path, eligible track count, and total size.
2. WHEN the user removes a folder THEN GsoundA SHALL release its persisted URI permission
   and remove it from storage.
3. WHEN the user removes a folder THEN GsoundA SHALL request confirmation before removing.
4. WHEN the folder list is empty THEN GsoundA SHALL show an empty state prompting the user
   to add a folder AND SHALL disable the Sync action.
5. WHEN the app restarts THEN GsoundA SHALL restore the previously persisted folder list.

### Requirement 3 — Scan folders for supported audio

**User story:** As a user, I want GsoundA to find playable music in my folders, so that
only watch-compatible files are offered for sync.

#### Acceptance Criteria
1. WHEN a folder is scanned THEN GsoundA SHALL recursively enumerate audio files within the
   tree URI.
2. GsoundA SHALL include only Garmin-supported formats (MP3 required; AAC/M4A, WAV, FLAC
   optional) determined by extension and MIME type.
3. WHEN an unsupported file is encountered THEN GsoundA SHALL skip it and count it as skipped.
4. WHEN scanning THEN GsoundA SHALL extract available metadata (title, artist, album,
   duration) and SHALL fall back to the filename when metadata is missing.
5. GsoundA SHALL assign each track a stable `id` that is identical across scans when the
   file content/path is unchanged (used for dedupe and reconciliation).
6. WHILE a scan is running GsoundA SHALL remain responsive and SHALL show scan progress.

### Requirement 4 — Pair with the watch app

**User story:** As a user, I want to pair GsoundA with GsoundG using a confirmation code,
so that only my watch can access my music.

#### Acceptance Criteria
1. WHEN the user starts pairing THEN GsoundA SHALL discover the GsoundG app on a connected
   Garmin device via the Connect IQ Mobile SDK.
2. IF no Garmin device or GsoundG app is available THEN GsoundA SHALL show an actionable
   error (e.g., "Open Garmin Connect / install GsoundG on the watch").
3. WHEN pairing begins THEN GsoundA SHALL generate a 6-digit code, a `pairingId` (UUID),
   and a `sharedSecret`, and SHALL send `PAIR_REQUEST` over the control channel.
4. WHEN pairing is in progress THEN GsoundA SHALL display the 6-digit code and instruct the
   user to confirm it matches the code on the watch.
5. WHEN the user taps Confirm AND a `PAIR_CONFIRM` is received from the watch THEN GsoundA
   SHALL persist the PairingRecord (`pairingId`, `peerName`, `sharedSecret`, `createdAt`).
6. IF pairing is not completed within a timeout (e.g., 120 s) THEN GsoundA SHALL abort and
   discard the temporary code/secret.
7. WHEN the user unpairs THEN GsoundA SHALL send `UNPAIR`, delete the local PairingRecord,
   and stop serving data to that peer.
8. GsoundA SHALL support only one active pairing at a time in v1.

### Requirement 5 — Local HTTP server (data channel)

**User story:** As a user, I want my phone to serve the music over Wi-Fi, so that the watch
can download files directly.

#### Acceptance Criteria
1. WHEN a sync is triggered THEN GsoundA SHALL start a local HTTP server bound to the
   phone's Wi-Fi (LAN) interface on an available port.
2. The server SHALL expose `GET /health`, `GET /manifest`, and `GET /media/{trackId}` as
   defined in the overview §5.2.
3. The server SHALL require the header `X-Gsound-Secret` matching the paired
   `sharedSecret` AND SHALL respond `401` to requests missing or failing this check.
4. `GET /media/{trackId}` SHALL stream audio bytes with the correct `Content-Type` AND
   SHALL support HTTP `Range` requests for resumable/partial download.
5. IF a requested `trackId` is not in the current manifest THEN the server SHALL respond `404`.
6. WHEN sync completes or is cancelled THEN GsoundA SHALL stop the server.
7. IF Wi-Fi is unavailable THEN GsoundA SHALL NOT start the server and SHALL inform the
   user that both devices must be on the same Wi-Fi network.
8. GsoundA SHALL display its LAN address/port so the user can verify connectivity.

### Requirement 6 — Trigger and monitor sync

**User story:** As a user, I want to start a sync and see its progress, so that I know my
music reached the watch.

#### Acceptance Criteria
1. WHEN the user taps Sync AND a pairing exists AND the folder list is non-empty THEN
   GsoundA SHALL build the manifest, start the server, and send `SYNC_MANIFEST` with its
   `serverUrl` and `sharedSecret` over the control channel.
2. WHILE sync runs GsoundA SHALL display progress (tracks done/total, bytes) updated from
   `SYNC_STATUS` messages and/or server request activity.
3. WHEN the watch reports completion THEN GsoundA SHALL show a success summary (synced,
   skipped, removed counts) and the last-sync timestamp.
4. IF the watch reports an error OR the control channel drops THEN GsoundA SHALL surface
   the error and allow retry.
5. WHEN the user cancels sync THEN GsoundA SHALL send a cancel signal, stop the server, and
   return to idle.
6. GsoundA SHALL prevent starting a second concurrent sync while one is active.

### Requirement 7 — View watch stats

**User story:** As a user, I want to see what's on the watch, so that I can confirm sync
state.

#### Acceptance Criteria
1. WHEN GsoundA receives a `STATS` message THEN it SHALL display watch file count, folder
   count, and last-sync time.
2. WHEN paired AND the watch is reachable THEN GsoundA SHALL be able to request fresh stats
   (e.g., via `PING`/stats request).
3. IF stats are unavailable THEN GsoundA SHALL show "unknown" rather than stale data without
   indication.

### Requirement 8 — Persistence & lifecycle

#### Acceptance Criteria
1. GsoundA SHALL persist: folder list (tree URIs + display metadata), PairingRecord, and
   last-sync summary.
2. GsoundA SHALL restore all persisted state on launch.
3. GsoundA SHALL keep the HTTP server and sync coordination alive while sync is active even
   if the UI is backgrounded (e.g., via a foreground service), subject to platform limits.
4. GsoundA SHALL NOT retain unencrypted secrets in logs.

### Requirement 9 — Permissions & platform

#### Acceptance Criteria
1. GsoundA SHALL target a modern Android API level (compileSdk current; minSdk chosen to
   cover common fēnix-owner devices, see design).
2. GsoundA SHALL request only necessary permissions (SAF persisted URIs, network/Wi-Fi
   state, foreground service for sync, Bluetooth as required by the Connect IQ SDK).
3. IF a required permission is denied THEN GsoundA SHALL explain why it is needed and degrade
   gracefully.

### Requirement 10 — Error handling & resilience

#### Acceptance Criteria
1. WHEN a file becomes unavailable mid-sync (moved/deleted) THEN GsoundA SHALL return `404`
   for that track and continue serving others.
2. WHEN the control channel is temporarily unavailable THEN GsoundA SHALL queue/retry
   control messages with backoff and inform the user if it stays down.
3. GsoundA SHALL validate incoming control messages (schema, `pairingId`) and ignore
   messages from unpaired peers.
4. GsoundA SHALL handle large libraries without loading all audio bytes into memory
   (stream from disk).
