# HANDOFF — SendGram

## What exists

Project governance, official memory, and an MVP architecture/decision set exist. No Android application code exists yet.

## Required reading for a new chat

1. RULES.md
2. AGENTS.md
3. docs/STATE.md
4. docs/HANDOFF.md
5. docs/SECURITY.md
6. Current GitHub branches/PRs/CI and relevant task files

## Current owner intent

Build SendGram from zero as a simple Android app focused on saving/downloading files to Telegram destinations.

Each user should provide their own Telegram API ID and API Hash. The repository is public, so no real credentials or personal information may ever be committed.

## Decided for MVP

- Android native + Kotlin.
- Android DocumentsProvider / Storage Access Framework is the primary save integration.
- TDLib will authenticate the user account.
- Each user supplies their own api_id/api_hash.
- No backend for MVP.
- Provider writes go to app-private temporary storage and upload after descriptor close.

## Still not proven

- Real-device DocumentsProvider save behavior across browser/apps.
- TDLib Android packaging and build integration.
- Large-file/background upload behavior.
- Final UX for credential onboarding and destination management.

## Next handoff action

After the architecture PR is merged, build the smallest Android/Kotlin skeleton and validate a writable DocumentsProvider on a real device before adding TDLib.
