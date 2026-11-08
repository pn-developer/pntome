# Implementation Plan: PNTome Store Release

## Overview

Rename GsoundA to PNTome (`dev.pntome.app`) and GsoundG to PNTomeGo, then add release engineering for Google Play (AAB), a self-hosted F-Droid repository and the Garmin Connect IQ Store. Work in this order: rename Android, rename Garmin, add signing and export, write metadata and documents, add the offline `check-release.sh` script, then verify with offline builds and tests.

Constraints that apply to every task:

- Implementation languages are the ones already in the repo: Kotlin (Gradle Kotlin DSL), Monkey C and bash. Documents are Markdown and YAML.
- Preserve the Connect IQ app id `0236dc0a39fd45f09a109f48fc8c7be2`.
- Never commit keystores, passwords, `.der` keys, `local.properties`, built `.apk`, `.aab` or `.iq` files.
- Use `./gradlew --offline` for all Gradle runs. If a tool is missing (for example `fdroidserver`, `apksigner`), do not install it. Report the package name, its one-line description and the `sudo apt install <pkg>` command to the user and wait.
- There is no phone and no watch. Checks that need hardware are written down as User_Actions, not run (see the `no-physical-devices` skill). Use the `connectiq-simulator` skill for simulator runs.
- Placeholders for user-supplied URLs and contacts use the host `example.invalid`.

## Tasks

- [x] 1. Baseline and top-level folder rename
  - [x] 1.1 Capture a baseline and rename the two app folders
    - Confirm a clean git tree. Record `sha256sum` of `GsoundA/app/schemas/*/{1,2,3}.json` to a temp file outside the repo.
    - Run `./gradlew --offline testDebugUnitTest` in `GsoundA/` and build `fenix6spro` with `GsoundG/build.sh`; note results (these are the pre-rename baselines).
    - Run `git mv GsoundA PNTome` and `git mv GsoundG PNTomeGo`. Delete leftover untracked build output (`build/`, `.gradle`, `.kotlin`, `dist/`) in the old paths if they remain.
    - Do not touch `.kiro/specs/gsound-*` folder names.
    - _Requirements: 1.1, 1.2, 1.7_

- [x] 2. Rename the Android app to PNTome
  - [x] 2.1 Move the Kotlin package and rewrite identifiers
    - `git mv` `src/main/java/dev/gsound/a`, `src/test/java/dev/gsound/a`, `src/androidTest/java/dev/gsound/a` to `.../dev/pntome/app`.
    - Rewrite every `package` and `import` from `dev.gsound.a` to `dev.pntome.app`; update `java_package` in `pairing.proto`, `settings.proto`, `sync_summary.proto`.
    - Set `namespace` and `applicationId` to `dev.pntome.app` in `PNTome/app/build.gradle.kts`; set `rootProject.name = "PNTome"` in `PNTome/settings.gradle.kts`.
    - Update `AndroidManifest.xml` references (`.GsoundApplication` stays until 2.2) and the androidTest manifest provider authority to `dev.pntome.app.test.seededdocs`, including the test code that uses it.
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.10_

  - [x] 2.2 Rename Gsound-prefixed symbols, constants, schema folder and resources
    - Use the semantic rename tool for classes: `GsoundApplication`, `GsoundDatabase`, `GsoundJson`, `GsoundLog`, `GsoundRouting`, `GsoundHttpServer`, `GsoundAppId`, and any other `Gsound*` identifier found by `grep`. Rename files to match.
    - `GsoundDatabase.NAME` to `"pntome.db"`; `CHANNEL_ID` to `"pntome_sync"`; `ACTION_*` strings to `dev.pntome.app.action.*`; log tags to `PNTome.HTTP`, `PNTome.CIQ`, `PNTome.Watch`; `SECRET_HEADER` to `"X-PNTome-Secret"`.
    - `git mv app/schemas/dev.gsound.a.data.folders.GsoundDatabase app/schemas/dev.pntome.app.data.folders.PNTomeDatabase`; JSON content must be unchanged (compare with the baseline checksums).
    - `strings.xml`: `app_name` = "PNTome"; replace "GsoundA"/"GsoundG" in user-facing strings; rename style `Theme.GsoundA` to `Theme.PNTome` and update manifest and `automotive_app_desc.xml` comment.
    - Update KDoc/comments mentioning GsoundA/GsoundG/Gsound in `src/main`, `src/test`, `src/androidTest`.
    - Do not change `GsoundAppId.VALUE`'s value (`0236dc0a39fd45f09a109f48fc8c7be2`).
    - _Requirements: 1.3, 1.5, 2.5, 2.6, 2.7, 2.8, 2.9, 2.11, 3.2, 3.3_

  - [x] 2.3 Compile and run the JVM tests after the rename
    - Run `./gradlew --offline testDebugUnitTest assembleDebugAndroidTest` in `PNTome/` and fix failures caused by the rename only.
    - Confirm the Room JVM migration test still reads the moved schema folder.
    - _Requirements: 2.12_

