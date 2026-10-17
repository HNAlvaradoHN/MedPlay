# PROJECT STATE — MedPlay ⏯️

status: ACTIVE_RESEARCH
protocol_version: 1
repository: HNAlvaradoHN/MedPlay
default_branch: main
visibility: public

## Session registry

last_session_number: 4
active_session: 4

MedPlay session numbering is maintained persistently in this file. Previous SendGram numbering/content in this repository is not part of MedPlay's session history.

## Verified project state

- Product code: initial Android/Kotlin scaffold and encoder capability probe merged to main; public Android CI passed.
- Stable version: none.
- Development version: none.
- Development APK: `dev-latest` prerelease generated successfully for real-device validation; includes `MedPlay-debug.apk` and `MedPlay-debug.sha256`.
- Public CI: REA/JADX smoke workflow exists and passed.
- Private lab: HNAlvaradoHN/Medplay-Lab.
- Private Medal analysis workflow: implemented and running successfully with GitHub Actions + JADX.
- External paid services: none approved.
- Secrets allowed in repository: none.
- Reference target: Medal 7.1.1 Android package, stored temporarily as a private Release asset in Medplay-Lab, not committed to Git history.

## Current objective

Validate the merged MedPlay recorder on real Android devices, using the built-in session diagnostics to measure sustained capture quality, thermals, encoder health, gameplay impact, retention behavior and A/V synchronization before increasing default quality.

Reference analysis of Medal remains supporting research only and must not block real-device validation of MedPlay's original implementation.

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
- Sustainable profile selector passed public CI and is merged to main.
- Measure sustained thermals, encoder health, A/V sync, dropped frames and gameplay impact on real devices.
- Public CI for the active-capture notification passed and the feature is merged to main.
- No open implementation PR remains; real-device validation is the current blocker before raising default quality.

## Next step

Install the current `dev-latest` development APK on a real Android device and perform validation using docs/DEVICE_TESTING.md. Validate floating/notification controls, 15/30/60/120/180/300-second saves where retention permits, internal-audio fallback, long-session A/V sync, gameplay smoothness, thermal behavior and the persisted sustainability diagnostics. Treat real-device findings as the evidence for the next recorder changes.


## Sustainable profile selector implementation

Initial automatic startup policy now exists in code:
- automatic startup requires a hardware video encoder;
- candidate resolution/FPS/bitrate combinations are validated with Android VideoCapabilities before selection;
- HEVC is preferred where the device reports a valid hardware path;
- automatic startup intentionally does not jump directly to 1080p60/1440p; higher load modes are reserved for later runtime health promotion;
- if no validated hardware startup profile exists, automatic recording does not silently fall back to software encoding.

This is only the startup guardrail. Runtime thermal/frame-loss adaptation is still pending and must be implemented before claiming full sustainable-performance protection.


## MediaProjection foundation

Implemented on feature branch:
- explicit user consent via MediaProjectionManager;
- foreground service declared with mediaProjection service type;
- Android foreground-service permissions for media projection;
- MediaProjection lifetime owned by CaptureService rather than Activity;
- MediaProjection.Callback registered so revoked/stopped projection tears the service down;
- MediaProjection foundation passed public CI and was merged; current feature branch adds VirtualDisplay -> surface-input hardware MediaCodec.


## Hardware video pipeline implementation

Current feature branch now connects the selected sustainable profile to a named hardware MediaCodec encoder and creates the MediaProjection VirtualDisplay directly onto the codec input Surface.

Performance properties:
- no CPU pixel readback/conversion path;
- no Bitmap/ImageReader frame copies;
- encoder output is drained continuously on a dedicated lightweight thread so codec buffers do not back up;
- keyframe interval starts at 2 seconds to support later segment/keyframe-safe clipping;
- encoder, input Surface and VirtualDisplay have explicit teardown paths.

Hardware video pipeline passed public CI and is merged. Current feature branch routes encoded samples directly into rolling MP4 segments; no video re-encoding is introduced.


## Rolling encoded segment buffer

Rolling MP4 segments passed public CI and are merged. The current feature branch implements video-only retroactive export:
- MediaCodec compressed output is written directly to MediaMuxer;
- segment cuts occur only on encoder keyframes;
- target segment duration is 2 seconds;
- timestamps are normalized per segment;
- default prototype retention is 60 seconds and old completed segments are deleted automatically;
- files live in app cache, not Gallery, until the user explicitly saves a clip;
- no raw-frame buffering and no re-encoding are used.

The writer currently performs MediaMuxer writes on the encoder drain thread. Because it writes compressed data rather than raw frames, this is intentionally simple for the first prototype, but real-device measurements must verify that storage latency does not back-pressure the encoder. If it does, move mux writes to a bounded dedicated I/O queue without introducing raw-frame copies.


## Retroactive video export

Retroactive video export passed public CI and is merged. Current feature branch adds runtime thermal protection:
- UI buttons request the previous 15, 30 or 60 seconds;
- only completed keyframe-aligned rolling segments are selected;
- selected segments are leased so retention cleanup cannot delete them mid-export;
- MediaExtractor reads compressed samples from each segment;
- MediaMuxer writes one final MP4 directly into MediaStore under Movies/MedPlay;
- timestamps are translated onto one monotonic output timeline;
- no video decode/re-encode occurs during save;
- export runs off the service main thread.

Important limitation for this prototype: the currently open rolling segment is not exported until it is completed on the next keyframe boundary. That keeps the save path safe and avoids reading an MP4 while MediaMuxer is still writing it. With the current 2-second keyframe/segment target, the saved clip may end roughly up to one segment behind the exact button press. A later refinement can request an immediate sync frame/segment rollover before export if real-device testing shows the extra complexity is worthwhile.


## Runtime thermal protection

Runtime thermal protection passed public CI and is merged. Current feature branch adds internal playback audio capture/AAC encoding:
- thermal status is monitored continuously while capture is active;
- NORMAL/LIGHT keeps the selected startup bitrate;
- MODERATE requests 85% of the selected bitrate;
- SEVERE requests 70%;
- CRITICAL or worse stops capture rather than continuing to stress the device;
- bitrate changes use MediaCodec.setParameters and do not restart the encoder;
- the thermal listener is removed on recorder teardown;
- unit tests cover the protection policy.

This is a real APK safeguard, not only documentation. It does not yet prove that bitrate reduction alone is sufficient on every SoC; real-device testing will determine whether severe states should also trigger an encoder restart into a lower resolution/FPS profile. No automatic resolution/FPS restart is added yet because doing so without measurement would create avoidable instability.


## Internal audio foundation

Internal playback-audio capture/AAC foundation passed public CI and is merged. Current feature branch synchronizes audio and video rolling timelines:
- requests RECORD_AUDIO at runtime, but continues video-only if the user denies it;
- uses AudioPlaybackCaptureConfiguration tied to the same MediaProjection;
- matches GAME and MEDIA playback usages;
- captures 48 kHz stereo PCM16;
- feeds PCM directly into MediaCodec AAC-LC at 128 kbps;
- prefers a hardware AAC encoder when available and falls back to another AAC encoder if needed;
- runs audio capture/encoding on a dedicated thread;
- records a monotonic capture-start timestamp for later A/V alignment;
- tears down AudioRecord and MediaCodec with the capture service.

Playback audio is now written into rolling AAC segments and included in the final MP4 when Android/source-app playback capture is available. Video-only export remains the fallback when audio is disabled, denied or unavailable.


## Synchronized A/V rolling export

Current feature branch completes the first audio-in-clip path:
- hardware video encoder start is anchored to SystemClock.elapsedRealtimeNanos();
- encoded video PTS is translated onto that monotonic timeline before segment bookkeeping;
- internal AAC capture already exposes its own monotonic start anchor;
- encoded AAC is written into independent 2-second rolling MP4 audio segments;
- audio retention is bounded to the same 60-second prototype window;
- save leases audio segments overlapping the selected video interval;
- the final MediaMuxer output contains both video and AAC tracks;
- both tracks are copied as already-compressed samples, with no decode/re-encode;
- audio samples outside the selected video interval are trimmed during remux;
- if playback capture is unavailable or denied, export remains valid video-only.

This creates one common time base for A/V alignment while keeping audio and video storage independently bounded. Real-device testing must still measure lip/game-audio sync drift over long sessions; if drift appears, future correction should be timestamp-based rather than by resampling or re-encoding unless measurement proves that necessary.


