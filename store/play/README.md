# Google Play release checklist

Everything in this list is a user action. Nothing here is automated.

## Before you start
- [ ] Create a Google Play developer account (one-time fee, identity verification).
- [ ] Create the app in Play Console: name `PNTome`, default language English (en-US), type App, free.
- [ ] Generate an upload keystore and keep it safe (never commit it). Enable Play App Signing.
- [ ] Re-check the Google Play target API level policy (see below).

## Build
- [ ] Set `PNTOME_KEYSTORE_FILE`, `PNTOME_KEYSTORE_PASSWORD`, `PNTOME_KEY_ALIAS`, `PNTOME_KEY_PASSWORD`.
- [ ] Run `scripts/check-release.sh`.
- [ ] Build the bundle: `./gradlew bundleRelease` in `PNTome/`.

## Store listing
- [ ] Copy title and descriptions from `PNTome/fastlane/metadata/android/en-US/` (see `listing.md`).
- [ ] Upload `icon.png` (512x512), `featureGraphic.png` (1024x500) and at least 2 phone screenshots (see `PNTome/fastlane/metadata/android/en-US/images/README.md`).
- [ ] Set the privacy policy URL to the hosted `PRIVACY.md` (placeholder: `https://example.invalid/pntome/PRIVACY.md`).
- [ ] Fill in contact details (placeholder: `contact@example.invalid`).

## App content
- [ ] Data safety form: use `data-safety.md`.
- [ ] Foreground service declarations (`dataSync` and `mediaPlayback`): use `permissions.md`; record a short video for each.
- [ ] Content rating questionnaire and target audience: see `listing.md`.
- [ ] Ads: no. News app: no. Government app: no.

## Release
- [ ] Upload the `.aab` to an internal testing track first and install from Play on a real device.
- [ ] Release notes: `PNTome/fastlane/metadata/android/en-US/changelogs/1.txt`.
- [ ] Promote to production after review.

## Target API policy (re-check before every upload)
The app currently sets `compileSdk = targetSdk = 35`. Google Play requires new apps and updates to target a recent API level and raises the bar on a yearly schedule. Before the first upload, read the current policy on the Play Console help page "Target API level requirements for Google Play apps" and record the result here:

- Date checked: _not yet checked_
- Required target API level: _not yet checked_
- Action needed: _none / bump to 36 and test on a device_

Bumping the target API changes runtime behaviour and needs testing on a device.
