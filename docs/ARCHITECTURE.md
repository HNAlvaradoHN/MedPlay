# ARCHITECTURE — MedPlay ⏯️ Prototype

Status: INITIAL_PROTOTYPE_DIRECTION
Protocol version: 1

## Goal

Build a native Android application that continuously captures recent screen activity into a bounded rolling buffer and lets the user save the previous configurable interval after an important event has already happened.

Primary use case: fast-motion mobile games where ordinary low-bitrate screen recording produces visible pixelation.

## Initial stack

- Android native.
- Kotlin.
- MediaProjection for screen capture.
- MediaCodec hardware encoder when available.
- MediaMuxer or equivalent containerization strategy for saved clips.
- Foreground service for long-running capture.
- Overlay/floating control for save action.
- App-private temporary/buffer storage.
- No backend required for the recording MVP.

## Capture pipeline

Target pipeline:

`MediaProjection -> VirtualDisplay -> hardware encoder -> rolling encoded buffer -> save trigger -> clip assembly/mux -> MediaStore/Gallery`

The app should avoid retaining an unlimited full-session recording. It should retain only the configured recent window plus small safety margins needed for valid keyframe-based clip creation.

## Quality model

Quality must not be defined by resolution alone.

The profile system should consider:
- source/device native resolution;
- output resolution;
- FPS;
- codec;
- bitrate or quality mode;
- encoder capabilities;
- keyframe interval;
- thermal/load limits.

Initial user-facing options should target:
- 720p;
- 1080p;
- 1440p when supported;
- native resolution when supported;
- 30/60 FPS;
- higher FPS only when hardware and Android encoder capabilities genuinely support it;
- H.264 for broad compatibility;
- HEVC/H.265 when supported for better quality-per-bit;
- AV1 only on devices with suitable hardware support.

## Retroactive clip strategy

A naive byte ring buffer is not enough because saved clips must begin on a decodable keyframe and contain valid container metadata.

Prototype directions to evaluate:
1. rolling sequence of short encoded segments;
2. rolling sample buffer with timestamps/keyframe indexes followed by remux;
3. fragmented MP4 segments if Android tooling and compatibility are acceptable.

The selected approach must:
- bound disk/RAM use;
- save quickly;
- produce a normal playable file;
- avoid unnecessary re-encoding;
- preserve A/V synchronization.

## Clip duration

Initial target presets:
- 15 seconds;
- 30 seconds;
- 60 seconds;
- 2 minutes;
- 3 minutes;
- 5 minutes;
- custom duration where device/storage limits allow.

## Audio

Evaluate:
- internal playback capture where Android/app policy permits;
- microphone;
- internal + microphone if technically safe and synchronized;
- no audio.

Do not promise internal audio for apps/devices that block capture.

## Floating controls

Minimum controls:
- save previous interval;
- show/hide controls;
- pause/stop recording;
- visible recording status.

Overlay behavior must not interfere with gameplay outside the control region.

## Device capability detection

At runtime inspect available encoders and supported:
- codec;
- size;
- FPS;
- bitrate range/profile/level.

Do not expose impossible combinations as if they were guaranteed.

## REA/JADX analysis workflow

Reference-app analysis is a supporting research workflow, not part of the shipped app.

Planned cloud path:
1. GitHub Actions receives or retrieves a permitted APK/APKS input without permanently committing it.
2. Install Java, JADX and REA-compatible tooling.
3. Produce structured analysis/log artifacts.
4. ChatGPT reviews the GitHub Actions output.
5. Findings are translated into original MedPlay design decisions.

## Definition of first prototype success

On a real Android device:
1. user grants MediaProjection consent;
2. MedPlay records continuously for several minutes;
3. configured rolling window remains bounded;
4. user taps save after an event;
5. the prior interval is saved as a playable video;
6. fast-motion quality is acceptable for the selected profile;
7. audio is synchronized when enabled;
8. gameplay remains usable;
9. recording stops/cleans up safely;
10. no private recording data leaves the device by default.
