# Sussurro Wear OS Companion — Handoff

Master reference for the Wear OS companion. The granular task list lives
in `docs/WEAR_COMPANION_TASKS.md`.

## Goal

Let the user record audio on their Wear OS smartwatch and have it transcribed
on their paired phone, with the transcript written directly into whatever
text field is focused on the phone — **even when Sussurro is not the
selected keyboard**.

UX target (per the reference screenshot at the top of `git log` — the
screenshot the user shared shows a near-empty round watch face with a faint
bluish glow growing from the bottom):

- Black background, glowing white waveform that grows from the bottom edge.
- Three states: `Idle`, `Recording`, `Transcribing`, plus brief `Committed`
  / `Error` flashes. Phone also shows progress in a notification.
- Single tap toggles: idle → recording → stop & transcribe.
- Long-press cancels.

## Architecture

Three Gradle modules:

| Module    | Role                                                                 |
|-----------|----------------------------------------------------------------------|
| `:shared` | Pure-Kotlin JVM. Wire protocol (paths, `SessionState`, codecs).      |
| `:wear`   | Wear OS APK. Records audio, streams PCM, shows the waveform UI.      |
| `:app`    | Phone APK. Receives PCM, runs whisper.cpp, injects text into fields. |

### Wire protocol (`:shared`)

`WearableProtocol`:
- `AUDIO_CHANNEL = "/sussurro/audio"` — ChannelClient stream, raw 16-bit LE
  PCM @ 16 kHz mono, watch → phone.
- `SESSION_START`, `SESSION_STOP`, `SESSION_CANCEL` — MessageClient pings
  from watch → phone, no payload.
- `PHONE_STATE` — phone → watch, `SessionStateCodec.encode(state, reason?)`
  payload (1 byte ordinal + optional UTF-8 reason).
- `PHONE_TRANSCRIPT` — phone → watch, UTF-8 transcript.
- Capabilities: phone advertises `sussurro_phone`, watch advertises
  `sussurro_watch`.

`SessionState` ordinals are protocol — **do not reorder**:

```
Idle, Recording, Transcribing, Committed, Error
```

### Watch (`:wear`)

- `WatchAudioRecorder` — 16 kHz mono PCM into an `OutputStream` (the
  ChannelClient sink). Exposes `state` and `rms` flows.
- `WearableTransport` — finds phone node, opens/closes ChannelClient,
  sends MessageClient pings.
- `WatchSessionController` (singleton) — owns `state`/`reason`/
  `lastTranscript` flows; `start/stop/cancel` mutate them under a mutex.
- `WatchSessionService` (foreground service, FGS_TYPE_MICROPHONE) — holds
  the mic claim while a session is running; promotes via `OngoingActivity`
  so the watch face status chip is visible.
- `WatchMessageListener` (`WearableListenerService`) — receives
  `PHONE_STATE` and `PHONE_TRANSCRIPT` and pipes them into the controller.
- `WaveformGlow` — the full-screen bottom-anchored animation. 24 bars at
  the bottom, soft underglow, edge taper, transcribing shimmer overlay.
- `WatchScreen` — black background, status text up top, waveform fills
  rest. Tap anywhere → toggle. Long-press → cancel.
- `MainActivity` — sets content, owns the RECORD_AUDIO permission launcher.

### Phone (`:app`)

- `WatchPhoneListener` (`WearableListenerService`) — catches the audio
  channel opened by the watch and forwards start/stop/cancel pings to the
  session service.
- `WatchPhoneSessionService` — foreground service that reads the
  ChannelClient input stream into a `FloatArray`, trims silence, calls
  `WhisperEngine.transcribe`, then routes the transcript through
  `TextInjector`. Shows a notification reflecting current state.
- `PhoneWearableTransport` — sends `PHONE_STATE` and `PHONE_TRANSCRIPT`
  back to the watch.
- `TextInjector` — IME path first (`SussurroIme.tryCommitFromExternal`),
  AccessibilityService fallback otherwise.
- `SussurroAccessibilityService` — tracks the last edited node, exposes
  `appendToFocusedField` using `ACTION_SET_TEXT` so we can write into any
  app's text fields without being the active IME.
- `SussurroIme` — extended with `tryCommitFromExternal` so the watch
  pipeline routes through the IME's `InputConnection` when Sussurro IS
  the active keyboard (preserves undo + autocorrect behaviour).

## Current build state

- `:shared` — compiles cleanly. Verified via `./gradlew :shared:compileKotlin`.
- `:wear` — **builds and assembles to APK** (`wear/build/outputs/apk/debug/wear-debug.apk`). Verified via `./gradlew :wear:assembleDebug`.
- `:app` — **builds and assembles to APK** (`app/build/outputs/apk/debug/app-debug.apk`). Verified via `./gradlew :app:assembleDebug`.

