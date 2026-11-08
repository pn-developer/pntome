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

## Open item: GPL-3.0-or-later with a proprietary SDK

PNTome is licensed under `GPL-3.0-or-later` (see `LICENSE`), yet it links a proprietary
SDK. Whether this combination is compatible with the GPL, and whether Garmin's terms allow
redistribution inside a GPL-licensed app, depends on the exact Garmin license text and on
how the SDK is linked. This file does not settle that question and is not legal advice.

Decision needed from the project owner:

- Confirm that `GPL-3.0-or-later` is the intended license given the Garmin SDK, or
- choose another approach (for example, add a GPL linking exception for the SDK, or pick a
  different license), after reading Garmin's SDK terms and, if needed, taking legal advice.

Until this is confirmed, the F-Droid metadata marks the app with the `NonFreeDep`
anti-feature.
