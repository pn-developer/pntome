# Implementation Plan: Apache License, CI and Release Automation

## Overview

Relicense the repository to Apache-2.0, add a `VERSION` file with scripts that propagate and check it, add the CI and release GitHub Actions workflows, and keep the git commit visible in the PNTomeGo watch app. Work in this order: license, version library and `set-version.sh`, `check-release.sh` extensions, watch build guard, release helper scripts, workflows, documentation, then verification on this machine.

Constraints that apply to every task:

- Implementation languages: bash for scripts, YAML for workflows, Kotlin DSL and Monkey C only where they already exist (no new Kotlin or Monkey C logic). Documents are Markdown.
- The work of `.kiro/specs/pntome-store-release/` is currently uncommitted. The Owner commits it before this plan runs. Do not create git commits, tags or pushes in any task. Temporary copies and clones are made under `/tmp` and removed afterwards.
- Never auto-install system packages. If a tool is missing (`shellcheck`, `actionlint`, `apksigner`, `xmllint`), report the apt package name, its one-line description and the `sudo apt install <pkg>` command to the user, and wait.
- No phone, no watch and no way to run GitHub Actions from this machine. Anything that needs them is written down as a User_Action, not run. The Connect IQ simulator is used only through the `connectiq-simulator` skill if needed.
- Do not invent URLs. The Apache License text is downloaded from apache.org and stored verbatim. Garmin license text is read from the locally installed SDK and the Garmin pages, and what is not verified stays listed as an Owner verification item.
- Property-test sub-tasks live in `scripts/tests/` with one file per property, named `prop-vl-*` (version library), `prop-sv-*` (set-version), `prop-ck-*` (check-release) and `prop-ci-*` (CI helper scripts). The runner scripts execute their files by name prefix. Each property test runs at least 100 generated cases, uses a seeded generator and prints the seed on failure.
- Never print or commit secrets, keystores or `.der` files.

## Tasks

- [x] 1. Apache-2.0 license change
  - [x] 1.1 Replace LICENSE and add NOTICE
    - Download the canonical text from `https://www.apache.org/licenses/LICENSE-2.0.txt` and write it unmodified to `LICENSE` (replace the GPL text). Do not retype or reformat it.
    - Create `NOTICE` per design section 1: names PNTome and PNTomeGo, `Copyright <year> Pavel Nemec` (year from the current date; the Owner confirms), and a sentence that the Garmin Connect IQ SDKs are proprietary and not covered by this license.
    - _Requirements: 1.1, 1.2, 1.3, 1.4_

  - [x] 1.2 Update every license mention
    - Find mentions with `grep -rIn -E "GPL|General Public"` outside `.kiro/specs/` and `PNTome/gradlew*`, and update them: `README.md` (tree comment and License section, SPDX `Apache-2.0`), `fdroid/metadata/dev.pntome.app.yml` (`License: Apache-2.0`), `PNTome/fastlane/metadata/android/en-US/full_description.txt` (stay within 4000 characters), `CHANGELOG.md`, `docs/RELEASE_CHECKLIST.md` (replace the GPL open item with a User_Action to verify the Garmin license terms), and any `store/` text.
    - `CHANGELOG.md`: add under `## [Unreleased]` a `### Changed` bullet "License changed from GPL-3.0-or-later to Apache-2.0" (this is the only allowed GPL mention line) and change the `0.1.0` line `LICENSE (GPL-3.0-or-later)` to `LICENSE (Apache-2.0)`.
    - `THIRD_PARTY.md`: replace the "Open item: GPL-3.0-or-later with a proprietary SDK" section with "License compatibility notes" using the points in design section 1 (no copyleft toward proprietary libraries, one-way compatibility with GPLv3, not compatible with GPLv2-only, no relicensing of Garmin SDKs, earlier snapshots keep GPL rights, not legal advice). Add the Owner verification list (Garmin SDK License Agreement redistribution and hosting terms, Connect IQ Mobile SDK terms for shipping inside an Apache-2.0 APK/AAB, `NOTICE` holder and year). Keep the proprietary SDK row and the `NonFreeDep` statement.
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 3.1, 3.2, 3.3, 3.4_

