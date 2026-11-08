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

## CI, release automation and license (User_Action)

These need your GitHub, Garmin and store accounts or a phone. The agent did not do them. See `docs/RELEASING.md` for the details of each step.

| # | User_Action | Notes |
|---|---|---|
| 1 | Create the GitHub secrets and variables for signing and the Connect IQ developer key | Encode files with `base64 -w0`. Never commit them. |
| 2 | Create the `release` Environment and restrict who can approve and which refs can deploy | Secret-reading release jobs use it. |
| 3 | Create the tag ruleset for `v*` | Limits who can create, move or delete release tags. |
| 4 | Run the CI workflow on GitHub for the first time and fix what it reports | It cannot be run from the development machine. |
| 5 | Decide how the watch app is built in CI (`CIQ_BUILD`, self-hosted runner or manual `.iq`) | Check the Garmin terms before automating the SDK download. |
| 6 | Push the first release candidate tag `v0.1.0-rc.1` and check the pre-release it creates | Confirm the APK, AAB, `SHA256SUMS` and release notes. |
| 7 | Verify the Garmin license terms | Connect IQ SDK License Agreement (redistribution and hosting) and Connect IQ Mobile SDK terms for shipping inside an Apache-2.0 APK/AAB. |
| 8 | Confirm the copyright holder and year in `NOTICE` | The year was taken from the current date. |
| 9 | Install a signed release APK on a phone and smoke-test it | Needs a phone. |
| 10 | Export the `.iq` and upload it to the Connect IQ Store | Needs your developer key and account. |

## Verification results

Recorded by the final verification task. Anything not listed there as passed is unverified.

### CI, release automation and license: results

Run on the development machine (Linux). No GitHub Actions run, phone or watch was available.

**Script tests and release check (9.1)**

- `scripts/test-version-lib.sh`: 5/5 passed (2.4 s).
- `scripts/test-set-version.sh`: 19/19 passed (about 16 min at the default 100 cases).
- `scripts/test-check-release.sh`: 33/33 passed (about 7 min at the default 100 cases).
- `scripts/test-ci-scripts.sh`: 26/26 passed (23 s).
- `scripts/check-release.sh`: OK. `scripts/check-release.sh --tag v0.1.0`: OK. Only warnings are the `example.invalid` placeholders in `store/`.
- `shellcheck` 0.11.0 is installed. Error level is clean after fixing SC1087 in `scripts/tests/prop-sv-roundtrip.sh`.

**Android build with a computed version (9.2)**

- In a temporary copy: `scripts/set-version.sh 1.2.3-rc.2`, then `./gradlew --offline assembleRelease`: BUILD SUCCESSFUL.
- `aapt2 dump badging`: `versionCode` 1020302, `versionName` 1.2.3-rc.2.

**Watch build with an rc version (9.3)**

- In a temporary clone, Connect IQ SDK 9.2.0: `monkeyc` accepts `SEMVER` 1.2.3-rc.2 with manifest version 1.2.3.
- Build output shows `version 1.2.3-rc.2 (<short commit>)` without `-dirty`.
- `build.sh --export` produced `dist/PNTomeGo-1.2.3-rc.2.iq`.
- `scripts/ci/collect-watch.sh --check-only` passed.
- Not run in the simulator or on a device.

**Workflow files (9.4)**

- `.github/workflows/ci.yml` and `.github/workflows/release.yml` both parse with Python `yaml.safe_load`.
- `actionlint` is not installed and `apt-cache policy actionlint` reports no installable package ("Unable to locate package"), so the workflows were not linted with it. Install it another way (for example the upstream release binary or `go install`) if you want that check.
- `scripts/check-release.sh` `[workflows]` check passes (pinning, no `pull_request_target`, top-level `permissions`, no secrets on `run:` lines, `timeout-minutes` on every job).
- Fix made during this task: the `check-release` job in `ci.yml` had `timeout-minutes: 10`, but the two property suites take about 16 and 7 minutes at 100 cases. The timeout is now 60, and the `Test check-release.sh` and `Test set-version.sh` steps set `ITER: 20` through step `env:`. CI therefore runs 20 generated cases per property, fewer than the 100 of a local run. Run the scripts locally without `ITER` for the full 100-case check before a release. `CK_SKIP_PROPS=1` is available for `test-check-release.sh` but is not used. The CI run itself is unverified, so the time for 20 cases is an estimate.

**Working tree (9.4)**

- `git status`: no keystores, `.iq` files, `.der` files, `out/` directories or temporary files in the repository. Temporary copies and clones under `/tmp` were removed. Changes remain uncommitted, as intended.

**Still unverified (User_Actions 1 to 10 above)**

- Secrets, variables, the `release` Environment and the `v*` tag ruleset do not exist yet (1, 2, 3).
- No GitHub Actions run of `ci.yml` or `release.yml` has happened (4, 6), including the `watch` job and the throwaway developer key commands, which are not confirmed against Garmin's page.
- Watch CI build strategy and Garmin terms for automating the SDK (5, 7).
- `NOTICE` holder and year (8).
- Signed APK install on a phone (9).
- `.iq` upload to the Connect IQ Store (10).
- Also unverified: `ITER: 20` runtime on GitHub runners, and the F-Droid `UpdateCheckMode` pattern against a real fdroidserver run.

## Earlier verification results

Recorded by the earlier store release verification task. Anything not listed there as passed is unverified.

### What was run and passed

- Android JVM unit tests: 468 pass.
- Android `assembleRelease`, `bundleRelease` and `lintRelease` succeed.
- Release signing verified with a throwaway key using `apksigner` (`/home/pavel/Android/Sdk/build-tools/36.0.0`). The throwaway key is not the real release key.
- Watch app builds for all 8 products, release and debug.
- Simulator unit tests: 54/54 pass on `fenix6pro` and `fenix847mm`.
- `.iq` export from a clean copy of the tree.
- `scripts/check-release.sh` exits 0 (only the expected `example.invalid` placeholder warnings remain) and `scripts/test-check-release.sh` passes 13/13.
- Repository state: no keystores, `.prg`, APK, AAB or `.iq` files are tracked. Old ignored build output of the previous app name was removed. The rename to PNTome is staged but not committed; `git diff --cached -M --stat` detects 261 renames. History is preserved at the file level: `git log --follow` on a moved file needs the old path until the commit is made, then follows the rename.

### Lint fixes made in task 9.1

See the task 9.1 changes for the exact edits; `lintRelease` passes after them.

### Known issues

- `SyncOrchestratorTest` is flaky (timing dependent). It passes on rerun.
- One-off simulator crash "Unexpected Type Error in onSong()" on the first run of the debug builds. It did not recur and is probably unrelated to the rename. Suspected cause: a null `toString` in a debug log line in `PNTomeContentDelegate.onSong`. Not investigated further.

### Not verified (User_Action or out of reach)

- Anything that needs a phone or a watch (see the table above).
- R8 / minification behaviour of the release build.
- Google Play, F-Droid and Connect IQ Store submission and review.
- User_Action: verify the Garmin license terms (Connect IQ SDK License Agreement on redistribution and hosting, Connect IQ Mobile SDK terms for shipping inside an Apache-2.0 APK/AAB). Confirm before publishing.
- The current Google Play target API level policy.
- Real hosting URLs, contact email and privacy policy URL (all still `example.invalid`; run `scripts/check-release.sh --release` to list them).
