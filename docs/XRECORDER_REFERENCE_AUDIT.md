# XRecorder reference audit for MedPlay

Status: ACTIVE_REFERENCE
Source reviewed: owner-provided XRecorder 2.5.5.1 `.apks`
Purpose: behavior and UX reference only. Do not copy proprietary source code, branding, artwork, filters, music, stickers or other protected assets.

## What was verified from the package

The package contains dedicated screens/layouts for:
- gallery and draft management;
- record/start/result flows;
- audio-source and audio-settings flows;
- internal-audio listing;
- video editor and preview;
- timeline and track editing;
- trim/cut;
- crop;
- speed;
- volume;
- filters and filter adjustment;
- HSL;
- transitions;
- background;
- text, text styling and templates;
- stickers;
- music/audio import and extraction;
- compression/export;
- video settings and custom recording parameters.

The editor resources show a modular panel architecture instead of one long settings screen. Relevant layout names include:
- `activity_video_edit2`
- `fragment_video_preview_layout`
- `fragment_video_timeline_layout`
- `fragment_video_track_layout`
- `fragment_video_trim_layout`
- `fragment_video_volume_layout`
- `fragment_video_filter_layout2`
- `fragment_video_hsl_layout`
- `fragment_video_crop_layout`
- `fragment_video_speed_layout`
- `fragment_video_background_layout`
- `fragment_video_transition_layout`

The visual tool set uses a persistent preview plus a horizontal tool rail. Resource names identify separate actions for trim, music, text, sticker, speed, crop, volume, filter, background and adjustment.

The gallery/draft resources include separate edit, share and delete actions and dedicated video/draft item layouts, rather than a text-only list.

## Audio architecture findings

The owner specifically supplied XRecorder because its internal-audio + microphone recording behaves better on the same device.

The native arm64 package contains:
- `libnative-lib.so`
- JNI audio mix and volume functions;
- WebRTC noise suppression functions (`WebRtcNs_*`);
- WebRTC automatic gain control functions (`WebRtcAgc_*`);
- WebRTC resampling helpers;
- `WebRtcAgc_AddFarend` / `WebRtcAgc_AddFarendToDigital`;
- FFmpeg-based audio/video processing in other native components.

This is materially more involved than MedPlay's original raw `MIC` + AAC path. Do not claim XRecorder's exact echo-cancellation algorithm without stronger evidence, but treat its use of native WebRTC processing and custom mixing as verified package evidence.

## Product structure to adopt in MedPlay

Use the interaction structure as inspiration, not a pixel clone:

### Media library
- real video thumbnails;
- duration badge on the thumbnail;
- date/audio state as secondary metadata;
- tap thumbnail/card to play;
- edit and delete as compact secondary actions;
- newest media easy to reach;
- empty state remains simple.

### Internal player
- video dominates the screen;
- playback controls remain obvious;
- compact action bar below the video;
- Edit is primary;
- Delete remains visually secondary and confirmed.

### Editor
- persistent large video preview;
- horizontal tool selector;
- only the selected tool's panel is expanded;
- timeline/trim remains directly manipulable;
- current MedPlay capabilities map to:
  - Recorte
  - Imagen (filter, saturation, brightness, contrast, rotation)
  - Audio (game + player voice)
  - Exportar
- do not add nonfunctional Music/Text/Sticker/Speed buttons just to resemble XRecorder;
- future editor features should plug into the same tool/panel architecture.

### Recording settings
- separate the recording setup experience from the media library;
- group source/mode, quality, audio and floating-control settings;
- advanced diagnostics remain secondary;
- a live preview is appropriate where a visual setting is being configured.

## Design rule

Similarity means:
- comparable information hierarchy;
- media-first layout;
- modular editor panels;
- discoverable controls;
- dark gaming/media presentation.

It does NOT mean:
- copying XRecorder assets;
- copying proprietary source;
- reproducing branding;
- copying paid/protected filter packs, music, stickers or templates.

Owner feedback and newer MedPlay-specific references override this document.