## Floating control and extended retention

Current feature branch adds:
- SYSTEM_ALERT_WINDOW declaration and explicit user-facing overlay permission flow;
- a draggable floating "Clip" control shown only while capture is active and permission is granted;
- tapping the control saves the default 30-second retroactive clip without returning to the app;
- direct presets for 2, 3 and 5 minutes were added to the prototype UI;
- maximum requested retention is extended to 300 seconds;
- actual retention is storage-aware and computed from selected video bitrate + AAC bitrate;
- rolling storage is capped to a 768 MiB budget and keeps a separate 768 MiB free-space reserve;
- if even the minimum 60-second rolling window cannot fit safely, capture is refused rather than risking low-storage instability;
- audio and video use the same selected retention window.

This is deliberately bounded: MedPlay does not blindly allocate five minutes on every device/profile. A high-bitrate profile can receive a shorter window if storage headroom is insufficient.


## Encoder hardening and health telemetry

Current feature branch closes two gaps before real-device validation:
- automatic startup encoder selection now requires COLOR_FormatSurface, matching the actual zero-copy MediaCodec input path;
- encoder capability reports now show whether Surface input is supported;
- encoders without Surface input are excluded before profile selection, even if their width/FPS/bitrate metadata otherwise matches;
- HardwareVideoEncoder now records lightweight runtime health counters using atomics only;
- tracked signals are encoded-sample count, presentation-timestamp regressions, maximum wall-clock gap between output samples, and time since last encoded output;
- telemetry is collected without per-frame logging, allocations beyond the existing sample path, or automatic profile restarts.

These metrics are observational for now. They are intentionally not used to trigger resolution/FPS changes until real-device measurements establish trustworthy thresholds.


## Encoder shutdown and fatal-error hardening

Current feature branch fixes shutdown correctness and pipeline failure propagation:
- stopping the video encoder now signals end-of-input without immediately terminating the drain loop;
- the drain thread continues until encoder EOS is observed or a bounded timeout expires;
- only after that does teardown release the input Surface and MediaCodec;
- this reduces the risk of truncating the final encoded samples/segment during service shutdown;
- output buffers are released from a finally block even if the sample consumer throws;
- fatal drain/consumer exceptions are surfaced to CaptureService;
- CaptureService posts teardown to the main thread when the video pipeline fails;
- the previous unused RequiresApi import was removed.

The stop path remains bounded: if EOS does not arrive within the grace period, MedPlay forces drain termination rather than hanging the foreground service indefinitely.


## Export consistency guards

Current feature branch hardens final clip creation before real-device testing:
- video and audio segment ranges are validated as strictly ordered and non-overlapping;
- every selected segment must exist and be non-empty before MediaStore insertion;
- every video segment must begin with a sync/keyframe sample;
- critical track format fields are compared across all selected segments so resolution/audio layout changes cannot be silently concatenated;
- per-track output timestamps must remain strictly increasing during remux;
- MediaMuxer.stop() failures now fail the export instead of being swallowed;
- MediaStore publication is checked; an unpublished/failed clip remains pending only until the failure path deletes it;
- any validation/remux/publication failure deletes the pending MediaStore item and releases segment leases.

The final export still copies compressed samples only; these guards do not introduce decode/re-encode.


## Serialized clip export and inactive-action cleanup

Current feature branch reduces avoidable I/O/thermal load and tightens service lifecycle:
- only one retroactive clip export may run at a time;
- repeated save taps while an export is active are ignored instead of spawning concurrent remux threads;
- the export-in-progress flag is always cleared in a finally block;
- if there is no completed video window to lease, the export slot is immediately released;
- ACTION_SAVE received without an active rolling writer no longer starts/keeps an otherwise idle service alive;
- ACTION_SHOW_OVERLAY received without an active MediaProjection also stops the transient service instance;
- the actual remux path remains off the main thread and still performs compressed-sample copy only.

This deliberately favors stable gameplay and low I/O contention over allowing multiple simultaneous saves.


## Segment finalization hardening

The same branch also tightens rolling-segment validity:
- a video/audio segment is added to the completed queue only if MediaMuxer.stop() succeeds;
- a segment whose muxer finalization fails is deleted immediately and never becomes leaseable/exportable;
- segment leases now use AtomicBoolean for thread-safe idempotent close;
- service teardown catches final-segment close failures so recorder shutdown stays bounded even when storage/muxer finalization fails.

A finalization failure during active video rotation still propagates through the encoder consumer path and triggers recorder teardown rather than continuing on a possibly corrupted rolling set.


## Internal-audio failure fallback

InternalAudioCapture now surfaces fatal worker/consumer exceptions to CaptureService. The service disables and finalizes only the audio path, then continues video capture and retroactive video export. This preserves recording availability when playback capture/AAC fails on a device or source app instead of leaving a dead audio pipeline or unnecessarily stopping video.


## Audio fallback recursion fix

A regression introduced while wiring video-only fallback caused disableInternalAudio() to call itself recursively. This branch fixes the fallback so it explicitly stops InternalAudioCapture, clears it, finalizes/closes the rolling audio writer, and clears that reference. Video capture remains active.


## MediaProjection lifecycle restart hardening

Current feature branch closes a session-replacement/resource-ownership gap:
- a new ACTION_START first refuses to replace the active capture while a clip export is in progress, avoiding deletion/reinitialization of rolling files that may still be leased;
- restarting capture now explicitly unregisters the old MediaProjection callback;
- the existing floating control, audio path, thermal guard, VirtualDisplay, video encoder and rolling writers are released before the new projection session is created;
- the old MediaProjection token is explicitly stopped after its owned pipeline is released;
- explicit shutdown now also stops the MediaProjection token rather than only clearing the field;
- onDestroy uses the same owned-resource shutdown path, making repeated teardown idempotent/bounded.

This prevents an old encoder/VirtualDisplay from surviving a capture restart and avoids leaving a projection token alive after thermal/error/manual shutdown.


## Video PTS normalization

This branch also fixes a potential A/V timeline error in the Surface-input path:
- codec output presentationTimeUs is no longer assumed to start at zero;
- the first observed video PTS becomes the origin of the codec's local timeline;
- later video samples use only the delta from that first PTS;
- that delta is anchored once to MedPlay's monotonic session start;
- this avoids double-offsetting timestamps when Surface/codec PTS already resembles a monotonic clock;
- regressions in the mapped PTS are rejected instead of silently producing invalid segment timing.

Unit tests cover both zero-based and large absolute-looking codec PTS domains. Real-device testing is still required to measure residual A/V start offset and long-session drift.


## AAC EOS and shutdown hardening

InternalAudioCapture teardown now distinguishes intentional stop from fatal failure, stops AudioRecord first to unblock READ_BLOCKING, gives the worker a bounded grace period to queue/drain AAC EOS, waits during EOS dequeue, force-stops MediaCodec at most once if necessary, always releases output buffers from finally, and surfaces negative AudioRecord.read() errors. This preserves as much final AAC output as possible while keeping shutdown bounded.


## Save-triggered sync-frame rollover

MedPlay now reduces the gap between the Clip tap and the end of the saved retroactive clip by marking the rolling writer to rotate on the next sync frame, requesting MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, and waiting off the main thread for at most 750 ms for that newly completed segment. If the encoder ignores the request or the timeout expires, export falls back to the latest completed segments. No active MP4 is read and no media is re-encoded.


## Video encoder stop race hardening

HardwareVideoEncoder.stop() is now synchronized with dynamic bitrate and sync-frame parameter updates. stopRequested remains set through teardown, and drain-loop exceptions that occur during an intentional stop are not surfaced as new fatal pipeline errors. start() resets stopRequested for a new encoder session. This closes a shutdown race between MediaCodec.setParameters(), EOS signaling and forced drain termination.


## Save-aligned AAC rollover

Before leasing AAC for a saved clip, MedPlay finalizes the currently open rolling audio segment under synchronization. The next encoded AAC sample automatically opens a new segment, so capture continues without restarting AudioRecord or MediaCodec. This keeps the final audio boundary close to the video boundary instead of trailing by the normal 2-second audio rotation interval. No decode, resample or re-encode is introduced.


## Adaptive remux sample buffer

Retroactive export inspects MediaExtractor.sampleSize before each compressed-sample read. The reusable direct buffer starts at 8 MiB and grows only when an actual encoded access unit needs more capacity, with a 64 MiB defensive per-sample ceiling. This removes the fixed-buffer failure mode for unusually large keyframes without reserving large memory for normal clips.


