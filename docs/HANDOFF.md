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

The active official session is **Ing. MedPlay ⏯️ #2**. Session numbering is persisted in docs/STATE.md; do not invent or reuse a number from model memory.

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

1. Treat main as the source of truth; the capture pipeline, retroactive A/V export, thermal guard, floating controls, notification controls, diagnostics and active-profile notification are merged.
2. The current development APK is already published as the `dev-latest` prerelease with `MedPlay-debug.apk` and `MedPlay-debug.sha256`.
3. Follow docs/DEVICE_TESTING.md on a real Android device, including notification permission, RECORD_AUDIO fallback, overlay control, all supported clip durations and a sustained 20–30 minute game session.
4. Copy the last-session diagnostics after testing and use those measurements to decide the next recorder change.
5. Do not raise default resolution/FPS/bitrate or add aggressive automatic profile switching without real-device evidence.

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

The current branch captures only playback audio that Android and the source app permit. RECORD_AUDIO denial does not block video recording. Audio is encoded to AAC-LC on a dedicated thread and hardware encoding is preferred when available. Encoded playback audio is now written into rolling AAC segments and included in the final MP4 when Android/source-app playback capture allows it. Video-only remains the fallback when audio is disabled, denied or unavailable.


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


## Sustainability verdict note

The persisted last-session diagnostics include sustainabilityVerdict=GOOD/DEGRADED/UNSUSTAINABLE. The evaluator uses encoder-health metrics, maximum thermal status, target FPS and session duration. Zero encoded samples are not allowed to appear healthy. Keep the verdict observational until thresholds are validated on real devices.


## Notification permission note

On Android 13+, MainActivity requests POST_NOTIFICATIONS before the audio/MediaProjection flow. Denial must not block capture; it only means the foreground notification and its Clip/Stop actions may be hidden by Android. RECORD_AUDIO remains independently optional.


## Encoder-health sample note

HardwareVideoEncoder no longer feeds BUFFER_FLAG_CODEC_CONFIG buffers into EncoderRuntimeHealth. Codec-config output still reaches the sample consumer; only health counters/gap/timestamp metrics exclude it.


## Duplicate start note

ACTION_START is ignored while MediaProjection is already active; the current session remains intact. Do not reintroduce live-session replacement on the Service main thread. Stop the active session first, then request a new projection session.


## Copy diagnostics note

MainActivity can copy the last persisted recording diagnostic summary to the clipboard. The copied text contains only compact session/encoder/thermal metadata, never recorded media payloads. This is the preferred way to paste a real-device session result back into ChatGPT.


## Active capture notification note

Once startup completes, the foreground notification reports selected resolution/FPS, actual storage-aware retention seconds and audio/video-only state. Use this together with last-session diagnostics during real-device validation.


## Active capture notification note

Once startup completes, the foreground notification reports selected resolution/FPS, actual storage-aware retention seconds and audio/video-only state. Use this together with last-session diagnostics during real-device validation.


## Session 2 APK generation status

The current development APK has been generated successfully and published under the public prerelease tag `dev-latest`. The APK release workflow has been restored to its original manual-only `workflow_dispatch` configuration after publication. Real-device validation is now the next required evidence step.


## Simplified settings / localization note

The UI now uses one Clip duration selector and one Save clip button; the floating and notification Clip actions read the same persisted duration. App language is selectable between Spanish and English. Recording quality offers Automatic plus only hardware-validated 720p30/720p60/1080p30 modes. Codec/bitrate remain automatically selected within the chosen profile. Do not add arbitrary bitrate or 1080p60 controls until real-device sustainability data justifies them.


## High-motion quality / inset handoff

The current quality branch responds to real shooter/gyroscope testing where 1080p30 HEVC at the prior conservative bitrate looked smeared/pixelated during rapid motion. The implementation raises per-profile bitrate budgets, prefers hardware VBR, exposes 1080p60/1440p modes only when the device validates them, and keeps Automatic focused on high-quality 1080p before using 1440p automatically.

MainActivity also handles system bars and display cutouts on every edge so the language/status/quality controls are not hidden by Android 15 edge-to-edge layout.

Do not add mandatory post-processing or sharpening to the normal save path: that would require decode/re-encode, increase heat/battery use and cannot restore detail already lost by the encoder. The next evidence step is a real-device shooter/gyro comparison using the updated APK and copied diagnostics.


## Game-only capture / premium overlay handoff

The active feature branch adds two user-requested improvements without changing the high-motion encoder tuning that was visually approved on-device.

1. **Solo juego (recomendado)** uses Android 14+ app-screen sharing via MediaProjection user-choice configuration. The system chooser must be used to select the game. Only the selected app is intended to be shared, excluding notifications and UI outside that app. Full-screen remains available as an alternative, and older Android versions fall back to full-screen.
2. The floating Clip control is now a custom glass-style rewind control with persisted tint, opacity and size settings. It provides immediate accepted feedback and later success/failure feedback from the actual export path. Duplicate save requests show busy feedback.

