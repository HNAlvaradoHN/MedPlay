# PROJECT STATE — MedPlay ⏯️

status: READY
protocol_version: 1
repository: HNAlvaradoHN/MedPlay
default_branch: main
visibility: public

## Session registry

last_session_number: 1
active_session: 1

This is MedPlay's first official session. Previous SendGram numbering/content in this repository is not part of MedPlay's session history.

## Verified project state

- Product code: none.
- Stable version: none.
- Development version: none.
- Release/APK: none.
- CI: not configured yet.
- Architecture: initial MedPlay direction documented for prototype.
- External paid services: none approved.
- Secrets allowed in repository: none.
- Reference analysis target available in conversation: Medal 7.1.1 Android package.
- REA/JADX cloud workflow: proposed, not yet implemented.

## Current objective

Bootstrap MedPlay as an Android project for high-quality retroactive screen recording and validate a cloud-based analysis workflow using GitHub Actions + REA/JADX without requiring the owner's PC.

## Product direction established by owner

MedPlay should:
- record the screen continuously into a rolling buffer;
- save the previous configurable interval when the user taps a floating action;
- prioritize very high recording quality for games;
- support multiple quality/resolution/time profiles;
- support internal audio and optional microphone where Android permits;
- avoid visible quality loss/pixelation as much as hardware limits allow.

## Blockers / pending validation

- Confirm practical MediaProjection + MediaCodec rolling-buffer strategy on Android.
- Determine safe keyframe/container strategy for retroactive MP4 clip extraction.
- Detect per-device encoder limits for resolution/FPS/bitrate/codecs.
- Validate internal-audio behavior across Android versions/devices.
- Build and test REA/JADX GitHub Actions workflow.
- No Android project exists yet.

## Next step

Create the first REA/JADX GitHub Actions experiment and then scaffold the smallest Android/Kotlin MedPlay prototype around MediaProjection + hardware encoding.