- [x] 2. Semantic version library and set-version script
  - [x] 2.1 Create the version library and the VERSION file
    - Create `scripts/lib/version.sh` with `valid_version`, `core_version`, `is_prerelease`, `version_code`, `version_cmp` (grammar and limits from design section 2; pure bash, no external tools beyond coreutils; safe to `source` under `set -euo pipefail`).
    - Create `VERSION` containing `0.1.0` and a trailing newline.
    - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5, 4.6, 4.7_

  - [x] 2.2 Create the shared test helper
    - Create `scripts/tests/lib.sh`: seeded generator (`SEED` from env or random, printed on failure), `gen_version` (valid, stable and rc, including boundary values), `gen_invalid_version` (leading zeros, `+build`, `-beta.1`, components over limits, empty, whitespace), `ref_cmp` and `ref_code` (independent reference implementations written differently from the library), `make_repo_copy` (copies tracked files plus `VERSION` into a `mktemp -d` directory and initializes a git repo there), `cleanup` trap, `fail`/`pass` helpers, and `ITER` default of 100.
    - _Requirements: 15.4_

  - [x] 2.3 Write property test for the version grammar
    - **Property 1: Version grammar**
    - File `scripts/tests/prop-vl-grammar.sh`. **Validates: Requirements 4.2, 4.3**

  - [x] 2.4 Write property test for the core version
    - **Property 2: Core version**
    - File `scripts/tests/prop-vl-core.sh`. **Validates: Requirements 4.4**

  - [x] 2.5 Write property test for the version code formula and bound
    - **Property 3: Version code formula and bound**
    - File `scripts/tests/prop-vl-code.sh`; include an exhaustive sweep of boundary values (MAJOR 0, 1, 209; MINOR and PATCH 0, 1, 99; N 1, 98). **Validates: Requirements 4.5**

  - [x] 2.6 Write property test for version comparison
    - **Property 4: Comparison follows SemVer precedence**
    - File `scripts/tests/prop-vl-cmp.sh`. **Validates: Requirements 4.6**

  - [x] 2.7 Write property test for version code monotonicity
    - **Property 5: Version code is monotonic**
    - File `scripts/tests/prop-vl-monotonic.sh`; include the pairs `X.Y.Z-rc.98` < `X.Y.Z` and `X.Y.Z` < `X.Y.(Z+1)-rc.1`. **Validates: Requirements 7.1**

  - [x] 2.8 Migrate the current repository state to the new scheme
    - Set `versionCode = 10099` in `PNTome/app/build.gradle.kts` (keep `versionName = "0.1.0"`, both as single plain literals).
    - `git mv PNTome/fastlane/metadata/android/en-US/changelogs/1.txt .../changelogs/10099.txt`.
    - In `fdroid/metadata/dev.pntome.app.yml`: `versionCode: 10099` in `Builds`, `CurrentVersionCode: 10099`, and `UpdateCheckMode: Tags v[0-9]+\.[0-9]+\.[0-9]+` (a pattern that matches only stable tags; confirm the syntax in the fdroidserver documentation before writing it and record the source in a comment). Leave `UpdateCheckData` as is (it reads the Gradle literals).
    - Do not run `set-version.sh` here: `0.1.0` is unreleased and `CHANGELOG.md` must not be rotated yet.
    - _Requirements: 6.9, 7.2, 7.3_

  - [x] 2.9 Create `scripts/set-version.sh`
    - Implement per design section 2: `set-version.sh <VERSION> [--dry-run] [--allow-downgrade]`; sources `scripts/lib/version.sh`; computes every edit first and writes only if all patterns matched exactly once (exit 2 otherwise); exit 1 for invalid version, downgrade without the option, or empty `Unreleased` on a stable version.
    - Targets: `VERSION`; `versionName` and `versionCode` in `PNTome/app/build.gradle.kts`; the `SEMVER` line (only) of `PNTomeGo/source/Version.mc`; the application `version` attribute of `PNTomeGo/manifest.xml` (Core_Version). For stable versions also the F-Droid `Builds` entry, `CurrentVersion`, `CurrentVersionCode`, `commit: v<VERSION>`, the `CHANGELOG.md` rotation (`[Unreleased]` to `[VERSION] - <UTC date>` plus a new empty `[Unreleased]`) and the fastlane `changelogs/<code>.txt` generated from the new section (not written, with a message, if longer than 500 characters).
    - No git commit, no tag. Print the next steps (check, commit, annotated tag, push).
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5, 5.6, 5.7, 5.8, 5.9, 5.10, 5.11, 5.12, 5.13_

  - [x] 2.10 Write property test for the set-then-check round trip
    - **Property 6: Set then check round trip**
    - File `scripts/tests/prop-sv-roundtrip.sh`: copy the repo, add a bullet to `Unreleased` for stable cases, run `set-version.sh`, read each carrier, run `check-release.sh`. **Validates: Requirements 5.1, 5.2, 5.6, 6.1, 6.2, 6.3, 7.2**

  - [x] 2.11 Write property test for idempotence
    - **Property 7: Set version is idempotent**
    - File `scripts/tests/prop-sv-idempotent.sh`. **Validates: Requirements 5.11**

  - [x] 2.12 Write property test for pre-release file scope
    - **Property 8: Pre-release leaves stable-only files untouched**
    - File `scripts/tests/prop-sv-prerelease.sh`. **Validates: Requirements 5.3, 6.4**

  - [x] 2.13 Write property test for the single-line Version.mc edit
    - **Property 9: Version.mc edit is a single line**
    - File `scripts/tests/prop-sv-versionmc.sh`. **Validates: Requirements 5.13**

  - [x] 2.14 Write property test for refusals writing nothing
    - **Property 10: Refusals write nothing**
    - File `scripts/tests/prop-sv-refusal.sh`; compare a `git status --porcelain` plus a content hash before and after. **Validates: Requirements 5.9, 5.10**

  - [x] 2.15 Create the runner scripts and example tests for version tooling
    - `scripts/test-version-lib.sh`: runs every `scripts/tests/prop-vl-*.sh` that exists, exits non-zero if any fails.
    - `scripts/test-set-version.sh`: runs every `scripts/tests/prop-sv-*.sh` that exists, plus example cases: changelog rotation (several shapes), empty `Unreleased` abort, fastlane file at 500 and 501 characters, pattern mismatch (exit 2, tree unchanged), no git commit or tag created.
    - _Requirements: 5.4, 5.5, 5.6, 5.7, 5.8, 5.12, 15.3, 15.4_

