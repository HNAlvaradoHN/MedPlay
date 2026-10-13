# PROJECT STATE — SendGram

status: BOOTSTRAP_INITIALIZED
protocol_version: 1
repository: HNAlvaradoHN/SendGram
default_branch: main
visibility: public

## Session registry

last_session_number: 0
active_session: none

A new chat may increment this counter only after full synchronization and a valid user authorization to modify.

## Verified project state

- Product code: none.
- Stable version: none.
- Development version: none.
- Release/APK: none.
- CI: not configured.
- Architecture: not yet approved.
- External paid services: none approved.
- Secrets allowed in repository: none.

## Current objective

Bootstrap the repository safely and establish persistent governance/memory so future chats can synchronize before working.

## Product direction already established by owner

Target concept: an Android application named SendGram that can use each user's own Telegram API ID/API Hash rather than credentials owned by the app developer.

Desired core experience to validate later:
Android save/download flow -> SendGram -> selected Telegram destination.

This is product direction, not yet an implemented or technically validated contract.

## Blockers / pending validation

- Technical feasibility of Android DocumentsProvider/Storage Access Framework + Telegram user-account integration must be validated before declaring the core flow solved.
- Telegram credential handling/storage design must be reviewed before implementation.
- No Android build exists yet.

## Next step

After bootstrap is re-read and synchronized, register session #1 for the current authorized chat. Then define and validate the minimum architecture/MVP before creating product code.
