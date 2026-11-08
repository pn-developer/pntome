# PNTomeGo: Garmin Connect IQ Store submission

This is a checklist for publishing PNTomeGo (the watch app) to the Connect IQ Store.
Every step marked **User action** needs your Garmin account or your developer key.
Nothing here is automated.

Related files: [listing.md](listing.md) (text for the store form), [images.md](images.md) (image requirements and capture).

## App facts

| Item | Value |
|------|-------|
| App name | PNTomeGo |
| Type | `audio-content-provider-app` |
| App id (UUID) | `0236dc0a39fd45f09a109f48fc8c7be2` (from `PNTomeGo/manifest.xml`; must never change) |
| Version | see `PNTomeGo/manifest.xml` (`version`), kept equal to `AppVersion.SEMVER` in `source/Version.mc` |
| Min API level | 3.0.0 |
| Permission | Communications |
| Supported devices | fenix 6S Pro (`fenix6spro`), fenix 6 Pro (`fenix6pro`, also the Solar variant), fenix 6X Pro (`fenix6xpro`), fenix 8 AMOLED 43 mm (`fenix843mm`), fenix 8 AMOLED 47 mm (`fenix847mm`), fenix 8 Pro 47 mm (`fenix8pro47mm`), fenix 8 Solar 47 mm (`fenix8solar47mm`), fenix 8 Solar 51 mm (`fenix8solar51mm`) |

If `manifest.xml` changes, update this table and `listing.md`.

## Keep the developer key (important)

The `.iq` package is signed with your Connect IQ developer key (`developer_key.der`).
Garmin ties the app listing to that key. Later updates must be signed with the same key,
or you cannot update the existing listing.

- **User action:** back up `developer_key.der` in at least two private places (for example an encrypted password manager and an offline drive).
- Never commit it. The root `.gitignore` ignores `*.der`, and `scripts/check-release.sh` fails if one is tracked.
- Never share it. Anyone with the key can sign apps as you.
- Do not generate a new key for a later release.

## Steps

1. **User action:** sign in to the Connect IQ developer portal (https://developer.garmin.com/connect-iq/) with your Garmin account and accept the developer agreement if asked.
2. Confirm the version is final. Bump `version` in `PNTomeGo/manifest.xml` and `AppVersion.SEMVER` in `PNTomeGo/source/Version.mc` together.
3. Commit all changes and tag the release. The export refuses a dirty working tree.
4. Point the build at your key and export. From `PNTomeGo/`:

   ```bash
   export CIQ_DEVKEY=/path/to/developer_key.der   # default: ~/.Garmin/developer_key.der
   ./build.sh --export
   ```

   The result is `PNTomeGo/dist/PNTomeGo-<version>.iq`. It covers all products in the manifest.
   `--export` implies a release build and refuses `--debug`.
5. **User action:** (optional but recommended) sideload a release `.prg` on a real supported watch and check sync and playback with PNTome on the phone, following `PNTomeGo/INSTALL.md`.
6. **User action:** in the developer portal choose "Upload an app" and upload the `.iq` file.
7. **User action:** fill in the listing using [listing.md](listing.md): name, short and long description, category, what's new.
8. **User action:** upload images prepared per [images.md](images.md). Re-check the current requirements in the portal first.
9. **User action:** confirm the supported devices list shown by the portal matches the table above.
10. **User action:** state the privacy position honestly: the watch app has no accounts, no analytics and no ads. It talks only to PNTome on the paired phone over the local network (Wi-Fi) and via Garmin Connect Mobile messaging. Link the privacy policy if the form asks for one (`PRIVACY.md` in the repository, or its published URL).
11. **User action:** submit for review. Garmin reviews each submission. Answer any reviewer questions. Review outcomes and timing are decided by Garmin.
12. **User action:** after approval, check the store page on a phone with Garmin Connect Mobile and install it on a watch.
13. Record the published version in `CHANGELOG.md`.

## Updates

- Use the same developer account and the same `developer_key.der`.
- Keep the same app id in `manifest.xml`.
- Bump the version, export a new `.iq`, then upload it as a new version of the existing app and write a new "what's new".

## Notes

- PNTomeGo needs the PNTome Android app and Garmin Connect Mobile. Old sideloaded builds from before the rename must be replaced together with the new phone app (see `CHANGELOG.md`).
- Store rules, image sizes and review criteria change over time. Verify them in the portal at upload time.
