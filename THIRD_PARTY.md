# Third-party software

PNTome (Android) links the runtime dependency groups below. Versions are pinned in
`PNTome/gradle/libs.versions.toml`. Test-only and build-time tools (JUnit, Espresso,
Robolectric, KSP, Room compiler, Hilt compiler, Android Gradle Plugin) are not shipped in
the app and are not listed. The watch app (PNTomeGo) uses only the Garmin Connect IQ
runtime on the device and bundles no third-party libraries.

Licenses below are those published by each project; confirm against the upstream
repositories before a release.

| Dependency group | Maven group(s) | Version key | License |
|---|---|---|---|
| AndroidX core, lifecycle, activity, documentfile, navigation | `androidx.core`, `androidx.lifecycle`, `androidx.activity`, `androidx.documentfile`, `androidx.navigation` | coreKtx, lifecycleRuntimeKtx, activityCompose, documentfile, navigationCompose | Apache-2.0 |
| Jetpack Compose (BOM, UI, Material 3) | `androidx.compose`, `androidx.compose.ui`, `androidx.compose.material3` | composeBom | Apache-2.0 |
| AndroidX Hilt navigation | `androidx.hilt` | hiltNavigationCompose | Apache-2.0 |
| Dagger Hilt | `com.google.dagger` | hilt | Apache-2.0 |
| Kotlin coroutines | `org.jetbrains.kotlinx` (coroutines) | coroutines | Apache-2.0 |
| kotlinx.serialization JSON | `org.jetbrains.kotlinx` (serialization) | kotlinxSerialization | Apache-2.0 |
| AndroidX DataStore | `androidx.datastore` | datastore | Apache-2.0 |
| Protocol Buffers (javalite runtime) | `com.google.protobuf` | protobuf | BSD-3-Clause |
| AndroidX Room | `androidx.room` | room | Apache-2.0 |
| Ktor server (CIO, content negotiation, partial content, auto head response, kotlinx JSON) | `io.ktor` | ktor | Apache-2.0 |
| AndroidX Media3 (ExoPlayer, session) | `androidx.media3` | media3 | Apache-2.0 |
| **Garmin Connect IQ Mobile SDK (companion app)** | `com.garmin.connectiq:ciq-companion-app-sdk` | connectIq | **Proprietary (Garmin license)** |

## Proprietary component

`com.garmin.connectiq:ciq-companion-app-sdk` is distributed by Garmin under Garmin's own
license terms, not under a free-software license. Its source is not available. It is
published on Maven Central and is linked into the PNTome APK and AAB.

## License compatibility notes

PNTome and PNTomeGo are licensed under `Apache-2.0` (see `LICENSE` and `NOTICE`). These
notes are engineering notes, not legal advice.

- Apache-2.0 is permissive and has no copyleft clause, so linking a proprietary library does
  not require that library to be licensed under Apache-2.0.
- Apache-2.0 code can be included in a GPLv3 project (one-way compatibility). Apache-2.0 is
  not compatible with GPLv2-only. This matters only for people who want to reuse PNTome code.
- The Apache-2.0 license covers only the code in this repository. It does not relicense the
  Garmin SDKs and grants no rights to them.
- Earlier project snapshots were published under the previous license (GPLv3 or later).
  Anyone who received those snapshots keeps the rights granted by that license for them.
  The Apache-2.0 license applies to new commits.
- F-Droid: the `NonFreeDep` anti-feature remains, because the app still links the proprietary
  Garmin Connect IQ Mobile SDK.

### Owner verification list

- Garmin Connect IQ SDK License Agreement: terms on redistribution and hosting of SDK
  materials and of apps built with it (for example, publishing the `.iq` as a release asset).
- Connect IQ Mobile SDK terms: whether the library may ship inside an APK/AAB distributed
  under Apache-2.0.
- Copyright holder name and year in `NOTICE`.
