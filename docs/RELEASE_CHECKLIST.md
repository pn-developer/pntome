# Release Checklist

Steps marked **User_Action** need your accounts, keys or hardware. They are not run by the agent.

## Ordered release steps

1. Make sure the working tree is clean and on the release branch.
2. Bump versions consistently: `versionName` and `versionCode` in `PNTome/app/build.gradle.kts`, `Version.mc` SEMVER and `manifest.xml` version in `PNTomeGo`.
3. Add `PNTome/fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` and update `CHANGELOG.md` and the date in `PRIVACY.md` if it changed.
4. Replace every `example.invalid` placeholder (URLs, contacts). Run `scripts/check-release.sh --release` to list what remains.
5. Run `scripts/check-release.sh` and fix every violation.
6. Run the offline JVM tests: `./gradlew --offline testDebugUnitTest testReleaseUnitTest` in `PNTome/`.
7. Build the watch app for all products: `./build.sh` in `PNTomeGo/`.
8. **User_Action**: set the signing variables (`PNTOME_KEYSTORE_FILE`, `PNTOME_KEYSTORE_PASSWORD`, `PNTOME_KEY_ALIAS`, `PNTOME_KEY_PASSWORD`) or `keystore.properties`. Never commit them.
9. Build signed artifacts: `./gradlew --offline assembleRelease bundleRelease` in `PNTome/`.
10. **User_Action**: export the watch package with `./build.sh --export` in `PNTomeGo/`, using your retained developer key.
11. **User_Action**: run the hardware checks in the table below on a release candidate.
12. **User_Action**: tag the release `v<versionName>` and push.
13. **User_Action**: upload the AAB and complete the listing, data safety and foreground service declarations (`store/play/`). Re-check the current Play target API policy.
14. **User_Action**: build and deploy the self-hosted F-Droid repo (`fdroid/README.md`).
15. **User_Action**: upload the `.iq` file and complete the listing (`store/garmin/`). Re-check current Connect IQ Store requirements.
16. **User_Action**: host `PRIVACY.md` at a public URL and enter it in the store listings.

## Checks that need a phone or watch (User_Action)

No phone or watch is available to the agent, so none of these have been run.

| # | Check | Needs | Status |
|---|---|---|---|
| 1 | Install the release APK and launch PNTome | Phone | User_Action, not run |
| 2 | Add a folder with the system picker, scan, and play a track | Phone | User_Action, not run |
| 3 | Pair the phone and watch with the 6-digit code | Phone and watch | User_Action, not run |
| 4 | Sync music over Wi-Fi or hotspot and play it on the watch | Phone and watch | User_Action, not run |
| 5 | Sync foreground notification and Cancel work; sync survives a denied notification permission | Phone | User_Action, not run |
| 6 | Android 15 data sync time limit: sync stops cleanly on timeout | Phone (Android 15) | User_Action, not run |
| 7 | Media notification, lock screen controls and Android Auto browsing | Phone, Android Auto head unit or DHU | User_Action, not run |
| 8 | Sideload or install the `.iq` and confirm it opens on each target watch model | Watch | User_Action, not run |
| 9 | Replace old phone and watch builds together and pair again | Phone and watch | User_Action, not run |
| 10 | Bookmarks and resume position on the watch | Watch | User_Action, not run |

## Verification results

Recorded by the final verification task. Anything not listed there as passed is unverified.

### What was run and passed

- Android JVM unit tests: 468 pass.
- Android `assembleRelease`, `bundleRelease` and `lintRelease` succeed.
- Release signing verified with a throwaway key using `apksigner` (`/home/pavel/Android/Sdk/build-tools/36.0.0`). The throwaway key is not the real release key.
- Watch app builds for all 8 products, release and debug.
- Simulator unit tests: 54/54 pass on `fenix6pro` and `fenix847mm`.
- `.iq` export from a clean copy of the tree.
- `scripts/check-release.sh` exits 0 (only the expected `example.invalid` placeholder warnings remain) and `scripts/test-check-release.sh` passes 13/13.
- Repository state: no keystores, `.prg`, APK, AAB or `.iq` files are tracked. Old ignored `GsoundG-*` build output was removed. The Gsound to PNTome move is staged but not committed; `git diff --cached -M --stat` detects 261 renames. History is preserved at the file level: `git log --follow` on a moved file needs the old path until the commit is made, then follows the rename.

### Lint fixes made in task 9.1

See the task 9.1 changes for the exact edits; `lintRelease` passes after them.

### Known issues

- `SyncOrchestratorTest` is flaky (timing dependent). It passes on rerun.
- One-off simulator crash "Unexpected Type Error in onSong()" on the first run of the debug builds. It did not recur and is probably unrelated to the rename. Suspected cause: a null `toString` in a debug log line in `PNTomeContentDelegate.onSong`. Not investigated further.

### Not verified (User_Action or out of reach)

- Anything that needs a phone or a watch (see the table above).
- R8 / minification behaviour of the release build.
- Google Play, F-Droid and Connect IQ Store submission and review.
- Whether GPL licensing is compatible with the Garmin Connect IQ SDK terms. Confirm before publishing.
- The current Google Play target API level policy.
- Real hosting URLs, contact email and privacy policy URL (all still `example.invalid`; run `scripts/check-release.sh --release` to list them).
