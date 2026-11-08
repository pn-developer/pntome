# Requirements Document

## Introduction

This document derives requirements from the approved design `design.md` of the feature `apache-license-ci-release`. The feature relicenses the repository from GPL-3.0-or-later to Apache-2.0, introduces semantic versioning with a single source of truth (`VERSION`), adds GitHub Actions workflows for continuous integration and tagged releases, and keeps the git commit visible in the PNTomeGo watch app.

Statements about license compatibility are engineering notes, not legal advice. Items that depend on Garmin's license terms or on account terms are written as owner verification steps, not as assumptions.

## Glossary

- **Repository**: The git repository `pn_gsound` containing PNTome, PNTomeGo, scripts, documentation and metadata.
- **PNTome**: The Android application in `PNTome/`.
- **PNTomeGo**: The Garmin Connect IQ watch application in `PNTomeGo/`.
- **Version_File**: The file `VERSION` at the repository root, holding the single version string.
- **Version**: A string matching `MAJOR.MINOR.PATCH` or `MAJOR.MINOR.PATCH-rc.N`, with MAJOR from 0 to 209, MINOR and PATCH from 0 to 99, and N from 1 to 98, without leading zeros.
- **Stable_Version**: A Version without the `-rc.N` suffix.
- **Pre_Release_Version**: A Version with the `-rc.N` suffix.
- **Core_Version**: The Version with any `-rc.N` suffix removed.
- **Version_Code**: The Android `versionCode` computed as `(MAJOR*10000 + MINOR*100 + PATCH)*100 + 99` for a Stable_Version and `(MAJOR*10000 + MINOR*100 + PATCH)*100 + N` for a Pre_Release_Version.
- **Version_Library**: The script `scripts/lib/version.sh` that implements validation, Core_Version, Version_Code and Version comparison.
- **Set_Version_Script**: The script `scripts/set-version.sh`.
- **Check_Script**: The script `scripts/check-release.sh`.
- **Release_Notes_Script**: The script `scripts/release-notes.sh`.
- **Checksum_Script**: The script `scripts/make-checksums.sh`.
- **CI_Workflow**: The GitHub Actions workflow `.github/workflows/ci.yml`.
- **Release_Workflow**: The GitHub Actions workflow `.github/workflows/release.yml`.
- **Release_Tag**: A git tag named `v` followed by a Version.
- **Short_Commit**: The output of `git rev-parse --short` for the commit being built.
- **Release_Environment**: The GitHub Environment named `release` that holds the signing secrets.
- **Signing_Secrets**: The four secrets `ANDROID_KEYSTORE_B64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`.
- **Developer_Key_Secret**: The secret `CIQ_DEVELOPER_KEY_B64`.
- **Watch_Build_Switch**: The repository variable `CIQ_BUILD`; the value `true` enables watch jobs.
- **Allowlist**: The set of paths and lines exempt from license-text checks: `.kiro/specs/**`, the `CHANGELOG.md` line describing the relicensing, and `PNTome/gradlew*`.
- **Release_Assets**: The files attached to a GitHub Release: Android APK, Android AAB, optionally the Connect IQ `.iq` package, and `SHA256SUMS`.
- **Owner**: The person who owns the repository and its signing keys.
- **User_Action**: A step that only the Owner can perform, such as creating secrets, pushing tags or verifying Garmin license terms.

## Requirements

### Requirement 1: Apache-2.0 license text and notice

**User Story:** As the Owner, I want the repository licensed under Apache-2.0, so that the license does not conflict with the proprietary Garmin SDKs the apps depend on.

#### Acceptance Criteria

1. THE Repository SHALL contain a file `LICENSE` holding the unmodified Apache License, Version 2.0 text.
2. THE Repository SHALL contain a file `NOTICE` at the repository root that names PNTome and PNTomeGo, states the copyright holder and year, and states that the Garmin Connect IQ SDKs are proprietary and are not covered by the Apache-2.0 license.
3. THE `LICENSE` file SHALL NOT contain the text "GNU GENERAL PUBLIC LICENSE".
4. THE Repository SHALL NOT add per-file license headers to source files as part of this feature.