## Lightweight local session diagnostics

Current feature branch prepares real-device validation without adding per-frame logging:
- each completed recording session stores one compact text summary in app-internal storage;
- summary includes active resolution/FPS/bitrate/codec/encoder, selected retention, session duration, whether internal audio was active, maximum Android thermal status observed, encoded sample count, timestamp regressions, maximum wall-clock output gap and time since the final encoded sample;
- the summary is produced from existing atomic encoder-health counters and thermal callbacks, so no frame payloads or screen/audio content are recorded;
- only the latest session summary is retained;
- MainActivity can display the latest summary directly on-device, avoiding ADB/PC for initial validation.

This telemetry is diagnostic only. It does not yet automatically change resolution/FPS because thresholds still require real-device evidence.


## Transactional encoder startup cleanup

Video and AAC startup now clean locally owned resources if initialization fails before those resources are published to the recorder service. HardwareVideoEncoder releases any created input Surface and MediaCodec after configure/createInputSurface/start/initial-callback failure. InternalAudioCapture stops/releases any partially started AudioRecord and AAC MediaCodec after initialization/configure/start/recording/callback failure. This prevents leaked codec/audio resources after a failed recording start.


## Capture startup failure containment

CaptureService startup contains failures from MediaProjection acquisition/callback registration, codec profile probing, video encoder startup, VirtualDisplay creation and thermal-guard startup. Each failure path performs bounded cleanup and stops the service instead of allowing a partially initialized recorder to escape through an exception. Secondary cleanup failures cannot prevent MediaProjection teardown.


## Lightweight clip-save feedback

The foreground-service notification now reports clip-save state without adding per-frame work:
- repeated save requests while one export is active report that a save is already in progress;
- the notification reports when a requested retroactive clip is being saved;
- if there is not enough completed rolling data yet, the notification says so;
- successful export reports the actual saved duration while recording continues;
- failed export reports failure while explicitly keeping the active recording session running;
- notification updates are ignored once MediaProjection is no longer active, preventing a late export thread from recreating stale recorder notifications after shutdown.

Notification updates happen only on save events and do not alter encoder, segment cadence or remux strategy.


## Persistent floating clip duration

The floating Clip control duration is now configurable and persistent:
- supported defaults are 15 s, 30 s, 60 s, 2 min, 3 min and 5 min;
- the selected value is stored locally in SharedPreferences;
- invalid/stale stored values fall back to 30 seconds;
- the floating control reads the preference at tap time, so changing it does not require restarting capture;
- direct save-now buttons remain independent of the floating default;
- MainActivity is wrapped in a ScrollView so all prototype controls remain reachable on smaller displays.

The setting changes only the requested export window. Actual available history still depends on the storage-aware rolling retention policy.


## Persistent internal-audio toggle

MedPlay now has an explicit persistent choice for internal playback audio:
- internal audio defaults to enabled;
- the choice is stored locally in SharedPreferences;
- when disabled, MainActivity skips the RECORD_AUDIO permission request and proceeds directly to MediaProjection consent;
- CaptureService re-checks the preference before creating AudioPlaybackCapture, AudioRecord or the AAC encoder, so disabled audio consumes no audio-capture/encode resources;
- changing the toggle applies to the next capture session; it does not tear down an already-running audio pipeline.

This provides an intentional video-only mode in addition to the existing automatic video-only fallback when playback capture is denied, unavailable or fails.


## Audio-aware retention budgeting

Rolling-retention storage estimation now uses the intended audio mode. If internal audio is disabled or RECORD_AUDIO is not granted, the retention policy budgets zero AAC bitrate instead of reserving 128 kbps for an audio stream that will not exist. The same free-space reserve and rolling-storage ceiling remain in force.


## Notification controls and asynchronous recorder shutdown

MedPlay exposes low-overhead Clip and Stop actions in the foreground-service notification. Clip without an explicit duration reads the persisted ClipLengthPreference; Stop uses the recorder-owned shutdown path. Normal, thermal and fatal shutdown no longer waits for audio/video EOS on the main thread: shutdown is admitted once, new recorder actions are rejected, the overlay is removed immediately, and recorder resources are released on a dedicated shutdown thread. Resource-release failures are isolated so cleanup continues, and service destruction waits until capture resources are released and any already-running clip export has finished.


## Notification controls and asynchronous recorder shutdown

MedPlay exposes low-overhead Clip and Stop actions in the foreground-service notification. Clip without an explicit duration reads the persisted ClipLengthPreference; Stop uses the recorder-owned shutdown path. Normal, thermal and fatal shutdown no longer waits for audio/video EOS on the main thread: shutdown is admitted once, new recorder actions are rejected, the overlay is removed immediately, and recorder resources are released on a dedicated shutdown thread. Resource-release failures are isolated so cleanup continues, and service destruction waits until capture resources are released and any already-running clip export has finished.


## Session sustainability verdict

RecordingSessionDiagnostics now derives and persists a measurement-only GOOD / DEGRADED / UNSUSTAINABLE verdict from:
- current target FPS;
- encoder sample count;
- timestamp regressions;
- maximum wall-clock encoder-output gap;
- time since the last encoded output;
- maximum Android thermal status;
- total session duration.

A session with no encoded samples is never reported as GOOD. Zero samples for two seconds or more is UNSUSTAINABLE; a very short zero-sample session is DEGRADED. Current thresholds are deliberately conservative and unit-tested. The verdict does not yet change resolution/FPS automatically; real-device sessions must validate thresholds first.


## Android 13+ notification permission flow

MedPlay already declares POST_NOTIFICATIONS and now requests it at runtime on Android 13+ before starting the capture-consent flow. Denial does not block MediaProjection or video recording; the app continues and explains that notification controls may be hidden. Internal-audio permission remains an independent optional step. This keeps recorder functionality available while making the foreground notification's Clip/Stop controls visible when the user grants notification permission.


## Encoder-health media-sample filtering

EncoderRuntimeHealth now observes only non-empty encoded media access units. MediaCodec BUFFER_FLAG_CODEC_CONFIG output is still delivered to the existing sample consumer path, but it is excluded from encodedSamples, timestamp-regression and wall-gap telemetry. This keeps session sustainability diagnostics from treating codec configuration bytes as a video frame/sample.


## Duplicate capture-start guard

CaptureService now rejects a second ACTION_START while a MediaProjection session is already active. The existing recorder remains untouched and the foreground notification reports that capture is already active. This avoids synchronously tearing down and recreating AudioRecord/MediaCodec/VirtualDisplay from the service main thread when the user taps Enable screen capture again. A new capture session should be started only after the current one has been stopped.


## Copyable session diagnostics

MainActivity now exposes a **Copy last recording diagnostics** action. It copies only the compact persisted diagnostic text (profile, duration, retention, thermal/encoder-health metrics and sustainability verdict) to the Android clipboard. No video/audio payload, recording file, token or private app data is included. If no completed session exists, the UI reports that instead of copying an empty value.


## Active capture status in notification

After a capture session is fully started, the foreground notification now shows the actual selected video resolution/FPS, the storage-aware rolling retention window in seconds, and whether internal audio is active or the session is video-only. This reuses existing session state and adds no polling or per-frame work. It gives real-device tests an immediate way to confirm the profile MedPlay actually selected.


## Active capture status in notification

After a capture session is fully started, the foreground notification shows the actual selected video resolution/FPS, the storage-aware rolling retention window in seconds, and whether internal audio is active or the session is video-only. This reuses existing session state and adds no polling or per-frame work. It gives real-device tests an immediate way to confirm the profile MedPlay actually selected.


## Session 2 development APK handoff

- The current development APK was generated successfully from main through the existing Manual dev APK release workflow.
- Release tag: `dev-latest`.
- Release assets: `MedPlay-debug.apk` and `MedPlay-debug.sha256`.
- The release workflow was temporarily enabled for one push only because the available GitHub connector cannot dispatch `workflow_dispatch` directly.
- After successful APK publication, the workflow was restored to manual-only and that restoration passed Android CI.
- Real-device validation is now the active blocker; CI does not substitute for physical capture, thermal, gameplay or A/V-sync testing.


## Simplified clip controls, language and recording quality

