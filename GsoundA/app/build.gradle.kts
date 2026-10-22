import com.google.protobuf.gradle.id

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.protobuf)
}

android {
    namespace = "dev.gsound.a"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.gsound.a"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        // Robolectric JVM unit tests need access to merged Android resources/manifest.
        unitTests.isIncludeAndroidResources = true
    }
}

// Room: export the DB schema to a versioned JSON file for migration tooling and tests.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// Proto DataStore: generate lite Java bindings from .proto schemas under src/main/proto.
protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.get()}"
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                id("java") {
                    option("lite")
                }
            }
        }
    }
}

// KSP (Hilt) must see the generated Proto DataStore classes, so ensure proto codegen runs
// before KSP processes annotations. Pair each ksp<Variant>Kotlin task with its matching main
// generate<Variant>Proto task only — depending on the *Test proto tasks would introduce a
// circular dependency via bundle*ClassesToCompileJar.
afterEvaluate {
    tasks.matching { it.name.startsWith("ksp") && it.name.endsWith("Kotlin") }.configureEach {
        // e.g. "kspDebugKotlin" -> variant "Debug" -> "generateDebugProto".
        val variant = name.removePrefix("ksp").removeSuffix("Kotlin")
        dependsOn("generate${variant}Proto")
    }
}

dependencies {
    // AndroidX + Compose
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    // LifecycleService: scopes the sync foreground service's coroutines to its lifecycle (task 9.2).
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    debugImplementation(libs.androidx.ui.tooling)

    // Navigation-Compose
    implementation(libs.androidx.navigation.compose)

    // Storage Access Framework helpers (DocumentFile for tree URI display-name resolution)
    implementation(libs.androidx.documentfile)

    // Hilt (DI)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    // Coroutines
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // DataStore (Proto)
    implementation(libs.androidx.datastore)
    implementation(libs.protobuf.javalite)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // kotlinx.serialization
    implementation(libs.kotlinx.serialization.json)

    // Ktor server (CIO engine, embedded)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.partial.content)
    implementation(libs.ktor.server.auto.head.response)

    // Garmin Connect IQ Mobile SDK (companion app AAR from Maven Central)
    implementation(libs.connectiq.companion.app.sdk)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    // Robolectric JVM unit tests for use cases that touch Context/ContentResolver/Uri and an
    // in-memory Room DB (task 4.3): no emulator required.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.junit)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.core.ktx)

    // Compose UI tests run on the JVM via Robolectric (task 10.3): createComposeRule + text
    // assertions, no emulator required. The BOM aligns these with the Compose versions above.
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.ui.test.junit4)
    // ui-test-manifest declares the empty ComponentActivity that createComposeRule launches. It
    // must merge into the app manifest (not the test classpath) so Robolectric can resolve the
    // activity when running the Compose UI tests as JVM unit tests. `./gradlew build` runs
    // testReleaseUnitTest, so the manifest contribution must be present for BOTH variants —
    // contributing only to debug made testReleaseUnitTest fail with "Unable to resolve activity
    // for Intent ... ComponentActivity". The activity is not exported and has no launcher filter,
    // so it is inert even if it reaches a release build.
    debugImplementation(libs.androidx.ui.test.manifest)
    releaseImplementation(libs.androidx.ui.test.manifest)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.ktor.server.test.host)

    // Instrumented tests: Room in-memory DB + DataStore round-trips (task 3.3)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.core.ktx)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