### Requirement 2: Update of all license mentions

**User Story:** As a user or packager, I want every document and metadata file to name the same license, so that no file claims GPL after the change.

#### Acceptance Criteria

1. THE `README.md` SHALL name `Apache-2.0` as the license of PNTome and PNTomeGo.
2. THE F-Droid metadata file `fdroid/metadata/dev.pntome.app.yml` SHALL contain the line `License: Apache-2.0`.
3. THE file `PNTome/fastlane/metadata/android/en-US/full_description.txt` SHALL name `Apache-2.0` as the license and SHALL stay within 4000 characters.
4. THE files under `store/` SHALL contain no mention of GPL.
5. THE `CHANGELOG.md` SHALL contain, under `Unreleased`, an entry that records the license change from GPL-3.0-or-later to Apache-2.0, and SHALL name `Apache-2.0` in its description of the `LICENSE` file for version 0.1.0.
6. THE `docs/RELEASE_CHECKLIST.md` SHALL replace the GPL open item with a User_Action to verify the Garmin license terms.
7. WHEN the Check_Script runs, THE Check_Script SHALL report a violation for each tracked file outside the Allowlist that matches `GPL`, `LGPL`, `GNU General Public` or `GPL-` followed by a digit.

### Requirement 3: License compatibility notes

**User Story:** As the Owner, I want clear compatibility notes and a verification list, so that I know what remains my decision.

#### Acceptance Criteria

1. THE `THIRD_PARTY.md` SHALL contain a section that states that Apache-2.0 has no copyleft clause toward proprietary libraries, that Apache-2.0 code can be included in GPLv3 projects, that it is not compatible with GPLv2-only, and that the notes are not legal advice.
2. THE `THIRD_PARTY.md` SHALL state that the Apache-2.0 license does not relicense the Garmin SDKs.
3. THE `THIRD_PARTY.md` SHALL list as Owner verification items: the Garmin Connect IQ SDK License Agreement terms on redistribution and hosting, the Connect IQ Mobile SDK terms for shipping the library inside an Apache-2.0 distributed APK and AAB, and the copyright holder name and year in `NOTICE`.
4. THE `THIRD_PARTY.md` SHALL keep the proprietary Garmin Connect IQ Mobile SDK row and SHALL state that the F-Droid anti-feature `NonFreeDep` remains.
5. WHEN the Check_Script runs, THE Check_Script SHALL report a violation IF `LICENSE` does not contain both `Apache License` and `Version 2.0`, IF `NOTICE` does not exist, or IF the F-Droid `License:` value is not `Apache-2.0`.

### Requirement 4: Version file and grammar

**User Story:** As the Owner, I want one file that defines the project version, so that all components agree on the same number.

#### Acceptance Criteria

1. THE Repository SHALL contain the Version_File with exactly one line holding a Version without a `v` prefix.
2. THE Version_Library SHALL accept a string as a Version if and only if the string matches the Version definition in the Glossary.
3. WHEN the Version_Library validates a string with a `+` build metadata part, a leading zero, a pre-release identifier other than `rc`, or a component above its limit, THE Version_Library SHALL reject the string.
4. WHEN the Version_Library derives the Core_Version of a Version, THE Version_Library SHALL return `MAJOR.MINOR.PATCH` of that Version.
5. WHEN the Version_Library derives the Version_Code of a Version, THE Version_Library SHALL return the integer defined in the Glossary, which is at most 2099999999.
6. WHEN the Version_Library compares two Versions, THE Version_Library SHALL order them by SemVer 2.0.0 precedence, so that `X.Y.Z-rc.1` is lower than `X.Y.Z-rc.2` and `X.Y.Z-rc.2` is lower than `X.Y.Z`.
7. THE Version_Library SHALL be the only implementation of validation, Core_Version, Version_Code and comparison used by the Set_Version_Script and the Check_Script.

