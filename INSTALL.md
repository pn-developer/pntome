# INSTALL — Building GsoundA

This guide sets up a build environment and produces the GsoundA debug APK on **Ubuntu**
(apt / Debian family) and **Manjaro** (pacman / Arch family). The Gradle build itself is
identical on both; only the toolchain install differs.

> All Gradle commands are run from the `GsoundA/` directory via the bundled wrapper
> (`./gradlew`). Do not install Gradle system-wide — the wrapper pins Gradle 8.9.

## 1. Requirements

| Component            | Version / note |
|----------------------|----------------|
| JDK                  | **17** (required; AGP 8.7 targets Java 17) |
| Android SDK          | Platform **android-35**, Build-Tools 35.x, platform-tools |
| Gradle               | 8.9 — provided by the wrapper, do not install manually |
| Git                  | to clone the repo |
| Internet access      | first build downloads Gradle + dependencies (incl. the Connect IQ SDK AAR from Maven Central) |

Environment variables the build relies on:

- `JAVA_HOME` → your JDK 17 install
- `ANDROID_HOME` (a.k.a. `ANDROID_SDK_ROOT`) → your Android SDK location

---

## 2. Ubuntu (apt)

Tested on Ubuntu 22.04 / 24.04.

### 2.1 Install JDK 17 and Git

> This project does not auto-install system packages. Run the install commands yourself
> (they need `sudo`).

```bash
sudo apt update
sudo apt install openjdk-17-jdk git unzip
```

Point `JAVA_HOME` at the JDK 17 install:

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
java -version   # should report 17.x
```

If several JDKs are installed, select 17 as the default:

```bash
sudo update-alternatives --config java
```

### 2.2 Install the Android SDK (command-line tools)

A full Android Studio install also works; the headless path below is enough to build.

```bash
# 1. Download the command-line tools (check developer.android.com for the current link).
export ANDROID_HOME="$HOME/Android/Sdk"
mkdir -p "$ANDROID_HOME/cmdline-tools"
cd /tmp
curl -o cmdline-tools.zip \
  https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip cmdline-tools.zip -d "$ANDROID_HOME/cmdline-tools"
# The tools must live under cmdline-tools/latest/
mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"

# 2. Put the tools on PATH
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

# 3. Accept licenses and install the packages this project needs
yes | sdkmanager --licenses
sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"
```

Persist the env vars (append to `~/.bashrc` or `~/.zshrc`):

```bash
echo 'export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64' >> ~/.bashrc
echo 'export ANDROID_HOME=$HOME/Android/Sdk' >> ~/.bashrc
echo 'export PATH=$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH' >> ~/.bashrc
source ~/.bashrc
```

Continue at [Section 4 — Build](#4-build).

---

## 3. Manjaro (pacman)

Tested on current Manjaro / Arch.

### 3.1 Install JDK 17 and Git

```bash
sudo pacman -Syu
sudo pacman -S jdk17-openjdk git unzip
```

Select JDK 17 as the active Java (Arch ships `archlinux-java`):

```bash
archlinux-java status            # list installed JVMs
sudo archlinux-java set java-17-openjdk
java -version                    # should report 17.x
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk
```

### 3.2 Install the Android SDK

**Option A — official repo packages (simplest):**

```bash
sudo pacman -S android-sdk android-sdk-cmdline-tools-latest \
               android-sdk-platform-tools android-sdk-build-tools
export ANDROID_HOME=/opt/android-sdk
# Make the SDK group-writable for your user (repo installs under /opt):
sudo groupadd -f android-sdk
sudo gpasswd -a "$USER" android-sdk
sudo setfacl -R -m g:android-sdk:rwx "$ANDROID_HOME"
sudo setfacl -d -m g:android-sdk:rwx "$ANDROID_HOME"
# Log out/in (or `newgrp android-sdk`) so the group membership takes effect.

