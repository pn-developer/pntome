# GsoundG (Garmin Connect IQ) — Requirements

## Introduction

GsoundG is the Garmin watch app for Gsound. It is a Connect IQ **Audio Content Provider**
app that pairs with the Android companion (GsoundA), syncs music over Wi-Fi/HTTP into its
encrypted media sandbox, reports stats, and plays the synced music through the native
Garmin media player with basic transport controls. See
`../gsound-overview/product-overview.md` for shared architecture, pairing, and protocol.

## Glossary

- **ACP**: Audio Content Provider — Connect IQ app type that plugs into the native media player.
- **Control channel**: phone↔watch messages via `Toybox.Communications` / Connect IQ Mobile SDK.
- **Data channel**: HTTP download over Wi-Fi from GsoundA's server.
- **Sandbox**: the app's encrypted media storage managed by `Toybox.Media`.
- **Manifest**: list of tracks + `serverUrl` sent by GsoundA (overview §5.1).

## Requirements

### Requirement 1 — Audio Content Provider app type & compatibility

**User story:** As a user, I want GsoundG to behave like a Garmin music provider, so that it
appears in the watch's music apps and uses the native player.

#### Acceptance Criteria
1. GsoundG SHALL be built as an `audio-content-provider-app` so it registers with the native
   media player and appears under Music > Music Providers.
2. GsoundG SHALL declare the required permissions: `Communications` (web + phone messaging),
   and media/background as required by the ACP framework.
3. GsoundG SHALL target `minApiLevel` 3.0 and SHALL list only music-capable devices in
   `manifest.xml`: fēnix 6S Pro, 6 Pro, 6X Pro, 6 Pro Solar, and fēnix 8 (AMOLED/Solar).
4. IF launched on a non-music device THEN the app SHALL not be installable/listed (enforced
   by manifest device list).

### Requirement 2 — Pairing with the phone

**User story:** As a user, I want to pair GsoundG with GsoundA using a confirmation code, so
that only my phone can sync to the watch.

#### Acceptance Criteria
1. WHEN pairing starts (initiated from either side) THEN GsoundG SHALL participate in the
   6-digit-code exchange over the control channel (overview §4).
2. WHEN a `PAIR_REQUEST` is received THEN GsoundG SHALL display the same 6-digit code and
   prompt the user to confirm it matches the phone.
3. WHEN the user confirms on the watch THEN GsoundG SHALL send `PAIR_CONFIRM` and persist the
   PairingRecord (`pairingId`, `peerName`, `sharedSecret`, `createdAt`) in `Application.Storage`.
4. IF the user rejects or a timeout elapses THEN GsoundG SHALL abort pairing and discard
   temporary values.
5. WHEN an `UNPAIR` message is received OR the user unpairs on the watch THEN GsoundG SHALL
   delete its PairingRecord.
6. GsoundG SHALL reject control messages whose `pairingId` does not match the stored record
   (except an initial `PAIR_REQUEST`).
7. GsoundG SHALL support only one active pairing at a time in v1.

### Requirement 3 — Receiving the sync manifest

**User story:** As a user, I want the watch to know which songs to fetch and from where.

#### Acceptance Criteria
1. WHEN a `SYNC_MANIFEST` message is received from the paired phone THEN GsoundG SHALL store
   the `serverUrl`, `sharedSecret` reference, and the track list for the pending sync.
2. IF the manifest is too large for a single control message THEN GsoundG SHALL support
   fetching the manifest from `GET /manifest` over HTTP instead (fallback path).
3. GsoundG SHALL validate manifest entries (id, path, mime) and ignore malformed entries.

### Requirement 4 — Syncing media over Wi-Fi (ACP sync flow)

**User story:** As a user, I want to sync my selected music to the watch over Wi-Fi, so I can
play it offline.

#### Acceptance Criteria
1. GsoundG SHALL provide a sync configuration view and a `SyncDelegate` so the native sync
   flow can start (Wi-Fi activated by the system).