### Requirement 5: Version propagation

**User Story:** As the Owner, I want one command to update every version carrier, so that releases cannot drift.

#### Acceptance Criteria

1. WHEN the Set_Version_Script is run with a valid Version, THE Set_Version_Script SHALL write the Version to the Version_File, the Version to `versionName` and the Version_Code to `versionCode` in `PNTome/app/build.gradle.kts`, the Version to `SEMVER` in `PNTomeGo/source/Version.mc`, and the Core_Version to the `version` attribute of the application in `PNTomeGo/manifest.xml`.
2. WHEN the Set_Version_Script is run with a Stable_Version, THE Set_Version_Script SHALL also update the `Builds` entry, `CurrentVersion` and `CurrentVersionCode` of `fdroid/metadata/dev.pntome.app.yml` and the `commit` value to the Release_Tag.
3. WHEN the Set_Version_Script is run with a Pre_Release_Version, THE Set_Version_Script SHALL leave `fdroid/metadata/dev.pntome.app.yml`, `CHANGELOG.md` and the fastlane changelog files unchanged.
4. WHEN the Set_Version_Script is run with a Stable_Version and the `Unreleased` section of `CHANGELOG.md` contains at least one bullet, THE Set_Version_Script SHALL rename that section to `[Version] - <UTC date>` and insert a new empty `Unreleased` section.
5. IF the `Unreleased` section of `CHANGELOG.md` contains no bullet WHEN the Set_Version_Script is run with a Stable_Version, THEN THE Set_Version_Script SHALL exit with a non-zero status and SHALL leave all files unchanged.
6. WHEN the Set_Version_Script is run with a Stable_Version, THE Set_Version_Script SHALL create `PNTome/fastlane/metadata/android/en-US/changelogs/<Version_Code>.txt` from the bullets of the new changelog section if the text is at most 500 characters.
7. IF the generated fastlane changelog text exceeds 500 characters, THEN THE Set_Version_Script SHALL not write the file and SHALL tell the Owner to write it by hand.
8. IF a target file does not contain exactly one line matching the expected literal pattern, THEN THE Set_Version_Script SHALL exit with status 2, name the file and pattern, and write no file.
9. IF the given string is not a valid Version, THEN THE Set_Version_Script SHALL exit with status 1 and write no file.
10. IF the given Version is lower than the Version in the Version_File and the option `--allow-downgrade` is absent, THEN THE Set_Version_Script SHALL exit with status 1 and write no file.
11. WHEN the Set_Version_Script is run twice with the same Version, THE Set_Version_Script SHALL leave the working tree identical to the state after the first run.
12. THE Set_Version_Script SHALL NOT create git commits or git tags.
13. THE Set_Version_Script SHALL change only the `SEMVER` line of `PNTomeGo/source/Version.mc` and SHALL leave the `COMMIT` line unchanged.

### Requirement 6: Version consistency check

**User Story:** As the Owner, I want the check script to catch version drift, so that a bad release is stopped before it is built.

#### Acceptance Criteria

1. WHEN the Check_Script runs, THE Check_Script SHALL report a violation IF the Version_File is missing, has more than one line, or does not contain a valid Version.
2. WHEN the Check_Script runs, THE Check_Script SHALL report a violation IF the Android `versionName` differs from the Version, the Android `versionCode` differs from the Version_Code, the `SEMVER` constant differs from the Version, or the manifest application `version` differs from the Core_Version, and the violation message SHALL show all compared values.
3. WHILE the Version is a Stable_Version, THE Check_Script SHALL compare the F-Droid metadata with the Gradle literals and SHALL evaluate the `UpdateCheckData` expression against `PNTome/app/build.gradle.kts`.
4. WHILE the Version is a Pre_Release_Version, THE Check_Script SHALL skip the F-Droid version comparison and SHALL print a warning.
5. WHILE the Version is a Stable_Version, THE Check_Script SHALL report a violation IF the file `changelogs/<Version_Code>.txt` is missing or IF `CHANGELOG.md` has no `## [Version]` heading.
6. WHEN the Check_Script runs, THE Check_Script SHALL report a violation IF `CHANGELOG.md` has no `## [Unreleased]` heading.
7. WHERE the option `--tag <ref>` is given, THE Check_Script SHALL report a violation IF `<ref>` is not equal to `v` followed by the Version.
8. WHEN the Check_Script finds any violation, THE Check_Script SHALL print every violation with its check name and SHALL exit with status 1.
9. THE F-Droid metadata `UpdateCheckMode` SHALL use a Tags pattern that matches only Stable_Version Release_Tags.

