import com.google.protobuf.gradle.id
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.protobuf)
}

// Release signing comes from environment variables or an ignored PNTome/keystore.properties
// (keys: storeFile, storePassword, keyAlias, keyPassword). Env wins per value. With no input at
// all, the release build stays unsigned (F-Droid / offline CI); with only some inputs, fail.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(envName: String, propName: String): String? =
    (System.getenv(envName) ?: keystoreProps.getProperty(propName))?.takeIf { it.isNotBlank() }

val signingInputs = linkedMapOf(
    "PNTOME_KEYSTORE_FILE" to signingValue("PNTOME_KEYSTORE_FILE", "storeFile"),
    "PNTOME_KEYSTORE_PASSWORD" to signingValue("PNTOME_KEYSTORE_PASSWORD", "storePassword"),
    "PNTOME_KEY_ALIAS" to signingValue("PNTOME_KEY_ALIAS", "keyAlias"),
    "PNTOME_KEY_PASSWORD" to signingValue("PNTOME_KEY_PASSWORD", "keyPassword"),
)
val missingSigningInputs = signingInputs.filterValues { it == null }.keys
val hasReleaseSigning = missingSigningInputs.isEmpty()
if (!hasReleaseSigning && missingSigningInputs.size < signingInputs.size) {
    throw GradleException(
        "Incomplete release signing configuration. Missing: " +
            missingSigningInputs.joinToString(", ") +
            ". Provide all four (environment variables or PNTome/keystore.properties) or none."
    )
}
if (!hasReleaseSigning) {
    logger.warn(
        "WARNING: no release signing inputs found; the release build will be UNSIGNED. " +
            "Set PNTOME_KEYSTORE_FILE, PNTOME_KEYSTORE_PASSWORD, PNTOME_KEY_ALIAS, " +
            "PNTOME_KEY_PASSWORD or create PNTome/keystore.properties to sign."
    )
}

android {
    namespace = "dev.pntome.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.pntome.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 10099
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(signingInputs.getValue("PNTOME_KEYSTORE_FILE")!!)
                storePassword = signingInputs.getValue("PNTOME_KEYSTORE_PASSWORD")
                keyAlias = signingInputs.getValue("PNTOME_KEY_ALIAS")
                keyPassword = signingInputs.getValue("PNTOME_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // F-Droid / reproducible builds: do not embed the encrypted dependency blob.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
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
        // Print failing tests (name + full stack trace) to the console, so a CI failure is
        // readable in the job log without downloading the report artifact.
        unitTests.all { test ->
            test.testLogging {
                events("failed", "skipped")
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
                showStackTraces = true
                showCauses = true
                showExceptions = true
            }
        }
    }

    // Room MigrationTestHelper (instrumented migration test) reads the exported schemas
    // (app/schemas) as androidTest assets. The JVM migration test reads them from disk instead.
    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }
}

base.archivesName = "PNTome"

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

    // Media3: ExoPlayer + MediaLibraryService/MediaSession for the phone player (PL-1, PL-4.3)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)

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