Do not add mandatory video post-processing or expensive real-time blur to the overlay. The recording-quality pipeline should remain the same as the already approved high-motion build.

Next evidence step after CI/merge: generate dev-latest and physically test single-app selection with a real game, trigger notifications/vendor sidebars over the game, save a clip, and confirm only game content is present while image/audio quality remains unchanged.


## Latest dev-latest for physical validation

The current `dev-latest` APK includes the game-only capture and premium glass floating Clip control from PR #58.

Release evidence:
- release run `38794076975`: success
- release target `1accba060c2806c9e761eebd5e0876761b34ae32`
- feature content originates from merge `77ba165ef7655c58dccf681fb3212c9b97d76ac4`
- current main after restoring the release workflow to manual-only: `09928e9977cc3ffc0d41045d189ed05c6f615bc2`
- app source is unchanged between the feature/release/restoration commits; only the GitHub Actions trigger changed.

Next step: install the latest APK and validate Solo juego with a real shooter, including a notification/vendor overlay test and a floating-button save.


## Polished recording UI handoff

The current UI cleanup responds directly to physical-device feedback:
- Android's own capture dialog is now the single place to choose **one app** versus **full screen**; MedPlay no longer duplicates that choice.
- The floating button is automatic. Missing overlay permission is requested as part of Start capture, then capture setup resumes on return.
- Tint/size/transparency have an exact live preview because MainActivity and the overlay now share GlassClipView.
- Stop and diagnostics remain available but are collapsed under Advanced options.
- Root scroll restoration is disabled and forced to top to prevent the upper settings from reopening partially hidden.

This pass intentionally does not change the visually approved high-motion recording pipeline.

Next physical validation: install the next dev-latest, confirm the screen opens at the top, customize the glass preview, start capture from a device with/without overlay permission, and verify Android presents only its own app-vs-full-screen choice.


## Latest dev-latest: polished recording UI

The current `dev-latest` APK contains PR #62:
- no duplicate MedPlay capture-scope selector;
- live floating-button glass preview;
- automatic floating-button permission/start flow;
- Stop and diagnostics under collapsed Advanced options;
- root ScrollView forced to the true top.

Release evidence:
- release run `38810905282`: success
- release target: `ddee563b8928f4fb20ae349ae761141f06552c4c`
- feature source merge: `a342659570e63180f550c275bf7a2c85c5bce85e`
- current main after restoring manual-only workflow: `e74ecb9a88f1e8978657f56eaaea665bc72593be`

Next step: install this APK and physically validate the UI and capture flow. Do not claim the UX issues fully resolved until that device evidence is received.


## Separate microphone voice handoff

The current feature branch/PR adds player voice as a second independent rolling AAC source.

Key behavior:
- player voice is opt-in and defaults OFF;
- internal game audio stays unchanged;
- Android microphone contention is observed through AudioRecord's recording callback;
- if the game takes microphone priority, MedPlay does not stop video or game audio and does not repeatedly reopen the mic; its active AudioRecord receives silence until Android unsilences it;
- floating UI reports pause/resume of the player-voice track;
- retroactive export writes game audio first and microphone voice second;
- session diagnostics record microphone active/silenced evidence.

Do not claim that ordinary gallery playback mixes both tracks. The later editor phase must preview/mix the two sources and export one final user-controlled soundtrack.

Next evidence step: build/install a validation APK and test one continuous session with game voice-chat OFF -> ON -> OFF, saving a clip that spans the transitions.


## Automatic thermal adaptation handoff

The thermal branch upgrades the prior bitrate-only protection:
- MODERATE remains bitrate-only at 85%.
- SEVERE uses 70% bitrate and automatically steps to the next lower validated hardware resolution/FPS profile.
- sustained SEVERE rechecks every 30 seconds and can continue stepping down until the device reaches its validated floor;
- CRITICAL still stops capture.

Thermal profile changes are implemented as video epochs. The old rolling-video epoch is closed only after the replacement hardware encoder is running and the VirtualDisplay has been resized/rebound. Old epoch files are then removed because final remux cannot safely concatenate different video formats. Audio/microphone buffers stay on their monotonic timeline and continue through the switch.

A save tap during the brief profile switch is intentionally refused rather than racing with writer replacement. Shutdown serializes against an active profile switch. Diagnostics aggregate video encoder health across epochs and record the initial/final profile plus downgrade count.

Do not claim physical thermal improvement yet. The next device test should compare the same game/session duration with and without MedPlay and inspect `maxThermalStatus`, `thermalProfileDowngrades`, encoder health and perceived gameplay smoothness.


## In-app clip library handoff

