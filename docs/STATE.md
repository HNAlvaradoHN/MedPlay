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
- Sustainable profile selector passed public CI and is merged to main.
- Measure sustained thermals, dropped frames and gameplay impact on real devices.

## Next step

Validate floating save control and storage-aware 2/3/5-minute retention in public CI, then continue with real-device validation and recorder UX hardening.


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

Important limitation: encoded AAC output is currently consumed by a discard sink. Audio is therefore not yet present in saved clips. This branch establishes and validates the capture/encode path first; the next block will place encoded audio into rolling segments aligned to the video timeline and then add the audio track during final remux.


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

Current feature branch adds an explicit persistent choice for internal playback audio:
- internal audio defaults to enabled;
- the setting is stored locally in SharedPreferences;
- when disabled, MainActivity skips the RECORD_AUDIO permission request and proceeds directly to MediaProjection consent;
- CaptureService re-checks the preference before creating AudioPlaybackCapture/AudioRecord/AAC, so disabled audio consumes no audio capture/encode resources;
- changing the toggle applies to the next capture session; an already-running audio pipeline is not torn down from the Activity.

This provides an intentional video-only mode in addition to the existing automatic video-only fallback when permission/capture is unavailable.