The prototype UI has been simplified after real-device usability feedback:
- the six separate save-duration buttons were removed;
- one persisted **Clip duration** selector now controls the in-app Save clip button, floating Clip control and notification Clip action;
- supported durations remain 15 s, 30 s, 60 s, 2 min, 3 min and 5 min;
- the app now has a persistent **Español / English** selector, with Spanish as the default for a fresh install;
- MainActivity user-facing controls/status messages are localized and capture-notification text/actions follow the selected language;
- recording quality now exposes **Automatic (recommended)** plus validated manual modes among 720p30, 720p60 and 1080p30;
- only manual modes reported as supported by the device's hardware Surface-input encoders are shown;
- a stale/unsupported saved manual mode falls back to Automatic instead of failing capture startup;
- HEVC is preferred over AVC for the same validated manual resolution/FPS where available;
- codec and bitrate remain profile-managed automatically rather than exposing unsafe arbitrary values.

1080p60 is intentionally not exposed yet. Capability metadata alone does not prove sustained gaming/thermal stability; higher-load profiles require real-device evidence first.


## High-motion sharpness and safe-inset implementation

Current feature work addresses real-device feedback from shooter/gyro capture:
- MainActivity now applies system-bar and display-cutout insets to all four edges of the root ScrollView, including Android 15 edge-to-edge behavior; controls must no longer begin underneath the status/camera area or end underneath navigation/gesture areas.
- High-motion quality targets now require both a supported size/FPS combination and sufficient hardware bitrate headroom before a mode is exposed.
- Manual hardware-validated modes can now include 1080p60, 1440p30 and 1440p60 in addition to the existing modes.
- Automatic mode intentionally prioritizes sharp 1080p60 when the hardware can sustain the configured high-motion bitrate, then falls back through 1080p30/720p60/720p30. 1440p remains manual-only until sustained real-device evidence justifies making it automatic.
- HEVC high-motion targets use substantially more bitrate than the original 12 Mbps 1080p30 prototype; AVC receives a larger bitrate budget because it is less efficient.
- HardwareVideoEncoder requests VBR when the selected encoder advertises VBR support and prefers HEVC Main / AVC High profiles when available.
- Existing thermal protection remains active: NORMAL/LIGHT uses the full selected bitrate, MODERATE reduces it, SEVERE reduces it further, and CRITICAL stops capture.
- The normal save path remains compressed-sample remux with no decode/re-encode. No sharpening/color postprocess was added because it would consume extra power and cannot recover detail already destroyed during capture.

Real-device validation is required before declaring the new high-motion targets sustainable. In particular, verify 1080p60 quality, gameplay smoothness, thermal behavior, storage-aware retention and long-session stability on the owner's shooter workload.


## Game-only capture and premium floating control

Current feature work adds privacy-focused single-app capture without changing the recording-quality pipeline:
- Android 14+ defaults MedPlay to **Solo juego (recomendado)**. The system capture chooser is configured for user choice so the user can select the game app; Android app-screen sharing excludes status/navigation bars, notifications, and other content outside the selected app.
- **Pantalla completa** remains an explicit alternative using the default-display projection configuration where the platform permits it.
- Android versions below API 34 fall back to full-screen capture.
- The encoder profile, codec, FPS, bitrate, thermal policy, rolling segments, internal-audio path and remux-only export are unchanged by capture scope.
- MainActivity is marked resizable to align with Android app-screen-sharing guidance.

The floating Clip overlay is now a custom lightweight glass-style control:
- circular translucent gradient rather than a flat system button;
- double-rewind icon to communicate retroactive clipping;
- immediate press/accepted animation;
- completed-save check feedback, busy feedback for duplicate export attempts, and failure feedback;
- persisted size, opacity and tint preferences;
- presets: ice, sky, violet, emerald and amber;
- style refresh is available by activating the floating button again while capture is active.

The glass appearance intentionally avoids cross-window/background blur. A small custom Canvas gradient/highlight is used instead so visual polish does not add meaningful GPU work while a game is running.

Real-device validation remains required. In particular verify that the owner's Android build exposes single-app selection, notifications/vendor overlays are excluded, the floating control itself is excluded from game-only clips, and recording quality remains identical to the previously validated high-motion build.


## Session 2 validation APK: game-only capture / glass overlay

A new `dev-latest` validation APK was generated successfully after merging the game-only capture and premium glass floating-control feature.

Release build:
- feature merge: `77ba165ef7655c58dccf681fb3212c9b97d76ac4`
- release-trigger merge: `1accba060c2806c9e761eebd5e0876761b34ae32`
- Manual dev APK release run: `38794076975` — success
- published `dev-latest`: `2026-10-15T19:28:56Z`
- APK size: 2,620,938 bytes
- release assets: `MedPlay-debug.apk` and `MedPlay-debug.sha256`
- the temporary push trigger was restored to workflow_dispatch-only by PR #60; current main afterward is `09928e9977cc3ffc0d41045d189ed05c6f615bc2`

The APK app code matches the game-only/glass-overlay feature build; the extra release-trigger/restoration commits only changed CI workflow behavior.

Physical validation is still pending for:
- selecting a real game through Android single-app capture;
- confirming notifications/vendor overlays and the MedPlay floating button are excluded from the saved game-only clip;
- confirming bitrate/FPS/audio quality remains equivalent to the previously approved high-motion build;
- confirming floating control tint, size, opacity and accepted/saved/error feedback on-device.


## Main recording UI polish

Real-device feedback showed that the first game-only/glass-overlay build was functionally correct but still exposed prototype-oriented controls and lacked visual feedback while customizing the overlay.

The main UI is now simplified:
- removed the redundant MedPlay **Qué grabar** selector; Android 14+ always opens the platform user-choice capture dialog, where the user selects one app or the full screen;
- added a concise explanation of the Android capture choice instead of duplicating it in MedPlay;
- removed the main-screen **Activar botón flotante** action; the overlay appears automatically when capture starts;
- if overlay permission is missing, **Iniciar grabación** opens the system permission page and resumes capture setup automatically after the user grants it;
- added a live in-app preview using the exact same GlassClipView renderer as the real floating overlay, so tint, size and transparency changes are visible immediately;
- moved Stop and diagnostic controls into a collapsed **Opciones avanzadas** section;
- disabled ScrollView state restoration and explicitly resets the root scroll to the top after layout so the first controls do not reopen clipped/off-screen;
- removed the now-unused CaptureScopePreference.

No encoder, bitrate, FPS, codec, thermal or export-quality behavior changed in this UI pass.


## Session 2 validation APK: polished recording UI

A new `dev-latest` APK was generated successfully after merging PR #62 (polished recording UI / live glass preview).

Release evidence:
- feature merge: `a342659570e63180f550c275bf7a2c85c5bce85e`
- temporary release-trigger merge: `ddee563b8928f4fb20ae349ae761141f06552c4c`
- Manual dev APK release run: `38810905282` — success
- published `dev-latest`: `2026-10-15T21:35:44Z`
- APK size: 2,618,626 bytes
- assets: `MedPlay-debug.apk` and `MedPlay-debug.sha256`
- the temporary push trigger was restored to workflow_dispatch-only by PR #64; current main afterward is `e74ecb9a88f1e8978657f56eaaea665bc72593be`

The app source in the release corresponds to the polished UI feature; the release-trigger/restoration commits changed CI workflow behavior only.

Physical validation is still pending for:
- opening from a cold start with the UI truly at the top and no clipped labels;
- Android showing the single app-vs-full-screen chooser only once;
- overlay permission continuing into capture automatically;
- live glass preview matching the real floating button;
- Advanced options collapsed by default;
- previously approved video/audio quality remaining unchanged.


## Separate player-microphone capture

Authorized Session 2 work adds an optional player-voice path without changing the validated video encoder or internal game-audio settings.

Implementation:
- new **Grabar mi voz (pista separada)** setting, default OFF;
- when enabled and RECORD_AUDIO is granted, CaptureService starts a foreground-service microphone type alongside media projection;
- MicrophoneVoiceCapture records 48 kHz mono PCM16 and encodes it independently as AAC-LC 96 kbps;
- the existing internal game-audio path remains 48 kHz stereo AAC-LC 128 kbps and is not mixed or reconfigured;
- microphone rolling segments use the same monotonic timeline and retention window as video/game audio;
- final retroactive MP4 exports preserve game audio as the first audio track and player microphone as a second independent AAC track;
- storage-aware retention includes both audio bitrates when both are enabled;
- AudioRecord.AudioRecordingCallback + AudioRecordingConfiguration.isClientSilenced detect when Android silences MedPlay because another app wins microphone capture priority;
- MedPlay keeps its AudioRecord alive while silenced, allowing Android to unsilence it automatically later without restarting the screen recording;
- a small MedPlay overlay message reports **Micrófono usado por el juego • tu voz no se está grabando** and later **Micrófono disponible • voz reanudada**;
- diagnostics now persist whether microphone capture was active and whether Android silenced it at least once.

