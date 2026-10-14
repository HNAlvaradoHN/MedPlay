# HANDOFF — MedPlay ⏯️

## What exists

MedPlay governance and official memory now exist. No Android application code exists yet.

## Required reading for a new chat

1. RULES.md
2. AGENTS.md
3. docs/STATE.md
4. docs/HANDOFF.md
5. docs/SECURITY.md
6. Current GitHub branches/PRs/CI and relevant task files

## Current owner intent

Build MedPlay as an Android application that can preserve a rolling window of screen recording and save what happened before the user taps a floating button.

Quality is a primary requirement. The app should expose several recording resolutions, frame rates, codecs, bitrate/quality profiles and clip durations.

## Reference analysis

A Medal 7.1.1 Android package was supplied in the conversation as a research/reference target.

The intended analysis path is:
GitHub Actions -> REA/JADX -> structured logs/artifacts -> ChatGPT review.

Reference analysis is for understanding architecture, APIs and behavior. Do not copy proprietary source code or protected assets into MedPlay.

## First prototype focus

1. Validate REA/JADX automation in GitHub Actions.
2. Create native Android/Kotlin project.
3. Capture screen with MediaProjection.
4. Encode with hardware MediaCodec.
5. Maintain a bounded rolling buffer.
6. Save a valid playable clip for the previous configured interval.
7. Add basic quality and duration settings.
8. Add a floating save control.

## Still not proven

- Best rolling-buffer storage model.
- Seamless MP4 generation from arbitrary prior time boundaries.
- Device-specific maximum stable bitrate/FPS/resolution.
- Thermal/battery behavior during long gaming sessions.
- Internal audio capture compatibility.
