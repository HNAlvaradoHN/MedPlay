# MedPlay product roadmap

status: ACTIVE
protocol_version: 1

This roadmap records the owner-approved product direction so future sessions do not depend on chat history.

## Phase 1 — Stabilize the retroactive recorder

Goal: preserve the already-approved high-motion quality while making the recorder reliable across games and orientations.

Scope:
- retroactive clip mode with internal game audio;
- hardware Surface -> MediaCodec capture;
- bounded rolling retention and remux-only clip save;
- app-only capture where Android supports it;
- floating Clip control;
- automatic thermal protection;
- in-app clip library;
- portrait/landscape captured-content adaptation;
- in-app playback.

Current status:
- IMPLEMENTADO_PENDIENTE_VALIDACIÓN for portrait capture and internal playback;
- the rest of the retroactive recording path has existing CI and prior physical quality evidence.

## Phase 2 — Full-session recording

Goal: add a second recording mode without replacing or weakening retroactive clips.

User-facing modes:
1. **Clip retroactivo** — lightweight rolling history; save recent seconds/minutes.
2. **Grabación completa** — record the entire play session until stopped.

Full-session requirements:
- internal game audio;
- optional player microphone;
- automatic save when Stop is pressed;
- storage-aware maximum safe session duration;
- no silent overwrite of the beginning of a long session;
- timer;
- pause/resume;
- explicit Stop;
- thermal safety;
- portrait/landscape support.

Floating control direction:
- small glass control that can be dragged;
- snaps to the nearest left/right screen edge;
- collapsed state stays discreet;
- tap expands inward;
- expanded controls include elapsed time, microphone, pause/resume and Stop;
- retroactive mode keeps its quick Save Clip behavior.

Implementation order:
1. recording-mode preference and safe full-session backend;
2. lateral expandable overlay;
3. timer;
4. pause/resume with gapless output timestamps;
5. microphone UX for full-session mode;
6. physical validation.

## Phase 3 — Internal player/editor

Goal: keep the original recording immutable and edit non-destructively.

Planned capabilities:
- play inside MedPlay;
- trim;
- rotate;
- basic color/saturation controls;
- realtime preview;
- independent game-audio / microphone gain when separate tracks exist;
- final compatible MP4 export.

Export strategy:
- trim/audio-only changes should preserve the video bitstream whenever possible;
- final audio mixing may decode/mix/re-encode audio only;
- color changes require video decode/re-encode and must keep the original untouched.

## Phase 4 — Automatic post-match highlights

Goal: detect noteworthy shooter moments after recording, without adding heavy realtime heat while the user is playing.

Trigger:
- after a full-session recording, offer **Crear highlights automáticamente**;
- future preference may support Ask / Automatic / Off.

No cloud dependency:
- no paid API;
- no mandatory server;
- processing should run locally on-device.

Primary shooter signal:
- locate the game's kill counter automatically;
- track the counter through the recording;
- an increment such as 3 -> 4 is treated as a strong kill event.

Supported-game strategy:
- game-specific visual profiles for titles such as Free Fire, PUBG Mobile, PUBG New State, COD Mobile, Blood Strike and Farlight Mobile;
- do not assume a fixed screen coordinate because players can customize HUD placement;
- search using HUD/icon/number appearance, then track the located region;
- store only MedPlay-created descriptors/templates, never proprietary source/assets.

Analysis strategy:
1. identify the recorded game;
2. sample frames sparsely to locate the kill-counter region;
3. once located, analyze only that small region;
4. recognize the number;
5. confirm real increments;
6. record kill timestamps;
7. fall back to other signals such as kill feed/text only where needed.

Local ML escalation:
- begin with lightweight computer vision/template matching/number recognition;
- only add a small on-device ML model (for example TensorFlow Lite / MediaPipe-class tooling) if classical methods are not robust enough;
- never require an external inference API for the base feature.

## Phase 5 — Intelligent highlight grouping and summary

Individual kill:
- initial target window: approximately 15 seconds before the kill and 5–10 seconds after.

Nearby kills:
- kills close together should form one continuous play, not separate clips;
- for example kills at 02:10, 02:16 and 02:24 should become roughly one 01:55–02:34 play;
- continue extending while new kills occur within an approximately 10–15 second grouping window;
- close the play after the quiet window passes.

Possible later ranking signals:
- double/triple/multi-kill;
- streak;
- clutch-like sequence;
- win/end-of-match result where reliably detectable.

Automatic summary:
- merge selected plays;
- remove dead time;
- use simple transitions;
- preview before export;
- allow user to include/exclude detected plays.

## Product constraints

- Protect the validated sharpness and game-audio quality.
- Avoid heavy per-frame AI during gameplay.
- Prefer post-match analysis for expensive work.
- No paid services without explicit approval.
- No API dependency for highlights.
- Keep originals immutable.
- Never claim physical validation from CI alone.


## Progress update — 2026-10-16

- Phase 1 portrait capture: physically validated on the owner's tested vertical game.
- Phase 1 internal player: physically validated on-device.
- Phase 2 full-session backend/overlay: implemented, still pending physical validation.
- Phase 3 editor foundation: implementation started with immutable-source edit projects, trim markers and per-track gain settings.


## Progress update — editor visual pass

Phase 3 now includes live-preview color filters, saturation/intensity, brightness, contrast and 90-degree rotation controls, plus processed MP4 export. The next required editor capability is final game-audio + microphone mixing, after which filtered exports can be enabled for two-track recordings without losing voice.


## Progress update — final audio mix

Phase 3 now has an implementation for final export with game/microphone gain and single-track AAC mixing. After device validation, the remaining editor work is primarily UX polish and optional higher-level tools (additional presets, transitions/text only if product value justifies them) before moving into post-match highlight detection.