Current limitation: the microphone and game sound are intentionally separate tracks. Common gallery players may play only one selected track. The future editor/mixer will provide simultaneous preview, per-track volume controls and a final mixed MP4 while keeping the original sources intact.

Status: IMPLEMENTADO_PENDIENTE_VALIDACIÓN on a real device with game voice chat.


## Automatic thermal load adaptation

Session 2 extends the existing runtime thermal guard so MedPlay can reduce encoder/display load automatically instead of relying on bitrate alone.

Behavior:
- NORMAL/LIGHT: keep the selected validated profile at full configured bitrate.
- MODERATE: keep resolution/FPS and request 85% bitrate.
- SEVERE: request 70% bitrate and move down one validated hardware profile.
- If SEVERE persists, reassess every 30 seconds and step down again when another validated lower mode exists.
- CRITICAL or worse: stop capture as before.

Current step-down order is intentionally conservative for gameplay detail:
`1440p60 -> 1440p30 -> 1080p60 -> 1080p30 -> 720p60 -> 720p30`, skipping modes the device does not validate. The same codec/encoder is preferred when a lower mode exists.

A profile transition creates a fresh rolling-video epoch and resizes/rebinds the existing VirtualDisplay to a new hardware encoder surface. This prevents MP4 segments with incompatible video formats from being concatenated. Internal game audio and microphone rolling timelines continue, and clip export uses only the current compatible video epoch.

During the short profile handoff, Clip requests are rejected with a thermal-adjustment message to avoid export/switch races. Shutdown waits a bounded time for an in-progress handoff. Encoder health counters are accumulated across thermal epochs for diagnostics.

Diagnostics now include `initialResolution`, `initialFps`, and `thermalProfileDowngrades`.

Status: IMPLEMENTADO_PENDIENTE_VALIDACIÓN. Real-device testing must verify temperature response, gameplay smoothness, visual quality after each downgrade and stability of VirtualDisplay resize/surface handoff on target devices.


## In-app clip library

Saved retroactive MP4s now remain accessible inside MedPlay as well as Gallery. MedPlay does not duplicate the video file: it stores only a private metadata index containing the MediaStore URI, display name, creation time, duration and whether game-audio/microphone tracks were present.

MainActivity exposes a **Mis clips / My clips** section. Entries can be refreshed and opened with the device video player. If a Gallery/MediaStore clip is deleted externally, the stale index entry is pruned the next time the library is read.

This is the storage/library foundation for the planned non-destructive editor. The original MediaStore clip remains the master source.

Status: IMPLEMENTADO_PENDIENTE_VALIDACIÓN on-device.


## Latest dev-latest: microphone + thermal adaptation + clip library

The current `dev-latest` APK now includes:
- separate optional player-microphone capture from PR #66;
- automatic thermal load adaptation from PR #67;
- in-app `Mis clips / My clips` library from PR #68.

Release evidence:
- release run `38822693354`: success
- release target: `a5120b2f030674a94c6701a889bbe7ebd69221e2`
- release published: `2026-10-15T23:18:44Z`
- APK size: 2,683,542 bytes
- current main after restoring the release workflow to manual-only: `cddbf1e7b23ebf2a540e20e784bc135faf6faab8`

Status remains IMPLEMENTADO_PENDIENTE_VALIDACIÓN for the new microphone contention behavior, thermal profile switching, and in-app library flow.


## Portrait capture + internal clip playback

PR #72 addresses two physical-device findings from the latest validation build.

### Portrait game capture
- Android MediaProjection `onCapturedContentResize(width, height)` is now used as the source of truth for captured-content orientation instead of assuming the recorder UI/device orientation at capture start.
- When the selected captured app is portrait and the active hardware profile is landscape, MedPlay validates the swapped profile (for example 1920x1080 -> 1080x1920) against the same named hardware encoder, Surface input, FPS and bitrate.
- If valid, MedPlay starts a replacement hardware encoder and rolling-video epoch, resizes/rebinds the existing VirtualDisplay, requests a sync frame, then retires the old epoch. No CPU frame conversion or mandatory re-encode was added.
- Thermal profile ranking now treats portrait and landscape forms of the same resolution class equivalently and re-orients thermal downgrade candidates back to the current content orientation when supported.
- If a portrait-swapped hardware mode is not supported by the device encoder, MedPlay keeps the current safe profile rather than forcing an invalid encoder configuration.

### Internal clip playback
- `Mis clips / My clips` now opens `ClipPlayerActivity` inside MedPlay instead of relying on an external ACTION_VIEW-capable video app.
- The player uses Android VideoView/MediaController and reads the existing MediaStore URI; the clip is not duplicated.

Status: IMPLEMENTADO_PENDIENTE_VALIDACIÓN. CI passed on PR #72. Required device validation: record a portrait game, confirm it fills a portrait output rather than appearing small inside a landscape canvas, then open the resulting clip from `Mis clips` and verify playback starts inside MedPlay.


## Full-session recording foundation

Phase 2 has started on PR #73.

Implemented in this foundation:
- persisted recording mode: `RETROACTIVE_CLIP` or `FULL_SESSION`;
- MainActivity exposes **Clip retroactivo** and **Grabación completa**;
- Clip mode keeps the existing rolling/save behavior;
- full-session mode keeps encoded video/audio segments for the whole safe session window and exports the entire retained session to MediaStore when Stop is pressed;
- full-session storage is budgeted from available bytes while preserving a 768 MiB free-space reserve;
- minimum viable full session is 60 seconds; maximum automatic session budget is 2 hours;
- full-session recording automatically stops/saves at its calculated safe maximum instead of silently deleting the beginning;
- optional microphone capture is now scoped to full-session mode; retroactive clip mode no longer starts the separate microphone track;
- severe thermal state still reduces bitrate in full-session mode, but automatic resolution/FPS epoch downgrade is suppressed so earlier parts of one full recording are not discarded by incompatible video-format changes;
- an initial captured-content orientation correction is allowed only during the first few seconds of a full session; later orientation changes do not rewrite the recording format mid-session;
- the existing notification Stop action finalizes/saves a full session.

Not yet implemented in this phase:
- lateral expandable full-session floating control;
- visible elapsed timer;
- pause/resume;
- microphone toggle from the floating control;
- highlight analysis.

Status: IMPLEMENTADO_PENDIENTE_VALIDACIÓN. CI passed on the initial PR #73 code. Physical testing must verify long-session retention, Stop -> complete MP4 export, audio/microphone tracks, storage-limit auto-stop, and that Clip mode remains unchanged.


## Full-session floating controls

PR #74 adds the owner-requested lateral full-session control.

Implemented:
- draggable floating session bubble;
- snaps to nearest left/right edge when released;
- tap expands a compact control panel inward;
- elapsed timer excludes paused time;
- microphone can be toggled ON/OFF during a full session when RECORD_AUDIO is granted;
- Pause/Resume is functional, not cosmetic;
- Stop & save finalizes the complete retained session.

Pause implementation:
- current video segment is finalized;
- VirtualDisplay output surface is detached while paused;
- internal-game-audio and microphone AAC writers suppress samples while paused;
- resume reattaches the same hardware encoder surface and requests a new sync frame;
- pause intervals are passed to the final remux and removed from video/game-audio/microphone timestamps so the exported MP4 timeline is gapless;
- safe full-session duration counts active recording time, not paused time.

The full-session foreground service starts with microphone capability when RECORD_AUDIO permission is available so the mic can be enabled later from the overlay without returning to MedPlay.

Status: IMPLEMENTADO_PENDIENTE_VALIDACIÓN. PR #74 initial code passed Android CI. Real-device tests must verify left/right snapping, timer, pause/resume A/V continuity, microphone toggling, and Stop & save.


## Latest validation APK — portrait/full-session controls

A fresh `dev-latest` APK was generated after merging PR #72, PR #73 and PR #74.

