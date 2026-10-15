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

## Functional smoke test

On a real Android device:

- grant MediaProjection consent;
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
