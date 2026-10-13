# HANDOFF — SendGram

## What exists

Only project governance and official memory. No Android application code exists yet.

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

## Not yet decided / not yet proven

- Exact Android architecture.
- Exact TDLib integration method/version.
- DocumentsProvider behavior with remote Telegram writes.
- UX for credential entry and destination selection.
- Background upload behavior and file-size constraints.

## Next handoff action

Synchronize against GitHub, register the next session number only after valid modification authorization, then work on the single official next task recorded in STATE.md.