Evidence:
- release workflow run `38904004611`: success;
- release target: `7754f43eb0c0875af1a4dee18615dae7a3711219`;
- published: `2026-10-16T12:56:49Z`;
- `MedPlay-debug.apk` size: 2,737,274 bytes;
- release contains the portrait-capture fix, in-app clip player, full-session recording mode, edge-snapping expandable session control, active-time timer, runtime microphone toggle, pause/resume and Stop & save.

The release workflow is restored to manual-only immediately afterward. Physical validation is still required; these features remain IMPLEMENTADO_PENDIENTE_VALIDACIÓN.


## Physical validation — portrait capture and internal player

Owner-provided device evidence on 2026-10-16 confirms:
- a portrait game now fills a portrait video frame instead of appearing as a tiny vertical image inside a landscape canvas;
- the same clip opens and plays inside MedPlay through the internal player;
- playback controls and portrait framing are visible and usable.

Status update:
- portrait capture adaptation: VALIDATED_ON_DEVICE for the tested device/title;
- internal clip player: VALIDATED_ON_DEVICE for the tested clip.

This does not yet validate every device/game orientation combination.

## Editor project foundation

Phase 3 has started.

Implemented:
- non-destructive edit projects stored privately by source MediaStore URI;
- original clip remains immutable;
- internal player exposes an Edit action;
- editor reuses the original MediaStore video for preview;
- trim start/end can be marked from the current playback position;
- trim can be reset to the full source duration;
- game-audio gain project setting supports 0–200%;
- microphone gain setting is shown only when the clip metadata says a microphone track exists;
- edit settings are persisted separately from the source video.

Not yet implemented:
- exporting the edited result;
- realtime gain mixing in preview;
- rotation;
- saturation/color;
- final single-track audio mix.

Status: IMPLEMENTADO_PENDIENTE_VALIDACIÓN.


## Latest validation APK — editor foundation

A fresh `dev-latest` APK was generated after merging PR #77.

Evidence:
- release workflow run `38917190060`: success;
- release target: `5cd80e2e8f1bf46bf9d508efd05da291dcb808b2`;
- published: `2026-10-16T14:38:10Z`;
- `MedPlay-debug.apk` size: 2,757,906 bytes;
- release includes the non-destructive editor foundation with Edit action, trim markers and per-track gain project settings.

The release workflow is restored to manual-only immediately afterward. Editor export/mixing/rotation/color remain pending.


## UI correction — visible editor action + 60s clip cap

Owner device evidence showed the editor action was not visible over VideoView. The player layout now places **Editar clip** in a fixed bar below the video surface instead of overlaying the VideoView.

Retroactive clip duration is now intentionally limited to:
- 15 seconds
- 30 seconds
- 60 seconds

The service also hard-caps retroactive save requests at 60 seconds, so stale/external values cannot request 2–5 minute clips. Full-session recording remains the path for longer recordings.

Status: IMPLEMENTADO_PENDIENTE_VALIDACIÓN.


## Latest validation APK — visible editor action + 60s clip maximum

A fresh `dev-latest` APK was generated after merging PR #80.

Evidence:
- release workflow run `38919967954`: success;
- release target: `7978f35fb6dbf40a56fffe29fed7a9ed18847ff4`;
- published: `2026-10-16T14:58:34Z`;
- `MedPlay-debug.apk` size: 2,757,954 bytes;
- the internal player now shows a fixed **Editar clip** action below the video;
- retroactive clip choices are 15/30/60 seconds only;
- CaptureService enforces a 60-second maximum.

The release workflow is restored to manual-only afterward.


## Editor trim timeline + fast export

Phase 3 now includes a visual trim timeline with two draggable range handles.

Implemented:
- draggable visual start/end trim handles;
- timeline changes seek the preview to the handle being adjusted;
- existing mark-start/mark-end buttons remain as shortcuts;
- **Exportar recorte** creates a new MP4 in MediaStore/Gallery and indexes it in **Mis clips**;
- original source clip remains immutable;
- export copies encoded video/audio tracks through MediaExtractor/MediaMuxer with no video decode/re-encode;
- trim start is snapped backward to the previous video sync frame when needed so the exported MP4 remains independently decodable;
- source rotation metadata is preserved when available;
- derived clips preserve the source library's game-audio/microphone metadata.

Current limitation:
- game/microphone gain values are still non-destructive project settings only and are NOT yet applied to this fast trim export;
- exact arbitrary-frame start would require video re-encode, so the current fast path may begin slightly earlier at the prior keyframe.

Status: IMPLEMENTADO_PENDIENTE_VALIDACIÓN.


## Latest validation APK — visual trim timeline + fast export

A fresh `dev-latest` APK was generated after merging PR #83.

Evidence:
- release workflow run `38924068103`: success;
- release target: `6589d32e466adbbe5e01779f589b97c58a8bb6c6`;
- published: `2026-10-16T15:28:20Z`;
- `MedPlay-debug.apk` size: 2,780,206 bytes;
- release includes the visual two-handle trim timeline and fast remux trim export.

The original clip remains immutable. Current fast export applies trim only; saved audio-gain settings are not yet mixed into the exported MP4.


## Editor color filters + rotation

Phase 3 now includes a broader local visual-editing pipeline.

Implemented:
- Media3 1.11.1 editor stack for preview/export;
- internal editor preview migrated from VideoView to ExoPlayer so visual effects can be previewed live;
- local filter presets: Natural, Vivo, Cálido, Frío, Cinemático and Blanco y negro;
- adjustable color intensity/saturation (0–200%);
- adjustable brightness (-50 to +50);
- adjustable contrast (-50 to +50);
- rotation choices 0°/90°/180°/270°;
- all visual settings persist in the non-destructive edit project;
- processed export uses Media3 Transformer and creates a new MP4; the source remains immutable;
- trim + visual effects are applied together during processed export;
- fast no-reencode trim export remains available separately.

Important audio compatibility guard:
- Media3 Transformer outputs at most one audio track for a single transformed item;
- therefore processed visual export is currently disabled for clips carrying a separate microphone track, to avoid silently losing player voice;
- those clips can still use fast trim export;
- the next editor block is audio mixing so filtered exports can safely produce one compatible final soundtrack.

Status: IMPLEMENTADO_PENDIENTE_VALIDACIÓN.


## Editor final export + audio mixing

Phase 3 now includes a final export path intended to apply the full edit project in one operation.

Implemented:
- audio tracks are demuxed from the source MP4 for the selected trim range;
- game and microphone gains are applied as 16-bit PCM gain processing with 0–200% range and clipping protection;
- multiple audio sources are composed/mixed through Media3 into one final AAC soundtrack;
- the final export combines trim, visual filters, saturation, brightness, contrast, rotation and audio gain settings;
- clips with separate game + microphone tracks no longer need to lose the microphone during visual export;
- output is a new MediaStore MP4 and the original remains untouched;
- final derived clips are indexed as a single compatible mixed-audio output;
- fast trim-only remux remains available separately for cases where no processing is wanted.

Implementation notes:
- source AAC tracks are first demuxed into temporary single-track MP4 assets for composition;
- video and audio are clipped to the same project range;
- processed audio is re-encoded to AAC;
- video can remain effectively pass-through when there are no visual effects, while visual effects/rotation require video processing;
- temporary export/audio files are deleted after success or failure.

Status: IMPLEMENTADO_PENDIENTE_VALIDACIÓN.


## Latest validation APK — expanded editor + final mixed-audio export

A fresh `dev-latest` APK was generated after merging PR #86 and PR #87.

Evidence:
- release workflow run `38930242477`: success;
- release target: `274fbc785bd75a4334d41ce959ed93e4e4176e73`;
- published: `2026-10-16T16:15:17Z`;
- `MedPlay-debug.apk` size: 11,686,163 bytes.

This build includes:
- live ExoPlayer preview with filters;
- Natural / Vivo / Cálido / Frío / Cinemático / Blanco y negro presets;
- color intensity 0–200%;
- brightness and contrast adjustment;
- 0°/90°/180°/270° rotation;
- persisted non-destructive visual project settings;
- fast trim-only export;
- final export applying trim + visual settings + per-track audio gain;
- game + microphone audio mixing into one AAC soundtrack for normal players.

Status remains IMPLEMENTADO_PENDIENTE_VALIDACIÓN for the new visual/audio editor pipeline until tested on a real device.


## Final editor audio-integrity guard

