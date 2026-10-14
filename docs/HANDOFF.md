# HANDOFF — MedPlay ⏯️

## Read this first in any new chat

Required reading:
1. RULES.md
2. AGENTS.md
3. docs/STATE.md
4. docs/HANDOFF.md
5. docs/SECURITY.md
6. docs/ARCHITECTURE.md
7. docs/DECISIONS.md
8. Current GitHub Actions state in HNAlvaradoHN/MedPlay and private HNAlvaradoHN/Medplay-Lab

The active official session is **Ing. MedPlay ⏯️ #1**. Do not invent a new session number unless project state is intentionally advanced.

## Owner's actual priority

The user wants MedPlay to produce visibly better recordings than ordinary mobile screen recorders, especially for fast-motion games, but does **not** want the app to heat phones excessively, cause gameplay stutter, drop frames, or force devices beyond sustainable limits.

Interpret "best quality" as **best sustainable quality per device**, not "always force the highest number".

Core engineering priorities:
1. hardware encode;
2. stable game performance;
3. thermal sustainability;
4. minimal/no re-encode when saving clips;
5. high motion quality;
6. adaptive capability-based profiles;
7. privacy/local processing by default.

## Product goal

MedPlay is an original Android app inspired functionally by Medal's retroactive clipping concept.

It should continuously retain a bounded recent recording window and save the previous N seconds/minutes when the user taps a floating control.

Target duration presets:
- 15 s
- 30 s
- 60 s
- 2 min
- 3 min
- 5 min
- custom where safe

Target quality options should eventually include 720p, 1080p, 1440p/native where supported, 30/60 FPS, H.264 and HEVC, with AV1 only where suitable hardware exists.

## Repository split

Public repository:
- HNAlvaradoHN/MedPlay
- source of truth for original MedPlay code, sanitized architecture, decisions, normal CI and public-safe tooling tests

Private lab:
- HNAlvaradoHN/Medplay-Lab
- used for Medal APK/APKS reference analysis and detailed temporary findings

Never move the Medal APK, proprietary decompiled source, private recordings, credentials or personal data into the public repository.

## Medal input

Reference package currently used:
- Medal 7.1.1
- uploaded as Medal_7.1.1.zip to a private GitHub Release with tag input in Medplay-Lab
- size previously verified as 42,490,040 bytes
- package is not committed to Git

After analysis is complete, the private Release asset should be deleted.

## Analysis workflow status

GitHub Actions is being used so the owner does not need a PC.

Public smoke test proved:
- Node/REA package can install;
- Java/JADX can install;
- GitHub Actions can perform the analysis workflow.

Private workflow:
.github/workflows/analyze-android-private.yml

It:
- downloads the private Release asset;
- extracts base.apk;
- invokes REA where possible;
- runs JADX CLI;
- searches targeted recorder internals;
- writes minimized findings to private workflow logs;
- removes temporary APK/decompilation files from runner storage.

Do not rely on Actions artifacts right now because the account hit artifact storage quota. Findings are intentionally printed to private logs instead.

## REA status

REA package inspection is not currently reliable:
- inspect-android-package returns code execution_failure;
- one attempted rea doctor invocation used an unsupported argument and returned VALIDATION_ERROR.

Do not spend repeated private Actions runs blindly trying REA. JADX is already yielding useful results. Validate REA syntax publicly before consuming private runs if revisiting it.

## Confirmed Medal findings

Confirmed from JADX/private logs:
- tv.medal.recorder.ui.activity.RecordActivity uses MediaProjectionManager.
- The capture flow includes concepts/methods such as startScreenCapture, acquireProjectionAndRecord and startRecord.
- Medal has a segmented recording subsystem.
- tv.medal.recorder.core.engine.segments.muxer contains SegmentsMediaMuxer-related classes.
- This strongly suggests Medal rotates/retains encoded segments and later uses those files for clip creation.
- Medal has tv.medal.model.VideoQualityInfo with fields bitrate, width and height.
- Bitrate enum values of 5/10/20/50/100 Mbps were observed in editor code, but must not yet be claimed as live-recorder settings.
- JADX sometimes returns exit code 3 because some classes/methods cannot be fully decompiled, but it still produces useful source and targeted findings.

## Architecture direction for MedPlay

Preferred design to prototype:

MediaProjection
-> VirtualDisplay
-> MediaCodec hardware video encoder
-> encoded rolling segments
-> bounded recent-window retention
-> save trigger
-> choose segments/keyframe-safe start
-> remux/assemble without re-encoding
-> MediaStore/Gallery

Why:
- avoids CPU-heavy software encoding;
- avoids full-video re-encode on clip save;
- bounds storage use;
- should reduce heat and save latency;
- preserves original encoded quality;
- makes retroactive clipping practical.

A naive byte ring buffer is not sufficient because MP4/container metadata, timestamps, keyframes and A/V sync must remain valid.

## What is still being investigated

The next analysis must determine:
- exact live-recorder bitrate choices;
- exact FPS handling;
- resolution selection;
- H.264 vs HEVC/other codec behavior;
- encoder capability checks;
- keyframe interval;
- segment length;
- retention/cleanup behavior;
- clip assembly path;
- thermal/battery/performance adaptation;
- dropped-frame handling.

Private workflow run #7 was launched specifically to inspect these recorder internals. Check its status/logs before creating another run.

## Prototype rule

Do not start by forcing 1440p/60/100 Mbps universally.

The first MedPlay prototype should detect hardware codec capabilities and use conservative, sustainable defaults. Higher profiles can be exposed only when the device can actually sustain them.

The intended long-term quality strategy is adaptive:
- prefer efficient hardware codec;
- choose resolution/FPS/bitrate as a balanced profile;
- monitor sustained health;
- degrade gracefully before thermal throttling or gameplay stutter;
- never re-encode a saved clip unless absolutely necessary.

## Immediate next action for a new chat

1. Inspect Medplay-Lab run #7.
2. Extract recorder-specific findings only.
3. Update public sanitized docs if conclusions are verified.
4. Define original MedPlay quality-profile algorithm.
5. Then scaffold the Android/Kotlin prototype.
