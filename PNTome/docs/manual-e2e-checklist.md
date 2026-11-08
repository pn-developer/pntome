# PNTome — Manual End-to-End Test Checklist

This checklist covers the parts of the PNTome flow that the automated unit and
instrumented suites cannot exercise: the real Connect IQ control channel and the
phone↔watch data channel over Wi-Fi. Run it against the **Connect IQ simulator** and,
when possible, a **real Garmin fēnix** watch.

- Spec: `.kiro/specs/gsound-android` (requirements §1–§10, design §1–§10)
- Scope: full E2E flow — folder add → scan → pair → sync → stats
- Deliverable for task **11.3** (Reqs 8.4, 9.2, 9.3, 10.3)

Legend: check each `[ ]` box as you verify it. Note the device/sim profile and app
versions at the top of each run.

```
Run date: __________    Tester: __________
Phone model / Android version: __________________    PNTome build (git sha): __________
Watch model or sim profile: __________________        PNTomeGo build / app UUID: __________
```

---

## 0. Known placeholders / blockers (read first)

> These are prerequisites that are NOT satisfied by this repo alone. The checklist cannot
> be completed end-to-end until they are resolved.

- [ ] **App id must match.** `net/garmin/PNTomeAppId.VALUE` is
      `0236dc0a39fd45f09a109f48fc8c7be2`; the PNTomeGo `manifest.xml` `id` must be
      identical. If they differ, the SDK reports PNTomeGo as **not installed**
      (`APP_NOT_INSTALLED`) and pairing/sync cannot succeed.
- [ ] **PNTomeGo must be installed.** Build it from `PNTomeGo/` (see its `INSTALL.md`) and
      install it on the watch or load it into the Connect IQ simulator. Pairing, sync,
      and stats all require a cooperating watch peer.
- [ ] **Old builds are replaced.** Uninstall earlier GsoundA/GsoundG builds on the phone and
      watch; the phone and watch builds must both be new (header `X-PNTome-Secret`).
- [ ] The two above must be in **lockstep**: the UUID in `PNTomeAppId.VALUE` must be
      byte-for-byte identical to the PNTomeGo manifest `id`.

---

## 1. Prerequisites & environment

- [ ] Android device or emulator on **minSdk 26+** (Android 8.0+); design targets
      `compileSdk/targetSdk = 35`.
- [ ] **Garmin Connect Mobile** installed on the phone and signed in (the Connect IQ Mobile
      SDK routes messages through it).
- [ ] A paired fēnix in Garmin Connect **OR** the **Connect IQ simulator** running with a
      fēnix device profile loaded.
- [ ] **PNTomeGo installed** on the watch/simulator with the matching app UUID (see §0).
- [ ] Phone and watch/simulator host are on the **same Wi-Fi / LAN subnet** (required for the
      data channel; Req 5.7).
- [ ] A **music folder** reachable via SAF containing at least a few **.mp3** files.
- [ ] (Optional) Add **.m4a / .aac / .wav / .flac** files to exercise the optional-format
      settings flag (default is mp3-only).
- [ ] (Optional) A laptop on the same LAN with `curl` for manual server probing (§6, §8).

---

## 2. Build, deploy, launch

- [ ] Build debug APK: `./gradlew assembleDebug` completes cleanly (ref task 12.1).
- [ ] Install to device: `./gradlew installDebug` **or** `adb install -r app/build/outputs/apk/debug/app-debug.apk` (ref task 12.2).
- [ ] Launch PNTome; the bottom-nav shell shows **Folders**, **Pairing**, **Sync**.
- [ ] No crash on cold start; no secrets printed during init (spot-check logcat).

---

## 3. Folder flow (Req 1, 2, 3)

### Add & persist (Req 1, 2.5)
- [ ] Tap **Add folder** → the **system SAF picker** (`OPEN_DOCUMENT_TREE`) opens (Req 1.1).
- [ ] Select a folder → it appears in the list and is persisted (Req 1.3).
- [ ] Force-stop and relaunch the app → the folder is **still listed** and still readable
      (persisted URI permission survived restart) (Req 1.2, 2.5).