The final editor export now verifies that the actual demuxed audio-track count matches the ClipLibrary metadata before processing.

Implemented:
- exact validation of expected game/microphone audio track count;
- deterministic game/microphone gain mapping;
- export fails visibly instead of silently dropping or unexpectedly mixing a track when metadata and source disagree;
- partial audio-demux failures clean already-created temporary files;
- unit coverage for game-only, microphone-only, dual-track, mismatch and gain-clamping cases.

CI evidence:
- Android build run `38933653818`: success before documentation-only update.

Status: IMPLEMENTADO_PENDIENTE_VALIDACIÓN. Real-device validation of final mixed-audio export remains required.


## Editor live-preview stall fix

Physical device feedback showed that the editor preview appeared frozen while brightness/contrast/color controls were adjusted, even when playback was requested.

Root cause:
- every slider/filter update called `setVideoEffects(...)` and immediately forced a seek back to the current playback position;
- continuous SeekBar updates therefore generated repeated same-position seeks and effectively stalled playback.

Fix:
- while playback is requested, visual-effect updates no longer force a seek;
- Media3 applies the updated effects to subsequent video frames;
- when playback is intentionally paused, a same-position redraw remains available so the paused frame can refresh;
- unit coverage verifies that playing preview never requests the redraw seek.

CI evidence:
- Android build run `38939129761`: success.

Status: IMPLEMENTADO_PENDIENTE_VALIDACIÓN on the owner's device.


## Latest validation APK — live editor preview fix

A fresh `dev-latest` APK was generated after the editor live-preview fix and final-export audio-integrity guard.

Evidence:
- release workflow run `38939454521`: success;
- release target: `34e56b49778f3afc9bc6b827de76a0047a1a5bba`;
- source main feature state: `d9199dbe0e77f5218fc4357e2ee6fee80824584d`;
- published: `2026-10-16T17:25:43Z`;
- `MedPlay-debug.apk` size: 11,689,111 bytes;
- SHA-256: `d21fa8d154c56e7582e315bed070a2143bac34109e8514b91151e5ed2d9f26d3`.

The release branch differs from main only by the temporary workflow push trigger used to generate the APK. Main keeps the release workflow manual-only.

Device validation priority: play a clip in the editor, continuously drag color/brightness/contrast while playback is active, and confirm the video keeps moving while the visual result updates.


## Editor realtime preview surface fix

Real-device evidence showed the prior seek-only fix was insufficient: playback still stopped and visual sliders did not visibly update the preview.

Verified root cause:
- the editor was still calling `ExoPlayer.setVideoEffects()` on every slider/filter change;
- Media3 documents that repeated rapid calls rebuild the frame-processing pipeline and can stop playback.

New preview architecture:
- editor PlayerView now uses a TextureView-backed video surface;
- slider/filter preview no longer calls `setVideoEffects()`;
- color preview is applied directly to the preview surface with Android ColorMatrix/RenderEffect (hardware-layer fallback before API 31);
- playback state is not touched while color values move;
- final MP4 export remains on the existing Media3 effect pipeline and still works from the immutable source;
- the superseded EditorPreviewPolicy/seek workaround was removed.

CI evidence:
- Android build run `38945577792`: success.

Status: IMPLEMENTADO_PENDIENTE_VALIDACIÓN on the owner's device.


## Latest validation APK — realtime editor preview surface

A fresh `dev-latest` APK was generated after replacing repeated Media3 preview-effect rebuilds with direct realtime preview-surface color processing.

Evidence:
- release workflow run `38945841210`: success;
- release target: `09c1b029b7ed1d1c3327460c6c53ec08b6c27f4a`;
- source main feature state: `96ad25a53492a0b9a71bff37c8f85e30d51b5914`;
- published: `2026-10-16T18:16:08Z`;
- `MedPlay-debug.apk` size: 11,692,770 bytes;
- SHA-256: `94495d87fa25b1501701f12df0a34d406b69328cbc87807be85cc456fa81c22e`.

Device validation priority: play a clip and continuously drag saturation/brightness/contrast. Playback should continue moving while color changes appear immediately. Final export must still reflect the persisted edit settings.


## Physical validation — realtime editor preview

The owner physically confirmed on-device that the realtime editor preview now works after the TextureView/direct ColorMatrix architecture change.

Validated on the owner's device:
- video playback continues in the editor;
- visual editing changes are visible in realtime.

Do not generalize this validation to every device/model, but the previously reported frozen-preview defect is resolved on the tested device.


## Confirmed clip deletion

Clip deletion is now implemented from both **Mis clips** and the internal player.

Behavior:
- deletion always requires an explicit confirmation dialog;
- confirmation states that the video will be removed from both MedPlay and the device Gallery and cannot be undone by MedPlay;
- confirmed deletion removes the actual MediaStore video;
- ClipLibrary metadata is removed;
- any non-destructive edit project for that source URI is removed;
- failed deletion leaves the library entry intact and reports failure.

A persistent visual/product direction is now stored in `docs/UX_DIRECTION.md`. It records the owner-supplied Medal-inspired direction for gallery/player/editor polish and explicitly marks the current text-button gallery/control stack as functional scaffolding rather than final design.

CI evidence:
- Android build run `38947464943`: success.

Status: deletion is IMPLEMENTADO_PENDIENTE_VALIDACIÓN on the owner's device.


## Latest validation APK — confirmed clip deletion

A fresh `dev-latest` APK was generated after adding confirmed clip deletion and the persistent media UX direction.

Evidence:
- release workflow run `38947691136`: success;
- release target: `79203e4dbf21c3b7ab9007178ccddf8897a460dc`;
- source main feature state: `c3d5bccc448dd5629a7c018480faa25b62aacbc8`;
- published: `2026-10-16T18:30:27Z`;
- `MedPlay-debug.apk` size: 11,698,510 bytes;
- SHA-256: `67e21a9b1563ab8a08756d01a51549944e201441ffe1da9359f387b58714ba18`.

Device validation priority: verify Cancel preserves a disposable clip, then confirm Delete removes it from Mis clips and the device Gallery.


## Stability/audio audit after device crash + missing microphone report

Owner device evidence:
- MedPlay process visibly crashed during the first transition into the selected game; reopening and starting again was required.
- visual editor preview is already physically validated as working.
- editor game/microphone gain controls were not audible in preview.
- microphone voice was not heard in a final export.

Audit findings and fixes:
- capture startup previously requested the microphone foreground-service type for every full-session recording with RECORD_AUDIO permission, even when player-voice capture was disabled;
- microphone foreground-service startup was unguarded and could crash the MedPlay process if Android/vendor policy rejected it during the app-selection/game transition;
- startup now requests microphone FGS only when voice capture is actually enabled and falls back to screen-capture-only rather than crashing if microphone FGS is rejected;
- editor gain sliders previously persisted values but ordinary ExoPlayer playback selected only one audio track, so independent game/voice gain was not actually previewed;
- editor now creates synchronized audio-only ExoPlayer instances, selects each source audio track independently, mutes the main video player's default audio after successful preparation, and applies game/voice preview gains in realtime;
- preview gains above 100% are capped at unity for realtime listening; final export still supports 0–200%;
- final game audio is stereo while microphone capture is mono; Media3 mixing requires compatible channel counts, so final export now normalizes every audio source to stereo before applying gain and mixing;
- microphone diagnostics now record `microphonePeakAbs` from actual PCM input so a future silent-voice report can distinguish Android-delivered silence from an export/mix defect.

CI evidence:
- earlier branch runs failed only because static sequence helpers were unavailable in Media3 1.8.1;
- those calls were removed;
- Android build run `38949778931`: success on commit `b8b861457120320b2be1a54978be593f066fe2ba`.

UX documentation:
- `docs/UX_DIRECTION.md` now includes the known recording-configuration structure and clearly states that the exact final settings pixel layout has not yet been owner-approved.
- gallery/player/editor remain Medal-inspired, media-first references without copying proprietary assets.

Status: IMPLEMENTADO_PENDIENTE_VALIDACIÓN for startup stability, audible dual-track preview, and final microphone mix.


## Final startup boundary hardening

The stability branch now also guards the Activity -> foreground-service boundary. `MainActivity` no longer allows a foreground-service launch rejection to escape and crash the process during the transition to the selected game.

