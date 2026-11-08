# Releasing PNTome and PNTomeGo

This guide describes how a release is cut with the scripts in `scripts/` and the
workflows in `.github/workflows/`. Steps that only the repository Owner can do (they need
GitHub settings, a phone, a watch or a vendor account) are collected in
[User actions](#user-actions). The store-by-store checklist is in
[RELEASE_CHECKLIST.md](RELEASE_CHECKLIST.md).

One version in the file `VERSION` drives everything:

- Stable: `MAJOR.MINOR.PATCH`, for example `1.2.3`.
- Release candidate: `MAJOR.MINOR.PATCH-rc.N`, for example `1.2.3-rc.2`.
- The Android `versionCode` is computed from the version by `scripts/lib/version.sh`
  (`1.2.3-rc.2` gives `1020302`, `1.2.3` gives `1020399`). The watch manifest carries the
  core version (`1.2.3`), the watch `SEMVER` constant carries the full version.

## Release procedure

Run from the repository root, on `main`, with a clean working tree.

1. Put the release notes under `## [Unreleased]` in `CHANGELOG.md`. A stable version
   needs a non-empty `Unreleased` section.
2. Set the version:

   ```bash
   scripts/set-version.sh 1.2.3          # or 1.2.3-rc.1 for a release candidate
   ```

   Use `--dry-run` to see the changes without writing. Use `--allow-downgrade` only on
   purpose. The script edits `VERSION`, the Gradle `versionName` and `versionCode`, the
   `SEMVER` line of `PNTomeGo/source/Version.mc` and the manifest version. For a stable
   version it also updates the F-Droid metadata, rotates the changelog and writes the
   fastlane changelog file. It never commits or tags. Exit code 1 means invalid input,
   a downgrade or an empty `Unreleased`; exit code 2 means a file did not contain the
   expected text exactly once (nothing is written in both cases).
3. Check the repository for drift:

   ```bash
   scripts/check-release.sh
   ```

4. Review the diff, then commit:

   ```bash
   git diff
   git add -A   # review the list first; add files by name if unsure
   git commit -m "Release 1.2.3"
   ```

5. Create an annotated tag. The tag name must be `v` plus the content of `VERSION`:

   ```bash
   git tag -a v1.2.3 -m "PNTome 1.2.3"
   scripts/check-release.sh --tag v1.2.3
   ```

6. Push the branch and the tag:

   ```bash
   git push origin main
   git push origin v1.2.3
   ```

Pushing a tag matching `v*.*.*` starts the Release workflow (`release.yml`):

| Job | What it does |
|-----|--------------|
| `verify` | Runs `scripts/check-release.sh --tag <tag>`, requires the tagged commit to be on `main`, and computes version, version code, pre-release flag and commit. A failure here starts no build. |
| `android` | Environment `release`. Builds the release APK and AAB with `scripts/ci/prepare-signing.sh`, verifies the signature and renames the files with `scripts/ci/collect-android.sh`. |
| `watch` | Runs only when the repository variable `CIQ_BUILD` is `true`. Environment `release`. Builds the `.iq` with `PNTomeGo/build.sh --export`, asserts the stamped commit and collects the file with `scripts/ci/collect-watch.sh`. |
| `release` | The only job with `contents: write`. Runs `scripts/ci/publish.sh`: writes `SHA256SUMS` and the release notes, then calls `gh release create --verify-tag`. |

Release behaviour:

- A `-rc.N` version is published as a pre-release.
- A stable version with unsigned artifacts is created as a draft. Review it and publish
  it by hand, or sign and tag again with a new patch version.
- If a release for the tag already exists, the workflow fails and changes nothing.

Asset names are `PNTome-<version>-<commit>-signed.apk` (or `-unsigned`), the matching
`.aab`, optionally `PNTomeGo-<version>-<commit>.iq`, and `SHA256SUMS`.

The CI workflow (`ci.yml`) runs on pull requests and on pushes to `main`. It uses no
secrets. It runs the script tests and `scripts/check-release.sh`, builds the Android app
(unit tests, lint, androidTest compile, unsigned release build) and, if enabled, the
watch app with a throwaway developer key.

## Secrets and variables

Create them in the GitHub repository settings. Store the secrets in the `release`
Environment, not as plain repository secrets, so that only the release jobs can read them.

| Name | Kind | Purpose |
|------|------|---------|
| `ANDROID_KEYSTORE_B64` | secret | Base64 of the Android release keystore. |
| `ANDROID_KEYSTORE_PASSWORD` | secret | Keystore password. |
| `ANDROID_KEY_ALIAS` | secret | Key alias in the keystore. |
| `ANDROID_KEY_PASSWORD` | secret | Password of that key. |
| `CIQ_DEVELOPER_KEY_B64` | secret | Base64 of the Connect IQ `developer_key.der` used to sign the store `.iq`. Needed only when the `watch` job runs in the Release workflow. |
| `CIQ_BUILD` | variable | Set to `true` to enable the `watch` job in CI and in releases. Unset means no watch job. |
| `CIQ_RUNS_ON` | variable | Runner label for the `watch` job. Default `ubuntu-latest`. Set it to the label of a self-hosted runner that has the SDK. |

Create the base64 values without line breaks:

```bash
base64 -w0 path/to/release.jks        # ANDROID_KEYSTORE_B64
base64 -w0 path/to/developer_key.der  # CIQ_DEVELOPER_KEY_B64
```

Signing rules enforced by `scripts/ci/prepare-signing.sh`:

- All four Android secrets set: the build is signed and the APK signature is verified.
- None set: the build is unsigned and a warning is shown.
- Only some set: the job fails and names the missing secrets (never their values).

Do not commit keystores, `.der` files or passwords. The scripts decode keys into
`$RUNNER_TEMP` with mode 600 and the workflow deletes them at the end of the job.

## Getting the Connect IQ SDK for a watch build

The watch build needs the Garmin Connect IQ SDK and a developer key. `scripts/ci/ciq-sdk.sh`
looks for the SDK in this order: the `CIQ_SDK` variable, the path in
`~/.Garmin/ConnectIQ/current-sdk.cfg`, then the newest
`~/.Garmin/ConnectIQ/Sdks/connectiq-sdk-lin-*` directory. There are three options.

### A. Self-hosted runner (implemented)

Install the SDK on your own machine, register it as a GitHub runner, and set
`CIQ_RUNS_ON` to its label and `CIQ_BUILD` to `true`. The SDK is never copied to GitHub.

Risks of a self-hosted runner:

- Code from a workflow runs on your machine, with access to that machine and its network.
- A pull request from a fork can run arbitrary code. `ci.yml` skips the `watch` job for
  fork pull requests for this reason. Do not remove that condition, and do not allow
  fork workflows on the runner.
- The runner keeps files between jobs. Keep the Owner's real developer key off it, or
  rely on the release job deleting `$RUNNER_TEMP/developer_key.der` after each run.
- Keep the runner and the SDK updated, and prefer a dedicated user or machine.

### B. Hosted runner with an SDK manager command line tool (not implemented)

Downloading the SDK on a GitHub-hosted runner with a command line SDK manager is not
implemented. Verify all of the following before using it:

- The Garmin license terms allow automated download and use of the SDK in CI.
- How the tool authenticates, and how the credentials are stored and passed (as a secret
  in the `release` Environment, never in a `run:` line).
- How long a login token lives and how it is renewed.
- Where the tool comes from, and that it can be trusted and pinned (a fixed version and
  a checksum, or a full commit SHA for an action).
- That the SDK is not stored in a cache or in an artifact, so it is not redistributed.

### C. No watch job (default)

Leave `CIQ_BUILD` unset. CI and releases build only the Android app. Build the watch
package on your own machine and attach it by hand (next section).

## Attaching a locally built `.iq` and regenerating `SHA256SUMS`

When the release has no watch package:

1. Build a clean export from the tagged commit (the build refuses to export from a dirty
   tree):

   ```bash
   git checkout v1.2.3
   cd PNTomeGo
   ./build.sh --export        # writes dist/PNTomeGo-1.2.3.iq
   ```

2. Check that the build output line reads `build.sh: version 1.2.3 (<commit>)` with the
   short commit of the tag and no `-dirty` suffix.
3. Download the release assets into an empty directory, add the `.iq` under the name
   `PNTomeGo-<version>-<commit>.iq`, and regenerate the checksums:

   ```bash
   gh release download v1.2.3 --dir /tmp/release-assets
   cp PNTomeGo/dist/PNTomeGo-1.2.3.iq /tmp/release-assets/PNTomeGo-1.2.3-<commit>.iq
   rm -f /tmp/release-assets/SHA256SUMS
   scripts/make-checksums.sh /tmp/release-assets
   (cd /tmp/release-assets && sha256sum -c SHA256SUMS)
   ```

4. Upload the new file and the new `SHA256SUMS`, replacing the old checksum file:

   ```bash
   gh release upload v1.2.3 /tmp/release-assets/PNTomeGo-1.2.3-<commit>.iq --clobber
   gh release upload v1.2.3 /tmp/release-assets/SHA256SUMS --clobber
   ```

5. Edit the release notes if they say the Connect IQ package was not built in CI.

## Pre-release flow

Use a release candidate to test the pipeline and the artifacts before a stable release.

1. `scripts/set-version.sh 1.2.3-rc.1`, `scripts/check-release.sh`, commit.
2. Tag `v1.2.3-rc.1` (annotated) and push the branch and the tag.
3. The Release workflow publishes a GitHub pre-release. A release candidate does not
   rotate the changelog, does not touch the F-Droid metadata and does not write a
   fastlane changelog file; `check-release.sh` only warns about the F-Droid entry.
4. Fix problems with `-rc.2`, `-rc.3`, and so on. When satisfied, run
   `scripts/set-version.sh 1.2.3` and release the stable version.

The F-Droid update check only follows stable tags (`vX.Y.Z`), so release candidates
never reach the F-Droid repository.

## Fixing a bad release

Do not rewrite or reuse a published version. Instead:

1. Delete the GitHub release and the tag, locally and on the remote:

   ```bash
   gh release delete v1.2.3 --yes
   git push origin --delete v1.2.3
   git tag -d v1.2.3
   ```

2. Fix the problem on `main`.
3. Release a new patch version (`1.2.4`) with the normal procedure.

The Release workflow refuses to touch an existing release, so the delete step is needed.

## User actions

These steps need GitHub settings, hardware or vendor accounts. They are not done by the
scripts or by the automated checks in this repository.

- [ ] Create the secrets and variables from the table above.
- [ ] Create the `release` Environment, store the secrets in it, and restrict who can
      approve or deploy to it.
- [ ] Create a tag ruleset that restricts creation of `v*` tags to the Owner.
- [ ] Run the CI workflow once on GitHub and fix what it reports.
- [ ] Push the first pre-release tag (`v0.1.0-rc.1`) and check the pre-release, its
      assets and `sha256sum -c SHA256SUMS`.
- [ ] Decide how the watch package is built (option A, B or C above).
- [ ] Verify the Garmin license terms:
  - the Connect IQ SDK License Agreement: what you may redistribute and host, and
    whether automated download in CI is allowed;
  - the Connect IQ Mobile SDK terms for shipping it inside an Apache-2.0 APK and AAB;
  - the `NOTICE` copyright holder and year.
- [ ] Install a signed APK on a phone and smoke-test pairing and sync (needs a phone and
      a watch).
- [ ] Upload the `.iq` to the Connect IQ Store and the AAB to Google Play, and publish the
      F-Droid metadata, as described in [RELEASE_CHECKLIST.md](RELEASE_CHECKLIST.md).