- [ ] Cancel the picker without selecting → folder list is **unchanged** (Req 1.5).

### Duplicate & remove (Req 1.4, 2.2, 2.3)
- [ ] Add the **same folder again** → no duplicate row AND the user is **informed** it is
      already added (Req 1.4).
- [ ] Tap remove on a folder → a **confirmation** dialog appears (Req 2.3).
- [ ] Confirm removal → folder disappears AND its persisted URI permission is **released**
      (verify the app can no longer read it afterward) (Req 2.2).

### Empty state & gating (Req 2.4)
- [ ] With **zero folders**, an empty state prompts to add a folder AND the **Sync action is
      disabled** (Req 2.4).

### Scan (Req 3)
- [ ] After adding a folder, a scan runs and **progress is shown**; UI stays responsive
      (Req 3.6).
- [ ] Each folder row shows **display name, derived path, eligible track count, total size**
      (Req 2.1) and the counts/sizes match the actual files.
- [ ] Scan **recurses** into subfolders (nested mp3s are found) (Req 3.1).
- [ ] **Unsupported files are skipped** and counted as skipped (drop a `.txt`/`.ogg` in the
      folder and confirm it is not offered) (Req 3.2, 3.3).
- [ ] With the optional-format flag **off** (default), only mp3 is eligible; with it **on**,
      m4a/aac/wav/flac also become eligible (Req 3.2).
- [ ] A file **with ID3 tags** shows metadata title/artist/album; a file **without tags**
      falls back to the **filename** for title (Req 3.4).
- [ ] Rescanning an unchanged folder keeps the **same track ids** (stable across scans)
      (Req 3.5).

---

## 4. Pairing flow (Req 4)

- [ ] Go to **Pairing** and tap **Pair** → PNTome discovers the PNTomeGo app on the connected
      device/sim (Req 4.1).
- [ ] **No device / app available** (e.g., PNTomeGo not installed, placeholder UUID, Garmin
      Connect closed, Bluetooth off) → an **actionable error** is shown, e.g. "Open Garmin
      Connect / install PNTomeGo" (Req 4.2).
- [ ] On a successful start, a **6-digit code** is displayed with instructions to confirm it
      matches the watch (Req 4.3, 4.4).
- [ ] Pairing requires **BOTH** the local **Confirm** tap AND a `PAIR_CONFIRM` from the watch:
  - [ ] Local Confirm only (no watch confirm) → does **not** complete.
  - [ ] Watch confirm only (no local Confirm) → does **not** complete.
  - [ ] Both → pairing **completes** and the `PairingRecord` (pairingId, peerName,
        sharedSecret, createdAt) is persisted (Req 4.5).
- [ ] **Timeout (~120 s):** start pairing, do nothing → pairing **aborts** and the temporary
      code/secret are discarded (Req 4.6).
- [ ] After pairing, the screen shows the **paired peer** (watch name/model).
- [ ] Tap **Unpair** → PNTome sends `UNPAIR`, clears the local record, and no longer serves
      that peer (Req 4.7).
- [ ] Only **one active pairing** is possible at a time (Req 4.8).

---

## 5. Permissions (Req 9)

- [ ] First time a notification is needed (sync), PNTome requests **POST_NOTIFICATIONS**
      (Android 13+) with a **rationale** explaining why (Req 9.2, 9.3).
- [ ] **Deny** POST_NOTIFICATIONS → sync still runs (foreground service active) but **no
      notification** appears in the shade; app degrades gracefully (Req 9.3).
- [ ] **No Bluetooth runtime prompt** is shown by PNTome (the Connect IQ link goes through
      Garmin Connect; no in-app BT scanning permission is expected) (Req 9.2).

---

## 6. Sync + local server (Req 5, 6, 8, 10)

### Gating & Wi-Fi guard (Req 5.7, 6.1)
- [ ] Sync button is **enabled only when** a pairing exists AND the folder list is non-empty.
- [ ] **Wi-Fi off / not on same LAN:** tapping Sync shows **guidance** that both devices must
      be on the same Wi-Fi, AND the server is **not started** (Req 5.7).

