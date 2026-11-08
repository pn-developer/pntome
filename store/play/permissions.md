# Permissions and foreground services

Source: `PNTome/app/src/main/AndroidManifest.xml`. Use this text for Play Console questions and the foreground service declarations.

## Permissions

| Permission | Justification |
|---|---|
| `android.permission.INTERNET` | Opens the listening socket of the embedded HTTP server that serves audio files to the user's watch on the local network. No remote servers are contacted. |
| `android.permission.ACCESS_NETWORK_STATE` | Finds the active Wi-Fi or hotspot address the server must bind to. |
| `android.permission.ACCESS_WIFI_STATE` | Reads Wi-Fi state to hold a Wi-Fi lock during sync. |
| `android.permission.CHANGE_WIFI_STATE` | Acquires a `WifiLock` during sync so transfers do not stall. |
| `android.permission.WAKE_LOCK` | Required by `WifiLock.acquire()`; keeps the radio up only while a sync runs. |
| `android.permission.FOREGROUND_SERVICE` | Runs the sync service and the playback service in the foreground. |
| `android.permission.FOREGROUND_SERVICE_DATA_SYNC` | Required on Android 14+ for the `dataSync` foreground service type used by `SyncForegroundService`. |
| `android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Required on Android 14+ for the `mediaPlayback` foreground service type used by `PlaybackService`. |
| `android.permission.POST_NOTIFICATIONS` | Shows sync progress and the media playback notification. Requested at runtime (Android 13+) with a rationale when the user starts a sync. Sync still works if denied. |

## Permissions deliberately not requested
- Storage and media (`READ_EXTERNAL_STORAGE`, `READ_MEDIA_AUDIO`): folders are chosen with the Storage Access Framework and access is persisted per folder.
- Bluetooth (`BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN`): Garmin Connect Mobile owns the Bluetooth link to the watch.
- Location: not used.

## Foreground service types

### `dataSync` (`SyncForegroundService`)
- Declared with `android:foregroundServiceType="dataSync"`, not exported.
- Purpose: transfers the user's selected music and audiobooks to their Garmin watch.
- Started only when the user taps Sync. A notification shows progress and a Cancel action. It stops when the transfer ends or is cancelled.
- Why foreground: a transfer can take many minutes and must continue while the screen is off or the user is in another app. Deferrable work APIs cannot keep a local server and Wi-Fi lock alive.
- Android 15 limits `dataSync` runtime per 24 hours; the service must stop cleanly when the system calls `onTimeout`.
- Video for the declaration: open the app, pair, tap Sync, show the notification and Cancel, finish.

### `mediaPlayback` (`PlaybackService`)
- Declared with `android:foregroundServiceType="mediaPlayback"`, exported with the Media3 `MediaLibraryService` intent filters so media controllers and Android Auto can bind.
- Purpose: plays the user's audio with a media notification and lock-screen controls.
- Core function of the app: audio playback continues in the background.
- Video for the declaration: start playback, leave the app, show the media notification and controls, pause.