- [x] 3. Checkpoint - version tooling works
  - Ensure all tests pass, ask the user if questions arise.

- [x] 4. Extend `scripts/check-release.sh`
  - [x] 4.1 Make the version checks version-aware and add `--tag`
    - Source `scripts/lib/version.sh`. Add `[version-file]`, replace `[versions]` (design table), make `[fdroid]` stable-only with a warning for pre-releases, make `[store-text]` changelog-file check stable-only, add `[changelog]` (`## [Unreleased]` always, `## [VERSION]` for stable), add `--tag <ref>` (`[tag]`).
    - Violation messages show all compared values and keep the `[check-name]` prefix.
    - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5, 6.6, 6.7, 6.8_

  - [x] 4.2 Add the license check
    - `[license]`: `LICENSE` contains `Apache License` and `Version 2.0` and not `GNU GENERAL PUBLIC LICENSE`; `NOTICE` exists; F-Droid `License:` is `Apache-2.0`; scan tracked files for `\bL?GPL\b`, `GNU General Public`, `GPL-[0-9]` outside the Allowlist (`.kiro/specs/**`, the single `CHANGELOG.md` line mentioning the relicensing, `PNTome/gradlew*`) and print file:line.
    - _Requirements: 2.7, 3.5_

  - [x] 4.3 Add the workflows check
    - `[workflows]`: at least one file in `.github/workflows/`; every `uses:` is `actions/*` or `gradle/*` with `@v<N>`, or any action pinned to a 40-hex SHA; no `pull_request_target`; top-level `permissions:` in each file; no `${{ secrets.` on a `run:` line or inside a multi-line `run: |` block; every job has `timeout-minutes`.
    - Use only bash, grep and awk (no YAML library dependency).
    - _Requirements: 13.1, 13.2, 13.3, 13.4, 13.6, 15.1, 8.8_

  - [x] 4.4 Write property test for drift detection
    - **Property 11: Drift is detected and reported**
    - File `scripts/tests/prop-ck-drift.sh`. **Validates: Requirements 6.2, 6.8**

  - [x] 4.5 Write property test for the tag predicate
    - **Property 12: Tag predicate**
    - File `scripts/tests/prop-ck-tag.sh`. **Validates: Requirements 6.7**

  - [x] 4.6 Write property test for the F-Droid tag pattern
    - **Property 13: F-Droid tag pattern selects stable tags only**
    - File `scripts/tests/prop-ck-fdroid-pattern.sh`: extract the pattern from the metadata file and apply it with `grep -E`/`[[ =~ ]]` to generated tags. **Validates: Requirements 6.9**

  - [x] 4.7 Write property test for the GPL scan allowlist
    - **Property 14: GPL scan honours the allowlist**
    - File `scripts/tests/prop-ck-gpl-scan.sh`. **Validates: Requirements 2.7, 2.4, 3.5**

  - [x] 4.8 Write property test for the action pinning predicate
    - **Property 15: Action pinning predicate**
    - File `scripts/tests/prop-ck-pinning.sh`: generate `uses:` lines (first-party and third-party owners, major tags, branches, short and full SHAs). **Validates: Requirements 13.1, 13.6, 15.1**

  - [x] 4.9 Extend `scripts/test-check-release.sh`
    - Add one injected-violation case per new check as listed in Requirement 15.2: bad Version_File, Version_Code drift, `SEMVER` drift, manifest not equal to Core_Version, GPL text in `LICENSE`, missing `NOTICE`, wrong F-Droid license, GPL mention outside the Allowlist, tag mismatch, unpinned non-first-party action, `pull_request_target`, missing top-level `permissions`, secret in a `run:` line. Each expects exit 1 and the check name in the output. Add a two-violations-at-once case and a pre-release case that expects only a warning for F-Droid.
    - Also run every `scripts/tests/prop-ck-*.sh` from the script.
    - _Requirements: 6.8, 15.2, 6.4_

