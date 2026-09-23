# Claude task: finish Wakey's first phone-agent vertical slice

Work from this `WakeyStarter` Android project. Do the implementation and build;
do not stop after a design document. The owner has an LLM API key, a Deepgram
key, and an AssemblyAI key, but none belongs in code, an APK, Git, logs, or
screenshots. They will enter keys at runtime. Do not ask them to paste keys in a
chat message or commit.

## Current source

The typed app launcher and flashlight commands are in `MainActivity.kt`.
`WakeyAccessibilityService.kt` observes visible UI and has tap/scroll/back
primitives. Inspect them first. This environment has JDK 17 but no Android
SDK, Gradle, emulator, or phone, so the source has not been compiled here.

## Milestone 1: APK that navigates by voice

1. Add a verified Gradle 8.10.2 wrapper, build the starter, and fix any
   compiler/runtime errors. Preserve the existing direct actions. Build a
   debug APK installable on Android 12+.
2. Add a tap-to-talk microphone button with Deepgram Nova-3 Multilingual
   streaming transcription (English/Hindi). Add AssemblyAI Universal-Streaming
   English as a selectable second adapter. Open the stream only after a user
   action, close on end-of-utterance/cancel/error, and show the recognized words.
   Android TextToSpeech is sufficient for the reply.
3. Add a runtime configuration screen for LLM provider base URL, model name,
   and user-supplied API key. Use an Android Keystore-backed at-rest storage
   approach. Probe provider compatibility with a safe small request. If the
   owner's endpoint supports it, benchmark `qwen3.8-flash`; otherwise use the
   owner's configured vision/tool-capable model. Do not silently switch the
   provider or incur unbounded charges.
4. Review the Apache-2.0 Android-native harness in
   https://github.com/imoonkey/closepaw before writing the agent loop. Reuse
   its suitable on-phone control and verification components if integration
   is practical; report the decision. Provide a bounded agent loop: observe
   current Accessibility UI tree;
   give the LLM the user goal, current app, actionable labels and recent
   actions; accept only validated typed actions (`open_app`, `tap_label`,
   `scroll`, `back`, `finish`, `ask_user`); execute one action; read the new
   screen; verify progress; stop on completion, repeated screen, user cancel,
   timeout, or 10 steps. Add a screenshot + vision fallback only where the
   visible UI tree cannot identify the next control. Request the necessary
   screenshot capability only when this fallback is implemented. Never claim
   success from a tap alone.
5. Show the user what the agent is doing and expose Stop. Ask confirmation
   before sending messages, spending money, or changing sensitive settings.
   Do not bypass the lock screen or assume secure app screens are readable.

## Test these exact flows

- `open YouTube` opens the installed YouTube app (or reports that it is absent).
- `turn on flashlight` changes the light and `turn off flashlight` reverses it.
- `open Settings and find Bluetooth` navigates real UI screens with an
  observe/act/verify loop; report whether Bluetooth settings were reached.
- `open Chrome and search for cats` performs a multi-step browser UI task
  without a desktop or ADB connection at runtime.
- Cancel while the agent is navigating; it must stop further taps and close
  any microphone stream.

Record success/failure, number of steps, median elapsed time, LLM calls and
tokens, speech session time, and device model/Android version. Run the flows
on a real Android device if one is connected; otherwise run what is available
in an emulator and mark real-phone testing as outstanding. Do not report an
unrun test as passed.

## Milestone 2: wake word, after milestone 1 works

Add a local `Hey Wakey` detector (properly trained/licensed weights), a visible
enable/disable switch, and a manual microphone fallback. The detector must
not keep Deepgram or AssemblyAI connected while idle. Test with display on
and off, false activations, and extra standby battery drain. Keep arbitrary
custom wake phrases as model import/training rather than mere text renaming.

## Optional Jev experiment, after the baseline

If the owner supplies TypeSafe access, add Jev behind a flag for choosing
among candidate actions from the Accessibility tree. It accepts structured
text and closed choices, not screenshots or free-form conversations. Compare
the same task traces against the LLM-only baseline. If the tree is incomplete
or confidence is low, use the vision-capable LLM or ask the user. Do not make
Jev a required dependency of the APK.

Deliver the code, APK, install steps, and a short test report. State separately
what was built, what ran on a device, and which phone/API keys are still needed.
