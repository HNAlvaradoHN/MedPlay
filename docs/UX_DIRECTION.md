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

## Visual references and future refinement

Owner-provided Medal screenshots are the reference for the intended level of polish, media emphasis, gallery organization and editor discoverability.

If later owner screenshots or explicit instructions conflict with this document, the newer owner direction wins and this document should be updated in the same session.

Do not claim the gallery/editor visual design is finished merely because the underlying functionality exists.
