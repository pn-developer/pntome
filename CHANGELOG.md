# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed

- License changed from GPL-3.0-or-later to Apache-2.0

## [0.1.0] - Unreleased

First public release.

### Changed

- Renamed the Android app from GsoundA to PNTome and the Garmin watch app from GsoundG to PNTomeGo.
- Renamed the Android application id and Kotlin package from dev.gsound.a to dev.pntome.app (this replaces the old Gsound identifiers).
- Renamed the HTTP header, log tags, notification channel and intent actions that carried the old Gsound name. The shared header is now `X-PNTome-Secret`.
- The Connect IQ app id is unchanged.

### Added

- Release signing from environment variables or `keystore.properties`, release APK and AAB builds.
- Connect IQ Store export (`./build.sh --export`).
- Store metadata for Google Play, a self-hosted F-Droid repository and the Connect IQ Store.
- `LICENSE` (Apache-2.0), `PRIVACY.md`, `THIRD_PARTY.md`, `CHANGELOG.md`, `docs/RELEASE_CHECKLIST.md`.

### Upgrade notes

- Pairing replacement: old phone and watch builds must be replaced together. The renamed header and tags are a shared protocol, so an old phone build does not work with a new watch build, or the other way around. Install the new PNTome and the new PNTomeGo, then pair again.
- The old app (dev.gsound.a) is a separate Android app. Its data is not migrated.

[Unreleased]: https://example.invalid/pntome/compare/v0.1.0...HEAD
[0.1.0]: https://example.invalid/pntome/releases/tag/v0.1.0