### Requirement 7: Android versionCode and release tags

**User Story:** As the Owner, I want release candidates and final builds to have increasing version codes, so that an installed release candidate can be updated by the final release.

#### Acceptance Criteria

1. WHEN two Versions A and B satisfy A lower than B by SemVer precedence, THE Version_Library SHALL return a Version_Code for A that is lower than the Version_Code for B.
2. THE `PNTome/app/build.gradle.kts` SHALL keep `versionName` and `versionCode` as single plain literals on separate lines so that F-Droid and the Check_Script can parse them.
3. WHEN the repository is at the state of this feature, THE `versionCode` SHALL be 10099 and the `versionName` SHALL be `0.1.0`, and the fastlane changelog file SHALL be named `10099.txt`.

### Requirement 8: CI workflow

**User Story:** As a developer, I want automatic checks on pull requests and pushes, so that broken changes are caught early.

#### Acceptance Criteria

1. WHEN a pull request is opened or updated, or a commit is pushed to the `main` branch, THE CI_Workflow SHALL start.
2. THE CI_Workflow SHALL run `scripts/test-check-release.sh`, `scripts/test-set-version.sh` and `scripts/check-release.sh` in a job named `check-release`.
3. THE CI_Workflow SHALL run `testDebugUnitTest`, `lintRelease`, `assembleDebugAndroidTest` and `assembleRelease` in `PNTome/` in a job named `android`, with JDK 17 and without any signing secret.
4. WHEN the Android CI job fails, THE CI_Workflow SHALL upload the Gradle report directory as an artifact retained for 7 days.
5. WHERE the Watch_Build_Switch is `true` and the pull request does not come from a fork, THE CI_Workflow SHALL run the PNTomeGo `build.sh` for the default device and with `--debug` for one other device in a job named `watch`, using a throwaway developer key generated on the runner.
6. WHEN the CI_Workflow checks out a pull request for the watch job, THE CI_Workflow SHALL check out the pull request head commit.
7. WHEN a newer run of the CI_Workflow starts for the same ref, THE CI_Workflow SHALL cancel the older in-progress run.
8. THE CI_Workflow SHALL define a timeout for every job.
9. THE CI_Workflow SHALL declare `permissions: contents: read` at workflow level and SHALL NOT use any secret.

### Requirement 9: Release workflow trigger and verification

**User Story:** As the Owner, I want a pushed version tag to build a release only when everything is consistent, so that bad tags do not publish.

#### Acceptance Criteria

1. WHEN a tag matching `v*.*.*` is pushed, THE Release_Workflow SHALL start with a job named `verify`.
2. WHEN the `verify` job runs, THE Release_Workflow SHALL run the Check_Script with `--tag` set to the pushed tag name.
3. IF the tag commit is not an ancestor of `origin/main`, THEN THE `verify` job SHALL fail.
4. WHEN the `verify` job succeeds, THE `verify` job SHALL output the Version, the Version_Code, whether the Version is a Pre_Release_Version, the Short_Commit and the full commit SHA.
5. IF the `verify` job fails, THEN THE Release_Workflow SHALL start no build job.
6. THE Release_Workflow SHALL allow only one run per tag at a time and SHALL NOT cancel a run in progress.
7. THE Release_Workflow SHALL declare `permissions: contents: read` at workflow level and SHALL raise to `contents: write` only in the job that creates the GitHub Release.

### Requirement 10: Android release build and signing