### Start & serve (Req 5, 6.1, 6.2)
- [ ] Turn Wi-Fi on, tap **Sync** → PNTome builds the manifest, starts the server, and sends
      `SYNC_MANIFEST` with `serverUrl` + `sharedSecret` (Req 6.1).
- [ ] The **LAN address/port** is displayed so you can verify connectivity (Req 5.8).
- [ ] `GET /health` returns `{"ok":true}` from a laptop on the LAN:
      `curl http://<lan-ip>:<port>/health` (note: health is reachable; secret-gated routes
      below).
- [ ] The watch pulls `/manifest` then `/media/{trackId}` for each track; **progress updates**
      (tracks done/total, bytes) appear in the UI from `SYNC_STATUS`/request activity (Req 6.2).

### Foreground service & lifecycle (Req 6.5, 8.3, 5.6)
- [ ] A **foreground notification** shows sync progress and a **Cancel** action (Req 6.5, 8.3).
- [ ] **Background the app** mid-sync (home button) → sync keeps running (foreground service
      + WifiLock) (Req 8.3).
- [ ] Tap **Cancel** → PNTome sends a cancel signal, **stops the server**, returns to idle
      (Req 6.5); re-probe `/health` with curl → connection **refused** (server down).
- [ ] On normal completion, the server **stops** too (Req 5.6).

### Completion summary (Req 6.3)
- [ ] On watch-reported completion, a **success summary** shows synced / skipped / removed
      counts AND the **last-sync timestamp** (Req 6.3), and it persists across restart (Req 8.1).

### Error paths (Req 6.4, 6.6)
- [ ] Simulate a watch error or drop the control channel mid-sync → PNTome **surfaces the
      error** and offers **retry** (Req 6.4).
- [ ] While a sync is active, attempting to start a **second sync** is **prevented** (Req 6.6).

### Security & resilience of the data channel (Req 5.3, 5.5, 10.1, 10.4)
- [ ] **Auth 401:** from a laptop, `curl http://<lan-ip>:<port>/manifest` with **no**
      `X-PNTome-Secret` → **401**; with a **wrong** secret → **401** (Req 5.3).
- [ ] **Correct secret** `curl -H "X-PNTome-Secret: <secret>" .../manifest` → **200** + JSON.
- [ ] **Unknown trackId:** `curl -H "X-PNTome-Secret: <secret>" .../media/doesnotexist` →
      **404** (Req 5.5).
- [ ] **Range/resume:** `curl -H "X-PNTome-Secret: <secret>" -H "Range: bytes=0-1023"
      .../media/<id>` → **206 Partial Content** with `Content-Range` and `Accept-Ranges:
      bytes` (Req 5.4). Interrupt a large transfer and confirm it resumes.
- [ ] **File removed mid-sync:** delete/move one source file during a sync → that track
      returns **404** while **others continue** to serve (Req 10.1).
- [ ] **Memory:** sync a large library and confirm PNTome does **not** load whole files into
      memory (streamed from disk; watch RAM/GC stays flat) (Req 10.4).

---

## 7. Watch stats (Req 7)

- [ ] After a sync, a `STATS` message updates the UI with watch **file count, folder count,
      and last-sync time** (Req 7.1).
- [ ] When stats are **unavailable** (e.g., just paired, no sync yet, watch unreachable), the
      UI shows **"unknown"** rather than stale data without indication (Req 7.3).
- [ ] While paired and the watch is reachable, **Refresh** requests fresh stats via
      `PING`/stats request and the values update (Req 7.2).

---

## 8. Security & logging hardening (Req 8.4, 10.3)

- [ ] **Secrets never logged:** run the full flow while capturing logcat, then grep for the
      secret and header name — expect **no matches** of the actual secret value:
      `adb logcat -d | grep -i -E 'X-PNTome-Secret|sharedSecret|<the-actual-secret>'`
      (the redacting logger should mask these; Req 8.4).
