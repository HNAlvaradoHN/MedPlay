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
- RecorderVideoUiState defaults observed in Medal: CLIPPING mode, 15-second clip length, RES_720, FPS_30.
- Medal exposes explicit resolution and FPS settings and checks battery-optimization state; this does not prove active thermal throttling logic in its encoder.

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

Private workflow run #7 completed successfully and was reviewed. Do not launch another private run unless a specific unanswered Medal question justifies it. The private workflow is manual-only.

## Prototype rule

Performance/thermal quality is a release criterion: a visually better setting that causes meaningful gameplay stutter, sustained overheating, excessive battery load, encoder instability or repeated dropped frames is not considered better.

Do not start by forcing 1440p/60/100 Mbps universally.

The first MedPlay prototype should detect hardware codec capabilities and use conservative, sustainable defaults. Higher profiles can be exposed only when the device can actually sustain them.

The intended long-term quality strategy is adaptive:
- prefer efficient hardware codec;
- choose resolution/FPS/bitrate as a balanced profile;
- monitor sustained health;
- degrade gracefully before thermal throttling or gameplay stutter;
- never re-encode a saved clip unless absolutely necessary.

## Immediate next action for a new chat

1. The Android/Kotlin scaffold has passed public CI and is merged to main.
2. Sustainable startup profile selector passed public CI and is merged.
3. MediaProjection consent + foreground-service lifecycle passed CI and is merged.
4. Video/audio capture, synchronized retroactive export and thermal protection are merged. Current branch adds a draggable floating clip control plus storage-aware retention up to 5 minutes.
5. Measure sustained performance on real devices before raising default quality.


## Implemented startup safety policy

The current selector refuses automatic software encoding, validates each target with the device's MediaCodec VideoCapabilities, and starts below the most aggressive 60 FPS/high-resolution combinations. This is deliberate: codec capability metadata proves support, not long-term thermal sustainability. Runtime health monitoring will later promote or reduce load based on actual behavior.


## MediaProjection implementation note

The projection token/lifecycle belongs to a dedicated foreground CaptureService. The Activity only requests explicit Android user consent and hands the result to the service. This separation is intentional so rotation/UI lifecycle does not own the recorder and so later encoder/segment work can remain inside the long-running service.


## Hardware encoder pipeline note

The capture path now targets zero CPU frame conversion: MediaProjection creates a VirtualDisplay whose Surface is owned by the selected hardware MediaCodec encoder. Output is drained continuously. Do not insert ImageReader/Bitmap/YUV conversion into the normal recording path unless a future feature has a compelling measured reason, because that would add memory bandwidth, CPU/GPU work and heat.


## Rolling segment implementation note

Video segments are cut only on sync frames and are written from already-compressed MediaCodec output into MediaMuxer. Default prototype target is 2-second segments retained for 60 seconds in app cache. The retention window is bounded and deletes old files. This is the foundation for later 15s/30s/60s/etc. retroactive clip selection without re-encoding.


## Retroactive export implementation note

The exporter never decodes or re-encodes video. It leases completed rolling segments, concatenates compressed video samples with MediaExtractor/MediaMuxer, and publishes the final MP4 to Movies/MedPlay through MediaStore. The active segment is intentionally excluded because it is still open for writing; current saves can therefore trail the button press by up to approximately one 2-second segment.


## Runtime thermal guard note

MedPlay now reacts to Android thermal status while recording. Moderate/severe heat lowers encoder bitrate dynamically; critical-or-worse thermal state stops capture. This is deliberately conservative. Resolution/FPS restarts are deferred until real-device measurements show they are needed and can be performed without destabilizing the recorder.


## Internal audio implementation note

The current branch captures only playback audio that Android and the source app permit. RECORD_AUDIO denial does not block video recording. Audio is encoded to AAC-LC on a dedicated thread and hardware encoding is preferred when available. Encoded audio is intentionally not yet written into the final clip; do not claim saved clips have audio until the A/V segment synchronization/export block is complete.


## A/V synchronization implementation note

Video and internal AAC now use monotonic start anchors so segment metadata shares one time base. Final export leases the overlapping audio range and writes both compressed tracks into one MP4. Audio remains optional: denied/blocked playback capture falls back cleanly to video-only. Long-session drift still requires real-device measurement before this is considered production-validated.


## Floating control / retention note

The overlay is optional and only appears when Android overlay permission is granted and capture is active. A tap saves the default 30-second clip; the control is draggable. The rolling window can extend to 2/3/5 minutes, but retention is calculated from current bitrate and free storage, capped to a 768 MiB rolling budget with a 768 MiB reserve. If a safe one-minute window cannot fit, recording startup is refused.


## Encoder hardening note

Startup selection now requires hardware-backed, size/rate/bitrate-valid encoders that also advertise COLOR_FormatSurface. The video encoder keeps lightweight health counters for output gaps and timestamp regressions. Do not turn those observations into aggressive automatic resolution/FPS switching until thresholds are validated on real phones.


## Encoder EOS / failure handling note

Video shutdown no longer flips the drain loop off before MediaCodec EOS can be consumed. Stop now signals EOS, drains with a bounded timeout, then releases codec resources. Fatal drain or sample-consumer errors propagate back to CaptureService and trigger recorder teardown instead of silently killing the drain thread.


## Export consistency guard note

Retroactive export now rejects missing/empty segments, overlapping timelines, non-keyframe video starts, format changes across segments and non-monotonic per-track timestamps. MediaMuxer finalization and MediaStore publication must succeed; otherwise the pending clip is deleted. No validation step decodes or re-encodes media.