- [x] 3. Checkpoint - Android rename compiles and passes
  - Ensure all tests pass, ask the user if questions arise.

- [x] 4. Rename the Garmin app to PNTomeGo
  - [x] 4.1 Rename watch resources, classes and shared protocol values
    - `strings.xml`: `AppName` and `ProviderName` = "PNTomeGo".
    - Rename `GsoundGApp` to `PNTomeGoApp` (class and file) and update `manifest.xml` `entry`. Rename `GsoundContentDelegate` and `GsoundSyncDelegate` (classes and files) to `PNTomeContentDelegate` and `PNTomeSyncDelegate`; update all references in `source/` including `source/test/`.
    - Change the header in `source/sync/Downloader.mc` to `X-PNTome-Secret`; change the log tag in `DebugLog.mc` to `PNTome.Watch`.
    - Update comments that name GsoundA/GsoundG. Update the `manifest.xml` header comment (it must still say the id equals `PNTomeAppId.kt`). Keep `id="0236dc0a39fd45f09a109f48fc8c7be2"`, `version`, products and permissions unchanged.
    - _Requirements: 1.4, 1.5, 3.1, 3.4, 3.5, 3.6, 4.1, 4.2_

  - [x] 4.2 Update watch scripts and skill paths
    - `build.sh`: default output `build/PNTomeGo-<device>.prg`, header comments, "run from PNTomeGo/" message.
    - `run_in_simulator.sh`, `sim/common.sh`, `sim/setup_container.sh`: new folder name and output name.
    - `.kiro/skills/connectiq-simulator/SKILL.md` (and `scripts/ciq-sim.sh` if it names the folder): use `PNTomeGo/` paths and `/tmp/pntomego.prg`.
    - Rename `test-media/gsound-test-tone.mp3` to `pntome-test-tone.mp3` only if referenced, and update references.
    - Add `*.iq`, `build/`, `dist/` coverage check in `PNTomeGo/.gitignore` (already present; keep).
    - _Requirements: 1.6, 1.8, 4.3, 4.4, 4.9_

  - [x] 4.3 Build the renamed watch app for all products
    - Run `PNTomeGo/build.sh` (release and `--debug`) for each of the eight manifest products and confirm eight `.prg` files in `build/PNTomeGo-<device>.prg`.
    - Confirm the existing version guard still passes.
    - _Requirements: 4.8, 4.10_

