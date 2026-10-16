# Real-device validation — MedPlay

This checklist is for development builds only. Passing GitHub CI does not prove
real-device capture quality, A/V sync, thermal sustainability or game smoothness.

## Build/install path

1. Run the manual GitHub Actions workflow **Manual dev APK release**.
2. It runs unit tests and assembles the debug APK.
3. It replaces the public prerelease tag/release `dev-latest`.
4. Download both:
   - `MedPlay-debug.apk`
   - `MedPlay-debug.sha256`
5. Verify the APK SHA-256 before installing.

The workflow intentionally uses GitHub Release assets instead of Actions
artifacts so MedPlay does not depend on the account's Actions artifact quota.

### Debug-signing caveat

This is a debug build. A fresh GitHub runner may produce an APK signed with a
different ephemeral debug key from a previous run. Android can then reject an
in-place update because the installed APK and the new APK have different
signatures. If that happens during development, uninstall the previous debug
build before installing the new one; uninstalling clears that app's local
settings and diagnostics.

Do not commit a private signing key to the public repository as a shortcut.
Stable development signing can be added later only through an appropriate
secret-backed workflow.

## Functional smoke test

On a real Android device:

- on Android 13+, test once with notification permission granted and once denied;
- grant MediaProjection consent;
- confirm the foreground notification shows the selected resolution/FPS, retention window and audio/video-only state;
- verify Clip/Stop notification actions are visible when notification permission is granted;
- verify capture still works when notification permission is denied;
- test with RECORD_AUDIO granted;
- test with RECORD_AUDIO denied (video-only fallback);
- grant overlay permission and verify the floating Clip control;
- drag the floating control without triggering a save;
- tap it and verify a 30-second clip save;
- test 15 s, 30 s, 60 s, 2 min, 3 min and 5 min requests where retention allows;
- verify saved files appear under Movies/MedPlay;
- verify clips begin on valid video and play through the end;
- verify internal audio is present only when Android/the source app permits it;
- stop capture and verify no overlay/foreground recorder remains active.

## Save-path checks

For each duration tested:

- confirm the notification reports saving and success/failure;
- confirm rapid repeated taps do not start concurrent exports;
- confirm saving does not visibly stutter gameplay;
- confirm the saved clip ends close to the tap rather than one full segment late;
- verify video and audio end close together;
- verify no corrupt/zero-length clip appears in Gallery after a forced failure.

## Sustained game test

Run at least one continuous gaming session long enough to reveal thermal issues.
A useful first pass is 20–30 minutes.

Observe:

- gameplay smoothness with MedPlay off vs on;
- device thermal state/subjective heat;
- battery drain trend;
- any capture stop/restart;
- visible frame pacing problems in the saved clip;
- A/V sync near the beginning and near the end of a long session.

Do not promote more aggressive resolution/FPS/bitrate defaults from one short
test. Capability metadata means “supported”, not “sustainable”.

## Session diagnostics

After stopping capture, use **Show last recording diagnostics** in MainActivity.
Use **Copy last recording diagnostics** to copy the same compact summary for sharing/comparison.

Record the values for:

- resolution / FPS / bitrate / codec / encoder;
- session duration;
- retention seconds;
- whether internal audio was active;
- maximum Android thermal status;
- encoded sample count;
- timestamp regressions;
- maximum wall-clock output gap;
- time since final encoded output.

No recorded frame/audio payload is included in this diagnostic summary.

## Acceptance direction

A profile should not be considered better merely because image quality is
higher. Reject or downgrade a profile if it causes meaningful game stutter,
persistent encoder stalls, timestamp errors, unstable capture, excessive heat
or materially worse battery load.

Real-device evidence from multiple device classes is required before MedPlay
automatically raises resolution/FPS beyond the conservative startup policy.


## Settings/UI validation

- switch between Español and English and verify visible app controls update;
- choose each quality mode shown by the device and verify the active notification reports the requested resolution/FPS;
- confirm unsupported quality modes are not shown;
- leave quality on Automatic and record the recommended profile shown by the UI;
- choose one Clip duration, then verify the in-app Save clip button, floating Clip control and notification Clip action all use that same duration;
- verify there are no duplicate duration controls that can disagree with one another;
- keep codec/bitrate automatic during this phase; do not infer sustainability from codec capability metadata alone.