The clip-library branch adds `ClipLibrary`, a lightweight private JSON metadata index. Exported videos stay only in MediaStore/Gallery; no second video copy is created. After a successful retroactive export, CaptureService records the result URI and metadata. MainActivity lists those entries under **Mis clips** and opens the existing MP4 through ACTION_VIEW with temporary URI read permission.

The library intentionally stores enough metadata for the next editor phase: source URI, duration, game-audio presence and microphone-track presence. External deletion is detected by attempting to reopen the URI and stale entries are removed.

Next editor work should treat this MediaStore URI as an immutable master source and persist edit instructions separately rather than overwriting the original clip.


## Latest validation APK handoff

`dev-latest` was regenerated after the microphone, automatic thermal adaptation and in-app clip library work.

Evidence:
- workflow run `38822693354`: success
- release target `a5120b2f030674a94c6701a889bbe7ebd69221e2`
- published `2026-10-15T23:18:44Z`
- `MedPlay-debug.apk` size 2,683,542 bytes
- release workflow restored to manual-only afterward; main restoration merge `cddbf1e7b23ebf2a540e20e784bc135faf6faab8`.

Recommended physical test sequence: play a sustained session with voice capture enabled, toggle the game's voice-chat microphone OFF -> ON -> OFF, save clips, verify `Mis clips`, and inspect diagnostics for thermal status/profile downgrade evidence.


## Portrait capture / internal player handoff

PR #72 fixes the reported portrait-letterboxing and external-player dependency.

Capture orientation is now driven by MediaProjection captured-content resize callbacks. The active profile is swapped to portrait dimensions only when the same hardware encoder validates that width/height/FPS/bitrate combination. The transition uses the existing encoder-epoch strategy so incompatible MP4 dimensions are never concatenated into one rolling segment set. Thermal step-down remains compatible with portrait profiles.

`Mis clips` now launches a private `ClipPlayerActivity` using VideoView + MediaController against the original MediaStore URI.

No claim of physical completion yet. Device test should cover at least one portrait title and one landscape title to ensure portrait fixing does not regress the previously good landscape capture. Also verify opening clips from the in-app library no longer shows the 'no application available' message.


## Full-session foundation handoff

PR #73 begins the second recording mode using the existing encoded-segment architecture rather than a second recorder implementation.

`RecordingModePreference` selects retroactive versus full-session mode. Full-session mode calculates a safe maximum duration with `FullSessionStoragePolicy`, reserving 768 MiB and capping automatic sessions at two hours. The same hardware video encoder / internal AAC / optional microphone sources feed bounded segment writers; the full-session bound is the safe session duration rather than the 5-minute rolling clip cap. Stop requests a final keyframe rollover, leases the retained full range, remuxes it through `RetroactiveVideoExporter`, indexes the MediaStore result in `ClipLibrary`, then shuts capture down.

Important compatibility guard: full-session mode does not perform thermal resolution/FPS step-down after recording is underway because the current exporter cannot concatenate incompatible video dimensions/codecs into one final MP4. Bitrate thermal reduction still applies, and CRITICAL requests a final save/stop. Initial orientation correction is limited to the first five seconds.

The next Phase 2 slice is the owner-requested lateral floating control: snap left/right, collapsed/expanded states, elapsed time, microphone action, pause/resume and Stop. Pause must preserve a gapless output timeline; do not implement it by merely dropping arbitrary encoded samples.


## Full-session overlay / pause handoff

PR #74 implements the first complete full-session overlay interaction: edge-snapping collapsed bubble, expandable panel, active-time timer, microphone toggle, pause/resume and Stop & save.

Pause is represented as monotonic `RecordingPauseInterval` ranges. Video writing is cut at pause, VirtualDisplay is detached, AAC writers ignore samples, and resume reattaches the same encoder surface with a requested sync frame. `RetroactiveVideoExporter` now accepts optional pause intervals and remaps each track timestamp by subtracting elapsed paused duration; samples inside a pause are skipped defensively. Retroactive Clip exports pass an empty pause list and remain unchanged.

Next Phase 2 work after physical validation: polish the session overlay visuals/interaction if needed, then move to Phase 3 editor/player capabilities. Do not add highlight analysis until full-session output and pause behavior are stable on-device.


## Latest full-session validation APK

`dev-latest` was regenerated from main after the portrait/internal-player and full-session work.

Evidence:
- run `38904004611`: success;
- target `7754f43eb0c0875af1a4dee18615dae7a3711219`;
- APK asset: `MedPlay-debug.apk`;
- checksum asset: `MedPlay-debug.sha256`;
- published `2026-10-16T12:56:49Z`.

Recommended device test order: portrait game framing -> open from Mis clips -> full-session start -> drag/snap overlay -> pause/resume -> mic toggle -> Stop & save -> inspect complete MP4 and A/V continuity. Do not move the implementation status beyond IMPLEMENTADO_PENDIENTE_VALIDACIÓN until the owner reports real-device evidence.
