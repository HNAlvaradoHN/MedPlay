# MedPlay UX / visual direction

status: ACTIVE
protocol_version: 1

This document preserves the owner-approved product/visual direction so future sessions do not have to reconstruct it from chat history.

## Overall direction

MedPlay should feel like a modern gaming media app rather than a diagnostic/settings utility.

The owner supplied Medal as the main visual/reference direction for the media experience. This is a direction reference, not permission to copy Medal pixel-for-pixel or reproduce proprietary assets.

Priorities:
- video and thumbnails first;
- dark, media-focused presentation;
- important actions easy to discover;
- technical recorder settings should not dominate the gallery/player/editor experience;
- avoid dense rows of generic Android buttons where a media-oriented card/control is more appropriate;
- preserve fast, responsive behavior.

## Gallery / Mis clips

Target direction:
- clips presented primarily as visual thumbnails/cards instead of text-only buttons;
- duration visible on or near the thumbnail;
- useful secondary metadata can include capture date/time and audio state, but must remain visually subordinate to the video;
- tapping the media/card opens the MedPlay player;
- actions such as edit/delete should be discoverable without cluttering every card;
- deletion is destructive and MUST require explicit confirmation;
- confirmed deletion removes the actual MediaStore/Gallery video, MedPlay library metadata, and any edit project associated with that source;
- empty state should remain simple and explanatory;
- newest clips should remain easy to reach.

The current text-button library is functional scaffolding, not the final visual design.

## Internal player

Target direction:
- video remains the dominant element;
- playback controls should be obvious and reliable;
- Edit must always be visibly accessible and must not be hidden behind the video surface;
- Delete must be available but visually secondary to playback/edit;
- Delete confirmation must clearly state that the video will also disappear from the device Gallery and cannot be restored by MedPlay;
- avoid forcing the user into an external player for normal supported clips.

## Editor

Target direction:
- large live video preview at the top;
- changes to filters/color/brightness/contrast should be visible in realtime without stopping playback;
- trim should be visual and touch-friendly;
- editing controls should be grouped by task rather than presented as an undifferentiated settings list;
- color/filter controls should support quick presets plus manual adjustment;
- game audio and player voice controls should be independently understandable when both exist;
- the source recording stays immutable;
- final export creates a new compatible video;
- destructive actions are not part of normal editing flow.

Current editor functionality is ahead of its final visual polish. The present control stack is acceptable as implementation scaffolding, not the final desired layout.


## Recording configuration

Target direction:
- configuration should feel like a clean gaming-recorder setup screen, not a developer/debug panel;
- primary choices should be visually grouped: recording mode, quality, audio, clip duration/full-session behavior, and floating control;
- the most common choices should be reachable without scrolling through diagnostics or implementation details;
- advanced/diagnostic controls should stay secondary or collapsed;
- the floating control customization should keep a live visual preview;
- labels should explain outcomes in user language (for example, game audio versus player voice) rather than expose codec/internal implementation jargon;
- Android's own screen-capture chooser remains the single place to choose one app versus full screen; MedPlay should not duplicate that selector;
- configuration and gallery are distinct product areas: settings should not visually dominate the media library;
- use a modern dark gaming/media presentation consistent with the owner-provided Medal references, while retaining MedPlay's own identity.

Known functional groups that must remain represented:
- clip retroactive versus full-session recording;
- recording quality / automatic recommended mode;
- internal game audio;
- player microphone voice when supported by the selected mode;
- retroactive clip duration (15 / 30 / 60 seconds);
- floating control appearance and behavior;
- advanced stop/diagnostics tools.

The exact final pixel layout of the configuration screen has not yet been owner-approved as a finished mockup. Do not invent or claim an exact Medal screen clone. Preserve these structure decisions and refine the visual layout from future owner feedback/screenshots.

## Visual references and future refinement

Owner-provided Medal screenshots are the reference for the intended level of polish, media emphasis, gallery organization and editor discoverability.

If later owner screenshots or explicit instructions conflict with this document, the newer owner direction wins and this document should be updated in the same session.

Do not claim the gallery/editor visual design is finished merely because the underlying functionality exists.


## XRecorder interaction reference

The owner supplied XRecorder 2.5.5.1 as an additional concrete reference for the media workflow.

Adopt these structural ideas where they improve MedPlay:
- visual media cards and thumbnails instead of text-only clip rows;
- persistent video preview in the editor;
- horizontal editor tool selector;
- one focused panel per editing task rather than a single long control list;
- compact secondary actions around the media;
- dedicated media/player/editor experiences that do not feel like recorder diagnostics.

Do not copy XRecorder assets, source, branding, paid packs or exact pixel layouts. MedPlay keeps its own dark gaming/media visual identity and only exposes tools that actually work.
