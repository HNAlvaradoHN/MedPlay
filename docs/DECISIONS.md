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