**User Story:** As the Owner, I want the tag build to produce a signed APK and AAB, so that I can distribute and upload them.

#### Acceptance Criteria

1. WHEN all four Signing_Secrets are present, THE Release_Workflow SHALL decode the keystore to a file under the runner temporary directory with owner-only permissions and SHALL pass `PNTOME_KEYSTORE_FILE`, `PNTOME_KEYSTORE_PASSWORD`, `PNTOME_KEY_ALIAS` and `PNTOME_KEY_PASSWORD` to the Gradle step through step-level environment variables.
2. WHEN all four Signing_Secrets are present, THE Release_Workflow SHALL verify the APK signature with `apksigner verify --print-certs`, SHALL print only the certificate SHA-256 fingerprint, and SHALL fail the job IF verification fails.
3. IF one to three of the Signing_Secrets are present, THEN THE Release_Workflow SHALL fail before building and SHALL name the missing secrets without printing any secret value.
4. IF none of the Signing_Secrets is present, THEN THE Release_Workflow SHALL build unsigned artifacts, SHALL emit a warning annotation, and SHALL name the artifacts with the suffix `-unsigned`.
5. THE Release_Workflow SHALL run `assembleRelease` and `bundleRelease` in `PNTome/` and SHALL name the outputs `PNTome-<Version>-<Short_Commit>-signed.apk`, `PNTome-<Version>-<Short_Commit>-signed.aab` or the `-unsigned` equivalents.
6. THE Release_Workflow SHALL delete the decoded keystore in a step that runs even when earlier steps fail.
7. THE Release_Workflow SHALL upload only the renamed artifacts from a staging directory and SHALL NOT upload the keystore.
8. THE Release_Workflow SHALL use `environment: release` for each job that reads a secret.

### Requirement 11: Watch export in the release

**User Story:** As the Owner, I want the release to optionally include the Connect IQ package built from the tag, so that the store upload matches the release.

#### Acceptance Criteria

1. WHERE the Watch_Build_Switch is `true`, THE Release_Workflow SHALL run a job named `watch` that locates the Connect IQ SDK with `scripts/ci/ciq-sdk.sh`, decodes the Developer_Key_Secret into the runner temporary directory with owner-only permissions, and runs `./build.sh --export` in `PNTomeGo/`.
2. IF the Watch_Build_Switch is not `true`, THEN THE Release_Workflow SHALL skip the `watch` job and the release notes SHALL state that the Connect IQ package was not built by CI.
3. IF the Watch_Build_Switch is `true` and `scripts/ci/ciq-sdk.sh` finds no SDK, THEN THE `watch` job SHALL fail and print the searched locations and the available options.
4. IF the Watch_Build_Switch is `true` and the Developer_Key_Secret is empty, THEN THE `watch` job SHALL fail.
5. WHEN the export succeeds, THE Release_Workflow SHALL copy `dist/PNTomeGo-<Version>.iq` to `PNTomeGo-<Version>-<Short_Commit>.iq` in the staging directory.
6. THE Release_Workflow SHALL delete the decoded developer key in a step that runs even when earlier steps fail.
7. THE Release_Workflow SHALL keep the SDK, the developer key and all temporary files outside the repository checkout so that `git status --porcelain` is empty during the export.
8. THE Repository SHALL NOT contain Connect IQ SDK files, and THE Release_Workflow SHALL NOT store the SDK in artifacts, caches or releases.
9. THE `scripts/ci/ciq-sdk.sh` SHALL use the same SDK discovery order as `PNTomeGo/build.sh`: the `CIQ_SDK` variable, then `~/.Garmin/ConnectIQ/current-sdk.cfg`, then the newest `~/.Garmin/ConnectIQ/Sdks/connectiq-sdk-lin-*` directory.
10. WHERE the repository variable `CIQ_RUNS_ON` is set, THE Release_Workflow and THE CI_Workflow SHALL run the `watch` job on that runner label, and otherwise on `ubuntu-latest`.

### Requirement 12: GitHub Release publication