- [ ] **Path traversal rejected:** `curl -H "X-PNTome-Secret: <secret>"
      "http://<lan-ip>:<port>/media/..%2f..%2fetc%2fpasswd"` (and `/media/../../`) →
      **404** (trackIds are opaque hashes; no traversal; Req 10.3 / design §8).
- [ ] **Unpaired-peer messages ignored:** send a control message with a wrong/missing
      `pairingId` → PNTome **ignores** it (Req 10.3). (Easiest with the simulator's message
      injection.)

---

## 9. Connect IQ reliability notes (design §10)

> The Connect IQ Mobile SDK message channel (via Garmin Connect) has had reported delivery
> regressions. Observe and note the following rather than treating them as hard pass/fail.

- [ ] If a control message appears lost, PNTome **retries with backoff** and keeps a
      user-visible status (Req 10.2) rather than silently hanging.
- [ ] If the watch goes out of BT range mid-sync and returns, note whether sync/control
      recovers or needs a retry.
- [ ] A full **re-pair** (unpair → pair again) succeeds and produces a new `pairingId`/secret.
- [ ] On some fēnix models Wi-Fi is restricted while BT-connected — if the data channel never
      connects, confirm both devices truly share a LAN and note the model (design §10 risk).

---

## 10. How to capture logs

- **Live app logs:** `adb logcat --pid=$(adb shell pidof -s dev.pntome.app)`
- **Dump + save a session:** `adb logcat -c` (clear) → run the flow →
  `adb logcat -d > e2e-run-$(date +%Y%m%d-%H%M%S).log`
- **Secret-leak grep (expect no hits):**
  `adb logcat -d | grep -i -E 'secret|x-pntome-secret'`
- **Server probing from a LAN laptop:** use `curl -v` against `http://<lan-ip>:<port>/...`;
  the LAN address/port is shown on the Sync screen (Req 5.8).
- **Connect IQ simulator:** launch via the Connect IQ SDK (`connectiq`), load the fēnix
  device profile, side-load PNTomeGo, and use its message console to inject/inspect control
  messages. Garmin Connect Mobile must be running for the SDK channel to route.

---

## 11. Release-candidate smoke (User_Action)

> **User_Action.** Needs a real phone, a real watch and your signing key, so it is not run
> by the agent or by CI. Do it once per release candidate (`versionName` / `versionCode` in
> `PNTome/app/build.gradle.kts`), before uploading to a store.

Candidate: version ________  versionCode ____  APK/AAB sha256: __________________

- [ ] Build signed artifacts with the four `PNTOME_*` values set (see `INSTALL.md`,
      section 4a): `./gradlew assembleRelease bundleRelease`. No "UNSIGNED" warning appears.
- [ ] `apksigner verify --print-certs` on the release APK lists your release certificate.
- [ ] Uninstall old GsoundA/GsoundG builds, then install the release **APK**
      (`adb install` or sideload) on a clean phone profile. App name shows **PNTome**.
- [ ] Install the matching **PNTomeGo** build on the watch (same version) and pair; the
      6-digit codes match and pairing completes.
- [ ] Add a folder, scan, run a sync of a few tracks; the watch plays them.
- [ ] Phone player: play, pause, seek, background playback, notification controls.
- [ ] Foreground-service timeout: leave a long sync running on Android 15 and confirm it
      stops cleanly with a visible message if the system times it out.
- [ ] Bookmark sync: listen on one device, Refresh stats, the other resumes about 5 s earlier.
- [ ] App data is not backed up (`allowBackup=false`): no restore prompt after reinstall.
- [ ] No secret in `adb logcat -d | grep -i -E 'x-pntome-secret|sharedSecret'`.
- [ ] If the AAB goes to Google Play, install it from the internal testing track and repeat
      the pairing and sync checks above.
- [ ] Result: ☐ Pass  ☐ Fail — notes: __________________________

---

## 12. Sign-off

- [ ] All applicable boxes above checked (note any skipped due to §0 blockers).
- [ ] Blockers / anomalies recorded with logcat excerpts attached.
- [ ] Result: ☐ Pass  ☐ Pass with notes  ☐ Fail — summary: __________________________