export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
yes | sdkmanager --licenses
sdkmanager "platforms;android-35" "build-tools;35.0.0"
```

> The AUR package `android-sdk` is an alternative if you prefer; the repo packages above
> avoid an AUR helper.

**Option B — command-line tools from Google (same as Ubuntu 2.2):** works identically on
Manjaro. Use `$HOME/Android/Sdk` for `ANDROID_HOME` and follow the steps in Section 2.2.

Persist the env vars (append to `~/.bashrc` or `~/.zshrc`):

```bash
echo 'export JAVA_HOME=/usr/lib/jvm/java-17-openjdk' >> ~/.bashrc
echo 'export ANDROID_HOME=/opt/android-sdk' >> ~/.bashrc
echo 'export PATH=$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH' >> ~/.bashrc
source ~/.bashrc
```

Continue below.

---

## 4. Build

Clone (if you haven't) and build the debug APK:

```bash
git clone <your-repo-url> pn_gsound
cd pn_gsound/GsoundA

# Sanity check the toolchain the wrapper will use:
./gradlew --version    # Gradle 8.9, JVM 17.x

# Build the debug APK (downloads dependencies on first run):
./gradlew assembleDebug
```

The APK is produced at:

```
GsoundA/app/build/outputs/apk/debug/app-debug.apk
```

Other useful builds:

```bash
./gradlew clean assembleDebug        # clean build
./gradlew testDebugUnitTest          # run the JVM unit test suite
./gradlew lintDebug                  # Android lint (report under app/build/reports/)
./gradlew assembleDebugAndroidTest   # compile instrumented tests (no device needed)
```

If the SDK location isn't picked up from `ANDROID_HOME`, create
`GsoundA/local.properties`:

```properties
sdk.dir=/home/you/Android/Sdk        # Ubuntu / cmdline-tools path
# sdk.dir=/opt/android-sdk           # Manjaro repo-package path
```

---

## 5. Install & run on a device

### 5.1 Connect a device or start an emulator

**Physical device:** enable Developer Options → USB debugging, connect via USB, approve
the RSA prompt.

**Emulator:** create and launch an AVD (image must be installed via `sdkmanager`):

```bash
sdkmanager "emulator" "system-images;android-35;google_apis;x86_64"
avdmanager create avd -n gsound35 -k "system-images;android-35;google_apis;x86_64"
emulator -avd gsound35 &
```

> On Linux, hardware acceleration needs KVM. Ensure your user is in the `kvm` group and
> `/dev/kvm` is accessible, otherwise the emulator is slow or won't start.

### 5.2 Install the APK

```bash
cd GsoundA
adb devices                 # confirm the device/emulator is listed
./gradlew installDebug      # build + install
# or:
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Launch GsoundA from the launcher. The bottom navigation shows **Folders**, **Pairing**,
and **Sync**.

---

## 6. Troubleshooting

- **`Unsupported class file major version` / wrong Java:** the build needs JDK 17. Verify
  `java -version` and `JAVA_HOME`. On Arch/Manjaro switch with
  `sudo archlinux-java set java-17-openjdk`; on Ubuntu use
  `sudo update-alternatives --config java`.
- **`SDK location not found`:** set `ANDROID_HOME`/`ANDROID_SDK_ROOT` or add `sdk.dir` to
  `GsoundA/local.properties`.
- **`Failed to install the following SDK components` / licenses not accepted:** run
  `yes | sdkmanager --licenses`.
- **Missing platform / build-tools:** install exactly `platforms;android-35` and a
  `build-tools;35.x` to match `compileSdk 35`.
- **Gradle can't download dependencies:** the first build needs internet access (Google's
  Maven, Maven Central — the Connect IQ SDK AAR comes from Maven Central). Behind a proxy,
  set `HTTP(S)_PROXY` or Gradle `systemProp.*.proxyHost` properties.
- **Permission denied on `/opt/android-sdk` (Manjaro):** apply the `setfacl` steps in 3.2
  or reinstall the SDK under your home directory (Option B).
- **Emulator won't start (KVM):** `sudo usermod -aG kvm "$USER"` then re-login.

---

## 7. Pairing & sync prerequisites (beyond building)

Building and unit-testing GsoundA needs none of the following, but a real pair/sync does:

- Garmin Connect Mobile installed and signed in on the phone.
- A paired Garmin watch, or the Connect IQ simulator with a device profile.
- The **GsoundG** watch app (separate project) installed, with its app UUID matching
  `GsoundA/app/src/main/java/dev/gsound/a/net/garmin/GsoundAppId.kt` (currently a
  placeholder — replace it with the real GsoundG manifest id).
- Phone and watch on the same Wi-Fi/LAN.

The complete manual checklist is in
[`GsoundA/docs/manual-e2e-checklist.md`](GsoundA/docs/manual-e2e-checklist.md).
