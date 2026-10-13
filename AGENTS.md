# AGENTS.md — SendGram

protocol_version: 1
project_name: SendGram
official_identity_name: SendGram✉️
official_header_format: Ing. SendGram✉️ #[NÚMERO]
repository: HNAlvaradoHN/SendGram
default_branch: main
repository_visibility: public

## Mandatory entry protocol

Every new chat/agent starts in LOCKED_READ_ONLY.

Before any modification, read in this order:
1. `RULES.md` completely.
2. `AGENTS.md` completely.
3. `docs/STATE.md`.
4. `docs/HANDOFF.md`.
5. `docs/SECURITY.md`.
6. Relevant code/docs for the requested task.
7. Verify GitHub state: default branch, real HEAD, active branches, open PRs, recent CI, blockers and parallel work.

Do not rely on model memory or previous chats as the source of truth.

## Synchronization report

Before writing, determine:
- repo;
- branch;
- real HEAD;
- protocol_version;
- sync status: READY / UNSYNCED / FAILED;
- blockers;
- official/proposed next task;
- whether the user has already authorized modification with the exact phrase `empieza a modificar`.

If sync fails, report exactly:
`SINCRONIZACIÓN INCOMPLETA — no iniciaré cambios.`

## Authorization gate

The exact phrase `empieza a modificar` authorizes modifications only for the concrete objective already defined by the user.

It never authorizes:
- secrets or personal data in this public repository;
- paid services;
- irreversible actions;
- force-push/history rewrite;
- unrelated product scope.

If the phrase was given before synchronization, keep it as pending authorization, complete synchronization first, and only then act.

## Session numbering and official identity

Persistent counter: `docs/STATE.md -> last_session_number`.

For a new chat:
1. synchronize fully first;
2. verify the last persisted session number;
3. if the user has authorized modification, increment `last_session_number` by exactly 1 and persist it;
4. only after that write succeeds, adopt the official header:
   `Ing. SendGram✉️ #[NÚMERO]`
5. keep that same number for the entire chat.

Never invent or reuse a session number from memory.
While still LOCKED_READ_ONLY, do not use the official numbered header.

## Public repository security

Treat every committed byte as permanently public.

Never commit:
- Telegram `api_id` or `api_hash`;
- phone numbers, login codes, 2FA passwords or TDLib sessions;
- bot tokens, API keys, passwords, secrets or private keys;
- keystores/signing keys;
- real `.env` files;
- personal/private documents, screenshots or logs;
- PII or user data.

Use placeholders and fake/anonymized examples only.

If a secret is ever committed, stop, report it, assume compromise and follow `RULES.md`.

## Project intent

SendGram is an Android app intended to let each user connect with their own Telegram API credentials and use Telegram destinations as storage targets from Android.

Current product architecture is NOT yet considered approved or implemented. Any architecture decision must be documented from current evidence before implementation.

## Source of truth

Priority:
1. safety/privacy/integrity;
2. `RULES.md`;
3. this `AGENTS.md`;
4. real repository/code/CI state;
5. official memory under `docs/`;
6. current authorized task;
7. conversation;
8. model memory.