- [x] 5. Watch build guard and commit stamping
  - [x] 5.1 Update `build.sh`, `Version.mc` header and root `.gitignore`
    - `PNTomeGo/build.sh`: in the version guard compare the Core_Version of `SEMVER` (strip a `-rc.N` suffix) with the manifest application version; keep all other behaviour, including the `COMMIT` stamping, `-dirty` for non-export builds and the clean export. Do not change the `build.sh: version <SEMVER> (<COMMIT>)` output line (CI parses it).
    - `PNTomeGo/source/Version.mc`: header comment states that `SEMVER` is written by `scripts/set-version.sh`; do not change `COMMIT = "dev"`.
    - Root `.gitignore`: add `out/`.
    - _Requirements: 14.1, 14.2, 14.3, 14.6, 14.7, 14.8_

  - [x] 5.2 Write property test for the watch version guard
    - **Property 22: Watch version guard uses the core version**
    - File `scripts/tests/prop-ci-guard.sh`: run `build.sh` in a temp copy with a stub `monkeyc` (a script on `CIQ_SDK/bin` that creates the output file and exits 0) and a dummy key, for generated `SEMVER` values and matching and mismatching manifest versions. **Validates: Requirements 14.6**

- [x] 6. Release helper scripts
  - [x] 6.1 Create the checksum and release-notes scripts
    - `scripts/make-checksums.sh <dir>`: writes `<dir>/SHA256SUMS` (`sha256sum` format, sorted, excludes `SHA256SUMS` itself, handles unusual file names safely).
    - `scripts/release-notes.sh <version>`: prints the template from design section 3 using `CHANGELOG.md` (the `[VERSION]` section for stable versions, `[Unreleased]` for rc), and `COMMIT_FULL`, `COMMIT`, `SIGNED`, `WATCH_BUILT` environment variables.
    - _Requirements: 12.2, 12.3, 12.4_

  - [x] 6.2 Create the CI helper scripts in `scripts/ci/`
    - `ciq-sdk.sh` (same discovery order as `build.sh`; export `CIQ_SDK` to `$GITHUB_ENV` when set; otherwise print searched locations and the options from `docs/RELEASING.md` and exit 1), `prepare-signing.sh`, `collect-android.sh`, `prepare-ciq-key.sh`, `collect-watch.sh`, `publish.sh` as described in design section 3.
    - `collect-watch.sh` supports `--check-only <version> <commit> <logfile>` (assert the stamp, no copying) in addition to the collect mode, so `ci.yml` can reuse it.
    - All scripts: `set -euo pipefail`, no `set -x`, `umask 077` when writing key material, read secrets only from environment variables, never print their values, write outputs to `$GITHUB_OUTPUT`/`$GITHUB_ENV` only when those variables are set (so the scripts run locally in tests).
    - `publish.sh` uses `gh release create "$TAG" ... --verify-tag --title ... --notes-file ...` with `--prerelease` for rc and `--draft` for unsigned stable, and fails when `gh release view "$TAG"` succeeds.
    - _Requirements: 10.1, 10.2, 10.3, 10.4, 10.5, 11.1, 11.3, 11.4, 11.5, 11.7, 11.9, 12.5, 12.6, 12.7, 12.8, 13.5, 14.4_

  - [x] 6.3 Write property test for signing inputs and secret hygiene
    - **Property 16: Signing inputs and secret hygiene**
    - File `scripts/tests/prop-ci-signing.sh`: all 16 subsets of the four secrets with unique sentinel values; assert outputs and absence of sentinels in stdout and stderr. **Validates: Requirements 10.1, 10.3, 10.4, 13.5**

  - [x] 6.4 Write property test for Android artifact naming
    - **Property 17: Android artifact naming**
    - File `scripts/tests/prop-ci-naming.sh`: fake `PNTome/app/build/outputs` tree, generated versions and commits. **Validates: Requirements 10.5**

  - [x] 6.5 Write property test for the checksums round trip
    - **Property 18: Checksums round trip**
    - File `scripts/tests/prop-ci-checksums.sh`: random file sets including names with spaces. **Validates: Requirements 12.2**

  - [x] 6.6 Write property test for release-notes section selection
    - **Property 19: Release notes select the right section**
    - File `scripts/tests/prop-ci-notes-section.sh`. **Validates: Requirements 12.3**

  - [x] 6.7 Write property test for release-notes content
    - **Property 20: Release notes contain the build facts**
    - File `scripts/tests/prop-ci-notes-fields.sh`. **Validates: Requirements 12.4, 14.2**

  - [x] 6.8 Write property test for the commit stamp assertion
    - **Property 21: Commit stamp assertion**
    - File `scripts/tests/prop-ci-stamp.sh`: generated logs with matching, `dev`, `-dirty` and different commits, and extra noise lines. **Validates: Requirements 14.4, 14.3**

  - [x] 6.9 Create `scripts/test-ci-scripts.sh` with example tests
    - Run every `scripts/tests/prop-ci-*.sh`.
    - Examples: `publish.sh` with a fake `gh` on `PATH` (flag matrix: stable signed, stable unsigned draft, rc prerelease, release already exists), `ciq-sdk.sh` discovery matrix with a fake `HOME` (env, cfg file, newest `Sdks` dir, none), `prepare-ciq-key.sh` with an empty secret, `collect-watch.sh` copy and rename, key file permissions `600`.
    - A shallow-clone case: `git clone --depth 1` of a temp repo, stub `monkeyc`, assert the stamped commit equals `git rev-parse --short HEAD`; and a dirty-tree `--export` refusal.
    - _Requirements: 10.1, 10.4, 11.3, 11.4, 11.5, 11.9, 12.5, 12.6, 12.7, 12.8, 14.3, 14.5, 15.5_