- [x] 5. Release engineering for Android and Garmin
  - [x] 5.1 Add release signing, AAB settings and ignore rules to the Android build
    - In `PNTome/app/build.gradle.kts`: add signing config read from `PNTOME_KEYSTORE_FILE`, `PNTOME_KEYSTORE_PASSWORD`, `PNTOME_KEY_ALIAS`, `PNTOME_KEY_PASSWORD` or `PNTome/keystore.properties`; unsigned plus warning when none are present; configuration failure naming missing inputs when only some are present.
    - Add `dependenciesInfo { includeInApk = false; includeInBundle = false }` and set the archives base name to `PNTome`.
    - Keep `versionCode = 1` and `versionName = "0.1.0"` as single literals; keep `targetSdk = 35`; keep `isMinifyEnabled = false`.
    - Add a comment to `proguard-rules.pro` explaining why minification is off for the first release (no device to smoke-test R8 output).
    - Add `keystore.properties`, `*.jks`, `*.keystore` to `PNTome/.gitignore`.
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5, 5.6, 5.7, 5.8, 5.9, 5.10_

  - [x] 5.2 Update the manifest and handle the dataSync timeout
    - Set `android:allowBackup="false"` in `AndroidManifest.xml`; update the permission-policy comment to PNTome; keep the permission list and service types unchanged.
    - Inspect `SyncForegroundService`. If it has no `onTimeout(startId, fgsType)` override, add one that cancels the sync, stops the foreground service and posts a user-visible message.
    - Add a Robolectric unit test that triggers the timeout path and asserts the sync is cancelled and the service stops.
    - _Requirements: 6.1, 6.2, 6.3, 6.5_

  - [x] 5.3 Add `--export` to the watch build script
    - In `PNTomeGo/build.sh`, add `--export`: release flavor, `monkeyc -e -r -f monkey.jungle -y "$CIQ_DEVKEY" -o dist/PNTomeGo-<version>.iq`, version taken from `Version.mc`.
    - Exit non-zero with a clear message when combined with `--debug`, and when `git status --porcelain` reports uncommitted changes.
    - Do not stamp a `-dirty` commit in export mode. Update the `--help` text.
    - _Requirements: 4.5, 4.6, 4.7_

  - [x] 5.4 Add license, third-party list and root ignore rules
    - Create `LICENSE` with the GPL-3.0 text (fetch the canonical text from gnu.org; do not paraphrase).
    - Create `THIRD_PARTY.md`: table of runtime dependency groups from `libs.versions.toml` with licenses; mark `com.garmin.connectiq:ciq-companion-app-sdk` as proprietary (Garmin license); add the open item about GPL with a proprietary SDK and ask the user to confirm (not legal advice).
    - Create root `.gitignore` with `*.jks`, `*.keystore`, `keystore.properties`, `*.der`, `*.apk`, `*.aab`, `*.iq`, `local.properties`, `fdroid/repo/`, `fdroid/archive/`, `fdroid/config.yml`, `fdroid/keystore*`.
    - _Requirements: 5.10, 8.9, 10.10, 10.12_

