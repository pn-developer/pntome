# Add project specific ProGuard rules here.
# By default no obfuscation/shrinking is enabled for the release build (see build.gradle.kts).
# Minification (R8) is intentionally OFF for the first release (isMinifyEnabled = false).
# Hilt, Room, protobuf-lite, the Ktor server (reflection, optional slf4j classes) and Media3
# need verified keep rules, and no physical device is available to smoke-test minified output.
# Enabling R8 with a mapping file is a follow-up; add and test keep rules before turning it on.
