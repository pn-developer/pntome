---
name: no-physical-devices
description: >-
  The development computer has NO phone and NO Garmin watch connected. Use this
  whenever a task involves adb install, instrumented/on-device tests, running the
  app on a phone, sideloading to a watch, Android Auto/DHU on a real device, or
  any "manual" verification step that needs physical hardware.
keywords:
  - adb
  - device
  - phone
  - watch
  - manual
  - instrumented
  - hardware
---

# No physical devices available

Neither the Android phone nor the Garmin watch is connected to this computer.

## Rules
- Do not run `adb install`, `adb shell`, `connectedAndroidTest`, or watch sideloading; they will fail.
- Verify with what works offline: Gradle compile/build, JVM unit tests, Robolectric tests, Monkey C build and the Connect IQ simulator (see the `connectiq-simulator` skill).
- Instrumented tests (`androidTest`): write them and make sure they compile (`assembleDebugAndroidTest`), but do not try to run them. State that they were not run.
- Runtime spikes needing a device (e.g. playing a SAF track, notification, Android Auto/DHU): implement the code, verify it compiles, and say clearly that runtime behaviour is unverified.
- Manual/hardware tasks (phone, watch, car, DHU): do not attempt them and do not mark them as verified. Leave a short checklist of steps for the user and report them as pending the user's hands-on test.
- Never claim device behaviour was confirmed unless it was run in an emulator/simulator.
