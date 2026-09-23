# Wakey Android starter

This is the first, text-driven slice of a sideloaded Android assistant. It provides
working Android action entry points so voice and an LLM can use the same phone
control layer.

## Implemented in source

- `open Settings`, `open YouTube`, or `open <installed app label>` via Android
  launcher intents.
- `flashlight on` and `flashlight off` with a runtime camera permission request.
- An optional Accessibility service that observes other apps' visible UI trees.
  Return to Wakey and press **Inspect last external screen** to see its bounded
  text summary.
- Accessible action primitives for tapping a labeled control, scrolling the
  active view, and going back. These are available to the upcoming agent loop.

The starter has **no microphone, wake word, LLM, or remote API calls** yet.
It cannot unlock a secure phone. APK sideloading does not grant Accessibility
permission automatically; enable it manually in Android Settings if desired.

## Build and install

Requires JDK 17+ and Android SDK 35 (platform `android-35`, build-tools 35.0.0).
The project uses Android Gradle Plugin 8.8.2, Kotlin 2.0.21 and the included
Gradle 8.10.2 wrapper (distribution and wrapper jar SHA-256 verified against
services.gradle.org).

```sh
echo "sdk.dir=/path/to/android-sdk" > local.properties   # or set ANDROID_HOME
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or copy `app-debug.apk` to the phone, open it, and allow "Install unknown apps"
for the file manager/browser you opened it from.

Android 12 (API 31) or later is needed for this initial project. A phone with a
flash is needed to test torch commands. Open other apps while Accessibility is
enabled, return to Wakey, and inspect the last external screen.

## Next task

Send `CLAUDE_TASK.md` and this project to the coding agent. It specifies a
single end-to-end voice/navigation milestone, test cases, and delivery checks.