## Serialized export note

MedPlay now allows only one retroactive remux/export at a time. Extra taps during an active export are ignored to avoid duplicated MediaExtractor/MediaMuxer I/O, temporary lease growth and avoidable heat. SAVE/overlay actions received while capture is inactive no longer leave a started service hanging around.


## Rolling segment finalization note

Completed rolling segments are now considered valid only after MediaMuxer.stop() succeeds. Failed finalization deletes the file instead of exposing it to leases/export. SegmentLease.close() is thread-safe/idempotent, and teardown catches close-time muxer failures so service shutdown remains bounded.


## Audio failure fallback note

A fatal internal-audio worker or AAC consumer failure now disables only audio and leaves the video recorder running. Saved clips then fall back to video-only. This is intentional because playback capture support varies by app/device and should not take down the core recorder.


## Audio fallback recursion regression

PR #19 compiled but introduced a recursive disableInternalAudio() implementation. The follow-up fix replaces recursion with explicit audio teardown while preserving video capture. Do not reintroduce self-calling fallback helpers.


## MediaProjection lifecycle restart note

Capture replacement now releases the entire old projection-owned pipeline before creating a new MediaProjection. Explicit shutdown also unregisters the callback and calls MediaProjection.stop(). A restart is ignored while a retroactive export is active so newly initialized rolling directories cannot invalidate files still leased by that export.


## Video PTS normalization note

Do not add encoderMonotonicStartUs directly to raw Surface/MediaCodec presentationTimeUs. Raw video PTS may not be zero-based. MedPlay now subtracts the first observed video PTS, then adds the monotonic session anchor. Tests cover both relative and large absolute-looking input PTS domains.


## AAC EOS shutdown note

InternalAudioCapture now unblocks AudioRecord before teardown, gives its worker time to queue/drain EOS, suppresses false fatal callbacks during intentional shutdown, force-stops MediaCodec only after a bounded timeout, releases output buffers in finally, and surfaces read errors instead of silently feeding empty AAC input.


## Save-triggered rollover note

A clip save requests a sync frame and asks RollingVideoSegmentWriter to cut on that next keyframe. The export worker waits up to 750 ms for completion, then falls back to the latest completed set if needed. This reduces end-of-clip lag without reading an active segment or re-encoding.


## Video stop race note

HardwareVideoEncoder.stop() now shares the same synchronization boundary as updateBitrate() and requestSyncFrame(). Keep stopRequested true until teardown completes so drain errors caused by intentional shutdown are suppressed; start() resets the flag.


## Save-aligned AAC rollover note

Before audio leasing, RollingAudioSegmentWriter finalizes its active AAC segment under synchronization. The next AAC sample opens a fresh segment automatically, keeping audio close to the video save boundary without restarting the audio codec.


## Remux buffer sizing note

RetroactiveVideoExporter checks MediaExtractor.sampleSize and grows its reusable direct buffer only when a compressed access unit exceeds the 8 MiB baseline, capped defensively at 64 MiB per sample.


## Session diagnostics note

MedPlay keeps only the latest compact recording-session summary in internal app storage. It records profile, duration, retention, whether audio was active, maximum Android thermal status, encoded sample count, timestamp regressions and output-gap metrics. It never stores frame/audio payloads in diagnostics. The summary can be viewed from MainActivity without ADB.


## Encoder startup cleanup note

Video and AAC startup are transactional. Resources created before full publication are cleaned immediately on failure; do not rely on stop() to find partially initialized MediaCodec/AudioRecord instances that were never assigned to fields.


## Capture startup failure note

MediaProjection acquisition/registration, profile probing, encoder startup, VirtualDisplay creation and thermal-guard startup are failure-contained. A startup exception must end in bounded cleanup and service stop; secondary cleanup failures must not block projection teardown.


## Clip-save feedback note

Clip-save progress/outcome is surfaced through the existing foreground-service notification only on save events. This adds no per-frame logging or media processing. A late export completion must not recreate a notification after MediaProjection has already shut down, and export failure must not stop the active recorder.


## Floating clip duration note

The floating Clip button reads a persisted default duration (15/30/60/120/180/300 s) at tap time. Invalid values fall back to 30 s. This changes only the requested save window; storage-aware rolling retention determines how much history is actually available.


## Internal audio toggle note

Internal playback audio can be disabled persistently. When off, MedPlay does not request RECORD_AUDIO and does not start AudioPlaybackCapture/AudioRecord/AAC for the next capture session. The setting is intentionally session-start scoped for now; video-only fallback still applies automatically when audio fails.


## Audio-aware retention note

Retention estimation includes AAC bitrate only when internal audio is enabled and RECORD_AUDIO is granted. Video-only sessions budget audio bitrate as zero; storage safety reserves and caps are unchanged.


## Notification controls / shutdown note

The foreground notification exposes Clip and Stop. Clip reads the persisted default duration when no explicit duration is supplied. Stop, thermal stops, projection callbacks and fatal video errors enter a one-shot asynchronous shutdown; heavy EOS/resource waits occur off the main thread, and stopSelf() waits for both recorder-resource release and any active export.


## Notification controls / shutdown note

The foreground notification exposes Clip and Stop. Clip reads the persisted default duration when no explicit duration is supplied. Stop, thermal stops, projection callbacks and fatal video errors enter a one-shot asynchronous shutdown; heavy EOS/resource waits occur off the main thread, and stopSelf() waits for both recorder-resource release and any active export.
