# DECISIONS — SendGram

## DEC-001 — Android native + Kotlin

Status: ACCEPTED_FOR_MVP

Use native Android/Kotlin for the first implementation.

Reason:
The core feature depends directly on Android's Storage Access Framework, `DocumentsProvider`, lifecycle/background behavior and file descriptors. Native Android minimizes integration layers for the first proof.

## DEC-002 — DocumentsProvider is the primary product integration

Status: ACCEPTED_FOR_MVP

SendGram will target Android's system document picker/save flow instead of only using the Share sheet.

Reason:
The product goal is for SendGram to behave like a storage destination when an app saves/downloads a document.

## DEC-003 — TDLib instead of Bot API

Status: ACCEPTED_FOR_MVP

Use TDLib to authenticate and act as the user's Telegram account.

Reason:
A bot cannot represent the user's complete Telegram account or Saved Messages in the same way.

## DEC-004 — Each user supplies their own Telegram API credentials

Status: OWNER_DECISION

Each user provides their own `api_id` and `api_hash`.

Reason:
The owner does not want a public APK to embed developer-owned Telegram API credentials.

Consequence:
Onboarding is less convenient, but developer credential exposure is avoided.

## DEC-005 — No backend for MVP

Status: ACCEPTED_FOR_MVP

The MVP has no SendGram server.

Reason:
The core path can be implemented device-to-Telegram, reducing cost, privacy exposure and operational complexity.

## DEC-006 — Local temporary file before Telegram upload

Status: ACCEPTED_FOR_PROTOTYPE

When another app writes a new file through the provider, SendGram uses an app-private temporary local file and uploads after the write descriptor closes.

Reason:
Android's official DocumentsProvider documentation explicitly supports a close listener for write-mode files and describes using that point to update cloud storage.

Revisit only if real-device tests show unacceptable storage/performance behavior.