## High-motion quality / system-inset validation

- verify the first visible control starts below the status bar/camera cutout and no control is hidden behind the bottom navigation/gesture area;
- rotate the device once and verify left/right display cutouts or navigation bars do not cover controls;
- in Automatic mode, record the recommended resolution/FPS/codec/bitrate shown by the UI;
- if the device exposes 1080p60 or 1440p modes, verify only the modes actually validated by the hardware encoder appear;
- record a shooter or other fast-camera-motion scene with rapid gyro turns and inspect foliage, text, edges and fine texture for smearing/blocking;
- compare Automatic against 1080p30 on the same scene when both are available; do not assume 60 FPS is sharper unless bitrate and thermals remain healthy;
- confirm Moderate/Severe thermal states still reduce bitrate and that cooling restores the full selected bitrate;
- record the actual retention window after the higher-bitrate profile is selected, since the storage-aware buffer may shorten at high quality;
- confirm save remains remux-only: no post-recording sharpening/color pass is required for the normal clip path.


## Single-app capture and premium floating control

- On Android 14 QPR2 or newer, leave **Solo juego (recomendado)** selected and start capture.
- In the Android capture chooser, select the actual game app rather than the entire screen.
- While recording, trigger a notification, open system UI, and show any vendor/sidebar overlay that is not part of the game. Confirm those elements are absent from the saved clip.
- Confirm UI that belongs to the game itself still appears; single-app capture does not remove content rendered by the selected game.
- Repeat once with **Pantalla completa** and confirm legacy whole-display capture remains available where Android permits it.
- Verify game-only capture preserves the same selected codec, resolution, FPS and bitrate profile as full-screen capture; this feature must not lower recording quality.
- Test the floating control in each glass tint. Verify it remains translucent rather than becoming an opaque flat-color disk.
- Test minimum/default/maximum button sizes and low/default/high opacity settings.
- Drag the button, then tap it during gameplay. A tap must provide immediate animated acknowledgement; a completed export must show a check confirmation; a failed export must show failure feedback.
- Tap repeatedly while a clip is already exporting and confirm the control shows a busy state instead of starting concurrent exports.
- Change size/tint/opacity while recording, press **Activar botón flotante** again, and confirm the overlay refreshes with the new appearance.
- In single-app mode, confirm the MedPlay floating control itself is not burned into the game-only recording.


## Polished recording UI validation

- Launch MedPlay from a cold start and after changing language; verify the scroll always begins at the real top and no labels are clipped under the app bar.
- Confirm there is no duplicate MedPlay selector for **Solo juego / Pantalla completa**. On Android 14+ the choice should appear only once in Android's own capture dialog after **Iniciar grabación**.
- In Android's capture dialog, choose **Una app** for game-only capture and **Pantalla completa** only when whole-device capture is intended.
- If overlay permission has not been granted, tap **Iniciar grabación**, grant the floating-window permission, return to MedPlay, and verify capture setup continues automatically without a separate "Activar botón flotante" action.
- Change glass tint, size and transparency and verify the in-app preview updates immediately and matches the actual floating control shown during capture.
- Confirm the floating control appears automatically when capture starts.
- Confirm **Detener grabación** and diagnostic controls are hidden under **Opciones avanzadas** by default.
- Expand/collapse **Opciones avanzadas** and verify Stop / Show diagnostics / Copy diagnostics still work when needed for testing.


## Separate microphone voice capture

Use a real game with voice chat to validate the optional **Grabar mi voz (pista separada)** path.

