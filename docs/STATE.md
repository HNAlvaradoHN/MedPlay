# PROJECT STATE — MedPlay ⏯️

status: ACTIVE_RESEARCH
protocol_version: 1
repository: HNAlvaradoHN/MedPlay
default_branch: main
visibility: public

## Session registry

last_session_number: 1
active_session: 1

This is MedPlay's first official session. Previous SendGram numbering/content in this repository is not part of MedPlay's session history.

## Verified project state

- Product code: initial Android/Kotlin scaffold and encoder capability probe merged to main; public Android CI passed.
- Stable version: none.
- Development version: none.
- Release/APK: none.
- Public CI: REA/JADX smoke workflow exists and passed.
- Private lab: HNAlvaradoHN/Medplay-Lab.
- Private Medal analysis workflow: implemented and running successfully with GitHub Actions + JADX.
- External paid services: none approved.
- Secrets allowed in repository: none.
- Reference target: Medal 7.1.1 Android package, stored temporarily as a private Release asset in Medplay-Lab, not committed to Git history.

## Current objective

Finish reference analysis of Medal's recorder internals, then scaffold MedPlay as an original native Android/Kotlin app for high-quality retroactive screen recording.

The owner's highest priority is not simply "maximum settings". The product must target the best sustainable visual quality while minimizing heat, battery cost, dropped frames, gameplay stutter and device stress.

## Product direction established by owner

MedPlay should:
- continuously retain only a recent rolling window;
- save the previous configurable interval when the user taps a floating action;
- prioritize excellent fast-motion game quality;
- use hardware encoding wherever practical;
- avoid re-encoding when saving retroactive clips;
- expose multiple resolution/FPS/quality profiles, but never pretend unsupported combinations are safe;
- support internal audio and optional microphone where Android permits;
- adapt to device capability and sustained load rather than blindly forcing maximum resolution/bitrate;
- prefer stable gameplay and thermal behavior over benchmark-style peak settings.

## Reference-analysis results confirmed so far

Private Medal analysis has confirmed:
- screen capture starts through MediaProjectionManager / MediaProjection;
- Medal has a dedicated RecordActivity flow around capture permission and recording startup;
- Medal uses a segmented recorder architecture;
- a component named SegmentsMediaMuxer exists under tv.medal.recorder.core.engine.segments.muxer;
- this strongly supports a rolling short-segment/remux architecture for MedPlay rather than an unlimited monolithic recording or naive raw byte ring buffer;
- Medal contains a VideoQualityInfo model with bitrate, width and height;
- editor-side bitrate presets include 5, 10, 20, 50 and 100 Mbps; these values are not yet proven to be live-recorder presets;
- Medal's recorder video UI state defaults to clipping mode, 15-second clips, 720p and 30 FPS;
- Medal keeps resolution and FPS as explicit recorder settings and separately checks whether battery optimization needs attention;
- JADX can decompile enough of Medal to expose relevant recorder architecture, even though some methods remain undecompiled;
- REA inspect-android-package currently returns execution_failure in this environment, so JADX is the reliable analysis path for now.

## GitHub Actions / analysis history

- Public REA/JADX smoke test: success.
- Private run #1: failed at REA before JADX.
- Private run #2: same issue due workflow replacement not taking effect.
- Private run #3: JADX completed useful discovery, but artifact upload failed because Actions artifact storage quota was exhausted.
- Private run #4: success after switching findings to private workflow logs instead of artifacts.
- Private run #5: invalid YAML introduced while inserting a targeted analysis step; no analysis job ran.
- Private run #6: success; targeted quality/performance scan completed.
- Private run #7: success; deep-dive recorder-internals scan reviewed without launching another private run.

## Important technical direction

Current preferred architecture remains:

MediaProjection -> VirtualDisplay -> hardware MediaCodec encoder -> short rolling encoded segments -> keep only recent window -> on save, select valid prior segments/keyframe boundary -> remux/assemble without re-encoding -> MediaStore/Gallery

This direction is favored because it can preserve quality while reducing CPU work and heat at save time.

## Blockers / pending validation

- Extract the actual live-recorder bitrate/FPS/resolution mapping from Medal.
- Identify which codecs Medal uses for live recording and whether it selects hardware encoders explicitly.
- Determine segment duration and retention behavior.
- Identify any per-device capability filtering.
- Identify any thermal/battery/performance adaptation logic.
- Determine keyframe interval and clip assembly behavior.
- Validate internal-audio strategy.
- Validate the sustainable profile selector in public CI.
- Measure sustained thermals, dropped frames and gameplay impact on real devices.

## Next step

Validate the new sustainable profile selector in public CI, then add MediaProjection capture.


## Sustainable profile selector implementation

Initial automatic startup policy now exists in code:
- automatic startup requires a hardware video encoder;
- candidate resolution/FPS/bitrate combinations are validated with Android VideoCapabilities before selection;
- HEVC is preferred where the device reports a valid hardware path;
- automatic startup intentionally does not jump directly to 1080p60/1440p; higher load modes are reserved for later runtime health promotion;
- if no validated hardware startup profile exists, automatic recording does not silently fall back to software encoding.

This is only the startup guardrail. Runtime thermal/frame-loss adaptation is still pending and must be implemented before claiming full sustainable-performance protection.
