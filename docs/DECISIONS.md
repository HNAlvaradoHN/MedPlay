# DECISIONS — MedPlay ⏯️

## DEC-001 — Native Android + Kotlin

Status: OWNER_DIRECTION / ACCEPTED_FOR_PROTOTYPE

Use native Android/Kotlin.

Reason:
The core features depend directly on MediaProjection, MediaCodec, foreground services, overlays, MediaStore and device encoder capabilities.

## DEC-002 — Retroactive recording is the core product feature

Status: OWNER_DECISION

MedPlay continuously retains only a configurable recent window and saves that previous interval when triggered.

Reason:
The user should not need to know beforehand that a noteworthy gameplay moment is about to happen.

## DEC-003 — Quality is a first-class requirement

Status: OWNER_DECISION

MedPlay should prioritize high visual quality for fast-motion content and expose multiple quality configurations.

Consequence:
Resolution alone is insufficient. Bitrate/quality mode, FPS, codec, encoder capability and thermal limits must be handled explicitly.

## DEC-004 — Hardware encoding preferred

Status: ACCEPTED_FOR_PROTOTYPE

Prefer MediaCodec hardware encoders when available.

Reason:
Continuous game capture must minimize CPU load and battery/thermal impact.

## DEC-005 — No backend required for recording MVP

Status: ACCEPTED_FOR_PROTOTYPE

Capture, rolling buffering and clip saving remain local to the Android device.

Reason:
Lower cost, lower privacy exposure and simpler architecture.

## DEC-006 — REA/JADX used only for reference analysis

Status: OWNER_DIRECTION

Use REA/JADX to study reference applications such as Medal and understand architecture/behavior relevant to MedPlay.

Constraints:
- no proprietary source copying;
- no protected asset copying;
- no permanent public commit of third-party APKs without redistribution rights;
- findings must be converted into original MedPlay implementation decisions.

## DEC-007 — Cloud analysis should avoid requiring the owner's PC

Status: OWNER_DECISION

Prefer GitHub Actions for REA/JADX experiments so the analysis can run from the cloud and results can be inspected from ChatGPT/GitHub.

## DEC-008 — Optimize for sustainable quality, not absolute maximum settings

Status: OWNER_DECISION

MedPlay must target the highest quality a device can sustain without materially harming gameplay responsiveness or thermal behavior.

Consequences:
- do not force the highest resolution, FPS or bitrate globally;
- capability detection is mandatory;
- stable frame delivery is more important than nominal peak settings;
- thermal/load safeguards should be designed into the recorder;
- user-visible high-quality modes may exceed defaults, but unsafe/unsupported combinations should not be presented as guaranteed.

## DEC-009 — Prefer segmented encoded rolling storage and remux over re-encoding

Status: RESEARCH-BACKED DIRECTION / ACCEPTED_FOR_PROTOTYPE

Prototype the rolling recorder as short encoded segments retained in a bounded window, then assemble/remux selected segments when the user saves a clip.

Reason:
Medal analysis confirms a segmented recorder/muxer subsystem, and this architecture aligns with MedPlay's own performance goals.

Expected benefits:
- low save-time CPU use;
- reduced thermal spikes;
- no generational quality loss from re-encoding;
- bounded storage;
- simpler retroactive retention.

This is an original MedPlay design decision; Medal's proprietary implementation must not be copied.


## DEC-010 — Performance regressions are product defects

Status: OWNER_DECISION

Recording quality improvements must not be accepted if they materially degrade gameplay responsiveness, sustained thermals, battery behavior, or encoder stability on the target device class.

Required engineering behavior:
- prefer zero-copy or minimum-copy paths where Android APIs allow;
- prefer surface-input hardware MediaCodec encoders;
- never re-encode saved retroactive clips by default;
- validate resolution/FPS/codec combinations against device capabilities;
- use conservative defaults and expose higher modes only when sustainable;
- measure dropped frames, encode latency, thermal state, memory and storage pressure during real-device tests;
- degrade quality gracefully before the OS/device is forced into severe thermal throttling;
- avoid background work unrelated to active capture while recording.

This decision is a release criterion, not an optional optimization task.


## DEC-011 — Use explicit playback-reference AEC3 for game + voice

Status: IMPLEMENTED_PENDING_DEVICE_VALIDATION

When MedPlay records internal playback and player microphone simultaneously, use software WebRTC AEC3 with the captured internal playback PCM as explicit far-end reference and the microphone PCM as near-end input before microphone AAC encoding.

Keep `AudioSource.MIC` rather than switching the primary path to a communication source. Do not stack Android platform AEC and software AEC3.

Reason:
The owner's real-device test showed that platform `AcousticEchoCanceler` alone did not adequately remove loudspeaker game audio from the microphone track. MedPlay already owns a clean playback PCM reference through AudioPlaybackCapture, making explicit reference-based cancellation the more controllable architecture.

Dependency decision:
Use `cn.enaium.webrtc.aec3:webrtc-aec3-kmp:1.0.3` rather than implementing an adaptive echo canceller from scratch. The wrapper is MIT-licensed, targets Android through JNI, and exposes the standalone WebRTC AEC3 primitives required by this pipeline. Its build metadata requires the project to compile with Kotlin 2.4.x / API 37; targetSdk remains 35 so this compile-time upgrade does not independently opt MedPlay into newer Android runtime behavior.

Acceptance remains physical: speaker bleed must be materially suppressed without unacceptable voice distortion, A/V drift, thermal load or gameplay degradation.
