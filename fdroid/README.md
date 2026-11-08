# PNTome self-hosted F-Droid repository

Files here:

- `metadata/dev.pntome.app.yml`: build metadata (also usable as a draft for fdroiddata).
- `config.example.yml`: example fdroidserver config with no secrets.

Main F-Droid inclusion is out of scope: the Garmin Connect IQ Mobile SDK is
proprietary, which is declared as the `NonFreeDep` anti-feature. All URLs in the
metadata use `example.invalid` and must be replaced.

Steps marked **User_Action** need your keys, accounts or server. They are never automated.

## Prerequisites

`fdroidserver` is not installed on this machine. Install it yourself:

```
sudo apt install fdroidserver
```

(Ubuntu package `fdroidserver`, candidate 2.4.3-1.) You also need the Android SDK
(`ANDROID_HOME`) and a JDK for the build.

## 1. Initialise the repository (User_Action)

Work in a separate, git-ignored directory, for example `fdroid/` itself
(`fdroid/repo/`, `fdroid/config.yml` and keystores are git-ignored).

```
cd fdroid
cp config.example.yml config.yml     # edit URLs and paths
fdroid init                          # User_Action: creates the repo signing key
```

`fdroid init` creates a keystore and asks for or generates passwords. Store them
safely and keep them out of git.

## 2. Build and sign the APK (User_Action)

Either let fdroidserver build from the metadata (`fdroid build dev.pntome.app`,
needs the tag `v0.1.0` to be published at the real `Repo` URL), or build the
release APK yourself and copy it into `fdroid/repo/`:

```
cd PNTome && ./gradlew assembleRelease
```

Signing is a User_Action: you provide the keystore and passwords
(`PNTOME_KEYSTORE_FILE`, `PNTOME_KEYSTORE_PASSWORD`, `PNTOME_KEY_ALIAS`,
`PNTOME_KEY_PASSWORD`, see `PNTome/INSTALL.md`). Do not paste them into files.

## 3. Update the index (User_Action)

```
cd fdroid
fdroid update --create-metadata      # signs the index with the repo key
```

Review the generated index and the fastlane texts taken from
`PNTome/fastlane/metadata/android/`.

## 4. Deploy (User_Action)

Set `serverwebroot` in `config.yml` to your web host, then:

```
fdroid deploy
```

Publishing needs your server and credentials. Users then add the repository URL
(and the repo key fingerprint) to the F-Droid client.

## Signing keys differ between channels

The Google Play build is signed with your Play upload key (Play re-signs with its
app signing key). The F-Droid repo build is signed with the key you use for this
repo. Android refuses updates across different signing keys, so a device cannot
update between the Play and the F-Droid repo versions; uninstall first. Keep each
key for the life of its channel.

## Keeping version info valid

`UpdateCheckData` reads `versionCode` and `versionName` from
`PNTome/app/build.gradle.kts`. Keep both as single plain literals there.