Behavior:
- first foreground-service start is wrapped;
- one short retry is made after 350 ms for a transient vendor/background transition rejection;
- if both attempts fail, MedPlay remains open and shows a retry message instead of crashing;
- failure stage, exception type and message are persisted through `CaptureService.recordStartupFailure()`;
- enabling microphone mid-full-session now safely upgrades the foreground-service type before starting microphone capture; if Android rejects the upgrade, microphone remains off and the recording continues.

Final CI evidence:
- Android build run `38950292280`: success on branch head `9fad50aae8b928a347db7dc5c96e774252ea7096`.

This does not prove the photographed device crash had exactly this exception without a device stack trace, but it closes two concrete uncaught foreground-service startup paths matching the reported transition.


## Latest validation APK — startup stability + dual-audio editor/export

A fresh `dev-latest` APK was generated after the startup-stability and dual-audio audit.

Evidence:
- release workflow run `38950569036`: build/tests and release steps successful;
- release target: `8157592a92fc98e205d9f38412f410fac8d4b4fc`;
- source main feature state: `365fd08bf22b539468828e9a060faae2873f5905`;
- published: `2026-10-16T18:52:52Z`;
- `MedPlay-debug.apk` size: 11,723,506 bytes;
- SHA-256: `aeb426348ef3a6b4f473508901b3a9e295843d78ae8d11e1281776dc22ffbaf0`.

Physical validation priority:
1. cold-start MedPlay -> Start recording -> choose the game; repeat several times and confirm the process does not crash;
2. record full-session with player voice enabled and speak clearly;
3. in editor, lower game audio to 0–20% and leave voice near 100%, then confirm both independent controls are audible;
4. export final and confirm microphone + game audio are both present and synchronized;
5. if voice is still absent, inspect last-session diagnostics and report `microphonePeakAbs`.


## Editor audio-preview stutter root cause and fix

Owner device evidence:
- game/microphone audio preview in the editor was audibly chopped/intermittent.

Root cause verified in code:
- `EditorAudioPreviewController` used independent ExoPlayers for the encoded audio tracks;
- a timer ran every 500 ms;
- whenever drift exceeded 140 ms, MedPlay called `seekTo()` on the audio players;
- those repeated seeks flushed/refilled decoder buffers and produced the audible chopping.

Fix:
- periodic drift polling and timer-based reseeks were removed entirely;
- editor video playback is held until auxiliary audio preview tracks are prepared;
- all preview tracks are aligned once before playback begins;
- re-alignment is now event-driven only: real seek/discontinuity, pause, and resume;
- moving game/microphone gain sliders changes volume only and never touches playback position;
- recording AAC, capture pipeline, final export mixer and visual preview were intentionally left unchanged.

CI evidence:
- Android build run `38953175312`: success.

Status: IMPLEMENTADO_PENDIENTE_VALIDACIÓN on the owner's device.


## Latest validation APK — continuous editor audio preview

A fresh `dev-latest` APK was generated after removing periodic audio-preview reseeks.

Evidence:
- release workflow run `38953415852`: build/tests and release steps succeeded;
- release target: `9bedb1f095dc1cc8f681feba0306f7d0cd5f446e`;
- source main feature state: `477ca1c334e9d0d13b6516623ccb1b7950684bcd`;
- published: `2026-10-16T19:15:31Z`;
- `MedPlay-debug.apk` size: 11,722,182 bytes;
- SHA-256: `2f5d97aaa734845fe1da68259dca6c0953a26c17dc20c0b5b3bcfcd014e34d2a`.

Physical validation priority: play a game+voice clip continuously for at least 20–30 seconds while repeatedly moving game and microphone volume sliders. Audio should remain continuous rather than chopped.


## XRecorder reference audit + first media UI slice

The owner supplied XRecorder 2.5.5.1 as a reference for both audio behavior and media/editor UX.

Static package audit verified:
- dedicated gallery/draft, player/result, recording settings and modular video editor resources;
- editor panels for preview, timeline/track, trim, crop, speed, volume, filters, HSL, transitions, background, text, stickers, music and compression/export;
- native WebRTC NS/AGC/resampling functions and custom audio mix/volume JNI;
- FFmpeg/native video processing components.

Reference usage is behavioral/structural only. MedPlay must not copy XRecorder proprietary source, branding, artwork, filter packs, music, stickers or templates. See `docs/XRECORDER_REFERENCE_AUDIT.md`.

First implemented UX slice:
- Mis clips now renders newest-first media cards with real video thumbnails, duration badge, audio/date metadata, direct Edit and confirmed Delete;
- internal player now keeps video dominant and uses a compact media action bar with metadata, primary Edit and secondary Delete;
- editor preview area is larger and persistent;
- the previous long editor settings stack is replaced by a horizontal tool rail with four real MedPlay capability groups: Recorte / Imagen / Audio / Exportar;
- only the selected tool panel is expanded;
- no nonfunctional XRecorder-like tools are exposed.

CI evidence:
- initial run `38974671910` failed only because `HorizontalScrollView` import was missing;
- corrected branch run `38974847658`: success.

Status: IMPLEMENTADO_PENDIENTE_VALIDACIÓN for the new media UI on-device.


## Latest validation APK — XRecorder-inspired media UI

A fresh `dev-latest` APK was generated after the first media-first UX slice.

Evidence:
- release workflow run `38975151735`: build/tests and release steps succeeded;
- release target: `cf3dd59360305e54e67294ae0b61ec828660ec19`;
- source main feature state: `07123f6ef90df1c84dbfeb4b34311b50c9307d8f`;
- published: `2026-10-16T22:21:43Z`;
- `MedPlay-debug.apk` size: 11,742,786 bytes;
- SHA-256: `100633bb120b623d8eaf7bec85d8a5572ae4c6269b23cdd3ea4f8067d3230a38`.

Physical validation priority:
- real thumbnail loading in Mis clips;
- newest-first card order;
- Edit/Delete actions;
- internal-player action bar;
- editor tool switching between Recorte / Imagen / Audio / Exportar;
- preview sizing for portrait and landscape clips;
- existing editor audio/visual/export behavior remains functional.


## Current microphone speaker-bleed status

Status: **ABIERTO — VALIDACIÓN FÍSICA NEGATIVA**.

User requirement:
- the player-voice microphone track must preserve the player's voice while materially suppressing game/audio emitted by the phone speaker;
- game audio is already available separately through internal playback capture and must not be duplicated intentionally into the microphone track.

Verified history that still matters:
- Android platform `AcousticEchoCanceler` was physically insufficient on the owner's target phone even when enabled;
- MedPlay therefore moved to WebRTC AEC3 with clean `AudioPlaybackCapture` PCM as far-end/render reference and raw microphone PCM as near-end/capture;
- an early software-AEC3 validation build had a microphone foreground-state regression that prevented the intended microphone path from starting; that regression was fixed;
- voice-only mode now keeps a hidden playback capture strictly as AEC reference without persisting a game-audio track;
- playback and microphone use 48 kHz / 10 ms frames and BOOTTIME-based capture timing.

Latest physically tested result:
- AEC3 reference alignment and clock-derived delay logic up through main audio commit `58ab7b2` were tested on-device;
- **the defect remained unchanged: speaker/game audio was still present in the isolated “Mi voz” track**;
- therefore the #114 approach is **not resolved** and must not be described as pending validation.

Current follow-up work after that failed device test:
- PR #115 adds correlation-derived external delay estimation: **CI failed**;
- PR #116 removes MedPlay's second render queue/delay override and lets AEC3 own its internal render queue/delay estimation: **CI passed**, not physically validated;
- PR #117 feeds render reference to AEC3 as it arrives and briefly queues microphone capture until render catches up; it also flushes queued microphone frames on stop: **CI failed**;
- none of #115/#116/#117 is merged into main as the accepted solution.

Current blocker:
- determine and fix the concrete build failure in #117 (or reject that approach with evidence) before producing another validation APK;
- do not generate another physical-test APK from #114 logic alone.

Diagnostics that remain useful:
- `microphoneAecMode`;
- render/capture frame counts;
- dropped render frames;
- ERL/ERLE;
- AEC estimated delay;
- render/capture skew.

Next step:
1. inspect #117's exact build error;
2. correct only the AEC ordering implementation needed to get a clean build;
3. review against #116 so contradictory AEC queue/delay strategies are not stacked;
4. require green CI;
5. then produce one new APK and physically retest with “Mi voz” isolated.

Do not mark the defect resolved until the owner's device shows material speaker-bleed reduction with intelligible voice.
