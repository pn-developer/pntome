# INSTALL — Building GsoundA (Android)

This guide sets up a build environment and produces the GsoundA debug APK on **Ubuntu**
(apt / Debian family) and **Manjaro** (pacman / Arch family). The Gradle build itself is
identical on both; only the toolchain install differs.

> For an overview of the whole repo and links to the GsoundG (watch) build guide, see the
> top-level [`../INSTALL.md`](../INSTALL.md).

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
- The **GsoundG** watch app installed — build it with
  [`../GsoundG/INSTALL.md`](../GsoundG/INSTALL.md). Its app UUID must match
  `GsoundA/app/src/main/java/dev/gsound/a/net/garmin/GsoundAppId.kt` (currently a
  placeholder — replace it with the real GsoundG `manifest.xml` id).
- Phone and watch on the same Wi-Fi/LAN.

The complete manual checklist is in
[`docs/manual-e2e-checklist.md`](docs/manual-e2e-checklist.md).

---
## 8. Android Auto (Desktop Head Unit)

GsoundA exposes its library to Android Auto as a media app (Audiobooks, Music, Recently
played). The steps below set up a test bench on the development computer. They describe the
intended procedure; they have not been verified on a device or in a car as part of this
guide (tracked as task 6.7 in `.kiro/specs/gsound-player/tasks.md`).

### 8.1 Enable Android Auto developer mode (phone)

1. Install/update **Android Auto** on the phone (it is built in on Android 10+; open
   *Settings → Apps → Android Auto*).
2. Open Android Auto settings and tap **Version** (or *Version and permission info*)
   **10 times**; confirm the prompt to enable developer mode.
3. Open the three-dot menu → **Developer settings**.
4. Turn on **Unknown sources**. GsoundA is installed from a debug APK, not from Google Play,
   so Android Auto lists it only with this switch on.

### 8.2 Install the Desktop Head Unit (computer)

1. In `sdkmanager` (or Android Studio *SDK Manager → SDK Tools*) install
   **Android Auto Desktop Head Unit Emulator**:
   ```bash
   sdkmanager "extras;google;auto"
   ```
   The binary is `$ANDROID_HOME/extras/google/auto/desktop-head-unit`.
2. If it fails to start because of a missing shared library, install the package your
   distribution names in the error message yourself (for example `sudo apt install <pkg>`);
   this guide does not install system packages.

### 8.3 Run it

1. Connect the phone by USB (USB debugging on) and install GsoundA (section 5.2). Add at
   least one folder and, for books, mark it as an audiobook.
2. Forward the head-unit port:
   ```bash
   adb forward tcp:5277 tcp:5277
   ```
3. On the phone: Android Auto → three-dot menu → **Start head unit server**.
4. On the computer:
   ```bash
   $ANDROID_HOME/extras/google/auto/desktop-head-unit
   ```
   The head unit window opens and mirrors the Android Auto car UI.

### 8.4 Browse, play, skip, resume

- Open the media launcher in the head unit and pick **Gsound**.
- **Audiobooks** / **Music** list the marked folders; **Recently played** lists the last
  books with a saved position (newest first).
- Tap a book: the whole book is queued and playback starts at the saved position, minus a
  5 second rewind. A finished book, or a book without a bookmark, starts at chapter 1.
  Tapping a chapter inside a book starts that chapter at 0. Music folders start at track 1.
- Next/previous skip chapters or tracks. Book queues also show **-30 s** and **+30 s**
  buttons.
- Resume: pause or disconnect, reopen **Gsound** (or **Recently played**) and tap the book;
  playback continues from where it stopped. The same position is shown on the phone.
- Voice ("play <title> in Gsound") matches a folder or chapter title; an unknown or empty
  request continues the last book.

### 8.5 Known limitations

- Sideload only: Android Auto shows the app only with *Unknown sources* enabled. The app is
  not published on Google Play and has not gone through Google's media-app review for cars.
- Artwork is the default app icon for every item; no per-book cover art.
- Browsing is limited to the three top-level lists, folders and chapters; there is no
  in-car settings screen.
- Real cars may behave differently from the Desktop Head Unit (voice, steering wheel keys).

---

## 9. Phone player

- On the **Folders** screen tap the play button of a folder to play it. A **mini-player** at
  the bottom shows the current item; tap it to open the full player (title, folder,
  position, seek, previous/next).
- Playback continues in the background with a media notification. Play/pause, previous/next
  and seek work from the notification and the lock screen.
- Folders marked as an audiobook play in natural file order (`2` before `10`) and show
  **-30 s** / **+30 s** buttons. Music folders play in their stored order.
- Positions of audiobooks are saved automatically (on pause, chapter change, seek, every
  15 s, and when the app is closed).

## 10. Bookmark sync (phone ↔ watch)

Audiobook positions are shared between the phone, Android Auto and the watch.

- Open the **Sync** screen and tap **Refresh stats**. The phone pulls the bookmarks of the
  watch and pushes its own newer ones. A sync also pushes newer phone bookmarks.
- Each book is listed as **Ch N · mm:ss** with the device it came from (**Phone** or
  **Watch**).
- The bookmark with the newest time (`at`) wins; on a tie the local one is kept.
- Resume starts 5 s before the saved position, on both devices.
- A book that is playing on the watch right now is not overwritten.
- Books are matched by folder id (`fid`), with the folder name as a fallback.

## 11. Limitations and still to check

- The watch saves its position on events (pause, chapter change, exit), so it can be stale
  (risk R4); a fresher phone position wins over it.
- Android Auto works only for sideloaded builds with *Unknown sources* on (section 8.1).
- Phone and watch clocks should agree. Timestamps more than 5 minutes in the future are
  clamped.
- Only folders marked as an audiobook sync bookmarks; music folders do not.
- None of this has been verified on a real phone, watch or car as part of this guide.
  Checks for you to do (see `.kiro/specs/gsound-player/tasks.md`):
  - [ ] 6.7 DHU browse/play/skip/resume, then in a car (voice, steering wheel keys).
  - [ ] 11.2 Listen on the watch, pause, Refresh stats; the phone shows the position and
        resumes from it.
  - [ ] 11.3 Listen in the car, Refresh stats; the watch Resume shows the car position
        (about 5 s earlier).
  - [ ] 11.4 Play on both devices between refreshes; the newest wins and the Refresh stats
        list names the device.
