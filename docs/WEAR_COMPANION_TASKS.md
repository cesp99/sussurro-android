# Sussurro Wear OS Companion — Task List

Atomic, picked-up-by-anyone tasks. Each one is sized to land independently.

Read `docs/WEAR_COMPANION.md` first for architecture / current state.

Sanity check before claiming any task complete:

```
./gradlew :shared:compileKotlin :wear:assembleDebug :app:assembleDebug
```

All three currently succeed. Keep them green.

---

## P1 — ONBOARDING FOR THE NEW PATHS

Once the build is green, the user still has to enable the accessibility
service and (optionally) install the watch app before either path works.

### Task 4 — Add onboarding cards for accessibility + watch

`app/src/main/java/de/aploi/sussurrobyeyed/ui/screens/OnboardingScreen.kt`
already has a card pattern (`OnboardingCard`). Add two more cards (after
the IME step, before "How to use"):

1. **Accessibility (optional but recommended).** Card uses
   `TextInjector.isAccessibilityEnabled(context)` for `completed`. Tap
   "Enable accessibility" → start an intent to
   `Settings.ACTION_ACCESSIBILITY_SETTINGS` with FLAG_ACTIVITY_NEW_TASK.
2. **Watch companion (optional).** Card with description from
   `R.string.step_watch_desc`. For status detection: `CapabilityClient`
   `getCapability(WearableProtocol.CAPABILITY_WATCH, FILTER_REACHABLE)`
   and show "paired" or "no watch detected" — wrap in a suspend helper
   so the card can show "Loading…" first.

Also extend `MainActivity.kt`'s `SussurroApp` composable to re-check
accessibility on resume just like it re-checks IME enabled state.

The `allReady` gate should **not** require the optional cards.

### Task 5 — Settings tile: watch status & "send a test transcription"

In `SettingsScreen.kt`, add a section above About:

```kotlin
SectionCard(title = "Watch companion") {
    // status row (paired/not, lastTranscript)
    // explicit "send test" button → forces an empty transcription to
    // confirm the IME / a11y path works
}
```

Optional, low priority — but quality-of-life if the user ever hits an
opaque "no focus" error.

---

## P2 — RELIABILITY HARDENING

### Task 6 — Survive watch loss of focus mid-recording

`WatchAudioRecorder` currently doesn't respond to
`AudioManager.OnAudioFocusChangeListener`. On Wear, dropping the wrist
backgrounds the activity but the FGS keeps the mic. Confirm by adb
logcat: drop wrist → mic should keep producing samples. If it doesn't,
add a `PARTIAL_WAKE_LOCK` in `WatchSessionService` while
`state == Recording`.

### Task 7 — Cap session length

Mirror the phone's `AudioRecorder.MAX_DURATION_SECONDS = 60` on the
watch: in `WatchAudioRecorder.start`, count bytes and call `stop()`
once 60 s of audio has been streamed; surface this via
`WatchSessionController` so the watch UI flashes "max length reached".

The phone side already trims correctly thanks to whisper.cpp's 30 s
window padding, but spamming a 5-minute session over BT will be slow
and the user gets no feedback.

### Task 8 — Auto-fade lastTranscript on the watch

`WatchSessionController.lastTranscript` is set but no UI reads it yet.
Add a tiny overlay in `WatchScreen` that, when `state == Committed`,
shows the first ~40 chars of the transcript for 1.5 s before fading
out. Spec:

- One-line, ellipsised.
- Sits between the status text and the waveform.
- Auto-clears via `LaunchedEffect(lastTranscript) { delay(1500); ... }`.

### Task 9 — Better waveform near the round edge

`WaveformGlow` currently tapers via `1 - (dx/r)^2`. On smaller watch
faces the outermost bars still disappear behind the bezel before the
taper fully zeroes them out. Switch to clipping the canvas to the bottom
half of the implied circle (use `clipPath` with the same arc Path we
already build for the ambient glow).

### Task 10 — Compact the bytes-on-the-wire

Right now the watch streams uncompressed PCM (32 kB/s). Over BLE that's
fine, but if the user records a 30 s clip we transfer ~1 MB which
takes noticeable time. Two options, in order of preference:

1. **Opus**: encode on the watch via `MediaCodec` (Opus is mandatory on
   API 21+). Decode on the phone in `WatchPhoneSessionService`.
2. **Downsample to 8 kHz then upsample on phone** before feeding
   Whisper. Cuts bandwidth in half. Easier than Opus, lossy.

Skip until the user complains about latency.

---

## P3 — POLISH

### Task 11 — Pull the watch's Compose theme into something deliberate

`WatchScreen` currently uses `Color.Black` and `Color.White` directly.
Promote to a `WatchColors` object in `ui/theme/Theme.kt` (new file) so
both ambient and active modes can share a single source of truth.

### Task 12 — Wear OS Tile

Add a `TileService` that lets the user tap a tile face → start a
Sussurro session without opening the app. Requires:

- `androidx.wear.tiles:tiles` dependency.
- New service + manifest entry.
- A `LayoutElementBuilders` tile with the same visual idiom as the main
  screen (waveform-style chip).

Genuinely nice-to-have, not blocking ship.

### Task 13 — Ongoing notification on the phone deep-links into focused app

When `WatchPhoneSessionService` shows the "Listening…" notification, its
content intent currently opens Sussurro itself. More useful: keep the
user where they were. We can leave the content intent as-is (back to
Sussurro is fine as a fallback) and add an explicit "Stop" action that
sends `SESSION_CANCEL` to the watch.

---

## Verification checklist (for the agent claiming "done")

1. `./gradlew :shared:compileKotlin` → 0 errors.
2. `./gradlew :wear:assembleDebug` → produces an APK at
   `wear/build/outputs/apk/debug/wear-debug.apk`.
3. `./gradlew :app:assembleDebug` → produces an APK at
   `app/build/outputs/apk/debug/app-debug.apk`.
4. `./gradlew :app:lintDebug` → no new errors (warnings OK).
5. Phone APK installs on a real device, settings screen still loads, the
   keyboard still works in isolation (regression check).
6. With the watch APK on a paired Wear OS device, a recorded session
   commits text into the focused field on the phone using either:
   - IME path (Sussurro is selected keyboard, keyboard visible)
   - A11y path (any other keyboard, any text field focused).

If any of these fails for a reason that's outside the task being
claimed, document it back into this file and split it into a new task.