- [x] 7. GitHub Actions workflows
  - [x] 7.1 Create `.github/workflows/ci.yml`
    - Implement the design: triggers, top-level `permissions: contents: read`, concurrency with cancel-in-progress, jobs `check-release`, `android`, `watch` with `timeout-minutes` on each, no secrets, fork-PR skip and PR-head checkout for `watch`, `CIQ_RUNS_ON` and `CIQ_BUILD` handling, failure-report artifact for `android`.
    - Look up the current major versions of `actions/checkout`, `actions/setup-java`, `actions/upload-artifact`, `gradle/actions/setup-gradle`, `gradle/actions/wrapper-validation` on their official pages and use those majors. Confirm the OpenSSL commands for the throwaway Connect IQ developer key against the current Garmin documentation; record the source in a YAML comment.
    - After each `./build.sh` in the watch job, run `scripts/ci/collect-watch.sh --check-only` (or an inline equivalent that calls it) to assert the stamped commit is the checked-out commit and has no `-dirty`.
    - _Requirements: 8.1, 8.2, 8.3, 8.4, 8.5, 8.6, 8.7, 8.8, 8.9, 11.10, 13.1, 13.2, 13.3, 13.4, 14.4_

  - [x] 7.2 Create `.github/workflows/release.yml`
    - Implement jobs `verify`, `android`, `watch`, `release` exactly as in design section 3: tag trigger `v*.*.*`, per-tag concurrency without cancel, `environment: release` on secret-reading jobs, `contents: write` only on `release`, secrets only through step `env:`, `if: always()` cleanup steps, `needs`/`if` conditions so that a failed `verify` starts no build, artifact upload only from `out/`, release creation through `scripts/ci/publish.sh`.
    - Use the same action majors as `ci.yml`.
    - _Requirements: 9.1, 9.2, 9.3, 9.4, 9.5, 9.6, 9.7, 10.1, 10.6, 10.7, 10.8, 11.1, 11.2, 11.6, 11.8, 11.10, 12.1, 13.1, 13.2, 13.3, 13.4_