End-to-end on real hardware is still unverified; the touched-but-untested
list lives at the bottom of this doc.

## Important conventions

- Plugin catalog: `kotlin.jvm` plugin is applied to `:shared` via raw
  `id("org.jetbrains.kotlin.jvm")` *without a version*. AGP 9.x has Kotlin
  on the classpath already; declaring a version errors with "plugin already
  on the classpath with an unknown version". Don't try to fix this with a
  version reference.
- `:wear` `compileSdk = 37` (required by transitive `androidx.core` 1.18).
  `minSdk = 30`, `targetSdk = 35`.
- `:wear` does **not** include any native code. Whisper inference happens
  only on the phone.
- StateFlow in `:wear`: don't call `distinctUntilChanged()` on it — already
  deduped by Operator Fusion, and the compiler treats it as deprecated.
- Phone foreground service uses `FOREGROUND_SERVICE_TYPE_DATA_SYNC`, not
  `_MICROPHONE`. The watch holds the mic; the phone just receives bytes.
  Asking for `MICROPHONE` here would force a phone-mic permission grant
  the user hasn't given.
- AccessibilityService strategy: `ACTION_SET_TEXT` with `existing + new`.
  We never wipe the user's text, only append. Cursor moved to end via
  `ACTION_SET_SELECTION` best-effort.
- `SussurroIme.tryCommitFromExternal` lives in the companion object and
  routes through a `@Volatile private var liveImeRef` populated in
  `onCreate` / cleared in `onDestroy`.

## Known UX edge cases to think about

- Both IME and AccessibilityService disabled → `TextInjector.inject`
  returns `Result(false, "enable accessibility")`. The session service
  forwards "enable accessibility" as the error reason; onboarding should
  prompt the user once and then stop bothering them.
- Watch loses BT mid-stream → `getInputStream` will hit EOF early. The
  service should still transcribe what it has (current code does).
- Concurrent watch sessions: gated behind `sessionMutex` in
  `WatchPhoneSessionService`, but the watch UI itself doesn't guard against
  spamming start. If this becomes a problem, add a debounce in
  `WatchSessionController.startInBackground`.
- The phone notification needs `POST_NOTIFICATIONS` runtime permission on
  Android 13+; manifest declares it but the phone onboarding doesn't ask
  yet (low priority, system will just silently drop the notification).

## How to test once the build is green

Two paired Android devices (or watch emulator + phone). Both with debug
APKs installed.

1. Phone: open Sussurro → grant mic → download model → enable a11y
   service (or keep IME selected).
2. Watch: open Sussurro → grant mic.
3. Tap watch screen → waveform grows.
4. Speak.
5. Tap again → watch shows "Transcribing", phone notification updates,
   text appears in the focused field.

Useful logcat tags: `WatchPhoneSession`, `WatchPhoneListener`,
`PhoneWearableTransport`, `SussurroA11y`, `SussurroIme`, plus on the watch
`WatchSessionController`, `WearableTransport`, `WatchAudioRecorder`.

## Out of scope (don't expand the feature yet)

- Wear OS Tile / complication launcher entry.
- Streaming partial transcription (we send full audio post-stop).
- Custom watch face / always-on dial behaviour.
- Multi-language hint UI on the watch.
- Battery telemetry / session history.

## Touched but never run on real hardware

These compile / assemble but have never been verified end-to-end on a
real watch + phone pair.

- Watch APK layout: the waveform animation is plausible but the bottom-
  edge clip taper may still bleed off the round screen depending on
  device size. Task 9 (clipPath to the bottom arc) hardens this.
- AccessibilityService `appendToFocusedField` has not been exercised
  against real input fields. Cursor positioning via `ACTION_SET_SELECTION`
  is best-effort and may regress in some apps (notably WebViews and
  Chrome's Omnibox, which often refuse the selection action).
- The IME's `tryCommitFromExternal` path has not been exercised; the
  static `liveImeRef` should work but check for races in
  `onCreateInputView` if `tryCommitFromExternal` is invoked while the
  IME is bound but not yet visible.

## Code style for this feature

- Comments explain *why*, not *what*. Every non-obvious choice has a
  short rationale in the surrounding code — match that tone.
- Imports stay alphabetically sorted within their group.
- No new `companion object`s that only hold a `TAG` constant — only add
  one when there's actual public surface.
- Logging tags are stable strings, no string interpolation in the TAG
  itself.
- Don't add the `kotlin-android` plugin alias to existing `:app` —
  AGP 9 auto-applies Kotlin support, declaring it again errors.
- Toolchain pinned to JDK 17 across all modules.
