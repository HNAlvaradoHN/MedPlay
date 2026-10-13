# PROJECT STATE — SendGram

status: READY
protocol_version: 1
repository: HNAlvaradoHN/SendGram
default_branch: main
visibility: public

## Session registry

last_session_number: 1
active_session: 1

A new chat may increment this counter only after full synchronization and a valid user authorization to modify.

## Verified project state

- Product code: none.
- Stable version: none.
- Development version: none.
- Release/APK: none.
- CI: not configured.
- Architecture: MVP architecture documented on branch `docs/mvp-architecture`; pending merge.
- External paid services: none approved.
- Secrets allowed in repository: none.

## Current objective

Validate and record the minimum MVP architecture before product code.

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

Review/merge the MVP architecture. After merge, create the first Android/Kotlin skeleton and prove that SendGram appears as a writable DocumentsProvider before integrating TDLib.