- [x] 8. Documentation
  - [x] 8.1 Write `docs/RELEASING.md` and link it from the README
    - Cover Requirement 16: procedure (set-version, check, commit, annotated tag, push), secrets and variables table with base64 commands (`base64 -w0`), the three SDK options with the verification list for the hosted option (Garmin terms for automated download, authentication and credential handling, token lifetime, trust and pinning of the CLI tool, no hosting of the SDK in caches or artifacts), manual `.iq` attachment and checksum regeneration, fixing a bad release (delete release and tag, new patch version), pre-release flow, self-hosted runner risks, and the User_Action list.
    - Add a link in `README.md`.
    - _Requirements: 13.7, 16.1, 16.2, 16.3, 16.4, 16.5, 16.6, 16.7_

  - [x] 8.2 Update `docs/RELEASE_CHECKLIST.md`
    - Add the new User_Actions: create secrets and variables, create the `release` Environment and the `v*` tag ruleset, first CI run on GitHub, first `v0.1.0-rc.1` tag, Garmin license verification, `NOTICE` holder/year confirmation, install check of a signed APK on a phone (needs hardware), upload of the `.iq` to the Connect IQ Store. Add a "Verification results" subsection filled in by task 9.
    - _Requirements: 2.6, 17.5_

- [x] 9. Verification on this machine
  - [x] 9.1 Run all script tests and the release check
    - Run `scripts/test-version-lib.sh`, `scripts/test-set-version.sh`, `scripts/test-check-release.sh`, `scripts/test-ci-scripts.sh` and `scripts/check-release.sh` (also with `--tag v0.1.0`) in this repository. Fix every failure at its source.
    - If `shellcheck` is installed, run it on all new scripts; if not, report the package and install command and continue.
    - _Requirements: 17.1, 17.4_

  - [x] 9.2 Build the Android app with a computed version
    - In a temporary copy under `/tmp`, run `scripts/set-version.sh 1.2.3-rc.2`, then `./gradlew --offline assembleRelease` in `PNTome/`. Read the merged manifest or the APK badging output (`aapt2 dump badging`, if present; otherwise inspect `output-metadata.json`) and confirm `versionName 1.2.3-rc.2` and `versionCode 1020302`. Remove the copy.
    - _Requirements: 17.2_

  - [x] 9.3 Build the watch app with an rc version and check the stamp
    - In a temporary clone under `/tmp` (committed state of a copy with `set-version.sh 1.2.3-rc.2` applied and committed there), run `PNTomeGo/build.sh` and confirm `monkeyc` accepts the `-rc.2` `SEMVER`, the guard passes against manifest `1.2.3`, and the output shows `version 1.2.3-rc.2 (<short commit>)` without `-dirty`. Run `build.sh --export` in the clean clone and confirm `dist/PNTomeGo-1.2.3-rc.2.iq` exists. Use the locally installed SDK and the `connectiq-simulator` skill only if a runtime check is needed. Remove the clone.
    - _Requirements: 14.1, 14.3, 14.5, 17.3_

  - [x] 9.4 Check the workflow files and record results
    - Parse both workflow files with Python `yaml.safe_load` (project-local, no install; if the module is missing, report the package) and run `actionlint` if installed (otherwise report `sudo apt install actionlint` availability via `apt-cache policy actionlint` and continue).
    - Write the results of 9.1 to 9.4 and the list of unverified User_Actions into the "Verification results" subsection of `docs/RELEASE_CHECKLIST.md`.
    - Run `git status` and confirm no keystores, `.iq` files, `out/` directories or temp files remain.
    - _Requirements: 17.1, 17.4, 17.5, 8.8_