2. WHEN `onStartSync` runs THEN GsoundG SHALL iterate manifest tracks and download each via
   `Communications.makeWebRequest` to `GET {serverUrl}/media/{trackId}` with the
   `X-Gsound-Secret` header and an audio response type, storing each into the media sandbox.
3. WHILE syncing GsoundG SHALL report progress to the system (`notifySyncProgress`) AND send
   `SYNC_STATUS` to the phone (done/total/bytes).
4. WHEN a track already exists in the sandbox AND is unchanged (same id) THEN GsoundG SHALL
   skip re-downloading it.
5. WHEN a track present in the sandbox is absent from the new manifest THEN GsoundG SHALL
   delete it (reconciliation).
6. WHEN sync finishes THEN GsoundG SHALL call `notifySyncComplete`, update stored stats, and
   send a final `SYNC_STATUS` + `STATS`.
7. IF a download fails THEN GsoundG SHALL retry a bounded number of times, then record the
   failure, continue with remaining tracks, and report the error in `SYNC_STATUS`.
8. IF the user cancels sync THEN GsoundG SHALL stop downloads and leave already-synced tracks
   intact.
9. GsoundG SHALL respect device storage limits and SHALL report an error if storage is
   exhausted.

### Requirement 5 — Stats

**User story:** As a user, I want to see sync stats on the watch and have them reflected on
the phone.

#### Acceptance Criteria
1. GsoundG SHALL maintain counts of synced files and distinct folders, plus `lastSyncAt`.
2. GsoundG SHALL display these stats in a view on the watch.
3. WHEN requested (e.g., `PING`/stats request) OR after sync THEN GsoundG SHALL send a `STATS`
   message to the phone.
4. Stats SHALL persist across app restarts (`Application.Storage`).

### Requirement 6 — Playback (behave like the native music app)

**User story:** As a user, I want to pick a folder and play its music with basic controls,
like Garmin's built-in music app.

#### Acceptance Criteria
1. GsoundG SHALL provide a playback configuration view listing synced **folders** (each
   folder acts as a playlist) and the tracks within a folder.
2. WHEN the user selects a folder/track THEN GsoundG SHALL start playback via the native media
   player (`Media.startPlayback` / content delegate), handing the system a `ContentIterator`
   over that folder's tracks.
3. GsoundG SHALL implement a `ContentDelegate` returning a `ContentIterator` of
   `ContentRef`s with metadata (title/artist/album) for display in the native player.
4. The native player SHALL provide basic controls: **play/pause (start/stop), next, previous**
   driven by the content iterator; GsoundG SHALL configure a `PlaybackProfile` enabling skip
   where appropriate.
5. WHEN playback reaches the end of a folder THEN GsoundG SHALL stop (v1: no cross-folder
   autoplay; looping optional).
6. IF no media is synced THEN the playback view SHALL show an empty state prompting a sync.

### Requirement 7 — Persistence & lifecycle

#### Acceptance Criteria
1. GsoundG SHALL persist PairingRecord, stats, and track/folder index in `Application.Storage`
   (respecting the 8 KB-per-value limit via a flat key structure; store per-track entries
   under individual keys, not one large nested table).
2. GsoundG SHALL restore state when launched in any mode (sync config, playback config,
   playback, or standalone).
3. GsoundG SHALL not write unencrypted secrets to logs.

### Requirement 8 — Error handling & resilience

#### Acceptance Criteria
1. IF Wi-Fi/HTTP is unreachable during sync THEN GsoundG SHALL report a clear error via
   `SYNC_STATUS` and the system sync UI, and SHALL not corrupt existing media.
2. IF the server returns `401` THEN GsoundG SHALL treat it as an auth/pairing error and
   prompt the user to re-pair.
3. IF the server returns `404` for a track THEN GsoundG SHALL skip it, count it, and continue.
4. GsoundG SHALL validate and ignore control messages from unpaired peers.
5. GsoundG SHALL handle being launched directly by the system in each ACP mode without crashing.
