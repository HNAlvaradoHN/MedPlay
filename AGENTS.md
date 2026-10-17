# AGENTS.md — MedPlay ⏯️

protocol_version: 1
project_name: MedPlay
official_identity_name: MedPlay ⏯️
official_header_format: Ing. MedPlay ⏯️ #[NÚMERO]
repository: HNAlvaradoHN/MedPlay
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
   `Ing. MedPlay ⏯️ #[NÚMERO]`
5. keep that same number for the entire chat.

MedPlay starts its own numbering independently from any previous project content that existed in this repository.
Never invent or reuse a session number from model memory.
While still LOCKED_READ_ONLY, do not use the official numbered header.

## Living documentation discipline

For MedPlay, official memory is a living operational snapshot, not an append-only journal.

Mandatory:
- update affected `docs/` memory during the same task whenever implementation state, CI evidence, physical validation, blockers or next steps change;
- physical device results override older `IMPLEMENTADO_PENDIENTE_VALIDACIÓN` wording immediately;
- remove or replace superseded APK notes, abandoned hypotheses, stale next steps and duplicated historical detail once they no longer help continuation;
- preserve only the minimum historical lesson needed to avoid repeating a known failure;
- use Git commits/PRs as the detailed historical record;
- before handoff, verify that `STATE.md` and `HANDOFF.md` describe the same current reality and a single actionable next step.

Do not leave obsolete text competing with current truth.

## Public repository security

Treat every committed byte as permanently public.

Never commit:
- API keys, passwords, tokens or private keys;
- signing keys/keystores;
- real `.env` files;
- private APKs or proprietary application packages unless the owner explicitly confirms they may be public;
- copyrighted third-party decompiled source;
- personal/private documents, screenshots or logs;
- PII or user data.

Use placeholders and fake/anonymized examples only.

If a secret is ever committed, stop, report it, assume compromise and follow `RULES.md`.

## Project intent

MedPlay is an Android application focused on high-quality retroactive screen recording for games and other fast-motion content.

Primary goals:
- continuously retain only a configurable recent window of screen capture;
- let the user save the previous N seconds/minutes on demand;
- prioritize high visual quality and low pixelation;
- expose configurable resolution, FPS, bitrate, codec, audio and clip duration;
- use floating controls suitable for games.

REA/JADX may be used as analysis tools to study public behavior/structure of reference applications such as Medal for interoperability and product research. MedPlay must implement its own code and must not copy proprietary source or protected assets.

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