- [x] 10. Final checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional property tests. They are cheap bash loops and recommended, but the feature works without them. The example tests in 2.15, 4.9 and 6.9 are required.
- Properties here are generated-input loops in bash, not a property-testing library. No test dependency is added.
- User_Actions (not done by this plan): commit the previous spec's work first; create GitHub secrets, variables, the `release` Environment and the tag ruleset; first CI run; first release-candidate tag; verify Garmin license terms; decide on the SDK option for watch builds; install and smoke-test a signed APK on a phone; upload to Google Play, F-Droid and the Connect IQ Store.
- The default watch behaviour is "no watch job in CI" (`CIQ_BUILD` unset). Self-hosted-runner support is implemented but opt-in. Downloading the SDK on GitHub-hosted runners is documented as unverified and not implemented.
- Out of scope: publishing to stores from CI, R8, SPDX headers in all files, build provenance attestation, Dependabot, adding the commit to the Android app UI.

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "2.1", "2.2"] },
    { "id": 1, "tasks": ["1.2", "2.3", "2.4", "2.5", "2.6", "2.7", "2.8"] },
    { "id": 2, "tasks": ["2.9", "5.1", "6.1"] },
    { "id": 3, "tasks": ["2.10", "2.11", "2.12", "2.13", "2.14", "2.15", "4.1", "5.2", "6.2"] },
    { "id": 4, "tasks": ["4.2", "4.4", "4.5", "4.6", "6.3", "6.4", "6.5", "6.6", "6.7", "6.8", "7.1", "7.2"] },
    { "id": 5, "tasks": ["4.3", "4.7", "6.9", "8.1", "8.2"] },
    { "id": 6, "tasks": ["4.8"] },
    { "id": 7, "tasks": ["4.9"] },
    { "id": 8, "tasks": ["9.1", "9.2", "9.3", "9.4"] }
  ]
}
```