- [x] 6. Store metadata
  - [x] 6.1 Create Google Play and fastlane metadata
    - Under `PNTome/fastlane/metadata/android/en-US/`: `title.txt` (`PNTome`), `short_description.txt` (80 chars or fewer), `full_description.txt` (4000 chars or fewer), `changelogs/1.txt` (500 chars or fewer), `images/README.md` (icon 512x512, feature graphic 1024x500, at least 2 phone screenshots; user supplies them).
    - Under `store/play/`: `README.md` (User_Action checklist and the Play target-API policy note to re-check), `listing.md`, `data-safety.md` (no data collected or shared; honest note that local transfer is not encrypted in transit), `permissions.md` (every permission and both foreground service types with justification).
    - Do not add fake screenshots or artwork.
    - _Requirements: 6.4, 7.1, 7.2, 7.3, 7.4, 7.5, 7.6, 7.7, 7.8, 7.9, 7.10, 5.8_

  - [x] 6.2 Create F-Droid self-hosted repository files
    - Create `fdroid/metadata/dev.pntome.app.yml` from the design draft (License `GPL-3.0-or-later`, `NonFreeDep` anti-feature, Builds entry with `subdir: PNTome/app`, `UpdateCheckData` for the two Gradle literals). Use `example.invalid` for URLs.
    - Create `fdroid/README.md` (init, update, deploy with `fdroidserver`; each signing or deploy step marked as a User_Action; note that Play and F-Droid repo builds use different signing keys) and `fdroid/config.example.yml` with no secret values.
    - If `fdroid` is not installed, only report the package and install command to the user; do not install.
    - _Requirements: 8.1, 8.2, 8.3, 8.4, 8.5, 8.6, 8.7, 8.8, 8.10_

  - [x] 6.3 Create Garmin Connect IQ Store files
    - Under `store/garmin/`: `README.md` (submission steps as User_Actions, including keeping the developer key), `listing.md` (name PNTomeGo, short and long description, category, what's new, states that PNTome on the phone and Garmin Connect Mobile are required), `images.md` (required images, capturing screenshots with the simulator, re-check current store requirements).
    - _Requirements: 9.1, 9.2, 9.3, 9.4, 9.5_

- [x] 7. Documentation
  - [x] 7.1 Update the root README and INSTALL
    - `README.md`: PNTome and PNTomeGo names, new layout tree, install channels, license (SPDX `GPL-3.0-or-later`), links to `PRIVACY.md`, `CHANGELOG.md`, `THIRD_PARTY.md`; remove the outdated placeholder-UUID paragraph; add the note that `.kiro/specs/gsound-*` are historical.
    - Root `INSTALL.md`: new names, paths and links; add a "Release builds" pointer.
    - _Requirements: 1.5, 10.1, 10.2, 10.3, 10.6, 10.7, 10.10_

  - [x] 7.2 Update the Android INSTALL guide and manual checklist
    - `PNTome/INSTALL.md`: names and paths; add sections for release APK, release AAB, signing (env variables and `keystore.properties`, `keytool` example with a placeholder path), unsigned fallback, and building the self-hosted F-Droid repo; note that missing system packages are installed by the user with `sudo apt install`.
    - `PNTome/docs/manual-e2e-checklist.md`: names and paths; add a release-candidate smoke section marked as User_Action.
    - State in INSTALL that old phone and watch builds must be replaced together.
    - _Requirements: 3.7, 10.4, 10.7_

  - [x] 7.3 Update the Garmin INSTALL guide
    - `PNTomeGo/INSTALL.md`: names and paths; add "Export for the Connect IQ Store" with `./build.sh --export`, developer-key retention, and the old/new pairing replacement note.
    - _Requirements: 3.7, 10.5, 10.7_

  - [x] 7.4 Create PRIVACY, CHANGELOG and the release checklist
    - `PRIVACY.md`: plain-language policy per requirement 10.8 and 10.9 (last-updated date, contact placeholder `privacy@example.invalid`).
    - `CHANGELOG.md`: Keep a Changelog format; `0.1.0` entry listing the rename (the only place besides the allowlisted specs that may name the old brand, on lines describing the rename) and the pairing-replacement note.
    - `docs/RELEASE_CHECKLIST.md`: ordered release steps; a table of checks that cannot be run without a phone or watch, marked User_Action.
    - _Requirements: 3.7, 10.8, 10.9, 10.11, 10.13, 12.8_

- [x] 8. Release-check script
  - [x] 8.1 Create `scripts/check-release.sh` with the legacy-name and package checks
    - Offline bash, `set -euo pipefail`, collects all violations and exits 1 at the end; supports `--release`.
    - Property 1 (legacy names outside the Allowlist, scanning tracked file paths and contents) and Property 2 (Kotlin `package` equals directory; `.proto` `java_package` prefix).
    - **Property 1: No legacy name outside the allowlist**
    - **Property 2: Package matches directory**
    - **Validates: Requirements 1.5, 1.6, 2.3, 2.4, 2.11, 4.4, 11.1, 11.2**

  - [x] 8.2 Add the contract, version and F-Droid checks
    - Property 3: compare Connect IQ id, Secret_Header and watch log tag between `PNTomeAppId.kt`/`SecretAuth.kt`/`ConnectIqManager.kt` and `manifest.xml`/`Downloader.mc`/`DebugLog.mc`.
    - Property 4: compare Android `versionName`, `Version.mc` SEMVER and manifest version; print all three on mismatch.
    - Property 5: compare `dev.pntome.app.yml` with the Gradle literals and apply `UpdateCheckData`.
    - **Property 3: Phone and watch share the same contract values**
    - **Property 4: Version contract**
    - **Property 5: F-Droid metadata mirrors Gradle**
    - **Validates: Requirements 3.2, 3.3, 3.4, 3.5, 3.8, 4.8, 8.4, 8.5, 11.3, 11.4, 11.8, 11.9**

  - [x] 8.3 Add the store-text, permission, secret and placeholder checks
    - Property 6: character limits (30, 80, 4000, 500) and changelog file for the current `versionCode`.
    - Property 7: every `uses-permission` name and `foregroundServiceType` in the source manifest appears in `store/play/permissions.md`.
    - Property 8: no tracked `*.jks`, `*.keystore`, `*.der`, `keystore.properties`, `local.properties`.
    - Property 9: `--release` lists and fails on remaining `example.invalid` placeholders; normal mode only warns.
    - **Property 6: Store text within limits**
    - **Property 7: Every manifest permission is justified**
    - **Property 8: No secrets tracked**
    - **Property 9: Placeholders are explicit**
    - **Validates: Requirements 5.10, 6.4, 7.2, 7.3, 7.4, 7.5, 10.13, 11.5, 11.6, 11.7, 11.10, 11.11**

  - [x] 8.4 Write the shell test for the script
    - Create `scripts/test-check-release.sh`: copy the repo (tracked files) to a temp dir, expect success, then inject one violation per check (legacy name, wrong package, differing app id, differing header, version drift, F-Droid drift, over-long title, missing changelog, undocumented permission, tracked `.jks`, remaining placeholder in `--release`) and expect failure naming the check each time. Clean up the temp dir.
    - _Requirements: 11.12_

- [x] 9. Verification without physical devices
  - [x] 9.1 Verify the Android build, tests and signing offline
    - In `PNTome/`: `./gradlew --offline testDebugUnitTest testReleaseUnitTest assembleDebugAndroidTest lintRelease assembleRelease bundleRelease`; confirm the AAB and unsigned APK exist and the merged release manifest satisfies requirements 6.2, 6.3, 6.6.
    - Check the dependency list for Google Play services, Firebase, ads or analytics (requirement 6.7).
    - Generate a throwaway keystore with `keytool` in a temp dir, set the `PNTOME_*` variables, rebuild, verify the signature with `apksigner verify` (if absent, report the package and install command to the user), confirm partial-input failure (5.4), then delete the temp keystore.
    - Confirm the built APK contains no dependency metadata block.
    - _Requirements: 5.2, 5.3, 5.4, 5.6, 5.7, 6.2, 6.3, 6.6, 6.7, 12.1, 12.2, 12.3_

  - [x] 9.2 Verify the watch builds, unit tests and export
    - Build debug and release for the eight products; run the Monkey C unit tests through the `connectiq-simulator` skill for `fenix6pro` and `fenix847mm` (use `ciq-sim.sh test`/`start`/`run`); confirm no runtime error at startup.
    - Run `./build.sh --export` on a clean tree (commit not required for verification: use a temporary git stash-free approach by running it against a clean checkout copy) and confirm `dist/PNTomeGo-<version>.iq`; confirm `--export --debug` and dirty-tree export fail.
    - _Requirements: 4.5, 4.6, 4.7, 4.10, 12.4, 12.5, 12.6_

  - [x] 9.3 Run the release-check and fix leftovers
    - Run `scripts/check-release.sh` and `scripts/test-check-release.sh`; fix every violation at its source (do not widen the Allowlist without justification).
    - Run `git status` to confirm no build artifacts, keystores or temp files are staged or left behind, and run `git log --follow` on one moved file to confirm history.
    - Write the verification results and the list of unverified User_Actions into `docs/RELEASE_CHECKLIST.md`.
    - _Requirements: 1.2, 11.11, 12.7, 12.8, 12.9_

- [x] 10. Final checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- This plan has no optional tasks: the shell test and every property check are required by the requirements.
- The properties are repository invariants checked by `scripts/check-release.sh`, not randomized property tests. No property-testing library is added.
- Publishing, signing keys, store accounts, store images, hosting the privacy policy and the fdroid repository, and checks that need a phone or watch are User_Actions. They are documented in `store/*/README.md`, `fdroid/README.md` and `docs/RELEASE_CHECKLIST.md`.
- Replace all `example.invalid` placeholders before submission. `scripts/check-release.sh --release` lists what remains.
- Open items for the user: confirm GPL-3.0-or-later with the proprietary Connect IQ SDK (see `THIRD_PARTY.md`); confirm `dev.pntome.app` is the id you want; check the current Google Play target-API and Connect IQ Store requirements at submission time.
- Out of scope: R8 minification, main F-Droid (fdroiddata) inclusion, additional locales, CI configuration, SPDX headers in every source file.

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1"] },
    { "id": 1, "tasks": ["2.1", "4.1"] },
    { "id": 2, "tasks": ["2.2", "4.2"] },
    { "id": 3, "tasks": ["2.3", "4.3"] },
    { "id": 4, "tasks": ["5.1", "5.2", "5.3", "5.4"] },
    { "id": 5, "tasks": ["6.1", "6.2", "6.3", "7.4"] },
    { "id": 6, "tasks": ["7.1", "7.2", "7.3"] },
    { "id": 7, "tasks": ["8.1"] },
    { "id": 8, "tasks": ["8.2"] },
    { "id": 9, "tasks": ["8.3"] },
    { "id": 10, "tasks": ["8.4"] },
    { "id": 11, "tasks": ["9.1", "9.2"] },
    { "id": 12, "tasks": ["9.3"] }
  ]
}
```