**User Story:** As a user, I want a GitHub Release with artifacts, checksums and notes, so that I can download and verify the build.

#### Acceptance Criteria

1. WHEN the `verify` and `android` jobs succeed and the `watch` job succeeded or was skipped, THE Release_Workflow SHALL create a GitHub Release named for the Release_Tag with the Release_Assets.
2. THE Checksum_Script SHALL write a file `SHA256SUMS` containing one `sha256sum` line for every asset in the staging directory, such that `sha256sum -c SHA256SUMS` succeeds in that directory.
3. THE Release_Notes_Script SHALL print the body of the `## [Version]` section of `CHANGELOG.md` for a Stable_Version and the body of the `## [Unreleased]` section for a Pre_Release_Version.
4. THE Release_Notes_Script SHALL print the full commit SHA, the Short_Commit, the Release_Tag, the Version_Code, the text `v<Version> (<Short_Commit>)` as the watch app display string, the signing state, the Connect IQ package state, and the verification command for `SHA256SUMS`.
5. WHEN the Version is a Pre_Release_Version, THE Release_Workflow SHALL mark the GitHub Release as a pre-release.
6. WHEN the Version is a Stable_Version and the artifacts are unsigned, THE Release_Workflow SHALL create the GitHub Release as a draft.
7. IF a GitHub Release for the Release_Tag already exists, THEN THE Release_Workflow SHALL fail without changing it.
8. THE Release_Workflow SHALL create the GitHub Release with the `gh` CLI and `--verify-tag` and SHALL use no third-party release action.

### Requirement 13: Workflow security rules

**User Story:** As the Owner, I want workflows that follow least privilege and do not leak secrets, so that the signing keys stay protected.

#### Acceptance Criteria

1. THE workflows SHALL reference `actions/*` and `gradle/*` actions by major version tag and any other action by a full 40-character commit SHA.
2. THE workflows SHALL NOT use the `pull_request_target` trigger.
3. THE workflows SHALL declare top-level `permissions` in every workflow file.
4. THE workflows SHALL pass secrets to scripts only through `env:` and SHALL NOT interpolate `${{ secrets.* }}` in a `run:` line.
5. THE scripts under `scripts/ci/` SHALL NOT enable shell tracing and SHALL NOT print the value of any secret, the decoded keystore or the developer key.
6. WHEN the Check_Script runs, THE Check_Script SHALL report a violation IF a workflow file breaks any of criteria 1 to 4 or IF no workflow files exist.
7. THE documentation SHALL instruct the Owner to restrict creation of `v*` tags with a tag ruleset and to store the secrets in the Release_Environment, as User_Actions.

### Requirement 14: Watch app commit display

**User Story:** As a tester, I want the watch app to keep showing the git commit it was built from, so that I can tell which build is installed.

#### Acceptance Criteria

1. WHEN `PNTomeGo/build.sh` builds, THE `build.sh` SHALL write the output of `git rev-parse --short HEAD` into the `COMMIT` constant of `source/Version.mc` for the build and SHALL restore the file afterwards.
2. THE watch UI display string SHALL be `v<SEMVER> (<COMMIT>)`, with the suffix ` dbg` on debug builds.
3. WHEN `PNTomeGo/build.sh --export` runs, THE `build.sh` SHALL stamp the clean Short_Commit without a `-dirty` suffix and SHALL refuse to run IF `git status --porcelain` reports changes.
4. WHEN a CI watch job builds, THE job SHALL compare the line `build.sh: version <SEMVER> (<COMMIT>)` of the build output with the expected Version and Short_Commit, and SHALL fail IF the commit is `dev`, ends with `-dirty`, or differs from the expected Short_Commit.
5. WHEN the checkout is shallow, THE `build.sh` SHALL still stamp the correct Short_Commit.
6. WHERE the SEMVER contains an `-rc.N` suffix, THE `build.sh` version guard SHALL compare the Core_Version of SEMVER with the manifest application version.
7. WHEN the `Version.mc` file is edited by the Set_Version_Script, THE header comment of `Version.mc` SHALL state that SEMVER is written by `scripts/set-version.sh`.
8. THE root `.gitignore` SHALL ignore the artifact staging directory `out/`.