1. Leave **Grabar audio interno** enabled and enable **Grabar mi voz (pista separada)**.
2. Start a game with its voice-chat microphone OFF. Save a clip after speaking for at least 10 seconds.
3. Confirm game playback audio remains present and that the MP4 contains a second AAC audio track for the player's microphone.
4. Turn the game's voice-chat microphone ON while MedPlay is still recording.
5. If Android gives the game microphone priority, confirm MedPlay shows the small overlay message that the player's voice track is paused. Video and internal game audio must continue uninterrupted.
6. Turn the game's voice-chat microphone OFF again. Confirm MedPlay reports that the microphone is available and the voice track resumes without restarting screen capture.
7. Save another clip spanning all three phases (voice available -> game owns mic -> voice available). The microphone track should contain a silent interval only for the period Android silenced MedPlay, while the game-audio track remains continuous.
8. Stop the session and inspect diagnostics for `microphoneWasActive=true` and, when contention occurred, `microphoneWasSilenced=true`.
9. Repeat once with **Grabar mi voz** disabled and confirm behavior/quality matches the previously validated recorder.

Important: this phase preserves game audio and microphone as independent AAC tracks. A standard gallery player may select only one audio track for playback. Mixing, per-track volume preview and final single-track MP4 export belong to the planned MedPlay editor phase and are not claimed complete here.


## Automatic thermal adaptation validation

For a sustained-game test, start with the same quality mode that previously looked good and play long enough for the phone to warm naturally.

Record:
- `initialResolution` / `initialFps`
- final `resolution` / `fps`
- `maxThermalStatus`
- `thermalProfileDowngrades`
- `sustainabilityVerdict`
- `maxWallOutputGapUs`

Expected behavior:
1. NORMAL/LIGHT: no resolution/FPS downgrade.
2. MODERATE: bitrate may reduce, but resolution/FPS stay unchanged.
3. SEVERE: MedPlay may briefly pause Clip saving while it switches to the next validated lower hardware profile. Screen capture and audio should continue.
4. Persistent SEVERE: another downgrade may occur after about 30 seconds.
5. CRITICAL: capture should stop rather than continue heating the device.
6. After a downgrade, save a clip and verify that video is decodable, audio remains synchronized and no incompatible-format export error occurs.

Also compare a similar-length game session with MedPlay OFF. The goal is not a cold phone during heavy gaming; the goal is to avoid MedPlay adding unnecessary heat and to reduce its load automatically before Android reaches critical pressure.


## Portrait capture and internal-player validation

1. Start MedPlay and choose a portrait game in Android's app-capture chooser.
2. Record long enough to complete several rolling segments and save a clip.
3. In Gallery and in MedPlay, verify the saved MP4 uses portrait dimensions (for example 1080x1920 when that profile is hardware-supported) and the game is not a small portrait image centered in a wide 1920x1080 canvas.
4. Repeat with a landscape game and confirm the prior landscape framing/quality remains unchanged.
5. Open the portrait and landscape clips from **Mis clips**. They should open inside MedPlay's player rather than requiring an external video application.
6. Verify play/pause/seek controls work and returning from the player brings the user back to MedPlay.
7. If the encoder does not support the swapped portrait profile, record the device/model/profile evidence rather than treating fallback to the safe landscape profile as an encoder failure.

Status remains IMPLEMENTADO_PENDIENTE_VALIDACIÓN until these checks pass on a real device.


## Full-session recording validation

1. Select **Grabación completa** and start a game capture.
2. Verify retroactive Clip duration/save controls are hidden for this mode and the microphone option is available.
3. Record at least 2–5 minutes, then use Stop from MedPlay/notification.
4. Wait for finalization and confirm one complete MP4 appears in Gallery and **Mis clips**.
5. Verify the beginning of the session is present, not only the most recent five minutes.
6. Check internal game audio from beginning to end.
7. Repeat once with player microphone enabled; verify the MP4 contains the microphone track where Android allowed capture.
8. Check that full-session mode does not expose the retroactive Clip notification action.
9. Verify a similar retroactive Clip session still behaves exactly as before.
10. For a long test, note the safe session duration implied by available storage and confirm MedPlay saves/stops before consuming the reserved free-space margin.
11. Trigger/observe MODERATE or SEVERE thermal pressure if naturally reached: bitrate protection may apply, but the full-session output should not change video dimensions mid-recording.

Status remains IMPLEMENTADO_PENDIENTE_VALIDACIÓN until real-device evidence is available.