### Requirement 15: Workflow checks in the Check_Script

**User Story:** As the Owner, I want the repository checks to cover the workflow files, so that the security rules are enforced by tests.

#### Acceptance Criteria

1. THE Check_Script SHALL implement the checks of Requirement 13 criteria 1 to 4 under the check name `[workflows]`.
2. THE test script `scripts/test-check-release.sh` SHALL inject one violation per new check (bad Version_File, Version_Code drift, `SEMVER` drift, manifest not equal to Core_Version, GPL text in `LICENSE`, missing `NOTICE`, wrong F-Droid license, GPL mention outside the Allowlist, tag mismatch, unpinned non-first-party action, `pull_request_target`, missing top-level `permissions`, secret in a `run:` line) and SHALL expect the Check_Script to fail naming that check each time.
3. THE test script `scripts/test-set-version.sh` SHALL exercise stable and pre-release runs, idempotence, invalid input, downgrade refusal, pattern failure without writes, changelog rotation, and the 500-character case.
4. THE test script `scripts/test-version-lib.sh` SHALL run at least 100 generated cases per property and SHALL print the random seed when a case fails.
5. THE test script `scripts/test-ci-scripts.sh` SHALL exercise `prepare-signing.sh` with all, none and partial Signing_Secrets, and `collect-watch.sh` with a matching commit, `dev`, and `-dirty`.

### Requirement 16: Release documentation

**User Story:** As the Owner, I want a short release guide, so that I can repeat the process and know which steps are mine.

#### Acceptance Criteria

1. THE `docs/RELEASING.md` SHALL describe the release procedure: run the Set_Version_Script, run the Check_Script, commit, create an annotated Release_Tag, push the branch and the tag.
2. THE `docs/RELEASING.md` SHALL list the secrets and repository variables with their purpose and how to create the base64 values.
3. THE `docs/RELEASING.md` SHALL describe the three ways to obtain the Connect IQ SDK for a watch build (self-hosted runner, hosted runner with an SDK manager command line tool, no watch job) and SHALL state what must be verified for the hosted option before use.
4. THE `docs/RELEASING.md` SHALL describe the manual attachment of a locally built `.iq` file and the regeneration of `SHA256SUMS`.
5. THE `docs/RELEASING.md` SHALL state that a bad release is fixed by deleting the release and tag and tagging a new patch version.
6. THE `docs/RELEASING.md` SHALL list as User_Actions: creating secrets and variables, creating the Release_Environment and the tag ruleset, the first CI run, the first pre-release tag, and the Garmin license verification items.
7. THE README SHALL link to `docs/RELEASING.md`.

### Requirement 17: Verification without a phone or a watch

**User Story:** As the Owner, I want the verification to rely only on what this machine can run, so that unverified items are clearly marked.

#### Acceptance Criteria

1. WHEN the feature is implemented, THE implementer SHALL run the four new test scripts and `scripts/check-release.sh` on the development machine and record the results.
2. WHEN the feature is implemented, THE implementer SHALL build `PNTome` with `./gradlew --offline` after running the Set_Version_Script on a temporary copy, and SHALL confirm that the built manifest carries the computed `versionCode` and `versionName`.
3. WHEN the feature is implemented, THE implementer SHALL build `PNTomeGo` with the locally installed SDK using an `-rc.1` Version on a temporary copy and SHALL confirm that `monkeyc` accepts it and that the stamped commit is shown in the build output.
4. IF a verification tool such as `shellcheck`, `actionlint` or `apksigner` is not installed, THEN THE implementer SHALL report the package name, the one-line description and the `sudo apt install <package>` command to the Owner and SHALL NOT install the package.
5. THE implementer SHALL record every check that needs GitHub, a phone or a watch as a User_Action in `docs/RELEASE_CHECKLIST.md`.
